package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** 按内容读取真实临时载荷：历史每代、v5裸载荷、封装、加密与失败。 */
public class NativeBackupInputTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  private byte[] legacy(int version) throws Exception {
    byte[] conversation = "history".getBytes(StandardCharsets.UTF_8);
    List<byte[]> members = new ArrayList<>();
    members.add(LegacyTarReaderTest.tar(".dsh", '5', new byte[0]));
    members.add(LegacyTarReaderTest.tar(".dsh/sessions", '5', new byte[0]));
    members.add(LegacyTarReaderTest.tar(".dsh/sessions/a", '0', conversation));
    Map<String, Object> manifest =
        new LinkedHashMap<>(Map.of("formatVersion", (long) version, "scope", "full"));
    if (version >= 3)
      manifest.put(
          "inventory",
          Map.of(
              ".dsh",
              Map.of("directory", true),
              ".dsh/sessions",
              Map.of("directory", true),
              ".dsh/sessions/a",
              Map.of(
                  "size",
                  (long) conversation.length,
                  "sha256",
                  BackupArchive.hex(BackupArchive.sha().digest(conversation)))));
    if (version == 4) manifest.put("pluginDependencyGraph", Map.of());
    members.add(
        LegacyTarReaderTest.tar(
            ".dsha-backup-manifest.json", '0', BackupJson.write(manifest, BackupLimits.MANIFEST)));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] member : members) out.write(member, 0, member.length - 1024);
    out.write(new byte[1024]);
    return LegacyTarReaderTest.gzip(out.toByteArray());
  }

  private File prepare(byte[] bytes, String name, char[] password) throws Exception {
    File operation = temp.newFolder(), input = new File(operation, "input");
    Files.write(input.toPath(), bytes);
    File output =
        NativeBackupInput.prepare(fs, operation, input, name, password, new BackupControl(null));
    assertArrayEquals(bytes, Files.readAllBytes(input.toPath()));
    try (InputStream in = fs.read(output, fs.stat(output))) {
      BackupArchive.read(in, null, new BackupControl(null));
    }
    return output;
  }

  @Test
  public void allFourHistoricalVersionsUseNativeConversionWithoutGuestPaths() throws Exception {
    for (int version = 1; version <= 4; version++) {
      File output = prepare(legacy(version), "DSHA-backup-history.tar.gz", null);
      assertEquals("restore.dshdata", output.getName());
    }
  }

  @Test
  public void plainAndWrappedV5HaveTheSameVerifiedPayload() throws Exception {
    byte[] plain = BackupArchiveTest.archive();
    assertArrayEquals(plain, Files.readAllBytes(prepare(plain, "plain.dshbak", null).toPath()));
    File operation = temp.newFolder(), payload = new File(operation, "payload");
    Files.write(payload.toPath(), plain);
    ByteArrayOutputStream wrapped = new ByteArrayOutputStream();
    PortableBackupEnvelope.writeUnencrypted(
        fs,
        payload,
        BackupArchive.hex(BackupArchive.sha().digest(plain)),
        wrapped,
        new BackupControl(null));
    assertArrayEquals(
        plain, Files.readAllBytes(prepare(wrapped.toByteArray(), "data.tar.gz", null).toPath()));
  }

  @Test
  public void encryptedV5RequiresFullAuthenticationAndNeverFallsBackToPython() throws Exception {
    char[] password = "temporary backup password".toCharArray();
    ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
    PortableBackupCrypto.encrypt(
        new ByteArrayInputStream(BackupArchiveTest.archive()),
        encrypted,
        password,
        new BackupControl(null));
    assertArrayEquals(
        BackupArchiveTest.archive(),
        Files.readAllBytes(
            prepare(encrypted.toByteArray(), "encrypted.dshbak", password).toPath()));
    assertThrows(
        IOException.class, () -> prepare(encrypted.toByteArray(), "encrypted.dshbak", null));
    assertThrows(
        IOException.class,
        () ->
            prepare(
                encrypted.toByteArray(),
                "encrypted.dshbak",
                "wrong temporary password".toCharArray()));
  }

  @Test
  public void unknownTruncatedAndExternalOnlyLinkArchivesFailClosed() throws Exception {
    assertThrows(IOException.class, () -> prepare(new byte[] {1, 2, 3}, "unknown", null));
    byte[] link = LegacyTarReaderTest.tar(".dsh/sessions", '2', new byte[0]);
    // 绝对目标只存在于外部设备，不能用于补齐历史包内正文。
    byte[] target = "/sdcard/Download/workspace".getBytes(StandardCharsets.UTF_8);
    System.arraycopy(target, 0, link, 157, target.length);
    for (int i = 148; i < 156; i++) link[i] = ' ';
    int sum = 0;
    for (int i = 0; i < 512; i++) sum += link[i] & 255;
    byte[] checksum = String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(checksum, 0, link, 148, 8);
    assertThrows(
        IOException.class,
        () -> prepare(LegacyTarReaderTest.gzip(link), "DSHA-backup-link.tar.gz", null));
  }
}
