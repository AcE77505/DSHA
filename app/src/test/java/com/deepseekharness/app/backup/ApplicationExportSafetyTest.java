package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Real temporary trees and streamed v5/tar roundtrips; no Android or user-data operation. */
public class ApplicationExportSafetyTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  void put(File file, String data) throws Exception {
    Files.createDirectories(file.toPath().getParent());
    Files.writeString(file.toPath(), data);
  }

  Map<String, Object> provenance() {
    return BackupArchiveTest.summary();
  }

  File snapshot(List<BackupSource> roots, File task, BackupControl control) throws Exception {
    File archive = new File(task, "snapshot.dshdata");
    HostSnapshot.create(fs, roots, task, archive, provenance(), false, control);
    return archive;
  }

  List<BackupArchive.Record> records(File archive) throws Exception {
    List<BackupArchive.Record> records = new ArrayList<>();
    try (InputStream input = fs.read(archive, fs.stat(archive))) {
      BackupArchive.read(
          input,
          new BackupArchive.Visitor() {
            public OutputStream payload(int n, BackupArchive.Record record) {
              records.add(record);
              return null;
            }
          },
          new BackupControl(null));
    }
    return records;
  }

  @Test
  public void allFiveScopesUseActualLocationCollectorAndRoundtripTarPayload() throws Exception {
    for (String scope : List.of("application", "sessions", "settings", "plugins", "projects")) {
      File files = temporary.newFolder(), pub = temporary.newFolder(), task = temporary.newFolder();
      File dsh = new File(files, "linux/ubuntu/root/.dsh");
      put(new File(dsh, "sessions/session.jsonl"), "conversation bytes");
      put(new File(dsh, "storages/registry.json"), "{}\n");
      put(new File(dsh, "attachments/image.dat"), "attachment bytes");
      put(new File(dsh, "settings.yaml"), "model: fixture\n");
      put(new File(dsh, "presets/persona.json"), "user persona bytes");
      put(new File(dsh, ".dsha-apikey"), "excluded legacy credential");
      put(
          new File(dsh, "node_modules/custom-plugin/package.json"),
          "{\"name\":\"custom-plugin\",\"version\":\"1.0.0\"}");
      put(new File(dsh, "node_modules/custom-plugin/main.js"), "custom plugin source");
      put(new File(dsh, "profiles/web/package.json"), "{\"name\":\"web\",\"version\":\"1\"}");
      put(
          new File(files, "linux/ubuntu/root/project/manual-backup.tar.gz"),
          "manual project backup");
      NativeDataLocations locations =
          new NativeDataLocations(
              fs,
              files,
              pub,
              key ->
                  ("{\"workdir\":\"root/project\""
                          + (key ? ",\"apiKey\":\"fixture-key\"" : "")
                          + "}")
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      NativeDataLocations.Selection selection = new NativeDataLocations.Selection();
      selection.scope = scope;
      if (scope.equals("application") || scope.equals("projects"))
        selection.guestProjects.add("/root/project");
      var located = locations.locate(selection, new BackupControl(null));
      assertFalse(scope, located.sources.isEmpty());
      Set<String> ids = new HashSet<>();
      for (var source : located.sources) assertTrue(scope, ids.add(source.id()));
      File archive = snapshot(located.sources, task, new BackupControl(null));
      List<BackupArchive.Record> record = records(archive);
      assertFalse(scope, record.isEmpty());
      assertTrue(scope, record.stream().noneMatch(r -> r.path.contains(".dsha-apikey")));
      File envelope = new File(task, "export.tar.gz"),
          unwrapped = new File(task, "roundtrip.dshdata");
      String hash;
      try (InputStream input = fs.read(archive, fs.stat(archive))) {
        hash = BackupArchive.digest(input, new BackupControl(null));
      }
      try (OutputStream output = fs.create(envelope)) {
        PortableBackupEnvelope.writeUnencrypted(fs, archive, hash, output, new BackupControl(null));
      }
      PortableBackupEnvelope.verifyUnencrypted(fs, envelope, hash, new BackupControl(null));
      assertTrue(
          PortableBackupEnvelope.unwrapIfPresent(fs, envelope, unwrapped, new BackupControl(null)));
      assertArrayEquals(
          Files.readAllBytes(archive.toPath()), Files.readAllBytes(unwrapped.toPath()));
      File restored = temporary.newFolder();
      try (InputStream input = fs.read(unwrapped, fs.stat(unwrapped))) {
        BackupArchive.read(
            input,
            new BackupArchive.Visitor() {
              File target(BackupArchive.Record r) throws IOException {
                String relative = r.root + (r.path.isEmpty() ? "" : "/" + r.path);
                fs.parents(restored, relative);
                return fs.child(restored, relative);
              }

              public OutputStream payload(int n, BackupArchive.Record r) throws IOException {
                return r.kind.equals("FILE") ? fs.create(target(r)) : null;
              }

              public void verified(int n, BackupArchive.Record r) throws IOException {
                if (r.kind.equals("DIRECTORY")) {
                  File at = target(r);
                  if (fs.stat(at).type.equals("MISSING")) fs.directory(at);
                }
              }
            },
            new BackupControl(null));
      }
      for (var r : record)
        if (r.kind.equals("FILE")) {
          File at = new File(restored, r.root + (r.path.isEmpty() ? "" : "/" + r.path));
          try (InputStream input = fs.read(at, fs.stat(at))) {
            assertEquals(scope, r.sha256, BackupArchive.digest(input, new BackupControl(null)));
          }
        }
      assertEquals(
          "manual project backup",
          Files.readString(
              new File(files, "linux/ubuntu/root/project/manual-backup.tar.gz").toPath()));
    }
  }

  @Test
  public void distinctLogicalRootsAreNotMergedByIdenticalContent() throws Exception {
    File source = temporary.newFolder(), task = temporary.newFolder();
    put(new File(source, "same"), "same bytes");
    File archive =
        snapshot(
            List.of(
                new FileBackupSource(fs, "first", "projects", source, false, null),
                new FileBackupSource(fs, "second", "projects", source, false, null)),
            task,
            new BackupControl(null));
    List<BackupArchive.Record> rows = records(archive);
    assertEquals(2, rows.stream().filter(r -> r.kind.equals("FILE")).count());
    assertEquals(Set.of("first", "second"), new HashSet<>(rows.stream().map(r -> r.root).toList()));
  }

  @Test
  public void caseAndUnicodeCollisionsRemainDistinctOnDiskAndFailWithOpaqueIdentity()
      throws Exception {
    for (List<String> pair :
        List.of(
            List.of("PRIVATE-secret-A", "private-secret-a"), List.of("caf\u00e9", "cafe\u0301"))) {
      File source = temporary.newFolder(), task = temporary.newFolder();
      put(new File(source, pair.get(0)), "first bytes");
      put(new File(source, pair.get(1)), "different second bytes");
      try (var names = Files.list(source.toPath())) {
        Assume.assumeTrue("requires a filesystem preserving both POSIX names", names.count() == 2);
      }
      IOException failure =
          assertThrows(
              IOException.class,
              () ->
                  snapshot(
                      List.of(
                          new FileBackupSource(
                              fs, "data-0123456789abcdef0123", "settings", source, false, null)),
                      task,
                      new BackupControl(null)));
      String code = BackupErrorCode.from(failure);
      assertTrue(code, code.matches("DUPLICATE_PATH:data-0123456789abcdef0123:[a-f0-9]{20}"));
      assertFalse(code.contains("PRIVATE-secret"));
      assertFalse(code.contains("caf"));
      assertFalse(code.contains(source.getAbsolutePath()));
      assertEquals("first bytes", Files.readString(new File(source, pair.get(0)).toPath()));
      assertEquals(
          "different second bytes", Files.readString(new File(source, pair.get(1)).toPath()));
      assertFalse(new File(task, "snapshot.dshdata").exists());
      assertFalse(new File(task, "capture").exists());
      assertFalse(new File(task, "source-checks").exists());
    }
  }

  @Test
  public void diagnosticsNeverAcceptArbitrarySuffixOrExposeUnknownRootNames() throws Exception {
    assertEquals(
        "IO_FAILURE", BackupErrorCode.from(new IOException("DUPLICATE_PATH:/private/key:secret")));
    String code =
        BackupPathFailure.at("DUPLICATE_PATH", "secret_private_key", "hidden/file").getMessage();
    assertTrue(code.matches("DUPLICATE_PATH:root-[a-f0-9]{20}:[a-f0-9]{20}"));
    assertFalse(code.contains("secret"));
    assertEquals("SOURCE_NOT_DIRECTORY", BackupErrorCode.from(new IOException("FILESYSTEM_20")));
    assertEquals("SOURCE_NOT_DIRECTORY", BackupErrorCode.errno(20));
    assertEquals(
        BackupPathFailure.at("DUPLICATE_PATH", "dsh", "A").getMessage(),
        BackupPathFailure.at("DUPLICATE_PATH", "dsh", "a").getMessage());
  }

  @Test
  public void intermediateFileIsRejectedBeforeAnyDescendantProbe() throws Exception {
    File root = temporary.newFolder();
    put(new File(root, "parent"), "file instead of directory");
    JvmBackupFileSystem guarded =
        new JvmBackupFileSystem() {
          public Node stat(File at) throws IOException {
            if (at.getPath().contains("parent/child"))
              throw new AssertionError("descendant stat after file");
            return super.stat(at);
          }
        };
    GuestDataResolver resolver = new GuestDataResolver(guarded, root, null, List.of());
    IOException failure =
        assertThrows(IOException.class, () -> resolver.resolve(new File(root, "parent/child")));
    assertEquals("SOURCE_NOT_DIRECTORY", failure.getMessage());
    assertEquals("file instead of directory", Files.readString(new File(root, "parent").toPath()));
  }

  @Test
  public void profileParentFileFailsWithSafeRootAndOriginalBytesStayInPlace() throws Exception {
    File files = temporary.newFolder(), pub = temporary.newFolder();
    put(new File(files, "linux/ubuntu/root/.dsh/profiles"), "manual profile file");
    var locations = new NativeDataLocations(fs, files, pub, key -> "{}".getBytes());
    NativeDataLocations.Selection selection = new NativeDataLocations.Selection();
    selection.scope = "application";
    IOException failure =
        assertThrows(IOException.class, () -> locations.locate(selection, new BackupControl(null)));
    assertTrue(BackupErrorCode.from(failure).matches("SOURCE_NOT_DIRECTORY:profiles:[a-f0-9]{20}"));
    assertEquals(
        "manual profile file",
        Files.readString(new File(files, "linux/ubuntu/root/.dsh/profiles").toPath()));
  }

  @Test
  public void preexistingPrivateArtifactIsNeverRemovedByCreationFailure() throws Exception {
    File source = temporary.newFolder(), task = temporary.newFolder();
    put(new File(source, "data"), "source");
    put(new File(task, "snapshot.dshdata"), "existing user target");
    put(new File(task, "capture"), "existing retained capture");
    assertThrows(
        IOException.class,
        () ->
            snapshot(
                List.of(new FileBackupSource(fs, "dsh", "application", source, false, null)),
                task,
                new BackupControl(null)));
    assertEquals(
        "existing user target", Files.readString(new File(task, "snapshot.dshdata").toPath()));
    assertEquals("existing retained capture", Files.readString(new File(task, "capture").toPath()));
  }

  @Test
  public void replacedArchiveInodeIsRetainedAfterFinalCheckFails() throws Exception {
    File source = temporary.newFolder(),
        task = temporary.newFolder(),
        archive = new File(task, "snapshot.dshdata");
    put(new File(source, "a"), "original");
    Assume.assumeFalse(
        "requires real inode identity; Linux runs it", fs.stat(task).key.equals("null"));
    assertThrows(
        IOException.class,
        () ->
            HostSnapshot.create(
                fs,
                List.of(new FileBackupSource(fs, "dsh", "application", source, false, null)),
                task,
                archive,
                provenance(),
                false,
                new BackupControl(null),
                control -> {
                  File kept = new File(task, "first-created");
                  fs.move(archive, kept);
                  try (OutputStream out = fs.create(archive)) {
                    out.write("replacement user bytes".getBytes());
                  }
                  throw new IOException("fixture failure");
                }));
    assertEquals("replacement user bytes", Files.readString(archive.toPath()));
    assertTrue(new File(task, "first-created").isFile());
  }

  @Test
  public void cancellationCleansOnlyCreatedPartsAndLeavesSuccessfulBackup() throws Exception {
    File source = temporary.newFolder(), task = temporary.newFolder();
    put(new File(source, "a"), "original");
    put(new File(task, "old-successful"), "backup bytes");
    BackupControl cancelled = new BackupControl(null);
    cancelled.cancel();
    assertThrows(
        InterruptedIOException.class,
        () ->
            snapshot(
                List.of(new FileBackupSource(fs, "dsh", "application", source, false, null)),
                task,
                cancelled));
    assertFalse(new File(task, "snapshot.dshdata").exists());
    assertEquals("backup bytes", Files.readString(new File(task, "old-successful").toPath()));
  }

  @Test
  public void realLinksRemainLinkRecordsAndNeverReadOutsideTargets() throws Exception {
    File source = temporary.newFolder(),
        outside = temporary.newFolder(),
        task = temporary.newFolder();
    put(new File(outside, "manual-backup.tar.gz"), "external backup");
    try {
      Files.createSymbolicLink(new File(source, "outside").toPath(), outside.toPath());
    } catch (FileSystemException unavailable) {
      Assume.assumeFalse(
          "Windows link privilege unavailable; Linux runs it",
          System.getProperty("os.name").startsWith("Windows"));
      throw unavailable;
    }
    File archive =
        snapshot(
            List.of(new FileBackupSource(fs, "project", "projects", source, false, null)),
            task,
            new BackupControl(null));
    var rows = records(archive);
    assertEquals(1, rows.stream().filter(r -> r.kind.equals("LINK")).count());
    assertTrue(rows.stream().noneMatch(r -> r.path.contains("manual-backup")));
    assertEquals(
        "external backup", Files.readString(new File(outside, "manual-backup.tar.gz").toPath()));
  }

  @Test
  public void maintenanceSnapshotRoundtripsHotLinksAndPersonalBackupWithoutDeletingExternalRoots()
      throws Exception {
    File files = temporary.newFolder(),
        pub = temporary.newFolder(),
        task = temporary.newFolder(),
        fresh = temporary.newFolder();
    File root = new File(files, "linux/ubuntu"),
        dsh = new File(root, "root/.dsh"),
        sessions = new File(pub, "Documents/dshdata/sessions");
    put(new File(sessions, "conversation.jsonl"), "external conversation");
    put(new File(sessions, "manual-backup.tar.gz"), "external manual backup");
    put(
        new File(dsh, "storages/workspace.json"),
        "{\"tables\":{\"workspaces\":{\"one\":{\"path\":\"/root/project\"}}}}");
    put(new File(dsh, "settings.yaml"), "model: personal\n");
    put(new File(dsh, "presets/custom"), "personal preset");
    put(new File(root, "root/project/manual-backup.tar.gz"), "project backup");
    try {
      Files.createSymbolicLink(
          new File(dsh, "sessions").toPath(), Path.of("/sdcard/Documents/dshdata/sessions"));
    } catch (FileSystemException unavailable) {
      Assume.assumeFalse(
          "Windows link privilege unavailable; Linux runs it",
          System.getProperty("os.name").startsWith("Windows"));
      throw unavailable;
    }
    var maintenance = new MaintenanceDataSnapshot(fs, files, task, pub);
    maintenance.capture("/root/project", "fixture", new BackupControl(null));
    maintenance.restore(fresh, new BackupControl(null));
    assertEquals(
        "external conversation",
        Files.readString(new File(fresh, "root/.dsh/sessions/conversation.jsonl").toPath()));
    assertEquals(
        "external manual backup",
        Files.readString(new File(fresh, "root/.dsh/sessions/manual-backup.tar.gz").toPath()));
    assertEquals(
        "project backup",
        Files.readString(new File(fresh, "root/project/manual-backup.tar.gz").toPath()));
    assertEquals(
        "personal preset", Files.readString(new File(fresh, "root/.dsh/presets/custom").toPath()));
    assertEquals(
        "external manual backup",
        Files.readString(new File(sessions, "manual-backup.tar.gz").toPath()));
    assertTrue(Files.isSymbolicLink(new File(dsh, "sessions").toPath()));
  }
}
