package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** 全部使用临时实际目录；外置目录内同时放项目与手动备份。 */
public class LegacyRestoreJournalRecoveryTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();
  private static final String TOKEN = "20261014-120000-a1b2c3d4";

  private File files() throws Exception {
    File files = temp.newFolder();
    Files.createDirectories(new File(files, "linux/ubuntu/root").toPath());
    return files;
  }

  private Map<String, Object> row(String target, boolean existed) {
    return Map.of(
        "dst",
        target,
        "old",
        target + ".pre-restore-" + TOKEN,
        "new",
        target + ".restore-stage-" + TOKEN,
        "existed",
        existed);
  }

  private byte[] journal(File files, boolean complete, List<Map<String, Object>> rows)
      throws Exception {
    byte[] bytes =
        BackupJson.write(Map.of("complete", complete, "steps", rows), BackupLimits.MANIFEST);
    Files.write(new File(files, LegacyRestoreJournalReader.JOURNAL).toPath(), bytes);
    return bytes;
  }

  private void put(File file, String text) throws Exception {
    Files.createDirectories(file.getParentFile().toPath());
    Files.writeString(file.toPath(), text);
  }

  private String recover(File files, LegacyRestoreJournalRecovery.Fault fault) throws Exception {
    return LegacyRestoreJournalRecovery.recover(
        fs, files, () -> {}, fault, new BackupControl(null));
  }

  @Test
  public void externalAliasesAreNeverOpenedOrMutatedAndOriginalJournalRemains() throws Exception {
    for (String alias :
        List.of(
            "/sdcard/Download/workspace",
            "/storage/emulated/0/Download/workspace",
            "/mnt/runtime/write/emulated/0/Download/workspace")) {
      File files = files();
      byte[] before = journal(files, false, List.of(row(alias, true)));
      var guarded =
          new JvmBackupFileSystem() {
            @Override
            public Node stat(File file) throws IOException {
              String path = file.getAbsolutePath().replace('\\', '/');
              if (path.contains("/sdcard/")
                  || path.contains("/storage/")
                  || path.contains("/mnt/runtime/"))
                throw new AssertionError("External filesystem access: " + path);
              return super.stat(file);
            }
          };
      assertEquals(
          "RETAINED_EXTERNAL",
          LegacyRestoreJournalRecovery.recover(
              guarded, files, () -> {}, null, new BackupControl(null)));
      assertArrayEquals(
          before, Files.readAllBytes(new File(files, LegacyRestoreJournalReader.JOURNAL).toPath()));
      assertNull(LegacyRestoreJournalRecovery.pending(guarded, files));
    }
  }

  @Test
  public void privateRollbackPreservesCurrentBackupAndCandidates() throws Exception {
    File files = files(),
        home = new File(files, "linux/ubuntu/root"),
        target = new File(home, ".dsh"),
        old = new File(home, ".dsh.pre-restore-" + TOKEN),
        next = new File(home, ".dsh.restore-stage-" + TOKEN);
    put(new File(target, "sessions/new"), "new conversation");
    put(new File(target, "manual-backup.tar.gz"), "manual backup created later");
    put(new File(old, "sessions/old"), "original");
    put(new File(next, "candidate"), "candidate");
    byte[] log = journal(files, false, List.of(row("/root/.dsh", true)));
    assertEquals("PRIVATE_RECOVERED", recover(files, null));
    assertEquals("original", Files.readString(new File(target, "sessions/old").toPath()));
    String id = LegacyRestoreJournalReader.read(fs, files).sha256;
    File kept = new File(files, LegacyRestoreJournalRecovery.HOME + "/" + id + "/displaced/row-0");
    assertEquals(
        "manual backup created later",
        Files.readString(new File(kept, "manual-backup.tar.gz").toPath()));
    assertEquals("candidate", Files.readString(new File(next, "candidate").toPath()));
    assertArrayEquals(
        log, Files.readAllBytes(new File(files, LegacyRestoreJournalReader.JOURNAL).toPath()));
    assertEquals("NO_LEGACY_RESTORE", recover(files, null));
  }

  @Test
  public void interruptionAtEveryRenameConvergesWithoutDeletingAnyVersion() throws Exception {
    for (String boundary : List.of("prepared", "displaced-0", "restored-0")) {
      File files = files(), home = new File(files, "linux/ubuntu/root");
      put(new File(home, ".dsh/current"), "current");
      put(new File(home, ".dsh.pre-restore-" + TOKEN + "/original"), "old");
      journal(files, false, List.of(row("/root/.dsh", true)));
      assertThrows(
          IOException.class,
          () ->
              recover(
                  files,
                  name -> {
                    if (name.equals(boundary)) throw new IOException("fixture interruption");
                  }));
      assertEquals("PRIVATE_RECOVERED", recover(files, null));
      assertEquals("old", Files.readString(new File(home, ".dsh/original").toPath()));
      String id = LegacyRestoreJournalReader.read(fs, files).sha256;
      assertEquals(
          "current",
          Files.readString(
              new File(
                      files,
                      LegacyRestoreJournalRecovery.HOME + "/" + id + "/displaced/row-0/current")
                  .toPath()));
    }
  }

  @Test
  public void changedCurrentBytesAfterPreflightAreRetainedAndBlockRecovery() throws Exception {
    File files = files(), home = new File(files, "linux/ubuntu/root");
    put(new File(home, ".dsh/current"), "before");
    put(new File(home, ".dsh.pre-restore-" + TOKEN + "/original"), "old");
    journal(files, false, List.of(row("/root/.dsh", true)));
    assertThrows(
        IOException.class,
        () ->
            recover(
                files,
                name -> {
                  if (name.equals("prepared")) throw new IOException("fixture interruption");
                }));
    put(new File(home, ".dsh/current"), "later edit");
    assertThrows(IOException.class, () -> recover(files, null));
    assertEquals("later edit", Files.readString(new File(home, ".dsh/current").toPath()));
    assertNotNull(LegacyRestoreJournalRecovery.pending(fs, files));
  }

  @Test
  public void missingOriginalNeverGuessesThatCurrentTreeIsTheOriginal() throws Exception {
    File files = files();
    put(new File(files, "linux/ubuntu/root/.dsh/current"), "current");
    journal(files, false, List.of(row("/root/.dsh", true)));
    assertThrows(IOException.class, () -> recover(files, null));
    assertEquals(
        "current", Files.readString(new File(files, "linux/ubuntu/root/.dsh/current").toPath()));
    assertNotNull(LegacyRestoreJournalRecovery.pending(fs, files));
  }

  @Test
  public void completeJournalKeepsLaterPrivateChangesAndExternalReferencesInert() throws Exception {
    File files = files();
    put(new File(files, "linux/ubuntu/root/.dsh/current"), "later edit");
    journal(files, true, List.of(row("/root/.dsh", true), row("/sdcard/Download/workspace", true)));
    assertEquals("COMMITTED_RETAINED", recover(files, null));
    assertEquals(
        "later edit", Files.readString(new File(files, "linux/ubuntu/root/.dsh/current").toPath()));
  }

  @Test
  public void unconfirmedExitCannotCreateRecoveryOperationOrRetireExternalReference()
      throws Exception {
    File files = files();
    journal(files, false, List.of(row("/sdcard/Download/workspace", true)));
    assertThrows(
        IOException.class,
        () ->
            LegacyRestoreJournalRecovery.recover(
                fs,
                files,
                () -> {
                  throw new IOException("guest still running");
                },
                null,
                new BackupControl(null)));
    assertFalse(new File(files, LegacyRestoreJournalRecovery.HOME).exists());
    assertNotNull(LegacyRestoreJournalRecovery.pending(fs, files));
  }

  @Test
  public void malformedOrUnknownPrivateRowsArePreservedWithoutTargetMutation() throws Exception {
    File files = files();
    byte[] bytes = journal(files, false, List.of(row("/etc/private", true)));
    assertThrows(IOException.class, () -> recover(files, null));
    assertArrayEquals(
        bytes, Files.readAllBytes(new File(files, LegacyRestoreJournalReader.JOURNAL).toPath()));
    File another = files();
    var malformed = new LinkedHashMap<>(row("/root/.dsh", true));
    malformed.put("old", "/sdcard/other");
    journal(another, false, List.of(malformed));
    assertThrows(IOException.class, () -> recover(another, null));
    assertFalse(new File(another, LegacyRestoreJournalRecovery.HOME).exists());
  }

  @Test
  public void legacyRegistryCannotClaimLaterPrivateWorkspaceOrItsManualBackup() throws Exception {
    File files = files();
    put(new File(files, "linux/ubuntu/root/project/manual-backup.tar.gz"), "backup");
    journal(files, false, List.of(row("/root/project", false)));
    assertThrows(IOException.class, () -> recover(files, null));
    assertEquals(
        "backup",
        Files.readString(
            new File(files, "linux/ubuntu/root/project/manual-backup.tar.gz").toPath()));
    assertNotNull(LegacyRestoreJournalRecovery.pending(fs, files));
  }

  @Test
  public void linkedPrivateParentCannotAccessExternalWorkspace() throws Exception {
    File files = files(), external = temp.newFolder();
    put(new File(external, "manual-backup.tar.gz"), "keep");
    File link = new File(files, "linux/ubuntu/root/project");
    try {
      Files.createSymbolicLink(link.toPath(), external.toPath());
    } catch (IOException | UnsupportedOperationException unavailable) {
      Assume.assumeNoException(unavailable);
    }
    journal(files, false, List.of(row("/root/project/.env", true)));
    assertThrows(
        IOException.class,
        () ->
            LegacyRestoreJournalRecovery.recover(
                fs, files, "/root/project", () -> {}, null, new BackupControl(null)));
    assertEquals("keep", Files.readString(new File(external, "manual-backup.tar.gz").toPath()));
    assertTrue(Files.isSymbolicLink(link.toPath()));
  }

  @Test
  public void changedOrMissingSourceJournalCannotHideInterruptedPrivateRecovery() throws Exception {
    for (boolean remove : List.of(false, true)) {
      File files = files(), home = new File(files, "linux/ubuntu/root");
      put(new File(home, ".dsh/current"), "current");
      put(new File(home, ".dsh.pre-restore-" + TOKEN + "/original"), "old");
      journal(files, false, List.of(row("/root/.dsh", true)));
      String original = LegacyRestoreJournalReader.read(fs, files).sha256;
      assertThrows(
          IOException.class,
          () ->
              recover(
                  files,
                  name -> {
                    if (name.equals("displaced-0")) throw new IOException("fixture interruption");
                  }));
      if (remove) Files.delete(new File(files, LegacyRestoreJournalReader.JOURNAL).toPath());
      else journal(files, true, List.of(row("/sdcard/Download/other", true)));
      assertEquals(original, LegacyRestoreJournalRecovery.pending(fs, files));
      assertThrows(IOException.class, () -> recover(files, null));
      assertEquals(
          "old",
          Files.readString(new File(home, ".dsh.pre-restore-" + TOKEN + "/original").toPath()));
    }
  }

  @Test
  public void guestHostAliasesOnlyMapToThisPrivateRootAndSafIsNeverUsedAsPath() throws Exception {
    for (String prefix :
        List.of(
            "/data/data/com.dsh.client/files/linux/ubuntu",
            "/data/user/0/com.dsh.client/files/linux/ubuntu",
            "/data/user_de/0/com.dsh.client/files/linux/ubuntu")) {
      File files = files(), home = new File(files, "linux/ubuntu/root");
      put(new File(home, ".dsh/current"), "current");
      put(new File(home, ".dsh.pre-restore-" + TOKEN + "/original"), "old");
      journal(files, false, List.of(row(prefix + "/root/.dsh", true)));
      assertEquals("PRIVATE_RECOVERED", recover(files, null));
      assertEquals("old", Files.readString(new File(home, ".dsh/original").toPath()));
    }
    File files = files();
    journal(files, false, List.of(row("content://fixture/tree/workspace", true)));
    assertThrows(IOException.class, () -> recover(files, null));
    assertFalse(new File(files, LegacyRestoreJournalRecovery.HOME).exists());
  }

  @Test
  public void changedOriginalIsRejectedBeforeCurrentContentIsDisplaced() throws Exception {
    File files = files(), home = new File(files, "linux/ubuntu/root");
    put(new File(home, ".dsh/current"), "current");
    put(new File(home, ".dsh.pre-restore-" + TOKEN + "/original"), "old");
    journal(files, false, List.of(row("/root/.dsh", true)));
    assertThrows(
        IOException.class,
        () ->
            recover(
                files,
                name -> {
                  if (name.equals("prepared")) throw new IOException("fixture");
                }));
    put(new File(home, ".dsh.pre-restore-" + TOKEN + "/original"), "changed original");
    assertThrows(IOException.class, () -> recover(files, null));
    assertEquals("current", Files.readString(new File(home, ".dsh/current").toPath()));
  }

  @Test
  public void tamperedIntentCannotTurnCommittedJournalIntoRollback() throws Exception {
    File files = files();
    put(new File(files, "linux/ubuntu/root/.dsh/current"), "later data");
    journal(files, true, List.of(row("/root/.dsh", true)));
    assertThrows(
        IOException.class,
        () ->
            recover(
                files,
                name -> {
                  if (name.equals("prepared")) throw new IOException("fixture");
                }));
    String id = LegacyRestoreJournalReader.read(fs, files).sha256;
    File intent = new File(files, LegacyRestoreJournalRecovery.HOME + "/" + id + "/intent.json");
    var plan = BackupJson.read(Files.readAllBytes(intent.toPath()), BackupLimits.MANIFEST);
    @SuppressWarnings("unchecked")
    var first = (Map<String, Object>) ((List<?>) plan.get("rows")).get(0);
    first.put("action", "RESTORE_ORIGINAL");
    Files.write(intent.toPath(), BackupJson.write(plan, BackupLimits.MANIFEST));
    assertThrows(IOException.class, () -> recover(files, null));
    assertEquals(
        "later data", Files.readString(new File(files, "linux/ubuntu/root/.dsh/current").toPath()));
  }

  @Test
  public void formerlyMissingKnownTargetCannotClaimLaterManualData() throws Exception {
    File files = files();
    put(new File(files, "linux/ubuntu/root/.dsh/manual-backup.tar.gz"), "later backup");
    journal(files, false, List.of(row("/root/.dsh", false)));
    assertThrows(IOException.class, () -> recover(files, null));
    assertEquals(
        "later backup",
        Files.readString(new File(files, "linux/ubuntu/root/.dsh/manual-backup.tar.gz").toPath()));
    assertNotNull(LegacyRestoreJournalRecovery.pending(fs, files));
  }

  @Test
  public void exactNativePreviousWorkspacePermissionSurvivesCompletedReceipt() throws Exception {
    File files = files(),
        env = new File(files, "linux/ubuntu/root/project/.env"),
        old = new File(env.getPath() + ".pre-restore-" + TOKEN);
    put(env, "candidate env");
    put(old, "original env");
    journal(files, false, List.of(row("/root/project/.env", true)));
    assertEquals(
        "PRIVATE_RECOVERED",
        LegacyRestoreJournalRecovery.recover(
            fs, files, "/root/project", () -> {}, null, new BackupControl(null)));
    put(env, "later manual env edit");
    assertNull(LegacyRestoreJournalRecovery.pending(fs, files));
    assertEquals(
        "NO_LEGACY_RESTORE",
        LegacyRestoreJournalRecovery.recover(
            fs, files, "/root/project", () -> {}, null, new BackupControl(null)));
    assertEquals("later manual env edit", Files.readString(env.toPath()));
  }

  @Test
  public void copyingCompletedRowToDifferentIndexCannotSkipThatRow() throws Exception {
    File files = files(), home = new File(files, "linux/ubuntu/root/.dsh");
    for (String name : List.of("sessions", "storages")) {
      put(new File(home, name + "/current"), name + " current");
      put(new File(home, name + ".pre-restore-" + TOKEN + "/old"), name + " original");
    }
    journal(
        files, false, List.of(row("/root/.dsh/sessions", true), row("/root/.dsh/storages", true)));
    assertThrows(
        IOException.class,
        () ->
            recover(
                files,
                name -> {
                  if (name.equals("displaced-0")) throw new IOException("fixture");
                }));
    String id = LegacyRestoreJournalReader.read(fs, files).sha256;
    File operation = new File(files, LegacyRestoreJournalRecovery.HOME + "/" + id);
    Files.copy(
        new File(operation, "row-1.json").toPath(), new File(operation, "row-0.json").toPath());
    assertThrows(IOException.class, () -> recover(files, null));
    assertEquals(
        "sessions original",
        Files.readString(new File(home, "sessions.pre-restore-" + TOKEN + "/old").toPath()));
    assertNotNull(LegacyRestoreJournalRecovery.pending(fs, files));
  }

  @Test
  public void inertExternalRetirementLeavesRealHostTransactionAndItsBarrierIntact()
      throws Exception {
    File files = files(),
        operations = HostOperationArchive.reserve(fs, files),
        operation = fs.child(operations, "11111111-2222-4333-8444-555555555555"),
        target = new File(files, "live-data/sessions");
    fs.directory(operation);
    fs.directory(new File(operation, "candidate"));
    put(target, "original live data");
    put(new File(operation, "candidate/sessions"), "candidate data");
    HostDataTransaction.Settings settings =
        new HostDataTransaction.Settings() {
          public Map<String, Object> current() {
            return Map.of("native", "original");
          }

          public void apply(Map<String, Object> values) {
            throw new AssertionError("unrelated settings changed");
          }
        };
    var transaction =
        new HostDataTransaction(
            fs,
            operation,
            id -> target,
            settings,
            boundary -> {
              if (boundary.equals("switching"))
                throw new AssertionError("fixture process interruption");
            });
    transaction.prepare(
        List.of("sessions"),
        Map.of("sessions", BackupTree.digest(fs, target, new BackupControl(null))),
        settings.current(),
        new BackupControl(null));
    assertThrows(AssertionError.class, () -> transaction.commit(new BackupControl(null)));
    byte[] originalPlan = Files.readAllBytes(new File(operation, "plan.json").toPath());
    journal(files, false, List.of(row("/sdcard/Download/workspace", true)));
    assertEquals(List.of(operation), HostPendingTransactions.pending(fs, files));
    assertTrue(
        LegacyRestoreJournalRecovery.retireInert(fs, files, () -> {}, new BackupControl(null)));
    assertNull(LegacyRestoreJournalRecovery.pending(fs, files));
    assertEquals(List.of(operation), HostPendingTransactions.pending(fs, files));
    assertArrayEquals(originalPlan, Files.readAllBytes(new File(operation, "plan.json").toPath()));
    assertEquals("original live data", Files.readString(target.toPath()));
  }

  @Test
  public void inertRetirementCannotWriteForPrivateOrUnknownLegacyTransaction() throws Exception {
    for (String target : List.of("/root/.dsh", "/root/project", "/etc/unknown")) {
      File files = files();
      journal(files, false, List.of(row(target, true)));
      assertFalse(
          LegacyRestoreJournalRecovery.retireInert(fs, files, () -> {}, new BackupControl(null)));
      assertFalse(new File(files, LegacyRestoreJournalRecovery.HOME).exists());
      assertNotNull(LegacyRestoreJournalRecovery.pending(fs, files));
    }
  }

  private File trustedFilesThroughParentAlias(File files) throws Exception {
    File aliases = temp.newFolder(), parentAlias = new File(aliases, "data-user-0");
    try {
      Files.createSymbolicLink(parentAlias.toPath(), files.getParentFile().toPath());
    } catch (IOException | UnsupportedOperationException unavailable) {
      Assume.assumeNoException(unavailable);
    }
    return new File(parentAlias, files.getName());
  }

  /** Model the production anchored open while retaining real temporary reads and link identities. */
  private JvmBackupFileSystem anchoredReader() {
    return new JvmBackupFileSystem() {
      @Override
      public InputStream read(File file, Node expected) throws IOException {
        for (File parent = file.getAbsoluteFile().getParentFile();
            parent != null;
            parent = parent.getParentFile())
          if (super.stat(parent).type.equals("LINK")) throw new IOException("PARENT_LINK");
        return super.read(file, expected);
      }
    };
  }

  @Test
  public void trustedParentAliasCanObserveExternalRetirementWithoutFalseBlocking()
      throws Exception {
    File files = files(),
        alias = trustedFilesThroughParentAlias(files),
        external = temp.newFolder(),
        manual = new File(external, "manual-backup.tar.gz");
    put(manual, "retained manual bytes");
    byte[] original = journal(files, false, List.of(row("/sdcard/Download/workspace", true)));
    var anchored = anchoredReader();
    assertThrows(IOException.class, () -> LegacyRestoreJournalRecovery.pending(anchored, alias));
    File trusted = alias.getCanonicalFile();
    assertEquals(files.getCanonicalFile(), trusted);
    assertNotNull(LegacyRestoreJournalRecovery.pending(anchored, trusted));
    assertTrue(
        LegacyRestoreJournalRecovery.retireInert(
            anchored, trusted, () -> {}, new BackupControl(null)));
    assertNull(LegacyRestoreJournalRecovery.pending(anchored, alias.getCanonicalFile()));
    assertArrayEquals(
        original, Files.readAllBytes(new File(files, LegacyRestoreJournalReader.JOURNAL).toPath()));
    assertEquals("retained manual bytes", Files.readString(manual.toPath()));
  }

  @Test
  public void trustedParentNormalizationDoesNotResolveAWorkspaceTargetLink() throws Exception {
    File files = files(),
        alias = trustedFilesThroughParentAlias(files),
        external = temp.newFolder(),
        manual = new File(external, "manual-backup.tar.gz"),
        target = new File(files, "linux/ubuntu/root/.dsh");
    put(manual, "external original");
    Files.createSymbolicLink(target.toPath(), external.toPath());
    byte[] original = journal(files, false, List.of(row("/root/.dsh", true)));
    var anchored = anchoredReader();
    File trusted = alias.getCanonicalFile();
    assertEquals(
        LegacyRestoreJournalReader.Kind.UNKNOWN,
        LegacyRestoreJournalReader.read(anchored, trusted).rows.get(0).kind);
    assertFalse(
        LegacyRestoreJournalRecovery.retireInert(
            anchored, trusted, () -> {}, new BackupControl(null)));
    assertThrows(
        IOException.class,
        () ->
            LegacyRestoreJournalRecovery.recover(
                anchored, trusted, () -> {}, null, new BackupControl(null)));
    assertNotNull(LegacyRestoreJournalRecovery.pending(anchored, trusted));
    assertArrayEquals(
        original, Files.readAllBytes(new File(files, LegacyRestoreJournalReader.JOURNAL).toPath()));
    assertTrue(Files.isSymbolicLink(target.toPath()));
    assertEquals("external original", Files.readString(manual.toPath()));
  }

  @Test
  public void trustedParentNormalizationDoesNotResolveTheJournalLink() throws Exception {
    File files = files(),
        alias = trustedFilesThroughParentAlias(files),
        external = temp.newFolder(),
        manual = new File(external, "manual-backup.tar.gz");
    put(manual, "external journal bytes");
    File source = new File(files, LegacyRestoreJournalReader.JOURNAL);
    Files.createSymbolicLink(source.toPath(), manual.toPath());
    assertThrows(
        IOException.class,
        () -> LegacyRestoreJournalRecovery.pending(anchoredReader(), alias.getCanonicalFile()));
    assertFalse(new File(files, LegacyRestoreJournalRecovery.HOME).exists());
    assertTrue(Files.isSymbolicLink(source.toPath()));
    assertEquals("external journal bytes", Files.readString(manual.toPath()));
  }
}
