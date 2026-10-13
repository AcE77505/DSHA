package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/** Windows 无普通 symlink 权限时只模拟链接元数据；其余读写仍是真实文件系统。 */
public final class ScratchFixtureFileSystem extends JvmBackupFileSystem {
  private final Set<File> links = new HashSet<>();

  public void fixtureLink(File link, File target) throws IOException {
    if (System.getProperty("os.name").startsWith("Windows")) {
      Files.createFile(link.toPath());
      links.add(link.getAbsoluteFile());
    } else Files.createSymbolicLink(link.toPath(), target.toPath());
  }

  @Override
  public Node stat(File file) throws IOException {
    Node actual = super.stat(file);
    return links.contains(file.getAbsoluteFile())
        ? new Node("LINK", actual.key, 0, actual.modified, actual.device, actual.mode)
        : actual;
  }

  @Override
  public void delete(File file) throws IOException {
    super.delete(file);
    links.remove(file.getAbsoluteFile());
  }
}
