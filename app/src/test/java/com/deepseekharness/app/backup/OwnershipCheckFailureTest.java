package com.deepseekharness.app.backup;

import org.junit.Test;
import static org.junit.Assert.*;

public class OwnershipCheckFailureTest {
  @Test
  public void rejectionCodeStaysFixedAndEvidenceDoesNotBecomeUiMessage() {
    var failure = new OwnershipCheckFailure("candidate/nested", 10123, 0, 040755, 7, 99);
    assertEquals("RETIRED_DIRECTORY_NOT_OWNED", BackupErrorCode.from(failure));
    assertFalse(failure.toString().contains("candidate"));
    assertEquals(0L, failure.diagnostic.get("actualUid"));
    assertEquals(10123L, failure.diagnostic.get("expectedUid"));
  }
}
