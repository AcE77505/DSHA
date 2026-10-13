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

  @Test
  public void privateReceiptUsesLogicalPathAndNeverLeaksOutsideAbsoluteTarget() {
    var root = new java.io.File("synthetic-private").getAbsoluteFile();
    var failure =
        new OwnershipCheckFailure(
            new java.io.File(root, "files/candidate").getPath(), 1, 2, 0, 7, 8);
    assertEquals(
        "files" + java.io.File.separator + "candidate",
        FactoryReset.ownershipDiagnostic(root, failure).get("path"));
    var outside =
        new OwnershipCheckFailure(new java.io.File("unrelated").getAbsolutePath(), 1, 2, 0, 7, 8);
    assertEquals(
        "UNMAPPED_PRIVATE_TARGET", FactoryReset.ownershipDiagnostic(root, outside).get("path"));
  }
}
