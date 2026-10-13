package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class PortableExportPlanTest {
  @Test
  public void onlyExactSingleFilesAndPortableDirectoriesAreSelected() {
    assertTrue(PortableExportPlan.selectable("sessions/project/id/session.v4.jsonl.zstd"));
    assertTrue(PortableExportPlan.selectable("profiles/web/cordis.patch.yml"));
    assertTrue(PortableExportPlan.selectable(".credentials.yaml"));
    for (String path :
        new String[] {
          "x.credentials.yaml",
          "arbitrary/.credentials.yaml",
          "storages/workspace.json/x",
          "profiles/a/b/cordis.patch.yml",
          "profiles/../cordis.patch.yml",
          "sessions/x/session.lock",
          ".bridge_token",
          "node_modules/x",
          "C:/secret",
          "sessions\\x",
          "sessions/NUL",
          "sessions/a:b",
          "sessions/trailing."
        }) assertFalse(path, PortableExportPlan.selectable(path));
  }

  @Test
  public void directoryNamesContainingDotsAreNotPathTraversal() {
    assertTrue(PortableExportPlan.selectable("sessions/a..b/file"));
    assertFalse(PortableExportPlan.selectable("sessions/../file"));
  }

  @Test
  public void credentialsNeedExplicitOptInAndMachineRecordsAreAlwaysPruned() {
    assertFalse(PortableExportPlan.credentialsIncludedByDefault());
    assertTrue(PortableExportPlan.mustPruneCredentialRecord("client-connection/any"));
    assertFalse(PortableExportPlan.mustPruneCredentialRecord("deepseek/api-key"));
  }

  @Test
  public void archiveIdentityIsStrictAndDoesNotAcceptAnEmptyName() {
    assertTrue(
        PortableExportPlan.looksLikePortableArchive(PortableExportPlan.archiveName("fixture")));
    assertFalse(PortableExportPlan.looksLikePortableArchive("DSHA-portable-.tar.gz"));
    assertThrows(IllegalArgumentException.class, () -> PortableExportPlan.archiveName("../x"));
    assertEquals(14, PortableExportPlan.excluded().size());
  }
}
