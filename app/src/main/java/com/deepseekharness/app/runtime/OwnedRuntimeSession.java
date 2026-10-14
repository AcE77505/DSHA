package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.util.ProcessIdentity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 独立应急会话复用签名 native-session 的出生握手；没有裸 PID 或旧记录强杀入口。 */
public final class OwnedRuntimeSession extends Process implements AutoCloseable, OwnedGuestProof {
  public static final class IdentityInspectionUnavailable extends IOException {
    IdentityInspectionUnavailable(Throwable cause) {
      super("OWNED_CURRENT_IDENTITY_INSPECTION_UNAVAILABLE", cause);
    }
  }

  private final IsolatedInstallProcess owner;
  private final String id;

  OwnedRuntimeSession(IsolatedInstallProcess owner, String id) {
    this.owner = owner;
    this.id = id;
  }

  public static OwnedRuntimeSession start(ProcessBuilder target, File temporary, Context context)
      throws IOException {
    return start(target, temporary, context, id -> {}, false);
  }

  public interface RecordBinder {
    void bind(String id) throws IOException;
  }

  public static OwnedRuntimeSession start(
      ProcessBuilder target, File temporary, Context context, RecordBinder binder)
      throws IOException {
    return start(target, temporary, context, binder, true);
  }

  private static OwnedRuntimeSession start(
      ProcessBuilder target, File temporary, Context context, RecordBinder binder, boolean retain)
      throws IOException {
    if (IsolatedInstallProcess.bundledLauncher(context) == null)
      throw new IOException("RECOVERY_NATIVE_SESSION_UNAVAILABLE");
    var record = BoundedGuestSessions.begin(context.getFilesDir());
    try {
      binder.bind(record.id());
      if (retain) record.retainCompletion();
      return new OwnedRuntimeSession(
          IsolatedInstallProcess.start(target, temporary, context, record), record.id());
    } catch (IOException | RuntimeException failure) {
      record.uncertain();
      throw failure;
    }
  }

  public String recordId() {
    return id;
  }

  public String runtimeMode() {
    return owner.runtimeMode();
  }

  public boolean confirmedTreeExit() {
    return owner.confirmedTreeExit();
  }

  public boolean commandWasNeverExecuted() {
    return owner.commandWasNeverExecuted();
  }

  @Override
  public boolean stopAndConfirm(long timeoutMs) {
    return owner.stopAndConfirm(timeoutMs);
  }

  public boolean confirmedExit() {
    return owner.confirmedClosed();
  }

  public java.util.Map<String, Object> diagnosticSnapshot() {
    return owner.diagnosticSnapshot();
  }

  public void requireCurrentBirth() throws IOException {
    owner.requireCurrentBirth();
  }

  /** 只核验旧记录对应的会话是否为空；旧/缺失/不可读记录绝不授予发送信号的权限。 */
  public static boolean recordedExit(File files, String id) throws IOException {
    if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
      throw new IOException("RECOVERY_SESSION_RECORD_INVALID");
    var fs = new AndroidBackupFileSystem();
    File entry = fs.child(files.getCanonicalFile(), "bounded-guest-active/" + id);
    if (fs.stat(entry).type.equals("MISSING"))
      return BoundedGuestSessions.recordedCompletion(fs, files.getCanonicalFile(), id);
    String value =
        new String(fs.small(fs.child(entry, "identity"), 128), StandardCharsets.US_ASCII);
    String[] lines = value.split("\n", -1);
    if (lines.length != 3
        || !id.equals(lines[0])
        || !lines[2].isEmpty()
        || !lines[1].matches("[1-9][0-9]* [1-9][0-9]* [1-9][0-9]*"))
      throw new IOException("RECOVERY_SESSION_RECORD_INVALID");
    String[] fields = lines[1].split(" ");
    int pid;
    try {
      pid = Integer.parseInt(fields[0]);
    } catch (NumberFormatException invalid) {
      throw new IOException("RECOVERY_SESSION_RECORD_INVALID", invalid);
    }
    if (pid < 2 || !fields[0].equals(fields[2]))
      throw new IOException("RECOVERY_SESSION_RECORD_INVALID");
    File protocol = fs.child(entry, "protocol");
    if (!fs.stat(protocol).type.equals("MISSING")) {
      if (!"DSHA_OWNED_TREE_V2\n"
          .equals(new String(fs.small(protocol, 64), StandardCharsets.US_ASCII)))
        throw new IOException("OWNED_TREE_PROTOCOL");
      String tree =
          new String(fs.small(fs.child(entry, "tree-empty"), 192), StandardCharsets.US_ASCII);
      if (!BoundedGuestSessions.validTree(value, tree))
        throw new IOException("OWNED_TREE_RECEIPT_MISSING");
      // 持久 native 树回执加原监督者出生身份消失；不以 SID 空代替 V2 证明。
      try {
        var current =
            com.deepseekharness.app.util.WebPidIdentity.parse(
                com.deepseekharness.app.util.Compat.readAll(new File("/proc/" + pid + "/stat")),
                pid);
        long started = Long.parseLong(fields[1]);
        if (current == null || current.started == started && !current.exited()) return false;
      } catch (IOException unavailable) {
        try {
          android.system.Os.kill(pid, 0);
          return false;
        } catch (android.system.ErrnoException absent) {
          if (absent.errno != android.system.OsConstants.ESRCH) return false;
        }
      }
      var members = new java.util.HashSet<>(fs.list(entry));
      members.remove("retain");
      if (!members.equals(java.util.Set.of("identity", "intent", "protocol", "tree-empty")))
        throw new IOException("OWNED_TREE_RECORD_CHANGED");
      BoundedGuestSessions.completeRecorded(fs, files.getCanonicalFile(), entry, id);
      return true;
    }
    TerminalProcessCloser.requireSessionEmpty(pid);
    try {
      android.system.Os.kill(-pid, 0);
      return false;
    } catch (android.system.ErrnoException failure) {
      if (failure.errno != android.system.OsConstants.ESRCH) return false;
      // 只轮换本条已经证明为空的记录，不处理其它正式/应急实例，也不发信号。
      var names = fs.list(entry);
      if (names.size() != 2
          || !names.contains("intent")
          || !names.contains("identity")
          || !(id + "\n")
              .equals(
                  new String(fs.small(fs.child(entry, "intent"), 64), StandardCharsets.US_ASCII))
          || !value.equals(
              new String(fs.small(fs.child(entry, "identity"), 128), StandardCharsets.US_ASCII)))
        throw new IOException("RECOVERY_SESSION_RECORD_CHANGED");
      File parent = entry.getParentFile();
      fs.removeOwned(parent, id);
      fs.syncDirectory(parent);
      return true;
    }
  }

  @Override
  public void close() {
    owner.close();
  }

  public boolean closeConfirmed() {
    owner.close();
    return owner.confirmedClosed();
  }

  @Override
  public void destroy() {
    close();
  }

  @Override
  public Process destroyForcibly() {
    close();
    return this;
  }

  @Override
  public InputStream getInputStream() {
    return owner.getInputStream();
  }

  @Override
  public InputStream getErrorStream() {
    return owner.getErrorStream();
  }

  @Override
  public OutputStream getOutputStream() {
    return owner.getOutputStream();
  }

  @Override
  public int exitValue() {
    return owner.exitValue();
  }

  @Override
  public int waitFor() throws InterruptedException {
    return owner.waitFor();
  }

  @Override
  public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
    return owner.waitFor(timeout, unit);
  }
}
