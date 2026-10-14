package com.deepseekharness.app.runtime;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupArchive;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.DocumentPaths;
import com.deepseekharness.app.util.RuntimeCapabilityProbe;
import com.deepseekharness.app.util.SensitiveData;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Isolated read-only qualification bound to actual guest bytes and signed component inputs. */
public final class RuntimeEngineQualification {
  public interface Builder {
    ProcessBuilder build(String command, File scratch) throws IOException;
  }

  public static ProcessBuilder probeBuilder(
      Context context, ContainerRuntime engine, File root, File scratch, String command)
      throws IOException {
    if (!(engine instanceof BxrootRuntime)) throw new IOException("RUNTIME_PROBE_ENGINE");
    var argv = new ArrayList<>(((BxrootRuntime) engine).probeArgv(root, new File(scratch, "l2s")));
    appendProbeArguments(argv, scratch, command);
    var builder = new ProcessBuilder(argv).redirectErrorStream(true);
    builder.environment().clear();
    engine.applyEnv(builder, scratch, scratch, scratch, root);
    builder.environment().put("BXROOT_L2S_DIR", new File(scratch, "l2s").getAbsolutePath());
    // Only this fixed isolated probe emits native compatibility diagnostics.
    builder.environment().put("BXROOT_VERBOSE", "1");
    builder.environment().put("HOME", "/root");
    builder.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
    builder.environment().put("DSHA_RUNTIME_ENGINE", "bxroot");
    Compat.redirectStdinDevNull(builder);
    return builder;
  }

  static void appendProbeArguments(List<String> argv, File scratch, String command) {
    // The launcher derives BXROOT_VERBOSE from argv and removes an env-only value.
    Collections.addAll(
        argv, "--verbose=1", "-b", scratch.getAbsolutePath() + ":/tmp", "/bin/bash", "-c", command);
  }

  public static String fingerprint(Context context, File root, boolean packages)
      throws IOException {
    return fingerprint(context, root, packages, RuntimeCapabilityProbe.Kind.PYTHON);
  }

