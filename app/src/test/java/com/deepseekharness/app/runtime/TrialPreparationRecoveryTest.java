package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TrialPreparationRecoveryTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private record Scene(
      File files, File home, File trial, File payload, BoundedGuestSessions.Operation record) {
    File active() {
      return new File(files, "bounded-guest-active/" + record.id());
    }

    File original() {
      return new File(files, "bounded-guest-pre-dispatch-originals/" + record.id());
    }
  }

  private Scene scene(JvmBackupFileSystem fs) throws IOException {
    File files = temporary.newFolder().getCanonicalFile();
    File home = new File(files, "runtime-trials");
    File trial = new File(home, UUID.randomUUID().toString());
    File payload = new File(trial, "payload");
    Files.createDirectories(payload.toPath());
    Files.writeString(new File(payload, "private-data").toPath(), "retained until closed");
    var record = BoundedGuestSessions.begin(fs, files);
    record.retainCompletion();
    record.uncertain();
    Files.writeString(new File(trial, "native-session-id").toPath(), record.id());
    Files.writeString(new File(trial, "launched").toPath(), trial.getName());
    return new Scene(files, home, trial, payload, record);
  }

  private void reap(JvmBackupFileSystem fs, Scene scene) throws IOException {
    BoundedGuestSessions.reapExited(
        fs,
        scene.files,
        (pid, birth) -> {
          throw new AssertionError("No PID/SID claim proves pre-dispatch");
        });
  }

  private void recoverTrial(JvmBackupFileSystem fs, Scene scene) throws IOException {
    TrialRecovery.recover(
        fs,
        scene.home,
        payload -> {
          String id = TrialSupport.boundSession(fs, payload.getParentFile());
          if (!BoundedGuestSessions.recordedCompletion(fs, scene.files, id))
            throw new IOException("TRIAL_PROCESS_UNCONFIRMED");
        });
  }

  @Test
  public void maintenanceReapPreservesExactOriginalThenPublishesBoundCompletionBeforeTrialCloses()
      throws Exception {
    var fs = new JvmBackupFileSystem();
    Scene scene = scene(fs);
    String intent = Files.readString(new File(scene.active(), "intent").toPath());
    String retain = Files.readString(new File(scene.active(), "retain").toPath());
    assertNotNull(BoundedGuestSessions.firstPending(fs, scene.files));
    assertTrue(scene.active().isDirectory());
    assertFalse(scene.original().exists()); // Pending reads never mutate or synthesize completion.
    reap(fs, scene);
    assertFalse(scene.active().exists());
    assertEquals(Set.of("intent", "retain"), Set.copyOf(fs.list(scene.original())));
    assertEquals(intent, Files.readString(new File(scene.original(), "intent").toPath()));
    assertEquals(retain, Files.readString(new File(scene.original(), "retain").toPath()));
    assertTrue(BoundedGuestSessions.recordedCompletion(fs, scene.files, scene.record.id()));
    assertTrue(scene.payload.isDirectory());
    assertFalse(new File(scene.trial, "closed").exists());
    recoverTrial(fs, scene);
    assertTrue(new File(scene.trial, "closed").isFile());
    assertFalse(scene.payload.exists());
    assertTrue(scene.original().isDirectory());
  }

  @Test
  public void protocolOrAnyUnknownMemberCannotBecomePreDispatchProof() throws Exception {
    for (String name :
        new String[] {"protocol", "protocol.tmp-unknown", "identity", "tree-empty", "extra"}) {
      var fs = new JvmBackupFileSystem();
      Scene scene = scene(fs);
      Files.writeString(new File(scene.active(), name).toPath(), "unconfirmed");
      assertThrows(IOException.class, () -> reap(fs, scene));
      assertTrue(scene.active().isDirectory());
      assertFalse(scene.original().exists());
      assertThrows(
          IOException.class,
          () -> BoundedGuestSessions.recordedCompletion(fs, scene.files, scene.record.id()));
      assertTrue(scene.payload.isDirectory());
    }
  }

  @Test
  public void aLegacyIntentOrUnboundRetainedRecordCannotUseTheV2TrialGate() throws Exception {
    for (boolean legacy : new boolean[] {true, false}) {
      var fs = new JvmBackupFileSystem();
      Scene scene = scene(fs);
      if (legacy) Files.delete(new File(scene.active(), "retain").toPath());
      else Files.delete(new File(scene.trial, "native-session-id").toPath());
      assertThrows(IOException.class, () -> reap(fs, scene));
      assertFalse(scene.original().exists());
      assertTrue(scene.active().isDirectory());
      assertTrue(scene.payload.isDirectory());
    }
  }

  @Test
  public void duplicateTrialBindingCannotRetireEitherPreparation() throws Exception {
    var fs = new JvmBackupFileSystem();
    Scene scene = scene(fs);
    File other = new File(scene.home, UUID.randomUUID().toString());
    Files.createDirectories(new File(other, "payload").toPath());
    Files.writeString(new File(other, "native-session-id").toPath(), scene.record.id());
    Files.writeString(new File(other, "launched").toPath(), other.getName());
    assertThrows(IOException.class, () -> reap(fs, scene));
    assertTrue(scene.active().isDirectory());
    assertFalse(scene.original().exists());
  }

  @Test
  public void currentPreparingOwnerAndAnExistingCompletionAreNotRewritten() throws Exception {
    var fs = new JvmBackupFileSystem();
    Scene scene = scene(fs);
    File completed = new File(scene.files, "bounded-guest-completed");
    Files.createDirectories(completed.toPath());
    Files.writeString(new File(completed, scene.record.id()).toPath(), "unknown receipt");
    assertThrows(IOException.class, () -> reap(fs, scene));
    assertEquals(
        "unknown receipt", Files.readString(new File(completed, scene.record.id()).toPath()));
    assertTrue(scene.active().isDirectory());
    assertFalse(scene.original().exists());
    File currentFiles = temporary.newFolder().getCanonicalFile();
    File home = new File(currentFiles, "runtime-trials");
    File trial = new File(home, UUID.randomUUID().toString());
    Files.createDirectories(new File(trial, "payload").toPath());
    var running = BoundedGuestSessions.begin(fs, currentFiles);
    running.retainCompletion();
    Files.writeString(new File(trial, "native-session-id").toPath(), running.id());
    Files.writeString(new File(trial, "launched").toPath(), trial.getName());
    assertFalse(BoundedGuestSessions.recoverTrialPreparation(fs, currentFiles, trial));
    assertTrue(new File(currentFiles, "bounded-guest-active/" + running.id()).isDirectory());
  }

  @Test
  public void failureAfterPreservingOriginalResumesOnlyFromThatExactBoundOriginal()
      throws Exception {
    var fs =
        new JvmBackupFileSystem() {
          boolean failed;

          @Override
          public void atomic(File directory, String name, byte[] value) throws IOException {
            if (!failed && directory.getName().equals("bounded-guest-completed")) {
              failed = true;
              throw new IOException("COMPLETION_WRITE_FAILED");
            }
            super.atomic(directory, name, value);
          }
        };
    Scene scene = scene(fs);
    assertThrows(IOException.class, () -> recoverTrial(fs, scene));
    assertFalse(scene.active().exists());
    assertEquals(Set.of("intent", "retain"), Set.copyOf(fs.list(scene.original())));
    assertTrue(scene.payload.isDirectory());
    assertFalse(new File(scene.trial, "closed").exists());
    recoverTrial(fs, scene);
    assertTrue(BoundedGuestSessions.recordedCompletion(fs, scene.files, scene.record.id()));
    assertTrue(new File(scene.trial, "closed").isFile());
    assertFalse(scene.payload.exists());
    assertEquals(
        "RECORDED_OWNER\n",
        new String(fs.small(new File(scene.original(), "retain"), 64), StandardCharsets.US_ASCII));
  }
}
