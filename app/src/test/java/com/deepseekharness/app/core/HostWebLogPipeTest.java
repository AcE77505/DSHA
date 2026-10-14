package com.deepseekharness.app.core;

import static org.junit.Assert.*;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.WebLaunchCommand;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Runs the real guest stdout reader and production host sink, without starting a guest/device. */
public class HostWebLogPipeTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void actualOutputReaderWritesOnlyThePrivateSinkAndRetainsAuthProtocol() throws Exception {
    File files = temp.newFolder();
    var fs = new JvmBackupFileSystem();
    var log = HostWebLog.create(fs, files);
    String token = "A".repeat(43), url = "http://127.0.0.1:1234/?token=" + token;
    List<String> auth = new ArrayList<>();
    List<Integer> exited = new ArrayList<>();
    Process process =
        new Process() {
          public InputStream getInputStream() {
            return new ByteArrayInputStream(
                ("plugin output\ndsh web: " + url + "\n").getBytes(StandardCharsets.UTF_8));
          }

          public InputStream getErrorStream() {
            return InputStream.nullInputStream();
          }

          public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
          }

          public int waitFor() {
            return 7;
          }

          public int exitValue() {
            return 7;
          }

          public void destroy() {
            throw new AssertionError();
          }
        };
    new WebOutputSession(1234)
        .drain(
            process,
            new WebOutputSession.Events() {
              public boolean current() {
                return true;
              }

              public void output(String value) {
                try {
                  log.append(value);
                } catch (IOException failure) {
                  throw new AssertionError(failure);
                }
              }

              public boolean authenticated() {
                return !auth.isEmpty();
              }

              public void authentication(String value) {
                auth.add(value);
              }

              public void readFailure(IOException failure) {
                throw new AssertionError(failure);
              }

              public void exited(int value) {
                exited.add(value);
              }
            });
    assertEquals(List.of(url), auth);
    assertEquals(List.of(7), exited);
    assertTrue(log.read().contains("plugin output"));
    assertFalse(log.read().contains(token));
    assertFalse(new File(files, "linux").exists());
    String command = WebLaunchCommand.build("default", true, "en", "web", 1234, 1);
    assertFalse(command.contains("dsh-web.log"));
    assertFalse(command.contains("tee "));
  }

  @Test
  public void actualFailureRecorderUsesTheSameHostSinkWithoutReadingLegacyLog() throws Exception {
    File files = temp.newFolder();
    var fs = new JvmBackupFileSystem();
    var log = HostWebLog.create(fs, files);
    log.append("actual pipe output\n?token=fixture-secret\n");
    var io = Executors.newSingleThreadScheduledExecutor();
    var diagnostics = new StartupDiagnostics(files, fs, () -> 1L, (stage, detail) -> {}, io);
    try {
      diagnostics.begin(1, false);
      diagnostics.preserveFailure(log, "actual failure");
      String saved = HostWebLog.readFailure(fs, files);
      assertTrue(saved.contains("actual failure"));
      assertTrue(saved.contains("actual pipe output"));
      assertFalse(saved.contains("fixture-secret"));
      assertFalse(new File(files, "linux").exists());
    } finally {
      diagnostics.closeHistoryForFixture(2000);
      io.shutdownNow();
    }
  }

  @Test
  public void actualFailureRecorderCannotOverwriteLinkedExternalManualFile() throws Exception {
    File files = temp.newFolder(),
        external = temp.newFolder(),
        manual = new File(external, "manual-backup.tar.gz");
    Files.writeString(manual.toPath(), "external manual bytes");
    try {
      Files.createSymbolicLink(
          new File(files, "last-startup-failure.log").toPath(), manual.toPath());
    } catch (IOException | UnsupportedOperationException unavailable) {
      Assume.assumeNoException(unavailable);
    }
    var fs = new JvmBackupFileSystem();
    var log = HostWebLog.create(fs, files);
    log.append("stdout");
    var io = Executors.newSingleThreadScheduledExecutor();
    var diagnostics = new StartupDiagnostics(files, fs, () -> 1L, (stage, detail) -> {}, io);
    try {
      diagnostics.begin(1, false);
      diagnostics.preserveFailure(log, "must retain");
      assertEquals("external manual bytes", Files.readString(manual.toPath()));
      assertTrue(Files.isSymbolicLink(new File(files, "last-startup-failure.log").toPath()));
    } finally {
      diagnostics.closeHistoryForFixture(2000);
      io.shutdownNow();
    }
  }
}
