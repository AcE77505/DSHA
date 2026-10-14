package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 只解析框架 filesDir 的系统别名；guest 后代逐段 NOFOLLOW，不能借 canonical 跟链。 */
final class BxrootPrivateRoot {
  private record Witness(File declared, File physical, BackupFileSystem.Node identity) {}

  private final BackupFileSystem fs;
  private final File declaredFiles, physicalFiles;
  private final List<Witness> witnesses = new ArrayList<>();

  BxrootPrivateRoot(BackupFileSystem fs, File frameworkFiles) throws IOException {
    this.fs = fs;
    if (frameworkFiles == null
        || !frameworkFiles.isAbsolute()
        || !"files".equals(frameworkFiles.getName()))
      throw new IOException("BXROOT_PRIVATE_FILES_AUTHORITY");
    for (File part = frameworkFiles; part.getParentFile() != null; part = part.getParentFile())
      if (part.getName().equals(".") || part.getName().equals(".."))
        throw new IOException("BXROOT_PRIVATE_FILES_AUTHORITY");
    declaredFiles = frameworkFiles;
    BackupFileSystem.Node identity = fs.stat(declaredFiles);
    if (!identity.type.equals("DIRECTORY"))
      throw new IOException("BXROOT_PRIVATE_ROOT_LINK_OR_CHANGED");
    // 此处是唯一 canonical 调用，输入只能是 Android Context.getFilesDir()。
    physicalFiles = frameworkFiles.getCanonicalFile();
    if (!sameDirectory(identity, fs.stat(physicalFiles)))
      throw new IOException("BXROOT_PRIVATE_ROOT_LINK_OR_CHANGED");
    witnesses.add(new Witness(declaredFiles, physicalFiles, identity));
    verify();
  }

  File root(File requested) throws IOException {
    if (requested == null || !requested.isAbsolute())
      throw new IOException("BXROOT_PRIVATE_ROOT_PATH");
    String relative = RuntimeAssetFiles.relative(requested, declaredFiles, physicalFiles);
    File declared = declaredFiles;
    File physical = physicalFiles;
    verify();
    for (String component : relative.split("/")) {
      declared = new File(declared, component);
      physical = fs.child(physical, component);
      remember(declared, physical);
    }
    verify();
    return physical;
  }

  File dataDomain(File requested) throws IOException {
    if (requested == null) return null;
    if (!requested.isAbsolute()
        || !(requested.equals(declaredFiles) || requested.equals(physicalFiles)))
      throw new IOException("BXROOT_PRIVATE_DATA_DOMAIN");
    verify();
    return physicalFiles;
  }

  private void remember(File declared, File physical) throws IOException {
    BackupFileSystem.Node node = fs.stat(physical);
    if (!sameDirectory(node, fs.stat(declared)))
      throw new IOException("BXROOT_PRIVATE_ROOT_LINK_OR_CHANGED");
    witnesses.add(new Witness(declared, physical, node));
  }

  void verify() throws IOException {
    for (Witness witness : witnesses)
      if (!sameDirectory(witness.identity, fs.stat(witness.physical))
          || !sameDirectory(witness.identity, fs.stat(witness.declared)))
        throw new IOException("BXROOT_PRIVATE_ROOT_LINK_OR_CHANGED");
  }

  private static boolean sameDirectory(
      BackupFileSystem.Node expected, BackupFileSystem.Node actual) {
    return expected.type.equals("DIRECTORY")
        && actual.type.equals("DIRECTORY")
        && expected.device == actual.device
        && expected.key.equals(actual.key);
  }
}
