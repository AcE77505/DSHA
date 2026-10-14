package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;
import com.deepseekharness.app.util.ProcessIdentity;
import org.junit.Test;

public class OwnedBirthEvidenceTest {
  private ProcessIdentity identity(long birth, int session) {
    String[] fields = new String[20];
    java.util.Arrays.fill(fields, "0");
    fields[0] = "S";
    fields[1] = "42";
    fields[2] = "123";
    fields[3] = String.valueOf(session);
    fields[19] = String.valueOf(birth);
    return ProcessIdentity.fromStat("123 (signed session) " + String.join(" ", fields), 123, 42);
  }

  @Test
  public void currentDeniedInspectionStillRequiresLiveOriginalKernelSessionAndPeer() {
    var born = identity(7, 123);
    assertSame(born, OwnedBirthEvidence.current(born, null, true, 123));
    assertNull(OwnedBirthEvidence.current(born, null, false, 123));
    assertNull(OwnedBirthEvidence.current(born, null, true, 124));
    assertNull(OwnedBirthEvidence.current(born, null, true, -1));
    assertTrue(OwnedBirthEvidence.peer(born, 123, 10000, 10000, true));
    assertFalse(OwnedBirthEvidence.peer(born, 124, 10000, 10000, true));
    assertFalse(OwnedBirthEvidence.peer(born, 123, 10001, 10000, true));
    assertFalse(OwnedBirthEvidence.peer(born, 123, 10000, 10000, false));
  }

  @Test
  public void inspectedReusedBirthAndIncorrectSessionCannotUseDeniedFallback() {
    var born = identity(7, 123);
    assertNull(OwnedBirthEvidence.current(born, identity(8, 123), true, 123));
    assertNull(OwnedBirthEvidence.current(born, identity(7, 124), true, 123));
    assertNull(OwnedBirthEvidence.current(null, null, true, 123));
    assertFalse(OwnedBirthEvidence.peer(identity(7, 124), 123, 10000, 10000, true));
  }
}
