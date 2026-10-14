package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.shadows.ShadowContentResolver;

/** Real Android DocumentsContract/ContentResolver/AFD calls in a host-only SDK sandbox. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = {23, 34},
    manifest = Config.NONE,
    application = android.app.Application.class,
    shadows = SafExportDirectoryTest.HostFdStat.class)
public class SafExportDirectoryTest {
  static final String AUTH = "fixture.safe.documents";
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  final JvmBackupFileSystem fs = new JvmBackupFileSystem();
  Provider provider;
  ContentResolver resolver;
  Uri tree;
  File source;
  String hash, name;

  @Before
  public void setup() throws Exception {
    HostFdStat.windowsModel.clear();
    provider = new Provider();
    provider.folder = temporary.newFolder();
    ProviderInfo info = new ProviderInfo();
    info.authority = AUTH;
    info.exported = true;
    info.grantUriPermissions = true;
    info.readPermission = "android.permission.MANAGE_DOCUMENTS";
    info.writePermission = info.readPermission;
    provider.attachInfo(RuntimeEnvironment.getApplication(), info);
    ShadowContentResolver.registerProviderInternal(AUTH, provider);
    resolver = RuntimeEnvironment.getApplication().getContentResolver();
    tree = DocumentsContract.buildTreeDocumentUri(AUTH, "root");
    source = temporary.newFile();
    Files.writeString(source.toPath(), "verified software fixture bytes");
    hash = BackupArchive.hex(BackupArchive.sha().digest(Files.readAllBytes(source.toPath())));
    name = "DSHA-data-v5-" + UUID.randomUUID() + ".tar.gz";
  }

  /** Robolectric's default fstat returns mode/ino zero. Linux uses the real open host FD.
   * Windows explicitly models Provider FD identity, because its JVM exposes no POSIX stat. */
  @Implements(android.system.Os.class)
  public static class HostFdStat {
    static final Map<FileDescriptor, android.system.StructStat> windowsModel =
        new IdentityHashMap<>();

    @Implementation
    protected static android.system.StructStat fstat(FileDescriptor fd)
        throws android.system.ErrnoException {
      if (File.separatorChar == '\\') {
        var stat = windowsModel.get(fd);
        if (stat != null) return stat;
        throw new android.system.ErrnoException("fixture-fstat", android.system.OsConstants.EBADF);
      }
      try {
        var field = FileDescriptor.class.getDeclaredField("fd");
        field.setAccessible(true);
        var values =
            Files.readAttributes(
                Path.of("/proc/self/fd/" + field.getInt(fd)), "unix:dev,ino,mode,size");
        return stat(
            ((Number) values.get("dev")).longValue(),
            ((Number) values.get("ino")).longValue(),
            ((Number) values.get("mode")).intValue(),
            ((Number) values.get("size")).longValue());
      } catch (Exception unavailable) {
        throw new android.system.ErrnoException(
            "fixture-fstat", android.system.OsConstants.EBADF, unavailable);
      }
    }

    static android.system.StructStat stat(long dev, long ino, int mode, long size) {
      return new android.system.StructStat(dev, ino, mode, 1, 0, 0, 0, size, 0, 0, 0, 4096, 0);
    }
  }

  public static class Provider extends DocumentsProvider {
    File folder;
    Map<String, File> files = new LinkedHashMap<>();
    Map<String, Long> modelObjects = new HashMap<>();
    boolean reuse, replaceOnRw, failOnRw;
    int writes, deletes, creates;
    long nextObject;
    BackupControl cancelAfterCreate;
    File changeSource;

    public boolean onCreate() {
      return true;
    }

    // Robolectric's resolver dispatches the legacy six-argument overload even on O+.
    // The platform transport normally adapts it to the bundle overload before provider dispatch.
    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] args,
        String order,
        CancellationSignal signal) {
      return Build.VERSION.SDK_INT >= 26
          ? super.query(uri, projection, new Bundle(), signal)
          : super.query(uri, projection, selection, args, order, signal);
    }

    @Override
    public boolean isChildDocument(String parent, String child) {
      return parent.equals("root") && (child.equals("root") || files.containsKey(child));
    }

    public Cursor queryRoots(String[] columns) {
      return new MatrixCursor(
          columns == null ? new String[] {DocumentsContract.Root.COLUMN_ROOT_ID} : columns);
    }

    String[] columns(String[] requested) {
      return requested == null
          ? new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS
          }
          : requested;
    }

    void add(MatrixCursor cursor, String id, File file) {
      Object[] values = new Object[cursor.getColumnCount()];
      String[] cols = cursor.getColumnNames();
      for (int i = 0; i < cols.length; i++)
        values[i] =
            switch (cols[i]) {
              case DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id;
              case DocumentsContract.Document.COLUMN_DISPLAY_NAME ->
                  file == null ? "fixture" : file.getName();
              case DocumentsContract.Document.COLUMN_SIZE -> file == null ? 0L : file.length();
              case DocumentsContract.Document.COLUMN_MIME_TYPE ->
                  file == null ? DocumentsContract.Document.MIME_TYPE_DIR : "application/gzip";
              case DocumentsContract.Document.COLUMN_FLAGS ->
                  file == null
                      ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                      : DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
              default -> null;
            };
      cursor.addRow(values);
    }

    public Cursor queryDocument(String id, String[] projection) {
      MatrixCursor cursor = new MatrixCursor(columns(projection));
      if (id.equals("root")) add(cursor, id, null);
      else if (files.containsKey(id)) add(cursor, id, files.get(id));
      return cursor;
    }

    public Cursor queryChildDocuments(String parent, String[] projection, String order) {
      MatrixCursor cursor = new MatrixCursor(columns(projection));
      if (parent.equals("root"))
        for (var entry : files.entrySet()) add(cursor, entry.getKey(), entry.getValue());
      return cursor;
    }

    public String createDocument(String parent, String mime, String name)
        throws FileNotFoundException {
      creates++;
      if (reuse) return files.keySet().iterator().next();
      String id = "doc-" + UUID.randomUUID();
      File file = new File(folder, name);
      try {
        Files.createFile(file.toPath());
        if (changeSource != null)
          Files.writeString(changeSource.toPath(), "changed source bytes of another length");
      } catch (IOException failure) {
        throw new FileNotFoundException("fixture create");
      }
      files.put(id, file);
      modelObjects.put(id, ++nextObject);
      if (cancelAfterCreate != null) {
        try {
          Files.move(file.toPath(), new File(folder, "original-created").toPath());
          Files.writeString(file.toPath(), "replacement on cancellation");
          modelObjects.put(id, ++nextObject);
        } catch (IOException failure) {
          throw new FileNotFoundException("fixture replace");
        }
        cancelAfterCreate.cancel();
      }
      return id;
    }

    public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
        throws FileNotFoundException {
      File file = files.get(id);
      if (file == null) throw new FileNotFoundException("fixture missing");
      if (mode.contains("w")) {
        writes++;
        if (failOnRw) throw new FileNotFoundException("fixture write failure");
        if (replaceOnRw) {
          try {
            Files.move(file.toPath(), new File(folder, "original-created").toPath());
            Files.writeString(file.toPath(), "replacement must survive");
            modelObjects.put(id, ++nextObject);
          } catch (IOException failure) {
            throw new FileNotFoundException("fixture replace");
          }
        }
      }
      var opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
      if (File.separatorChar == '\\')
        HostFdStat.windowsModel.put(
            opened.getFileDescriptor(),
            HostFdStat.stat(
                1,
                modelObjects.computeIfAbsent(id, key -> ++nextObject),
                android.system.OsConstants.S_IFREG | 0600,
                file.length()));
      return opened;
    }

    public void deleteDocument(String id) throws FileNotFoundException {
      deletes++;
      throw new FileNotFoundException("automatic delete prohibited");
    }
  }

  @Test
  public void normalProviderUsesRwAndReadbackWithApi23CompatibleTreeConstructor() throws Exception {
    var result =
        NewDocumentExport.write(
            fs,
            source,
            hash,
            name,
            new SafExportDirectory(resolver, tree),
            new BackupControl(null));
    assertTrue(result.readback);
    assertEquals(1, provider.writes);
    assertEquals(0, provider.deletes);
    assertArrayEquals(
        Files.readAllBytes(source.toPath()),
        Files.readAllBytes(provider.files.values().iterator().next().toPath()));
  }

  @Test
  public void existingEmptyDocumentIdCannotBeWrittenOrDeleted() throws Exception {
    File existing = new File(provider.folder, name);
    Files.createFile(existing.toPath());
    provider.files.put("existing", existing);
    provider.reuse = true;
    assertThrows(
        IOException.class,
        () ->
            NewDocumentExport.write(
                fs,
                source,
                hash,
                name,
                new SafExportDirectory(resolver, tree),
                new BackupControl(null)));
    assertEquals(0, provider.writes);
    assertEquals(0, provider.deletes);
    assertEquals(0, existing.length());
  }

  @Test
  public void replacementBeforeRwOpenCannotBeTruncatedWrittenOrDeleted() throws Exception {
    provider.replaceOnRw = true;
    assertThrows(
        IOException.class,
        () ->
            NewDocumentExport.write(
                fs,
                source,
                hash,
                name,
                new SafExportDirectory(resolver, tree),
                new BackupControl(null)));
    assertEquals(
        "replacement must survive",
        Files.readString(provider.files.values().iterator().next().toPath()));
    assertEquals(0, provider.deletes);
  }

  @Test
  public void foreignAuthorityForeignTreeAndNonMemberNeverAcquireStreams() throws Exception {
    var directory = new SafExportDirectory(resolver, tree);
    for (Uri value :
        List.of(
            DocumentsContract.buildDocumentUri("other.authority", "unknown"),
            DocumentsContract.buildDocumentUriUsingTree(
                DocumentsContract.buildTreeDocumentUri(AUTH, "other"), "unknown"),
            DocumentsContract.buildDocumentUri(AUTH, "unknown"))) {
      var document = new NewDocumentExport.Document(value, "unknown");
      assertThrows(IOException.class, () -> directory.metadata(document, new BackupControl(null)));
      assertThrows(
          IOException.class, () -> directory.objectIdentity(document, new BackupControl(null)));
      assertThrows(
          IOException.class,
          () -> directory.checkedOutput(document, "FILE:1:2", new BackupControl(null)));
      assertThrows(IOException.class, () -> directory.input(document, new BackupControl(null)));
    }
    assertEquals(0, provider.writes);
    assertEquals(0, provider.deletes);
  }

  @Test
  public void selectedTreeRejectsExtraSegmentsQueryAndFragment() throws Exception {
    for (Uri value :
        List.of(
            DocumentsContract.buildDocumentUriUsingTree(tree, "other"),
            tree.buildUpon().appendQueryParameter("unsafe", "1").build(),
            tree.buildUpon().fragment("unsafe").build()))
      assertThrows(IOException.class, () -> new SafExportDirectory(resolver, value));
  }

  @Test
  public void invalidSourceNeverCreatesAnyProviderDocument() throws Exception {
    assertThrows(
        IOException.class,
        () ->
            NewDocumentExport.write(
                fs,
                source,
                "0".repeat(64),
                name,
                new SafExportDirectory(resolver, tree),
                new BackupControl(null)));
    assertEquals(0, provider.creates);
    assertTrue(provider.files.isEmpty());
    assertEquals(0, provider.deletes);
  }

  @Test
  public void cancelledCreationKeepsSameIdReplacementWithoutWriteOrDelete() throws Exception {
    var control = new BackupControl(null);
    provider.cancelAfterCreate = control;
    var retained =
        assertThrows(
            NewDocumentExport.Retained.class,
            () ->
                NewDocumentExport.write(
                    fs, source, hash, name, new SafExportDirectory(resolver, tree), control));
    assertEquals("CANCELLED", BackupErrorCode.from(retained));
    assertEquals(
        "replacement on cancellation",
        Files.readString(provider.files.values().iterator().next().toPath()));
    assertEquals(0, provider.writes);
    assertEquals(0, provider.deletes);
  }

  @Test
  public void failedRwLeavesPossibleZeroByteOutputWithoutDeleting() throws Exception {
    provider.failOnRw = true;
    var retained =
        assertThrows(
            NewDocumentExport.Retained.class,
            () ->
                NewDocumentExport.write(
                    fs,
                    source,
                    hash,
                    name,
                    new SafExportDirectory(resolver, tree),
                    new BackupControl(null)));
    assertEquals("SAF_OUTPUT_UNSUPPORTED", BackupErrorCode.from(retained));
    assertEquals(1, provider.creates);
    assertEquals(0, provider.files.values().iterator().next().length());
    assertEquals(0, provider.deletes);
  }

  @Test
  public void sourceChangedAfterCreationRemainsAndOutputIsNotDeleted() throws Exception {
    provider.changeSource = source;
    assertThrows(
        NewDocumentExport.Retained.class,
        () ->
            NewDocumentExport.write(
                fs,
                source,
                hash,
                name,
                new SafExportDirectory(resolver, tree),
                new BackupControl(null)));
    assertEquals("changed source bytes of another length", Files.readString(source.toPath()));
    assertEquals(0, provider.deletes);
    assertEquals(1, provider.creates);
  }
}
