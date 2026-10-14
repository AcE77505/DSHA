package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class NewDocumentExportTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  final JvmBackupFileSystem fs = new JvmBackupFileSystem();
  final String name = "DSHA-data-v5-" + UUID.randomUUID() + ".tar.gz";
  File source;
  String hash;

  @Before
  public void fixture() throws Exception {
    source = temporary.newFile();
    Assume.assumeFalse(
        "document-ID fixture requires real filesystem identity; Linux runs it",
        fs.stat(source).key.equals("null"));
    Files.writeString(source.toPath(), "verified export bytes");
    hash = BackupArchive.hex(BackupArchive.sha().digest(Files.readAllBytes(source.toPath())));
  }

  class Provider implements NewDocumentExport.Documents {
    final File directory;
    File reused, created;
    boolean noReadback,
        failOutput,
        cancelAfterCreate,
        unknownCreate,
        replaceOnOutput,
        changeSource,
        stablePathId,
        replaceBeforeOpen,
        emptyReplacement;
    int creates, writes, deletes;
    BackupControl operation;

    Provider() throws Exception {
      directory = temporary.newFolder();
    }

    public Set<String> existingIds(BackupControl control) throws IOException {
      Set<String> result = new HashSet<>();
      for (String leaf : fs.list(directory)) result.add(fs.stat(new File(directory, leaf)).key);
      return result;
    }

    public NewDocumentExport.Document create(String requested, BackupControl control)
        throws IOException {
      creates++;
      operation = control;
      created = reused == null ? new File(directory, requested) : reused;
      if (reused == null) try (OutputStream out = fs.create(created)) {}
      var document = new NewDocumentExport.Document(created, fs.stat(created).key);
      if (changeSource)
        Files.writeString(source.toPath(), "changed source bytes of another length");
      if (cancelAfterCreate) control.cancel();
      if (unknownCreate) throw new IOException("SAF_CREATE_RESULT_UNKNOWN");
      return document;
    }

    public NewDocumentExport.Metadata metadata(
        NewDocumentExport.Document document, BackupControl control) throws IOException {
      File file = (File) document.handle;
      var node = fs.stat(file);
      return new NewDocumentExport.Metadata(
          stablePathId ? document.id : node.key, file.getName(), node.size);
    }

    public String objectIdentity(NewDocumentExport.Document document, BackupControl control)
        throws IOException {
      return fs.stat((File) document.handle).key;
    }

    public OutputStream checkedOutput(
        NewDocumentExport.Document document, String expectedIdentity, BackupControl control)
        throws IOException {
      writes++;
      File file = (File) document.handle;
      if (replaceOnOutput || replaceBeforeOpen) {
        fs.move(file, new File(directory, "original-created"));
        try (OutputStream output = fs.create(file)) {
          if (!emptyReplacement) output.write("replacement user bytes".getBytes());
        }
        if (replaceOnOutput) throw new IOException("SAF_WRITE_FAILED");
      }
      if (failOutput) throw new IOException("SAF_WRITE_FAILED");
      if (!expectedIdentity.equals(fs.stat(file).key))
        throw new IOException("SAF_OUTPUT_OBJECT_CHANGED");
      return Files.newOutputStream(
          file.toPath(),
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING,
          LinkOption.NOFOLLOW_LINKS);
    }

    public InputStream input(NewDocumentExport.Document document, BackupControl control)
        throws IOException {
      if (noReadback) throw new IOException("SAF_UNREADABLE");
      File file = (File) document.handle;
      return fs.read(file, fs.stat(file));
    }

    public void delete(NewDocumentExport.Document document) throws IOException {
      deletes++;
      fs.delete((File) document.handle);
    }
  }

  @Test
  public void normalProviderCreatesVerifiedNewLeafAndKeepsOtherFiles() throws Exception {
    Provider provider = new Provider();
    File previous = new File(provider.directory, "previous-backup");
    Files.writeString(previous.toPath(), "original backup");
    var result = NewDocumentExport.write(fs, source, hash, name, provider, new BackupControl(null));
    assertTrue(result.readback);
    assertEquals(name, ((File) result.document.handle).getName());
    assertArrayEquals(
        Files.readAllBytes(source.toPath()), Files.readAllBytes(provider.created.toPath()));
    assertEquals("original backup", Files.readString(previous.toPath()));
    assertEquals(0, provider.deletes);
  }

  @Test
  public void reusedExistingIdNeverAcquiresWriteOrDeleteEvenForEmptyFile() throws Exception {
    for (String original : List.of("", "existing manual backup")) {
      Provider provider = new Provider();
      provider.reused = new File(provider.directory, name);
      Files.writeString(provider.reused.toPath(), original);
      assertThrows(
          IOException.class,
          () -> NewDocumentExport.write(fs, source, hash, name, provider, new BackupControl(null)));
      assertEquals(original, Files.readString(provider.reused.toPath()));
      assertEquals(0, provider.writes);
      assertEquals(0, provider.deletes);
    }
  }

  @Test
  public void badSourceCannotCreateAnyExternalDocument() throws Exception {
    Provider provider = new Provider();
    assertThrows(
        IOException.class,
        () ->
            NewDocumentExport.write(
                fs, source, "0".repeat(64), name, provider, new BackupControl(null)));
    assertEquals(0, provider.creates);
    assertTrue(fs.list(provider.directory).isEmpty());
  }

  @Test
  public void cancellationAfterCreationRetainsPossibleOutputAndNeverDeletes() throws Exception {
    Provider provider = new Provider();
    provider.cancelAfterCreate = true;
    NewDocumentExport.Retained retained =
        assertThrows(
            NewDocumentExport.Retained.class,
            () ->
                NewDocumentExport.write(fs, source, hash, name, provider, new BackupControl(null)));
    assertEquals("CANCELLED", BackupErrorCode.from(retained));
    assertTrue(provider.created.exists());
    assertEquals(0, provider.deletes);
    assertEquals(0, provider.writes);
  }

  @Test
  public void failedWriteRetainsPossibleZeroByteDocumentWithoutClaimingPublication()
      throws Exception {
    Provider provider = new Provider();
    provider.failOutput = true;
    assertThrows(
        IOException.class,
        () -> NewDocumentExport.write(fs, source, hash, name, provider, new BackupControl(null)));
    assertTrue(provider.created.exists());
    assertEquals(0, provider.deletes);
  }

  @Test
  public void changedSourceAfterCreationKeepsOriginalAndUncertainExternalLeaf() throws Exception {
    Provider provider = new Provider();
    provider.changeSource = true;
    assertThrows(
        IOException.class,
        () -> NewDocumentExport.write(fs, source, hash, name, provider, new BackupControl(null)));
    assertTrue(provider.created.exists());
    assertEquals("changed source bytes of another length", Files.readString(source.toPath()));
  }

  @Test
  public void changedDocumentIdentityKeepsReplacementAndDoesNotDelete() throws Exception {
    Provider provider = new Provider();
    provider.replaceOnOutput = true;
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                NewDocumentExport.write(fs, source, hash, name, provider, new BackupControl(null)));
    assertEquals("replacement user bytes", Files.readString(provider.created.toPath()));
    assertEquals(0, provider.deletes);
    assertTrue(error instanceof NewDocumentExport.Retained);
  }

  @Test
  public void unknownCreationResultDoesNotGuessWhichDocumentToDelete() throws Exception {
    Provider provider = new Provider();
    provider.unknownCreate = true;
    assertThrows(
        IOException.class,
        () -> NewDocumentExport.write(fs, source, hash, name, provider, new BackupControl(null)));
    assertTrue(provider.created.exists());
    assertEquals(0, provider.deletes);
    assertEquals(0, provider.writes);
  }

  @Test
  public void pathBasedProviderIdCannotDeleteDifferentReplacementBytes() throws Exception {
    Provider provider = new Provider();
    provider.replaceOnOutput = true;
    provider.stablePathId = true;
    assertThrows(
        IOException.class,
        () -> NewDocumentExport.write(fs, source, hash, name, provider, new BackupControl(null)));
    assertEquals("replacement user bytes", Files.readString(provider.created.toPath()));
    assertEquals(0, provider.deletes);
  }

  @Test
  public void emptyReplacementWithSamePathIdCannotBeWrittenOrDeleted() throws Exception {
    Provider provider = new Provider();
    provider.replaceBeforeOpen = true;
    provider.stablePathId = true;
    provider.emptyReplacement = true;
    assertThrows(
        IOException.class,
        () -> NewDocumentExport.write(fs, source, hash, name, provider, new BackupControl(null)));
    assertTrue(provider.created.exists());
    assertEquals(0, provider.created.length());
    assertEquals(0, provider.deletes);
  }

  @Test
  public void unavailableReadbackRetainsWrittenFileWithoutClaimingVerifiedResult()
      throws Exception {
    Provider provider = new Provider();
    provider.noReadback = true;
    var result = NewDocumentExport.write(fs, source, hash, name, provider, new BackupControl(null));
    assertFalse(result.readback);
    assertTrue(provider.created.exists());
    assertEquals(0, provider.deletes);
  }
}
