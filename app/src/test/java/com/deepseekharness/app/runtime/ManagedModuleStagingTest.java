package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.*;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class ManagedModuleStagingTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final JvmBackupFileSystem fs =
      new JvmBackupFileSystem() {
        @Override
        public Node stat(File file) throws IOException {
          var node = super.stat(file);
          if (node.type.equals("MISSING") || !node.key.equals("null")) return node;
          // 本机 Windows 文件系统不提供 fileKey；临时夹具使用实际创建时间见证替换。
          var born =
              Files.readAttributes(
                      file.toPath(),
                      java.nio.file.attribute.BasicFileAttributes.class,
                      LinkOption.NOFOLLOW_LINKS)
                  .creationTime();
          return new Node(
              node.type,
              "born:" + born,
              node.size,
              node.modified,
              node.device,
              node.mode,
              node.ctime);
        }
      };

  @Test
  public void randomSlotCleansItsFilesAndNeverClaimsLegacyRootfsSlot() throws Exception {
    File files = temp.newFolder();
    File legacy = new File(files, "linux/ubuntu/.dsha-managed-module-1234/manual-backup.tar.gz");
    Files.createDirectories(legacy.getParentFile().toPath());
    Files.writeString(legacy.toPath(), "keep");
    File slot;
    try (var staging = ManagedModuleStaging.create(fs, files)) {
      slot = staging.directory();
      Files.writeString(new File(slot, "module.js").toPath(), "module");
    }
    assertFalse(slot.exists());
    assertEquals("keep", Files.readString(legacy.toPath()));
  }

  @Test
  public void nestedLinkCleanupKeepsExternalProjectAndManualBackup() throws Exception {
    File files = temp.newFolder(), external = temp.newFolder();
    Files.writeString(new File(external, "manual-backup.tar.gz").toPath(), "backup");
    var staging = ManagedModuleStaging.create(fs, files);
    try {
      Files.createSymbolicLink(
          new File(staging.directory(), "external").toPath(), external.toPath());
    } catch (IOException | UnsupportedOperationException unavailable) {
      staging.close();
      Assume.assumeNoException(unavailable);
    }
    staging.close();
    assertEquals("backup", Files.readString(new File(external, "manual-backup.tar.gz").toPath()));
  }

  @Test
  public void replacedSlotIsRetainedAndLinkParentIsRefused() throws Exception {
    File files = temp.newFolder(), external = temp.newFolder();
    var staging = ManagedModuleStaging.create(fs, files);
    File original = staging.directory();
    Files.move(original.toPath(), new File(original.getParentFile(), "retained-original").toPath());
    Files.createDirectory(original.toPath());
    assertThrows(IOException.class, staging::close);
    assertTrue(original.isDirectory());
    File second = temp.newFolder();
    try {
      Files.createSymbolicLink(
          new File(second, ManagedModuleStaging.HOME).toPath(), external.toPath());
    } catch (IOException | UnsupportedOperationException unavailable) {
      Assume.assumeNoException(unavailable);
    }
    assertThrows(IOException.class, () -> ManagedModuleStaging.create(fs, second));
    assertEquals(0, external.list().length);
  }

  @Test
  public void crossDeviceParentIsRefusedBeforeCreatingOrListingAStagingSlot() throws Exception {
    File files = temp.newFolder(), parent = new File(files, ManagedModuleStaging.HOME);
    Files.createDirectory(parent.toPath());
    Files.writeString(new File(parent, "manual-backup.tar.gz").toPath(), "keep");
    JvmBackupFileSystem mounted =
        new JvmBackupFileSystem() {
          @Override
          public Node stat(File file) throws IOException {
            var node = super.stat(file);
            return file.equals(parent)
                ? new Node(
                    node.type,
                    node.key,
                    node.size,
                    node.modified,
                    node.device + 1,
                    node.mode,
                    node.ctime)
                : node;
          }

          @Override
          public java.util.List<String> list(File file) throws IOException {
            if (file.equals(parent)) throw new AssertionError("external parent traversed");
            return super.list(file);
          }
        };
    assertThrows(IOException.class, () -> ManagedModuleStaging.create(mounted, files));
    assertEquals("keep", Files.readString(new File(parent, "manual-backup.tar.gz").toPath()));
    assertEquals(1, parent.list().length);
  }
}
