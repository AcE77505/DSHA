package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Shared mechanics only; each trial retains its own data, validation and close contract. */
final class TrialSupport {
  private TrialSupport() {}

  interface ExitCheck {
    boolean confirmed(Process launcher) throws IOException;
  }

  interface CloseRecord {
    void close() throws IOException;
  }

  interface Cleanup {
    void stop() throws IOException;

    boolean confirmed(Process launcher) throws IOException;

    void closeRecord() throws IOException;
  }

  static void write(BackupFileSystem fs, File root, String name, byte[] bytes) throws IOException {
    fs.parents(root, name);
    File target = fs.child(root, name);
    try (OutputStream out = fs.create(target)) {
      out.write(bytes);
    }
    fs.syncDirectory(target.getParentFile());
  }

  static String identityPrefix() {
    return "set -e; printf '%s\\n' $$ > .dsha-web.pid; "
        + "IFS= read -r DSHA_STAT < /proc/$$/stat; "
        + "DSHA_FIELDS=${DSHA_STAT##*) }; set -- $DSHA_FIELDS; "
        + "[ $# -ge 20 ] || exit 78; DSHA_BORN=${20}; "
        + "case \"$DSHA_BORN\" in ''|*[!0-9]*|0) exit 78;; esac; "
        + "printf '%s %s\\n' $$ \"$DSHA_BORN\" > .dsha-web.identity; ";
  }

  static String runtimeMode(Process process) throws IOException {
    String mode = OwnedGuestProof.modeOf(process);
    if (!"bxroot".equals(mode) && !"proot".equals(mode))
      throw new IOException("TRIAL_RUNTIME_UNCONFIRMED");
    return mode;
  }

  interface RecordedExit {
    boolean confirmed(String sessionId) throws IOException;
  }

  static String boundSession(BackupFileSystem fs, File operation) throws IOException {
    File bound = fs.child(operation, "native-session-id");
    String type = fs.stat(bound).type;
    if (type.equals("MISSING")) return "";
    if (!type.equals("FILE")) throw new IOException("TRIAL_SESSION_BINDING");
    String id = new String(fs.small(bound, 128), java.nio.charset.StandardCharsets.US_ASCII);
    if (!com.deepseekharness.app.util.Ids.uuid(id)) throw new IOException("TRIAL_SESSION_BINDING");
    return id;
  }

  /** 无返回句柄仍可能已经 START；只有本次绑定的宿主退出回执可解除启动现场。 */
  static boolean untrackedExitConfirmed(BackupFileSystem fs, File operation, RecordedExit recorded)
      throws IOException {
    File launched = fs.child(operation, "launched");
    String type = fs.stat(launched).type;
    if (type.equals("MISSING")) return true;
    if (!type.equals("FILE")
        || !operation
            .getName()
            .equals(
                new String(fs.small(launched, 128), java.nio.charset.StandardCharsets.US_ASCII)))
      throw new IOException("TRIAL_MARKER");
    String id = boundSession(fs, operation);
    return !id.isEmpty() && recorded.confirmed(id);
  }

  static Process checkedExit(Process launcher, ExitCheck check, Runnable stop, String errorCode) {
    return checkedExit(launcher, check, stop, () -> {}, errorCode);
  }

  static Process checkedExit(
      Process launcher, ExitCheck check, Runnable stop, CloseRecord close, String errorCode) {
    if (launcher == null || check == null || stop == null || close == null)
      throw new IllegalArgumentException("TRIAL_EXIT_ARGUMENT");
    return new CheckedExit(launcher, check, stop, close, errorCode);
  }

  /** Keep the primary failure, and retain an unconfirmed guest until both exit and record close. */
  static boolean finish(
      Process launcher,
      com.deepseekharness.app.util.RuntimeWorkPort.Work work,
      Cleanup cleanup,
      Throwable primary)
      throws IOException {
    IOException stopping = null;
    try {
      if (launcher != null) cleanup.stop();
      if (!cleanup.confirmed(launcher)) throw new IOException("TRIAL_PROCESS_UNCONFIRMED");
    } catch (IOException | RuntimeException error) {
      stopping = new IOException("TRIAL_PROCESS_UNCONFIRMED", error);
    }
    if (stopping != null) {
      try {
        work.retainUntilExit(
            new CheckedExit(
                launcher,
                cleanup::confirmed,
                () -> {
                  try {
                    cleanup.stop();
                  } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                  }
                },
                cleanup::closeRecord,
                "TRIAL_PROCESS_UNCONFIRMED"));
      } catch (RuntimeException retaining) {
        stopping.addSuppressed(retaining);
      }
      secondary(primary, stopping);
      return false;
    }
    IOException closing = null;
    try {
      cleanup.closeRecord();
    } catch (IOException | RuntimeException error) {
      closing = new IOException("TRIAL_RECORD_CLOSE_FAILED", error);
    }
    try {
      work.close();
    } catch (RuntimeException error) {
      IOException release = new IOException("TRIAL_WORK_RELEASE_FAILED", error);
      if (closing == null) closing = release;
      else closing.addSuppressed(release);
    }
    if (closing != null) {
      secondary(primary, closing);
      return false;
    }
    return true;
  }

  private static void secondary(Throwable primary, IOException failure) throws IOException {
    if (primary == null) throw failure;
    primary.addSuppressed(failure);
  }

  /** A launcher exit cannot release a lease while its owned guest remains unconfirmed. */
  private static final class CheckedExit extends Process {
    private final Process launcher;
    private final ExitCheck check;
    private final Runnable stop;
    private final CloseRecord close;
    private final String errorCode;
    private boolean recordClosed;

    CheckedExit(
        Process launcher, ExitCheck check, Runnable stop, CloseRecord close, String errorCode) {
      this.launcher = launcher;
      this.check = check;
      this.stop = stop;
      this.close = close;
      this.errorCode = errorCode;
    }

    @Override
    public synchronized int exitValue() {
      int value = launcher == null ? 125 : launcher.exitValue();
      try {
        if (!check.confirmed(launcher)) throw new IllegalThreadStateException(errorCode);
        if (!recordClosed) {
          close.close();
          recordClosed = true;
        }
      } catch (IOException unknown) {
        IllegalThreadStateException error = new IllegalThreadStateException(errorCode);
        error.initCause(unknown);
        throw error;
      }
      return value;
    }

    @Override
    public int waitFor() throws InterruptedException {
      for (; ; ) {
        try {
          return exitValue();
        } catch (IllegalThreadStateException waiting) {
          Thread.sleep(100);
        }
      }
    }

    @Override
    public InputStream getInputStream() {
      return launcher == null
          ? new java.io.ByteArrayInputStream(new byte[0])
          : launcher.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return launcher == null
          ? new java.io.ByteArrayInputStream(new byte[0])
          : launcher.getErrorStream();
    }

    @Override
    public OutputStream getOutputStream() {
      return launcher == null ? new java.io.ByteArrayOutputStream() : launcher.getOutputStream();
    }

    @Override
    public void destroy() {
      stop.run();
    }
  }
}
