package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class HostWebLogTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  private void link(File source, File target) throws Exception {
    try {
      Files.createSymbolicLink(source.toPath(), target.toPath());
    } catch (IOException | UnsupportedOperationException unavailable) {
      Assume.assumeNoException(unavailable);
    }
  }

  @Test
  public void legacyWorkspaceLogAndManualFileAreNeverOpenedOrOverwritten() throws Exception {
    File files = temp.newFolder(),
        external = temp.newFolder(),
        manual = new File(external, "manual-backup.tar.gz"),
        legacy = new File(files, "linux/ubuntu/root/dsh-web.log");
    Files.writeString(manual.toPath(), "manual bytes");
    Files.createDirectories(legacy.getParentFile().toPath());
    link(legacy, manual);
    JvmBackupFileSystem guarded =
        new JvmBackupFileSystem() {
          public Node stat(File file) throws IOException {
            if (file.getAbsolutePath().contains(File.separator + "linux" + File.separator))
              throw new AssertionError("workspace log inspected");
            return super.stat(file);
          }
        };
    var log = HostWebLog.create(guarded, files);
    log.append("real guest output\ndsh web: http://127.0.0.1:1234/?token=fixture-secret\n");
    HostWebLog.saveFailure(guarded, files, log.read());
    assertTrue(log.read().contains("real guest output"));
    assertFalse(log.read().contains("fixture-secret"));
    assertTrue(HostWebLog.readFailure(guarded, files).contains("real guest output"));
    assertEquals("manual bytes", Files.readString(manual.toPath()));
    assertTrue(Files.isSymbolicLink(legacy.toPath()));
  }

  @Test
  public void replacedFinalLogLinkCannotReadOrWriteItsExternalTarget() throws Exception {
    File files = temp.newFolder(),
        external = temp.newFolder(),
        manual = new File(external, "manual-backup.tar.gz");
    Files.writeString(manual.toPath(), "external secret");
    var log = HostWebLog.create(fs, files);
    File output = new File(log.directory(), "stdout.log");
    Files.delete(output.toPath());
    link(output, manual);
    assertThrows(IOException.class, () -> log.append("cannot overwrite"));
    assertThrows(IOException.class, log::read);
    assertEquals("external secret", Files.readString(manual.toPath()));
  }

  @Test
  public void linkedPrivateHomeAndFailureOutputAreRetained() throws Exception {
    File files = temp.newFolder(),
        external = temp.newFolder(),
        manual = new File(external, "manual-backup.tar.gz");
    Files.writeString(manual.toPath(), "manual");
    link(new File(files, HostWebLog.HOME), external);
    assertThrows(IOException.class, () -> HostWebLog.create(fs, files));
    link(new File(files, "last-startup-failure.log"), manual);
    assertThrows(IOException.class, () -> HostWebLog.saveFailure(fs, files, "failure"));
    assertThrows(IOException.class, () -> HostWebLog.readFailure(fs, files));
    assertEquals("manual", Files.readString(manual.toPath()));
    assertEquals(1, external.list().length);
  }

  @Test
  public void boundedPrivateTailAndIndependentAttemptsPreserveEarlierEvidence() throws Exception {
    File files = temp.newFolder();
    var first = HostWebLog.create(fs, files);
    var second = HostWebLog.create(fs, files);
    first.append("old failure");
    second.append("中".repeat(HostWebLog.LIMIT) + "latest error");
    assertNotEquals(first.directory(), second.directory());
    assertEquals("old failure", first.read());
    assertTrue(second.read().endsWith("latest error"));
    assertFalse(second.read().contains("\ufffd"));
    assertTrue(new File(second.directory(), "stdout.log").length() <= HostWebLog.LIMIT);
  }

  @Test
  public void incompletePublicationOrUserModificationIsRetained() throws Exception {
    File files = temp.newFolder();
    var log = HostWebLog.create(fs, files);
    Files.writeString(new File(log.directory(), "stdout.log.previous").toPath(), "manual bytes");
    assertThrows(IOException.class, () -> log.append("new output"));
    assertEquals(
        "manual bytes",
        Files.readString(new File(log.directory(), "stdout.log.previous").toPath()));
    var changed = HostWebLog.create(fs, files);
    Files.writeString(new File(changed.directory(), "stdout.log").toPath(), "user edit");
    assertThrows(IOException.class, () -> changed.append("new output"));
    assertEquals(
        "user edit", Files.readString(new File(changed.directory(), "stdout.log").toPath()));
  }
}
