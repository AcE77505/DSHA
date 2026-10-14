package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.*;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.RuntimeCapabilityProbe;
import com.deepseekharness.app.backup.BackupJson;

public class RuntimeEngineQualificationTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void regularBytesUseCompleteDigest() throws Exception {
    File file = temporary.newFile("component");
    Files.write(file.toPath(), new byte[] {1, 2, 3});
    assertEquals(
        "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
        RuntimeEngineQualification.fileHash(new JvmBackupFileSystem(), file));
  }

  @Test
  public void isolatedProbeRequestsLauncherVerboseBeforeTheFixedGuestCommand() throws Exception {
    var argv =
        new java.util.ArrayList<>(java.util.List.of("/signed/libbxroot.so", "--kill-on-exit"));
    File scratch = temporary.newFolder("probe");
    String command = RuntimeCapabilityProbe.command();
    RuntimeEngineQualification.appendProbeArguments(argv, scratch, command);
    assertEquals("/signed/libbxroot.so", argv.get(0));
    assertTrue(argv.indexOf("--verbose=1") < argv.indexOf("/bin/bash"));
    assertEquals(1, java.util.Collections.frequency(argv, "--verbose=1"));
    assertEquals(scratch.getAbsolutePath() + ":/tmp", argv.get(argv.indexOf("-b") + 1));
    assertEquals("-c", argv.get(argv.size() - 2));
    assertEquals(command, argv.get(argv.size() - 1));
  }

  @Test
  public void nonRegularAndEmptyInputsNeverReachRead() throws Exception {
    for (String type : new String[] {"SPECIAL", "DIRECTORY", "LINK", "MISSING"}) {
      var fs =
          new JvmBackupFileSystem() {
            @Override
            public Node stat(File file) {
              return new Node(type, "test", 1, 0, 0, 0);
            }

            @Override
            public InputStream read(File file, Node expected) {
              throw new AssertionError("must not open non-regular input");
            }
          };
      assertThrows(
          IOException.class, () -> RuntimeEngineQualification.fileHash(fs, new File("component")));
    }
    assertThrows(
        IOException.class,
        () ->
            RuntimeEngineQualification.fileHash(
                new JvmBackupFileSystem(), temporary.newFile("empty")));
  }

  @Test(timeout = 2000)
  public void actualLinuxFifoIsRejectedWithoutOpening() throws Exception {
    org.junit.Assume.assumeTrue(File.separatorChar == '/');
    File file = new File(temporary.getRoot(), "fifo");
    assertEquals(0, new ProcessBuilder("mkfifo", file.getPath()).start().waitFor());
    assertThrows(
        IOException.class,
        () -> RuntimeEngineQualification.fileHash(new JvmBackupFileSystem(), file));
  }

  @Test
  public void unknownFailureRetainsRedactedOutputWithoutClaimingClosureOrFallback()
      throws Exception {
    var result =
        BoundedProcessRunner.localCompletion(
            "bxroot-ulx: 装载 /private/ld.so 失败: Permission denied\n"
                + "Authorization: Bearer secret-value-123456\n");
    var saved =
        RuntimeEngineQualification.diagnostic(
            "a".repeat(64),
            RuntimeCapabilityProbe.Kind.NODE,
            false,
            "UNCONFIRMED",
            false,
            result,
            new IOException("probe failed", new IOException("password=do-not-persist")));
    assertEquals(false, saved.get("ownedTreeEmpty"));
    assertEquals(false, saved.get("launcherExited"));
    assertEquals(false, saved.get("supported"));
    String encoded =
        new String(BackupJson.write(saved, 65536), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(encoded.contains("Permission denied"));
    assertTrue(encoded.contains("probe failed"));
    assertFalse(encoded.contains("secret-value-123456"));
    assertFalse(encoded.contains("do-not-persist"));
  }

  @Test
  public void diagnosticRemainsBoundedEvenForLargeMultibyteOutputAndCauseChain() throws Exception {
    String output = "首".repeat(5000) + "结束";
    var failure = new IOException("错".repeat(10000));
    Throwable current = failure;
    for (int i = 0; i < 12; i++) {
      var next = new IOException("因".repeat(10000));
      current.initCause(next);
      current = next;
    }
    var saved =
        RuntimeEngineQualification.diagnostic(
            "a".repeat(64),
            RuntimeCapabilityProbe.Kind.PYTHON,
            false,
            "UNCONFIRMED",
            false,
            BoundedProcessRunner.localCompletion(output),
            failure);
    assertEquals(4000, ((String) saved.get("head")).length());
    assertTrue(((String) saved.get("tail")).endsWith("结束"));
    assertEquals(4, ((java.util.List<?>) saved.get("failure")).size());
    assertTrue(BackupJson.write(saved, 65536).length < 65536);
  }
}
