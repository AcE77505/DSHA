package com.deepseekharness.app.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class TrialPathsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void recordAuthorityStaysInFilesAcrossLiveAndCandidateLayouts() throws Exception {
    File files = temporary.newFolder("files").getCanonicalFile();
    String id = UUID.randomUUID().toString();
    File payload = new File(files, "runtime-trials/" + id + "/payload");
    Files.createDirectories(payload.toPath());
    String guest = "/root/.dsha-runtime-trial-" + id.replace("-", "");
    for (String root :
        new String[] {
          "linux/ubuntu",
          "cold-install-operations/" + UUID.randomUUID() + "/linux/ubuntu",
          "managed-runtime-operations/" + UUID.randomUUID() + "/candidate/linux/ubuntu",
          "recovery-runtimes/fixture/linux/ubuntu"
        }) {
      File rootfs = new File(files, root);
      Files.createDirectories(rootfs.toPath());
      File legacyHome = new File(rootfs.getParentFile().getParentFile(), "runtime-trials");
      assertEquals(
          root.equals("linux/ubuntu"), legacyHome.equals(payload.getParentFile().getParentFile()));
      TrialPaths.requirePayload(files, payload);
      TrialPaths.requireBinding(files, payload, guest);
      assertEquals(new File(files, "runtime-trials"), TrialPaths.home(files));
      if (!root.equals("linux/ubuntu")) {
        File unrelated = new File(legacyHome, id + "/payload");
        Files.createDirectories(unrelated.toPath());
        assertThrows(IOException.class, () -> TrialPaths.requirePayload(files, unrelated));
      }
    }
  }

  @Test
  public void payloadCannotChangeNonceRootOrUseLinkedParents() throws Exception {
    File files = temporary.newFolder("files").getCanonicalFile();
    String id = UUID.randomUUID().toString();
    File payload = new File(files, "runtime-trials/" + id + "/payload");
    Files.createDirectories(payload.toPath());
    String guest = "/root/.dsha-runtime-trial-" + id.replace("-", "");
    assertThrows(IOException.class, () -> TrialPaths.requireBinding(files, payload, guest + "0"));
    assertThrows(
        IOException.class,
        () ->
            TrialPaths.requireBinding(
                files,
                payload,
                "/root/.dsha-runtime-trial-" + UUID.randomUUID().toString().replace("-", "")));
    assertThrows(
        IOException.class, () -> TrialPaths.requirePayload(files, new File(payload, "../payload")));
    assertThrows(
        IOException.class,
        () ->
            TrialPaths.requirePayload(files, new File(files, "runtime-trials/not-a-uuid/payload")));
    File alias = new File(temporary.getRoot(), "linked-files");
    try {
      Files.createSymbolicLink(alias.toPath(), files.toPath());
    } catch (IOException | UnsupportedOperationException denied) {
      Assume.assumeNoException("Host does not allow symbolic links", denied);
    }
    assertThrows(
        IOException.class,
        () ->
            TrialPaths.requirePayload(files, new File(alias, "runtime-trials/" + id + "/payload")));
  }
}
