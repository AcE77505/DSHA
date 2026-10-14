package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.ProcessIdentity;
import java.io.IOException;

/** 复用 JNI 已建立的会话及出生身份，不再次 setsid。 */
public final class PtyGuestOwner {
  private final BoundedGuestSessions.Operation record;
  private final NativeTreeControl control;
  private boolean closed;
  public final String[] argv;

  public PtyGuestOwner(Context context, String[] engineArgv) throws IOException {
    String supervisor = IsolatedInstallProcess.bundledLauncher(context);
    if (supervisor == null) throw new IOException("PTY_SUPERVISOR_UNAVAILABLE");
    record = BoundedGuestSessions.begin(context.getFilesDir());
    NativeTreeControl preparing = null;
    try {
      record.nativeProtocol();
      preparing = new NativeTreeControl(record);
      var command = new java.util.ArrayList<String>();
      java.util.Collections.addAll(
          command,
          supervisor,
          "--pty-existing-session",
          preparing.argument(),
          "--stop-signal=" + (engineArgv[0].contains("libproot") ? 3 : 15),
          "--");
      java.util.Collections.addAll(command, engineArgv);
      argv = command.toArray(new String[0]);
      control = preparing;
    } catch (IOException | RuntimeException | Error failure) {
      try {
        if (preparing != null) preparing.close();
        record.completedBeforeDispatch();
      } catch (IOException cleanup) {
        record.uncertain();
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  public void accept(ProcessIdentity identity, java.util.function.BooleanSupplier live)
      throws IOException {
    ProcessIdentity current = null;
    if (identity != null)
      try {
        current =
            ProcessIdentity.fromStat(
                com.deepseekharness.app.util.Compat.readAll(
                    new java.io.File("/proc/" + identity.pid + "/stat")),
                identity.pid,
                android.os.Process.myPid());
      } catch (IOException unreadable) {
      }
    if (OwnedBirthEvidence.current(
            identity,
            current,
            live.getAsBoolean(),
            identity == null ? -1 : NativeProcess.sessionId(identity.pid))
        == null) throw new IOException("PTY_CURRENT_BIRTH_UNCONFIRMED");
    control.accept(identity, live);
  }

  public void stop() throws IOException {
    if (control.neverDispatched()) control.close();
    else control.stop(false);
  }

  public void abortBeforeFork() throws IOException {
    if (!control.neverDispatched()) throw new IOException("PTY_DISPATCH_STATE_UNKNOWN");
    control.close();
    record.completedBeforeDispatch();
    closed = true;
  }

  public synchronized void confirmed(int nativeStatus) throws IOException {
    if (closed) return;
    if (control.neverDispatched()) {
      record.completedBeforeDispatch();
      control.close();
      closed = true;
      return;
    }
    if (!control.treeEmpty()
        || control.commandExit() == null
        || control.commandExit() != nativeStatus)
      throw new IOException("PTY_OWNED_TREE_EXIT_UNCONFIRMED:" + control.unknown());
    record.completed();
    control.close();
    closed = true;
  }

  public void uncertain() {
    record.uncertain();
  }
}
