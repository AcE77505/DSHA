package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorkspaceEntryPolicyTest {
  @Test
  public void hostBackupAndRescueRemainReachableAcrossReadinessPendingAndBusyStates() {
    for (boolean ready : new boolean[] {false, true})
      for (boolean pending : new boolean[] {false, true})
        for (boolean busy : new boolean[] {false, true}) {
          boolean recovery = !ready || pending;
          assertTrue(
              WorkspaceEntryPolicy.enabled(WorkspaceEntryPolicy.Entry.HOST_DATA, busy, recovery));
          assertEquals(
              ready && !pending && !busy,
              WorkspaceEntryPolicy.enabled(
                  WorkspaceEntryPolicy.Entry.CONFIG_RESET, busy, recovery));
        }
  }

  @Test
  public void openingHostDataDuringRecoveryNeverEnablesConfigurationReset() {
    assertTrue(WorkspaceEntryPolicy.enabled(WorkspaceEntryPolicy.Entry.HOST_DATA, false, true));
    assertFalse(WorkspaceEntryPolicy.enabled(WorkspaceEntryPolicy.Entry.CONFIG_RESET, false, true));
    assertTrue(WorkspaceEntryPolicy.enabled(WorkspaceEntryPolicy.Entry.HOST_DATA, true, false));
    assertFalse(WorkspaceEntryPolicy.enabled(WorkspaceEntryPolicy.Entry.CONFIG_RESET, true, false));
  }
}
