package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** 受管模块读取的随机私有槽位；旧 rootfs 槽位不认领，清理不跟随链接。 */
public final class ManagedModuleStaging implements AutoCloseable {
  public static final String HOME = "managed-module-staging";
  private final BackupFileSystem fs;
  private final File parent, directory;
  private final BackupFileSystem.Node born;

  private ManagedModuleStaging(BackupFileSystem fs, File parent, File directory)
      throws IOException {
    this.fs = fs;
    this.parent = parent;
    this.directory = directory;
    born = fs.stat(directory);
    if (!born.type.equals("DIRECTORY")) throw new IOException("MODULE_STAGING_TYPE");
  }

  public static ManagedModuleStaging create(BackupFileSystem fs, File files) throws IOException {
    File parent = fs.child(files, HOME);
    if (fs.stat(parent).type.equals("MISSING")) fs.directory(parent);
    if (!fs.stat(parent).type.equals("DIRECTORY")) throw new IOException("MODULE_STAGING_PARENT");
    if (fs.stat(parent).device != fs.stat(files).device)
      throw new IOException("MODULE_STAGING_FILESYSTEM_BOUNDARY");
    File directory = fs.child(parent, UUID.randomUUID().toString());
    fs.directory(directory);
    return new ManagedModuleStaging(fs, parent, directory);
  }

  public File directory() {
    return directory;
  }

  @Override
  public void close() throws IOException {
    var current = fs.stat(directory);
    if (current.type.equals("MISSING")) return;
    if (!current.type.equals("DIRECTORY")
        || !born.key.equals(current.key)
        || born.device != current.device) throw new IOException("MODULE_STAGING_CHANGED_RETAINED");
    fs.removeOwned(parent, directory.getName());
  }
}
