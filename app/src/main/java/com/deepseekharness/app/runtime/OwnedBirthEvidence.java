package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.ProcessIdentity;

/** Current pre-guest signed birth only; never used to claim an old PID or release an exit barrier. */
final class OwnedBirthEvidence {
  static ProcessIdentity current(
      ProcessIdentity born, ProcessIdentity inspected, boolean rawAlive, int sid) {
    if (born == null || !rawAlive || !born.ownsSession() || born.exited()) return null;
    if (inspected != null)
      return born.sameProcess(inspected) && inspected.ownsSession() && !inspected.exited()
          ? inspected
          : null;
    return sid == born.session ? born : null;
  }

  static boolean peer(ProcessIdentity born, int pid, int uid, int appUid, boolean rawAlive) {
    return born != null
        && rawAlive
        && born.ownsSession()
        && !born.exited()
        && born.pid == pid
        && uid == appUid;
  }

  private OwnedBirthEvidence() {}
}
