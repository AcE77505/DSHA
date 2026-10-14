package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/** An exclusive private output; cleanup never claims a pre-existing file or replacement inode. */
final class OwnedBackupArtifact {
  final OutputStream stream;
  private final BackupFileSystem fs;
  private final File file, parent;
  private final BackupFileSystem.Node identity, parentIdentity;

  private OwnedBackupArtifact(
      BackupFileSystem fs,
      File file,
      BackupFileSystem.CreatedFile created,
      BackupFileSystem.Node parentIdentity) {
    this.fs = fs;
    this.file = file;
    parent = file.getParentFile();
    this.parentIdentity = parentIdentity;
    identity = created.identity;
    stream = created.stream;
  }

  static OwnedBackupArtifact create(BackupFileSystem fs, File file) throws IOException {
    file = fs.child(file.getAbsoluteFile().getParentFile(), file.getName());
    var parent = fs.stat(file.getParentFile());
    var created = fs.createOwned(file);
    OwnedBackupArtifact owned = new OwnedBackupArtifact(fs, file, created, parent);
    try {
      if (!created.identity.type.equals("FILE")
          || !sameIdentity(parent, fs.stat(file.getParentFile()))
          || !sameIdentity(created.identity, fs.stat(file)))
        throw new IOException("OWNED_ARTIFACT_CHANGED");
      return owned;
    } catch (IOException failure) {
      try {
        created.stream.close();
      } catch (IOException closing) {
        failure.addSuppressed(closing);
      }
      throw failure;
    }
  }

  private static boolean sameIdentity(
      BackupFileSystem.Node expected, BackupFileSystem.Node actual) {
    return expected.type.equals(actual.type)
        && expected.key.equals(actual.key)
        && expected.device == actual.device;
  }

  void remove() throws IOException {
    var current = fs.stat(file);
    if (current.type.equals("MISSING")) return;
    if (identity.key.isEmpty()
        || parentIdentity.key.isEmpty()
        || !sameIdentity(parentIdentity, fs.stat(parent))
        || !sameIdentity(identity, current)) throw new IOException("OWNED_ARTIFACT_CHANGED");
    fs.delete(file);
    fs.syncDirectory(parent);
  }
}
