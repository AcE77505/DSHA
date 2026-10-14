package com.deepseekharness.app.runtime;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ProcessIdentity;
import com.deepseekharness.app.util.ProcessTermination;
import com.deepseekharness.app.util.ProcessBirthHandshake;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/** 所有有界/非交互命令的宿主监督器；command exit 与 owned-tree closure 分离。 */
final class IsolatedInstallProcess extends Process implements AutoCloseable, OwnedGuestProof {
  private final Process supervisor;
  private final ProcessIdentity identity;
  private final File status;
  private final BoundedGuestSessions.Operation record;
  private volatile boolean closing, closed, recordRetired;

  interface ExitEvidence {
    boolean groupGone();

    boolean sessionEmpty();
  }

  private final ExitEvidence evidence;
  private NativeTreeControl control;
  private String runtimeMode = "UNKNOWN";

  static boolean supported(android.content.Context context) {
    return sessionLauncher(context) != null;
  }

  static String sessionLauncher(android.content.Context context) {
    String bundled = bundledLauncher(context);
    if (bundled != null) return bundled;
    File system = new File("/system/bin/setsid");
    return system.canExecute() ? system.getAbsolutePath() : null;
  }

  static String bundledLauncher(android.content.Context context) {
    File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libdsha-session.so");
    return binary.isFile() && binary.length() > 0 ? binary.getAbsolutePath() : null;
  }

  static IsolatedInstallProcess start(
      ProcessBuilder target, File temporary, android.content.Context context) throws IOException {
    return start(target, temporary, context, null);
  }