  public static String fingerprint(
      Context context, File root, boolean packages, RuntimeCapabilityProbe.Kind kind)
      throws IOException {
    var required = RuntimeCapabilityProbe.inputs(kind, packages);
    var inputs = new TreeMap<String, Object>();
    inputs.put("qualification", kind.name());
    inputs.put("root", root.getCanonicalPath());
    try {
      var stat = Os.lstat(root.getPath());
      if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != android.os.Process.myUid())
        throw new IOException("RUNTIME_QUALIFICATION_ROOT_OWNERSHIP");
      inputs.put("rootIdentity", stat.st_dev + ":" + stat.st_ino);
    } catch (android.system.ErrnoException denied) {
      throw new IOException("RUNTIME_QUALIFICATION_ROOT_UNKNOWN", denied);
    }
    inputs.put("plan", assetHash(context, "runtime-descriptor.json"));
    inputs.put("nativeLock", assetHash(context, "bxroot-native-lock.json"));
    if (packages) inputs.put("packageBundle", assetHash(context, "ubuntu-tools.manifest.json"));
    var paths = paths(root);
    for (String name : required) {
      try {
        inputs.put(name, fileHash(paths.resolve("linux/ubuntu" + name, true)));
      } catch (IOException unavailable) {
        throw new IOException("RUNTIME_QUALIFICATION_INPUT_INVALID:" + name, unavailable);
      }
    }
    if (packages) {
      String ldconfig = null;
      for (String name :
          List.of(
              "/usr/sbin/ldconfig.real",
              "/sbin/ldconfig.real",
              "/usr/sbin/ldconfig",
              "/sbin/ldconfig"))
        if (paths.resolve("linux/ubuntu" + name, true).isFile()) {
          ldconfig = name;
          break;
        }
      if (ldconfig == null) throw new IOException("RUNTIME_QUALIFICATION_LDCONFIG_MISSING");
      for (String name : List.of(ldconfig, "/usr/bin/dpkg", "/usr/bin/dpkg-deb", "/bin/tar"))
        inputs.put(name, fileHash(paths.resolve("linux/ubuntu" + name, true)));
    }
    return BackupArchive.hex(BackupArchive.sha().digest(BackupJson.write(inputs, 32768)));
  }

  private static DocumentPaths paths(File root) throws IOException {
    return new DocumentPaths(
        root.getParentFile().getParentFile(),
        file -> {
          try {
            return OsConstants.S_ISLNK(Os.lstat(file.getPath()).st_mode)
                ? Os.readlink(file.getPath())
                : null;
          } catch (android.system.ErrnoException error) {
            if (error.errno == OsConstants.ENOENT) return null;
            throw new IOException(error);
          }
        });
  }

  private static String assetHash(Context context, String name) throws IOException {
    try (InputStream input = context.getAssets().open(name)) {
      return hash(input);
    }
  }

  private static String fileHash(File file) throws IOException {
    return fileHash(new AndroidBackupFileSystem(), file);
  }

  static String fileHash(com.deepseekharness.app.backup.BackupFileSystem fs, File file)
      throws IOException {
    var expected = fs.stat(file);
    if (!expected.type.equals("FILE") || expected.size < 1 || expected.size > 128L * 1024 * 1024)
      throw new IOException("RUNTIME_QUALIFICATION_INPUT_INVALID");
    try (InputStream input = fs.read(file, expected)) {
      return hash(input);
    }
  }

  private static String hash(InputStream input) throws IOException {
    var sha = BackupArchive.sha();
    byte[] bytes = new byte[65536];
    int count;
    while ((count = input.read(bytes)) != -1) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("CANCELLED");
      sha.update(bytes, 0, count);
    }
    return BackupArchive.hex(sha.digest());
  }

  /** False only means an explicit capability rejection with trusted tree and launcher closure. */
  public static boolean check(
      Context context, File root, File tmp, boolean packages, String fingerprint, Builder builder)
      throws IOException {
    return check(
        context, root, tmp, packages, fingerprint, RuntimeCapabilityProbe.Kind.PYTHON, builder);
  }

  public static boolean check(
      Context context,
      File root,
      File tmp,
      boolean packages,
      String fingerprint,
      RuntimeCapabilityProbe.Kind kind,
      Builder builder)
      throws IOException {
    RuntimeCapabilityProbe.inputs(kind, packages);
    OwnedRuntimeSession process = null;
    BoundedProcessRunner.Result result = null;
    Throwable primary = null;
    String outcome = "UNCONFIRMED";
    var fs = new AndroidBackupFileSystem();
    File work = fs.child(context.getFilesDir().getCanonicalFile(), "runtime-capability-work");
    if (fs.stat(work).type.equals("MISSING")) fs.directory(work);
    File scratch = fs.child(work, UUID.randomUUID().toString());
    fs.directory(scratch);
    boolean closed = false;
    try {
      process =
          OwnedRuntimeSession.start(
              builder.build(
                  packages ? RuntimeEngineProbe.packages() : RuntimeCapabilityProbe.command(kind),
                  scratch),
              tmp,
              context);
      result = BoundedProcessRunner.collect(process, 60_000, 32768, Compat::destroy);
      if (result.timedOut) throw new IOException("RUNTIME_CAPABILITY_TIMEOUT_NO_REPLAY");
      if (!process.confirmedTreeExit())
        throw new IOException("RUNTIME_CAPABILITY_TREE_UNCONFIRMED");
      closed = true;
      if (!fingerprint.equals(fingerprint(context, root, packages, kind)))
        throw new IOException("RUNTIME_CAPABILITY_INPUT_CHANGED_NO_REPLAY");
      String marker =
          packages ? "DSHA_ENGINE_PACKAGE_CAPABILITY_READY" : RuntimeCapabilityProbe.marker(kind);
      boolean ready =
          result.exitCode == 0 && ("\n" + result.output + "\n").contains("\n" + marker + "\n");
      boolean unsupported =
          result.exitCode != 0
              && (com.deepseekharness.app.util.RuntimeEnginePolicy.readOnlyProbeEngineFailure(
                      result.output)
                  || result.output.contains("DSHA_ENGINE_CAPABILITY_UNSUPPORTED"));
      if (!ready && !unsupported)
        throw new IOException("RUNTIME_CAPABILITY_FAILED_NO_REPLAY:" + result.exitCode);
      outcome = ready ? "READY" : "UNSUPPORTED";
      return ready;
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      var failure = new IOException("RUNTIME_CAPABILITY_INTERRUPTED_NO_REPLAY", cancelled);
      primary = failure;
      throw failure;
    } catch (IOException | RuntimeException | Error failure) {
      primary = failure;
      throw failure;
    } finally {
      // The diagnostic is not closure evidence. Retain unknown output before any cleanup.
      try {
        File receipts =
            fs.child(context.getFilesDir().getCanonicalFile(), "runtime-capability-receipts");
        if (fs.stat(receipts).type.equals("MISSING")) fs.directory(receipts);
        var diagnostic = diagnostic(fingerprint, kind, packages, outcome, closed, result, primary);
        if (process != null) diagnostic.put("sessionRecord", process.recordId());
        fs.atomic(receipts, scratch.getName() + ".json", BackupJson.write(diagnostic, 65536));
      } catch (IOException | RuntimeException failed) {
        if (primary != null) primary.addSuppressed(failed);
        else throw failed;
      } finally {
        try {
          if (process != null) process.close();
        } catch (RuntimeException failure) {
          if (primary != null) primary.addSuppressed(failure);
          else throw failure;
        } finally {
          if (closed) {
            try {
              fs.removeOwned(work, scratch.getName());
            } catch (IOException failure) {
              if (primary != null) primary.addSuppressed(failure);
              else throw failure;
            }
          }
        }
      }
    }
  }

  static Map<String, Object> diagnostic(
      String fingerprint,
      RuntimeCapabilityProbe.Kind kind,
      boolean packages,
      String outcome,
      boolean closed,
      BoundedProcessRunner.Result result,
      Throwable failure) {
    var value = new LinkedHashMap<String, Object>();
    value.put("schema", 2L);
    value.put("engine", "bxroot");
    value.put("inputSha256", fingerprint);
    value.put("qualification", kind.name());
    value.put("packageTools", packages);
    value.put("outcome", outcome);
    value.put("supported", "READY".equals(outcome));
    value.put("ownedTreeEmpty", closed);
    value.put("launcherExited", closed);
    value.put("checkedAt", System.currentTimeMillis());
    if (result != null) {
      value.put("commandExit", result.exitCode);
      value.put("timedOut", result.timedOut);
      value.put("totalBytes", result.totalBytes);
      value.put("truncated", result.truncated);
      value.put("head", safeOutput(result.output, false));
      value.put("tail", safeOutput(result.tail, true));
    }
    var causes = new ArrayList<String>();
    for (Throwable current = failure;
        current != null && causes.size() < 4;
        current = current.getCause()) {
      String cause = SensitiveData.redact(current.toString());
      causes.add(cause.substring(0, Math.min(cause.length(), 1000)));
    }
    if (!causes.isEmpty()) value.put("failure", causes);
    return value;
  }

  static String safeOutput(String output, boolean tail) {
    String safe = SensitiveData.redact(output == null ? "" : output);
    if (safe.length() <= 4000) return safe;
    return tail ? safe.substring(safe.length() - 4000) : safe.substring(0, 4000);
  }

  private RuntimeEngineQualification() {}
}
