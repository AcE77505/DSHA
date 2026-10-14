package com.deepseekharness.app.runtime;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 固定独立 Android ULX 闭包；不借旧 proroot loader 或正式数据域。 */
public final class BxrootRuntime implements ContainerRuntime {
  public static final String[] LIBS = {"libbxroot.so", "libbxroot-runtime.so", "libbxroot-ulx.so"};
  private final Context context;
  private final File directory;

  public BxrootRuntime(Context context) {
    this.context = context;
    directory = new File(context.getApplicationInfo().nativeLibraryDir);
  }

  public String id() {
    return "bxroot";
  }

  public String displayName() {
    return "bxroot";
  }

  public boolean available() {
    for (String name : LIBS)
      if (!new File(directory, name).isFile() || new File(directory, name).length() == 0)
        return false;
    return true;
  }

  public String unavailableReason() {
    return available() ? "" : "BXROOT_NATIVE_CLOSURE_MISSING";
  }

  public void prepare() throws IOException {
    if (!available()) throw new IOException(unavailableReason());
    try (var input = context.getAssets().open("bxroot-native-lock.json")) {
      var content = new java.io.ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int size;
      while ((size = input.read(chunk)) != -1) {
        if (content.size() + size > 32768) throw new IOException("BXROOT_NATIVE_LOCK_LIMIT");
        content.write(chunk, 0, size);
      }
      byte[] data = content.toByteArray();
      if (data.length > 32768) throw new IOException("BXROOT_NATIVE_LOCK_LIMIT");
      var lock = new org.json.JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
      if (lock.getInt("schema") != 1 || !lock.getString("sourceCommit").matches("[a-f0-9]{40}"))
        throw new IOException("BXROOT_NATIVE_LOCK_SCHEMA");
      var hashes = lock.getJSONObject("nativeHashes");
      for (String name : LIBS) {
        String expected = hashes.getString(name);
        if (!expected.matches("[a-f0-9]{64}")) throw new IOException("BXROOT_NATIVE_LOCK_HASH");
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var bytes = new java.io.FileInputStream(new File(directory, name))) {
          byte[] buffer = new byte[65536];
          int count;
          while ((count = bytes.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest())
          actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!expected.equals(actual.toString()))
          throw new IOException("BXROOT_NATIVE_HASH_MISMATCH:" + name);
      }
    } catch (org.json.JSONException | java.security.NoSuchAlgorithmException invalid) {
      throw new IOException("BXROOT_NATIVE_LOCK_INVALID", invalid);
    }
  }

  public List<String> baseArgv(File rootfs, boolean links) {
    return baseArgv(rootfs, links, context.getFilesDir());
  }

  public List<String> baseArgv(File rootfs, boolean links, File dataDomain) {
    return arguments(rootfs, dataDomain, new File(rootfs, ".l2s"), false);
  }

  List<String> probeArgv(File rootfs, File l2s) {
    return arguments(rootfs, null, l2s, true);
  }

  private List<String> arguments(File rootfs, File dataDomain, File l2s, boolean probe) {
    try {
      var authority =
          new BxrootPrivateRoot(
              new com.deepseekharness.app.backup.AndroidBackupFileSystem(), context.getFilesDir());
      File physicalRoot = authority.root(rootfs), physicalDomain = authority.dataDomain(dataDomain);
      List<String> argv = buildArguments(physicalRoot, physicalDomain, l2s, probe);
      authority.verify();
      return argv;
    } catch (IOException failure) {
      throw new IllegalStateException("BXROOT_PRIVATE_ROOT_UNREADABLE", failure);
    }
  }

  private List<String> buildArguments(File rootfs, File dataDomain, File l2s, boolean probe) {
    List<String> argv = new ArrayList<>();
    java.util.Collections.addAll(
        argv,
        new File(directory, "libbxroot.so").getAbsolutePath(),
        "--kill-on-exit",
        "-r",
        rootfs.getAbsolutePath(),
        "-0",
        "-l",
        "-w",
        "/root");
    for (Bind bind : ContainerRuntime.binds())
      if (new File(bind.host()).exists()) {
        if (probe && (bind.host().startsWith("/storage") || bind.host().startsWith("/sdcard")))
          continue;
        argv.add("-b");
        argv.add(bind.host() + ":" + bind.guest());
      }
    if (!l2s.isDirectory() && !l2s.mkdirs())
      throw new IllegalStateException("BXROOT_L2S_DIRECTORY_UNAVAILABLE");
    argv.add("-b");
    argv.add(l2s.getAbsolutePath() + ":" + l2s.getAbsolutePath());
    if (dataDomain != null) UserDataBindings.append(argv, rootfs, dataDomain);
    return argv;
  }

  public void applyEnv(ProcessBuilder builder, File base, File lib, File tmp) throws IOException {
    throw new IOException("BXROOT_EXPLICIT_ROOT_REQUIRED");
  }

  public void applyEnv(ProcessBuilder builder, File base, File lib, File tmp, File root)
      throws IOException {
    prepare();
    var env = builder.environment();
    env.keySet()
        .removeIf(
            key ->
                key.startsWith("PROROOT_")
                    || key.startsWith("PROOT_")
                    || key.startsWith("BXROOT_"));
    env.remove("LD_PRELOAD");
    env.remove("LD_LIBRARY_PATH");
    env.put("BXROOT_LIB_PATH", new File(directory, "libbxroot-runtime.so").getAbsolutePath());
    env.put("BXROOT_TMP_DIR", tmp.getAbsolutePath());
    env.put("BXROOT_L2S_DIR", new File(root, ".l2s").getAbsolutePath());
    env.put("BXROOT_AUTO_HOST", "1");
    env.put("BXROOT_HOST_PATH", "");
    env.put("BXROOT_REENTRY", "0");
    env.put("DSHA_BXROOT_STRICT_EXEC", "1");
  }
}
