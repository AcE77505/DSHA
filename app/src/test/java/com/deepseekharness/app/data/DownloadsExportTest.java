package com.deepseekharness.app.data;

import static org.junit.Assert.*;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.*;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** 可信应用根别名只展开一次；缓存与任意下载源的后代链接仍拒绝。 */
public class DownloadsExportTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private JvmBackupFileSystem anchored() {
    return new JvmBackupFileSystem() {
      private void parents(File file) throws IOException {
        for (File parent = file.getAbsoluteFile().getParentFile();
            parent != null;
            parent = parent.getParentFile())
          if (super.stat(parent).type.equals("LINK")) throw new IOException("PARENT_LINK");
      }

      @Override
      public InputStream read(File file, BackupFileSystem.Node expected) throws IOException {
        parents(file);
        return super.read(file, expected);
      }

      @Override
      public OutputStream create(File file) throws IOException {
        parents(file);
        return super.create(file);
      }
    };
  }

  private File app() throws IOException {
    File app = temporary.newFolder();
    Files.createDirectories(new File(app, "files").toPath());
    Files.createDirectories(new File(app, "cache").toPath());
    return app;
  }

  private void link(File at, File target) throws IOException {
    try {
      Files.createSymbolicLink(at.toPath(), target.toPath());
    } catch (UnsupportedOperationException | FileSystemException unavailable) {
      Assume.assumeNoException("Linux runs real alias and link cases", unavailable);
    }
  }

  @Test
  public void contextParentAliasStagesCanonicalSourceAndUsesTheRealReadCheck() throws Exception {
    File app = app(), aliases = temporary.newFolder(), alias = new File(aliases, "data-user-0");
    link(alias, app.getParentFile());
    File mapped = new File(alias, app.getName());
    File original = new File(mapped, "cache/original.txt");
    Files.writeString(original.toPath(), "existing private bytes");
    var fs = anchored();
    assertEquals(
        "PARENT_LINK",
        assertThrows(
                IOException.class,
                () -> DownloadsExport.inspectSource(fs, original, new BackupControl(null)))
            .getMessage());
    File source =
        DownloadsExport.stagePrivateCacheSource(
            fs,
            new File(mapped, "files"),
            new File(mapped, "cache"),
            "diagnostic bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(new File(app, "cache").getCanonicalFile(), source.getParentFile());
    assertEquals("diagnostic bytes", Files.readString(source.toPath()));
    assertEquals(
        source.length(),
        DownloadsExport.inspectSource(fs, source, new BackupControl(null)).integrity.size);
    assertEquals("existing private bytes", Files.readString(original.toPath()));
  }

  @Test
  public void privateCacheSymlinkCannotStageIntoExternalDirectory() throws Exception {
    File app = app(), outside = temporary.newFolder(), cache = new File(app, "cache");
    File retained = new File(outside, "manual-backup.tar.gz");
    Files.writeString(retained.toPath(), "retained external bytes");
    Files.delete(cache.toPath());
    link(cache, outside);
    assertEquals(
        "EXPORT_CACHE_TYPE",
        assertThrows(
                IOException.class,
                () ->
                    DownloadsExport.stagePrivateCacheSource(
                        anchored(), new File(app, "files"), cache, "log".getBytes()))
            .getMessage());
    assertEquals("retained external bytes", Files.readString(retained.toPath()));
    assertArrayEquals(new String[] {"manual-backup.tar.gz"}, outside.list());
    assertTrue(Files.isSymbolicLink(cache.toPath()));
  }

  @Test
  public void unrelatedCacheRootCannotBeMadeTrustedByCanonicalization() throws Exception {
    File app = app(), outside = temporary.newFolder(), cache = new File(outside, "cache");
    Files.createDirectory(cache.toPath());
    assertEquals(
        "EXPORT_CACHE_ROOT",
        assertThrows(
                IOException.class,
                () ->
                    DownloadsExport.stagePrivateCacheSource(
                        anchored(), new File(app, "files"), cache, "log".getBytes()))
            .getMessage());
    assertEquals(0, cache.list().length);
  }

  @Test
  public void arbitrarySourceParentAndFinalLinksStillFailTheActualDownloadReadCheck()
      throws Exception {
    File app = app(), outside = temporary.newFolder(), target = new File(outside, "user.txt");
    Files.writeString(target.toPath(), "original external content");
    File parent = new File(app, "cache/parent"), leaf = new File(app, "cache/leaf");
    link(parent, outside);
    link(leaf, target);
    var fs = anchored();
    assertEquals(
        "PARENT_LINK",
        assertThrows(
                IOException.class,
                () ->
                    DownloadsExport.inspectSource(
                        fs, new File(parent, "user.txt"), new BackupControl(null)))
            .getMessage());
    assertEquals(
        "EXPORT_SOURCE_TYPE",
        assertThrows(
                IOException.class,
                () -> DownloadsExport.inspectSource(fs, leaf, new BackupControl(null)))
            .getMessage());
    assertEquals("original external content", Files.readString(target.toPath()));
    assertTrue(Files.isSymbolicLink(parent.toPath()));
    assertTrue(Files.isSymbolicLink(leaf.toPath()));
  }

  @Test
  public void cacheReplacementBeforeCreationIsRejectedWithoutWritingExternalBytes()
      throws Exception {
    File app = app(), outside = temporary.newFolder(), cache = new File(app, "cache");
    File probe = new File(app, "link-probe");
    link(probe, outside);
    Files.delete(probe.toPath());
    var fs =
        new JvmBackupFileSystem() {
          @Override
          public OutputStream create(File file) throws IOException {
            Files.delete(cache.toPath());
            Files.createSymbolicLink(cache.toPath(), outside.toPath());
            return anchored().create(file);
          }
        };
    assertEquals(
        "PARENT_LINK",
        assertThrows(
                IOException.class,
                () ->
                    DownloadsExport.stagePrivateCacheSource(
                        fs, new File(app, "files"), cache, "log".getBytes()))
            .getMessage());
    assertEquals(0, outside.list().length);
    assertTrue(Files.isSymbolicLink(cache.toPath()));
  }
}
