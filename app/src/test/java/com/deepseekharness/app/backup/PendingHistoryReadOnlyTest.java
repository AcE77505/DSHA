package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Ordinary maintenance inventory must work even when history cannot be written. */
public final class PendingHistoryReadOnlyTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private final BackupFileSystem delegate = new JvmBackupFileSystem();
  private final BackupFileSystem readOnly =
      (BackupFileSystem)
          Proxy.newProxyInstance(
              BackupFileSystem.class.getClassLoader(),
              new Class[] {BackupFileSystem.class},
              (proxy, method, args) -> {
                if (Set.of(
                        "create",
                        "createOwned",
                        "directory",
                        "move",
                        "delete",
                        "syncDirectory",
                        "mode",
                        "symlink",
                        "prepareOwnedRemoval",
                        "parents",
                        "removeOwned",
                        "atomic")
                    .contains(method.getName()))
                  throw new IOException("PENDING_MUTATION_" + method.getName());
                try {
                  return method.invoke(delegate, args);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });

  private List<String> domains() {
    return List.of("host", "configuration", "environment", "runtime", "plugin");
  }

  private File home(File files, String domain) {
    return new File(
        files,
        switch (domain) {
          case "host" -> HostOperationArchive.ROOT;
          case "configuration" -> ConfigurationSnapshots.OPERATIONS;
          case "environment" -> EnvironmentRebuildTransaction.HOME;
          case "runtime" -> ManagedRuntimeTransaction.HOME;
          case "plugin" -> "dsh/" + PluginInstallJournals.DIRECTORY;
          default -> throw new IllegalArgumentException(domain);
        });
  }

  private void inspect(File files, String domain) throws IOException {
    switch (domain) {
      case "host" -> assertTrue(HostPendingTransactions.pending(readOnly, files).isEmpty());
      case "configuration" ->
          assertFalse(
              new ConfigurationSnapshots(readOnly, files, new File(files, "dsh")).pending());
      case "environment" -> assertNull(EnvironmentRebuildTransaction.pending(readOnly, files));
      case "runtime" -> assertTrue(ManagedRuntimeTransaction.pending(readOnly, files).isEmpty());
      case "plugin" ->
          assertTrue(PluginInstallJournals.pending(readOnly, new File(files, "dsh")).isEmpty());
      default -> throw new IllegalArgumentException(domain);
    }
  }

  private File proof(File files, String domain) {
    return new File(
        domain.equals("plugin") ? home(files, domain) : files,
        switch (domain) {
          case "host" -> "host-backup-completed-v1.json";
          case "configuration" -> "startup-config-completed-v1.json";
          case "environment" -> "host-environment-completed-v1.json";
          case "runtime" -> "host-runtime-completed-v1.json";
          case "plugin" -> ".completed-proof-v1.json";
          default -> throw new IllegalArgumentException(domain);
        });
  }

  @Test
  public void legacyCompletedWithoutProofIsInspectedWithoutAnyMutation() throws Exception {
    for (String domain : domains()) {
      File files = temp.newFolder(domain), history = new File(home(files, domain), "completed");
      Files.createDirectories(history.toPath());
      for (int repetition = 0; repetition < 3; repetition++) inspect(files, domain);
      try (var contents = Files.walk(files.toPath())) {
        assertTrue(domain, contents.allMatch(Files::isDirectory));
      }
    }
  }

  @Test
  public void completedBytesAndStaleProofStayUnchangedDuringReadOnlyInspection() throws Exception {
    for (String domain : domains()) {
      File files = temp.newFolder(domain),
          operation = new File(home(files, domain), "completed/" + UUID.randomUUID());
      Files.createDirectories(operation.toPath());
      String id = operation.getName();
      String terminal =
          switch (domain) {
            case "environment", "plugin" -> "committed";
            default -> "finalized";
          };
      Files.writeString(new File(operation, terminal).toPath(), id + "\n" + terminal + "\n");
      Map<String, Object> plan = Map.of("version", 1L, "id", id);
      String planName = "plan.json";
      switch (domain) {
        case "host" -> {
          planName = "operation.json";
          plan =
              Map.of("version", 1L, "id", id, "busy", false, "stage", "FINISHED", "artifact", "");
        }
        case "environment" -> {
          planName = "intent.json";
          plan =
              Map.of(
                  "version",
                  1L,
                  "id",
                  id,
                  "had",
                  false,
                  "oldKey",
                  "",
                  "archive",
                  "a".repeat(64),
                  "mapping",
                  "b".repeat(64));
        }
        case "runtime" ->
            Files.write(
                new File(operation, "runtime-mapping.json").toPath(),
                BackupJson.write(
                    Map.of(
                        "version",
                        1L,
                        "id",
                        id,
                        "paths",
                        Map.of(
                            "runtime-0",
                            com.deepseekharness.app.util.ManagedRuntimeLayout.ROOT
                                + com.deepseekharness.app.util.ManagedRuntimeLayout.DSH),
                        "candidateDescriptor",
                        ManagedRuntimeTransactionTest.descriptor('a').json(),
                        "createdAt",
                        1L),
                    BackupLimits.MANIFEST));
        case "plugin" -> plan = Map.of("format", 2L, "id", id);
        default -> {}
      }
      Files.write(new File(operation, planName).toPath(), BackupJson.write(plan, 16384));
      String before = BackupTree.digest(delegate, files, new BackupControl(null));
      inspect(files, domain);
      assertEquals(domain, before, BackupTree.digest(delegate, files, new BackupControl(null)));

      File proof = proof(files, domain);
      Files.write(
          proof.toPath(),
          BackupJson.write(
              Map.of(
                  "version",
                  1L,
                  domain.equals("plugin") ? "homeKey" : "filesKey",
                  "old-owner",
                  "historyKey",
                  "old-history"),
              4096));
      before = BackupTree.digest(delegate, files, new BackupControl(null));
      inspect(files, domain);
      assertEquals(domain, before, BackupTree.digest(delegate, files, new BackupControl(null)));
    }
  }

  @Test
  public void explicitMaintenanceStillSealsValidatedHistoryForLaterReadOnlyChecks()
      throws Exception {
    for (String domain : domains()) {
      File files = temp.newFolder(domain), history = new File(home(files, domain), "completed");
      Files.createDirectories(history.toPath());
      inspect(files, domain);
      assertFalse(domain, proof(files, domain).exists());
      switch (domain) {
        case "host" -> HostOperationArchive.verifyCompleted(delegate, files);
        case "configuration" ->
            new ConfigurationSnapshots(delegate, files, new File(files, "dsh"))
                .recover(new BackupControl(null));
        case "environment" -> EnvironmentRebuildTransaction.archiveCompleted(delegate, files);
        case "runtime" -> ManagedRuntimeTransaction.archiveCompleted(delegate, files);
        case "plugin" -> PluginInstallJournals.archiveCompleted(delegate, new File(files, "dsh"));
        default -> throw new IllegalArgumentException(domain);
      }
      assertTrue(domain, proof(files, domain).isFile());
      String before = BackupTree.digest(delegate, files, new BackupControl(null));
      inspect(files, domain);
      assertEquals(domain, before, BackupTree.digest(delegate, files, new BackupControl(null)));
    }
  }

  @Test
  public void unknownCompletedRecordRemainsBlockedAndUntouchedWithoutProof() throws Exception {
    for (String domain : domains()) {
      File files = temp.newFolder(domain),
          unknown = new File(home(files, domain), "completed/" + UUID.randomUUID());
      Files.createDirectories(unknown.toPath());
      File original = new File(unknown, "retained-original.txt");
      Files.writeString(original.toPath(), "keep unknown history");
      IOException failure = assertThrows(IOException.class, () -> inspect(files, domain));
      assertFalse(domain + ": " + failure, failure.getMessage().startsWith("PENDING_MUTATION_"));
      assertEquals("keep unknown history", Files.readString(original.toPath()));
    }
  }
}