  static IsolatedInstallProcess start(
      ProcessBuilder target,
      File temporary,
      android.content.Context context,
      BoundedGuestSessions.Operation record)
      throws IOException {
    BoundedGuestSessions.Operation operation =
        record == null ? BoundedGuestSessions.begin(context.getFilesDir()) : record;
    NativeTreeControl control = null;
    Process supervisor = null;
    IsolatedInstallProcess ready = null;
    boolean accepted = false;
    try {
      String launcher = bundledLauncher(context);
      if (launcher == null) throw new IOException("OWNED_RUNTIME_SUPERVISOR_UNAVAILABLE");
      operation.nativeProtocol();
      control = new NativeTreeControl(operation);
      var command = new java.util.ArrayList<String>();
      command.add(launcher);
      command.add("--birth-handshake");
      command.add(control.argument());
      command.add(
          "--stop-signal="
              + ("proot".equals(target.environment().get("DSHA_RUNTIME_ENGINE"))
                      || target.command().get(0).contains("libproot")
                  ? 3
                  : 15));
      command.add("--");
      command.addAll(target.command());
      target.command(command);
      Process launched = target.start();
      supervisor = launched;
      ProcessIdentity birth =
          ProcessBirthHandshake.read(
              launched.getInputStream(),
              android.os.Process.myPid(),
              () -> !ProcessTermination.exited(launched),
              3000);
      ProcessIdentity current = readIdentity(birth.pid);
      current =
          OwnedBirthEvidence.current(
              birth,
              current,
              !ProcessTermination.exited(launched),
              current == null ? NativeProcess.sessionId(birth.pid) : current.session);
      if (current == null) throw new IOException("PROCESS_BIRTH_RECHECK_UNCONFIRMED");
      ready = new IsolatedInstallProcess(launched, current, null, operation);
      ready.control = control;
      ready.runtimeMode = target.environment().getOrDefault("DSHA_RUNTIME_ENGINE", "UNKNOWN");
      control.accept(current, () -> !ProcessTermination.exited(launched));
      accepted = true;
      return ready;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("OWNED_RUNTIME_START_INTERRUPTED", interrupted);
    } finally {
      if (!accepted) {
        operation.uncertain();
        // START 可能已送达时，先保留通道收取真实 ECHILD 与 launcher 退出，不能先断开证明。
        if (control != null && !control.neverDispatched() && ready != null) {
          try {
            ready.close();
          } catch (RuntimeException unknown) {
            operation.uncertain();
          }
        } else {
          try {
            if (control != null) control.close();
          } catch (IOException ignored) {
          }
          if (supervisor != null) {
            Compat.requestGracefulStop(supervisor, 1000);
            ProcessTermination.awaitExit(supervisor, 3000);
          }
          // spawn 从未成功，或本轮从未尝试 START 且 launcher 已退出，才可清理预备记录。
          if (supervisor == null || ProcessTermination.exited(supervisor)) {
            try {
              operation.completedBeforeDispatch();
            } catch (IOException cleanup) {
              operation.uncertain();
            }
          }
        }
        try {
          if (control != null) control.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  private IsolatedInstallProcess(
      Process supervisor,
      ProcessIdentity identity,
      File status,
      BoundedGuestSessions.Operation record) {
    this.supervisor = supervisor;
    this.identity = identity;
    this.status = status;
    this.record = record;
    evidence = null;
  }

  IsolatedInstallProcess(
      Process supervisor, ProcessIdentity identity, File status, ExitEvidence evidence) {
    this.supervisor = supervisor;
    this.identity = identity;
    this.status = status;
    this.record = null;
    this.evidence = evidence;
  }

  private static ProcessIdentity readIdentity(int pid) {
    if (pid <= 1) return null;
    try {
      return ProcessIdentity.fromStat(
          Compat.readAll(new File("/proc/" + pid + "/stat")), pid, android.os.Process.myPid());
    } catch (IOException | RuntimeException error) {
      return null;
    }
  }

  void requireCurrentBirth() throws IOException {
    ProcessIdentity current = readIdentity(identity.pid);
    if (current == null
        && control != null
        && !ProcessTermination.exited(supervisor)
        && NativeProcess.sessionId(identity.pid) == identity.session)
      throw new OwnedRuntimeSession.IdentityInspectionUnavailable(null);
    if (!identity.sameProcess(current) || !current.ownsSession())
      throw new IOException("OWNED_SESSION_BIRTH_CHANGED");
    try {
      if (Os.stat("/proc/" + identity.pid).st_uid != android.os.Process.myUid())
        throw new IOException("OWNED_SESSION_UID_CHANGED");
    } catch (android.system.ErrnoException unreadable) {
      if (control != null && !ProcessTermination.exited(supervisor))
        throw new OwnedRuntimeSession.IdentityInspectionUnavailable(unreadable);
      throw new IOException("OWNED_SESSION_UID_UNAVAILABLE", unreadable);
    }
    if (!identity.sameProcess(readIdentity(identity.pid)))
      throw new IOException("OWNED_SESSION_BIRTH_CHANGED");
  }

  public String runtimeMode() {
    return runtimeMode;
  }

  public boolean confirmedTreeExit() {
    return control != null && poll() != null;
  }

  public boolean commandWasNeverExecuted() {
    return control != null && control.engineNeverExecuted();
  }

  @Override
  public synchronized boolean stopAndConfirm(long timeoutMs) {
    if (control == null) return false;
    try {
      closeOwned(timeoutMs);
      return closed;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    } catch (IOException | RuntimeException unknown) {
      return false;
    }
  }

  synchronized boolean confirmedClosed() {
    return closed;
  }

  /** 超时回收前的只读会话快照；不读取 cmdline/env/栈，也不取得特权。 */
  java.util.Map<String, Object> diagnosticSnapshot() {
    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("schema", 1L);
    result.put("leader", identity.pid);
    result.put("started", identity.started);
    result.put("session", identity.session);
    var members = new java.util.ArrayList<java.util.Map<String, Object>>();
    result.put("members", members);
    try {
      requireCurrentBirth();
      File[] processes = new File("/proc").listFiles();
      if (processes == null) throw new IOException("PROC_LIST_UNAVAILABLE");
      java.util.Arrays.sort(processes, java.util.Comparator.comparing(File::getName));
      for (File file : processes) {
        if (!file.getName().matches("[1-9][0-9]{0,9}")) continue;
        int pid;
        try {
          pid = Integer.parseInt(file.getName());
        } catch (NumberFormatException ignored) {
          continue;
        }
        if (NativeProcess.sessionId(pid) != identity.session) continue;
        if (members.size() >= 64) {
          result.put("truncated", true);
          break;
        }
        var entry = new java.util.LinkedHashMap<String, Object>();
        entry.put("pid", pid);
        members.add(entry);
        try {
          if (Os.stat(file.getPath()).st_uid != android.os.Process.myUid()) {
            entry.put("state", "UID_UNCONFIRMED");
            continue;
          }
          var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
          String status =
              new String(
                  fs.small(new File(file, "status"), 8192),
                  java.nio.charset.StandardCharsets.US_ASCII);
          for (String line : status.split("\n")) {
            if (line.startsWith("State:") || line.startsWith("TracerPid:"))
              entry.put(
                  line.substring(0, line.indexOf(':')),
                  line.substring(line.indexOf(':') + 1).trim());
          }
          String wait =
              new String(
                      fs.small(new File(file, "wchan"), 256),
                      java.nio.charset.StandardCharsets.US_ASCII)
                  .trim();
          entry.put("wchan", wait.matches("[a-zA-Z0-9_]+") ? wait : "UNAVAILABLE");
        } catch (IOException | android.system.ErrnoException unavailable) {
          entry.put("state", com.deepseekharness.app.backup.BackupErrorCode.from(unavailable));
        }
      }
      requireCurrentBirth();
    } catch (IOException | RuntimeException | LinkageError unknown) {
      result.put("identity", "UNCONFIRMED");
    }
    return result;
  }

  private synchronized Integer poll() {
    if (control == null) return legacyPoll();
    if (closed) return control.commandExit();
    if (control.treeEmpty()
        && ProcessTermination.exited(supervisor)
        && control.commandExit() != null
        && supervisor.exitValue() == control.commandExit()) {
      closed = true;
      retireRecord();
      try {
        control.close();
      } catch (IOException failed) {
        throw new IllegalStateException("OWNED_CONTROL_CLOSE_FAILED", failed);
      }
      return control.commandExit();
    }
    return null;
  }

  private synchronized Integer legacyPoll() {
    if (closing
        && !closed
        && groupGone()
        && ProcessTermination.exited(supervisor)
        && boundedSessionEmpty()) {
      return supervisor.exitValue();
    }
    if (closed) return supervisor.exitValue();
    if (!closing && status != null && status.isFile()) {
      try {
        int code = com.deepseekharness.app.util.IsolatedCommand.status(Compat.readAll(status));
        if (code >= 0) return code;
      } catch (IOException ignored) {
      }
    }
    if (!closing && ProcessTermination.exited(supervisor) && groupGone() && boundedSessionEmpty()) {
      // Native leader ends the group if its shell exits before publishing status.
      // No status is never successful, even if a shell returned zero.
      int code = supervisor.exitValue();
      return code == 0 ? 125 : code;
    }
    return null;
  }

  @Override
  public int exitValue() {
    Integer value = poll();
    if (value != null) return value;
    throw new IllegalThreadStateException(com.deepseekharness.app.util.UiText.text("安装进程组尚未结束"));
  }

  @Override
  public boolean isAlive() {
    return poll() == null;
  }

  @Override
  public int waitFor() throws InterruptedException {
    long delay = 10;
    while (true) {
      try {
        return exitValue();
      } catch (IllegalThreadStateException running) {
        Thread.sleep(delay);
        delay = Math.min(100, delay * 2);
      }
    }
  }

  @Override
  public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit)
      throws InterruptedException {
    long started = System.nanoTime(), budget = unit.toNanos(timeout), delay = 10;
    do {
      if (poll() != null) return true;
      long remaining = budget - (System.nanoTime() - started);
      if (remaining <= 0) return false;
      java.util.concurrent.TimeUnit.NANOSECONDS.sleep(
          Math.min(remaining, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delay)));
      delay = Math.min(100, delay * 2);
    } while (true);
  }

  @Override
  public InputStream getInputStream() {
    return supervisor.getInputStream();
  }

  @Override
  public InputStream getErrorStream() {
    return supervisor.getErrorStream();
  }

  @Override
  public OutputStream getOutputStream() {
    return supervisor.getOutputStream();
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
  public synchronized void close() {
    if (closed) {
      retireRecord();
      return;
    }
    if (control != null) {
      try {
        closeOwned(7000);
        return;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("OWNED_TREE_STOP_INTERRUPTED", interrupted);
      } catch (IOException failure) {
        throw new IllegalStateException("OWNED_TREE_CONTROL_UNCONFIRMED", failure);
      }
    }
    closing = true;
    ProcessIdentity current = readIdentity(identity.pid);
    if (!identity.sameProcess(current) || !current.ownsSession()) {
      if (ProcessTermination.exited(supervisor) && groupGone() && boundedSessionEmpty()) {
        closed = true;
        status.delete();
        retireRecord();
        return;
      }
      throw new IllegalStateException(
          com.deepseekharness.app.util.UiText.text("安装进程组身份不可确认，已停止后续维护"));
    }
    try {
      requireCurrentBirth();
    } catch (IOException unknown) {
      throw new IllegalStateException("OWNED_SESSION_IDENTITY_UNCONFIRMED", unknown);
    }
    try {
      Os.kill(-identity.pid, OsConstants.SIGKILL);
    } catch (android.system.ErrnoException error) {
      throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("无法回收安装进程组"), error);
    }
    if (!ProcessTermination.awaitExit(supervisor, 3000))
      throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("安装监督进程未退出"));
    boolean interrupted = Thread.interrupted();
    long deadline = SystemClock.elapsedRealtime() + 3000;
    try {
      while (!groupGone() && SystemClock.elapsedRealtime() < deadline) {
        try {
          Thread.sleep(50);
        } catch (InterruptedException error) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
    if (!groupGone())
      throw new IllegalStateException(
          com.deepseekharness.app.util.UiText.text("安装子进程尚未完全退出，已暂停后续维护"));
    if (!boundedSessionEmpty())
      throw new IllegalStateException("BOUNDED_GUEST_SESSION_UNCONFIRMED");
    closed = true;
    status.delete();
    retireRecord();
  }

  private void closeOwned(long timeoutMs) throws IOException, InterruptedException {
    closing = true;
    IOException stopFailure = null;
    try {
      control.stop(false);
    } catch (IOException failure) {
      stopFailure = failure;
    }
    long started = System.nanoTime();
    long budget = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
    while (poll() == null && System.nanoTime() - started < budget) Thread.sleep(20);
    if (poll() == null)
      throw new IllegalStateException(
          "OWNED_TREE_EXIT_UNCONFIRMED:" + control.unknown(), stopFailure);
    control.close();
  }

  private void retireRecord() {
    if (record == null || recordRetired) return;
    try {
      record.completed();
      recordRetired = true;
    } catch (IOException failure) {
      throw new IllegalStateException("BOUNDED_GUEST_RECORD_RETAINED", failure);
    }
  }

  void markRecordUncertain() {
    if (record != null) record.uncertain();
  }

  private boolean groupGone() {
    if (evidence != null) return evidence.groupGone();
    try {
      Os.kill(-identity.pid, 0);
      return false;
    } catch (android.system.ErrnoException error) {
      return error.errno == OsConstants.ESRCH;
    }
  }

  private boolean boundedSessionEmpty() {
    if (evidence != null) return evidence.sessionEmpty();
    if (record == null) return true;
    try {
      TerminalProcessCloser.requireSessionEmpty(identity.pid);
      return true;
    } catch (IOException unknown) {
      return false;
    }
  }
}
