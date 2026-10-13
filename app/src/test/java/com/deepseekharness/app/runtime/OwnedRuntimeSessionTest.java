package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.ProcessIdentity;
import java.io.*;
import java.nio.file.Files;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class OwnedRuntimeSessionTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private OwnedRuntimeSession fixture(boolean ended, boolean empty, File status) {
    Process process =
        new Process() {
          public InputStream getInputStream() {
            return InputStream.nullInputStream();
          }

          public InputStream getErrorStream() {
            return InputStream.nullInputStream();
          }

          public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
          }

          public int waitFor() {
            return exitValue();
          }

          public int exitValue() {
            if (!ended) throw new IllegalThreadStateException();
            return 0;
          }

          public void destroy() {
            throw new AssertionError("不能以 Process.destroy 冒充会话退出");
          }
        };
    var birth =
        ProcessIdentity.fromStat(
            "123 (signed leader) S 99 123 123 " + "0 ".repeat(15) + "456", 123, 99);
    return new OwnedRuntimeSession(
        new IsolatedInstallProcess(
            process,
            birth,
            status,
            new IsolatedInstallProcess.ExitEvidence() {
              public boolean groupGone() {
                return ended;
              }

              public boolean sessionEmpty() {
                return empty;
              }
            }),
        "synthetic");
  }

  @Test
  public void foregroundStatusNeverProvesWholeSessionExited() throws Exception {
    File status = temporary.newFile();
    Files.writeString(status.toPath(), "0\n");
    var owned = fixture(false, false, status);
    assertEquals(0, owned.exitValue());
    assertFalse(owned.confirmedExit());
    assertThrows(IllegalStateException.class, owned::closeConfirmed);
    assertTrue(status.isFile());
  }

  @Test
  public void exitedLauncherWithRemainingGuestKeepsBarrier() throws Exception {
    File status = temporary.newFile();
    var owned = fixture(true, false, status);
    assertThrows(IllegalStateException.class, owned::closeConfirmed);
    assertFalse(owned.confirmedExit());
    assertTrue(status.isFile());
  }

  @Test
  public void onlyWholeGroupAndSessionExitRetireStatus() throws Exception {
    File status = temporary.newFile();
    var owned = fixture(true, true, status);
    assertTrue(owned.closeConfirmed());
    assertTrue(owned.confirmedExit());
    assertFalse(status.exists());
    assertTrue(owned.closeConfirmed());
  }
}
