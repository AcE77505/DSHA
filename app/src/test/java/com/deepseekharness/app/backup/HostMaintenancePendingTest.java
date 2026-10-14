package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Exercises the production read-only domain sequence with synthetic probes only. */
public class HostMaintenancePendingTest {
  private static final File FILES = new File("synthetic-files");

  @Test
  public void productionInventoryIncludesLegacyRestoreWithExactWeights() {
    assertEquals(
        List.of(
            "legacy-restore",
            "cold-install",
            "legacy-maintenance",
            "legacy-runtime",
            "managed-runtime",
            "startup-configuration",
            "plugin-install",
            "environment-rebuild",
            "host-data"),
        HostMaintenancePending.RECOVERABLE.stream().map(domain -> domain.name).toList());
    assertEquals(
        List.of(false, false, false, false, true, false, false, false, true),
        HostMaintenancePending.RECOVERABLE.stream().map(domain -> domain.countEach).toList());
    assertFalse(
        HostMaintenancePending.RECOVERABLE.stream()
            .anyMatch(domain -> domain.name.equals("bounded-guest")));
  }

  @Test
  public void bothProductionReadAdaptersNormalizeOnlyTheTrustedFilesRoot() throws Exception {
    java.nio.file.Path main =
        java.nio.file.Path.of(System.getProperty("dsha.assetsDir", "app/src/main/assets"))
            .toAbsolutePath()
            .getParent();
    java.nio.file.Path source = main.resolve("java/com/deepseekharness/app");
    String domains =
        java.nio.file.Files.readString(source.resolve("backup/HostMaintenancePending.java"));
    String legacy =
        domains.substring(
            domains.indexOf("\"legacy-restore\""), domains.indexOf("\"cold-install\""));
    assertTrue(
        legacy
            .replaceAll("\\s+", " ")
            .contains(
                "LegacyRestoreJournalRecovery.pending( new AndroidBackupFileSystem(), root.getCanonicalFile())"));
    String manager = java.nio.file.Files.readString(source.resolve("BackupManager.java"));
    String startup =
        manager.substring(
            manager.indexOf("public static void requireLegacyRestoreClosed("),
            manager.indexOf("public static void recoverInterrupted("));
    assertTrue(startup.contains("controller.context().getFilesDir().getCanonicalFile()"));
    String reader =
        java.nio.file.Files.readString(source.resolve("backup/LegacyRestoreJournalReader.java"));
    assertFalse(reader.contains("getCanonical"));
    assertTrue(reader.contains("return fs.child(files, GuestPaths.ROOT_RELATIVE + guest)"));
  }

  @Test
  public void ordinaryGateReturnsFirstDomainAndDoesNotProbeLaterOnes() {
    AtomicInteger later = new AtomicInteger();
    var domains =
        List.of(
            new HostMaintenancePending.Domain("legacy-runtime", root -> List.of(), false),
            new HostMaintenancePending.Domain(
                "plugin-install", root -> List.of("first", "second"), false),
            new HostMaintenancePending.Domain(
                "host-data",
                root -> {
                  later.incrementAndGet();
                  return List.of("later");
                },
                true));
    var check = HostMaintenancePending.inspectRecoverable(FILES, domains);
    assertTrue(check.blocked);
    assertEquals("plugin-install", check.domain);
    assertEquals("first", check.operation);
    assertEquals(0, later.get());
  }

  @Test
  public void pluginCandidatesCountAsOneButHostAndManagedCountEach() throws Exception {
    var plugin =
        new HostMaintenancePending.Domain("plugin-install", root -> List.of("a", "b", "c"), false);
    assertFalse(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(plugin)));
    var legacy = new HostMaintenancePending.Domain("legacy-runtime", root -> List.of("old"), false);
    assertTrue(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(plugin, legacy)));
    var host = new HostMaintenancePending.Domain("host-data", root -> List.of("a", "b"), true);
    var runtime =
        new HostMaintenancePending.Domain("managed-runtime", root -> List.of("a", "b"), true);
    assertTrue(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(host)));
    assertTrue(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(runtime)));
  }

  @Test
  public void multipleCountStopsAtTwoAndUnreadableStillFailsClosed() throws Exception {
    AtomicInteger later = new AtomicInteger();
    var first = new HostMaintenancePending.Domain("host-data", root -> List.of("a", "b"), true);
    var unreadable =
        new HostMaintenancePending.Domain(
            "startup-configuration",
            root -> {
              later.incrementAndGet();
              throw new IOException("damaged journal");
            },
            false);
    assertTrue(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(first, unreadable)));
    assertEquals(0, later.get());
    assertThrows(
        IOException.class,
        () -> HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(unreadable)));
    var display = HostMaintenancePending.inspectRecoverable(FILES, List.of(unreadable));
    assertTrue(display.blocked);
    assertEquals("startup-configuration", display.domain);
    assertEquals("unknown", display.operation);
    assertTrue(display.error.contains("damaged journal"));
  }
}
