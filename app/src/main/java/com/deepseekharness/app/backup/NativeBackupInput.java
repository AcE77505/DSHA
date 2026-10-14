package com.deepseekharness.app.backup;

import java.io.*;
import java.util.Arrays;

/** 所有历史输入只在私有编号槽位解封/认证/转换；从不调用 guest 恢复或提交脚本。 */
public final class NativeBackupInput {
  private NativeBackupInput() {}

  public static File prepare(
      BackupFileSystem fs,
      File operation,
      File input,
      String filename,
      char[] password,
      BackupControl control)
      throws IOException {
    File portable = input;
    byte[] signature = signature(fs, portable);
    if ((signature[0] & 255) == 31 && (signature[1] & 255) == 139) {
      File transport = fs.child(operation, "transport.dshbak");
      if (PortableBackupEnvelope.unwrapIfPresent(fs, input, transport, control)) {
        portable = transport;
        signature = signature(fs, portable);
      }
    }
    if (BackupArchive.hasMagic(signature)) return portable;
    File plain = fs.child(operation, "restore.dshdata");
    if (Arrays.equals(signature, PortableBackupCrypto.MAGIC)) {
      if (password == null) throw new IOException("BACKUP_PASSWORD_REQUIRED");
      try (InputStream source = fs.read(portable, fs.stat(portable));
          OutputStream out = fs.create(plain)) {
        PortableBackupCrypto.decrypt(source, out, password, control);
      }
    } else new LegacyBackupImporter(fs, operation).convert(input, plain, filename, control);
    return plain;
  }

  private static byte[] signature(BackupFileSystem fs, File input) throws IOException {
    byte[] bytes = new byte[8];
    try (InputStream source = fs.read(input, fs.stat(input))) {
      new DataInputStream(source).readFully(bytes);
    }
    return bytes;
  }
}
