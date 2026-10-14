package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RuntimeExecutionTrialTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private record Scene(
      File files,
      File operation,
      File payload,
      String guest,
      BoundedGuestSessions.Operation record) {}

  private Scene scene(JvmBackupFileSystem fs) throws IOException {
    File files = temporary.newFolder().getCanonicalFile();
    String id = UUID.randomUUID().toString();
    File operation = new File(files, "runtime-trials/" + id);
    File payload = new File(operation, "payload");
    java.nio.file.Files.createDirectories(payload.toPath());
    return new Scene(
        files,
        operation,
        payload,
        "/root/.dsha-runtime-trial-" + id.replace("-", ""),
        BoundedGuestSessions.begin(fs, files));
  }

  @Test
  public void productionTrialBuilderHandsOffAnIsolatedRecordAndExactPayloadBinding()
      throws Exception {
    var fs = new JvmBackupFileSystem();
    Scene scene = scene(fs);
    AtomicInteger starts = new AtomicInteger();
    RuntimeExecution.startBoundTrial(
        fs,
        scene.operation,
        scene.payload,
        scene.guest,
        scene.record,
        LaunchSpec.runtime(RuntimeLauncherTest.fake()).dataDomain(scene.files),
        spec -> {
          starts.incrementAndGet();
          assertTrue(spec.isolated);
          assertSame(scene.record, spec.record);
          assertEquals(scene.files, spec.dataDomain);
          assertEquals(1, spec.binds.size());
          assertEquals(scene.payload.getAbsolutePath(), spec.binds.get(0).host());
          assertEquals(scene.guest, spec.binds.get(0).guest());
          assertEquals(scene.record.id(), TrialSupport.boundSession(fs, scene.operation));
          assertEquals(
              scene.operation.getName(),
              new String(
                  fs.small(new File(scene.operation, "launched"), 128), StandardCharsets.US_ASCII));
          scene.record.completedBeforeDispatch(); // This test starter deliberately spawns nothing.
          return null;
        });
    assertEquals(1, starts.get());
    assertTrue(BoundedGuestSessions.recordedCompletion(fs, scene.files, scene.record.id()));
  }

  @Test
  public void buildFailureCompletesTheCurrentRecordWithoutClaimingAnyLaunch() throws Exception {
    var fs = new JvmBackupFileSystem();
    Scene scene = scene(fs);
    AtomicInteger starts = new AtomicInteger();
    var builder =
        LaunchSpec.runtime(RuntimeLauncherTest.fake()).environment(Map.of("bad-key", "value"));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RuntimeExecution.startBoundTrial(
                    fs,
                    scene.operation,
                    scene.payload,
                    scene.guest,
                    scene.record,
                    builder,
                    spec -> {
                      starts.incrementAndGet();
                      return null;
                    }));
    assertEquals("LAUNCH_ENVIRONMENT_FORMAT", failure.getMessage());
    assertEquals(0, starts.get());
    assertFalse(new File(scene.operation, "launched").exists());
    assertTrue(BoundedGuestSessions.recordedCompletion(fs, scene.files, scene.record.id()));
    assertTrue(
        TrialSupport.untrackedExitConfirmed(
            fs,
            scene.operation,
            id -> BoundedGuestSessions.recordedCompletion(fs, scene.files, id)));
  }

  @Test
  public void publishedLaunchMarkerWithFailedSyncStillGetsOnlyCurrentPreDispatchProof()
      throws Exception {
    var fs =
        new JvmBackupFileSystem() {
          boolean failed;

          @Override
          public void syncDirectory(File directory) throws IOException {
            if (!failed && new File(directory, "launched").isFile()) {
              failed = true;
              throw new IOException("LAUNCHED_SYNC_FAILED");
            }
            super.syncDirectory(directory);
          }
        };
    Scene scene = scene(fs);
    AtomicInteger starts = new AtomicInteger();
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                RuntimeExecution.startBoundTrial(
                    fs,
                    scene.operation,
                    scene.payload,
                    scene.guest,
                    scene.record,
                    LaunchSpec.runtime(RuntimeLauncherTest.fake()),
                    spec -> {
                      starts.incrementAndGet();
                      return null;
                    }));
    assertEquals("LAUNCHED_SYNC_FAILED", failure.getMessage());
    assertEquals(0, starts.get());
    assertTrue(new File(scene.operation, "launched").isFile());
    assertTrue(BoundedGuestSessions.recordedCompletion(fs, scene.files, scene.record.id()));
    assertTrue(
        TrialSupport.untrackedExitConfirmed(
            fs,
            scene.operation,
            id -> BoundedGuestSessions.recordedCompletion(fs, scene.files, id)));
  }

  @Test
  public void failureAfterHandoffCannotPublishAFalsePreDispatchCompletion() throws Exception {
    var fs = new JvmBackupFileSystem();
    Scene scene = scene(fs);
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                RuntimeExecution.startBoundTrial(
                    fs,
                    scene.operation,
                    scene.payload,
                    scene.guest,
                    scene.record,
                    LaunchSpec.runtime(RuntimeLauncherTest.fake()),
                    spec -> {
                      throw new IOException("DISPATCH_RESULT_UNKNOWN");
                    }));
    assertEquals("DISPATCH_RESULT_UNKNOWN", failure.getMessage());
    assertTrue(new File(scene.operation, "launched").isFile());
    assertThrows(
        IOException.class,
        () -> BoundedGuestSessions.recordedCompletion(fs, scene.files, scene.record.id()));
    assertNotNull(BoundedGuestSessions.firstPending(fs, scene.files));
    assertFalse(TrialSupport.untrackedExitConfirmed(fs, scene.operation, id -> false));
  }

  @Test
  public void launchSpecStillRejectsARecordWithoutIsolation() throws Exception {
    var fs = new JvmBackupFileSystem();
    Scene scene = scene(fs);
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> LaunchSpec.runtime(RuntimeLauncherTest.fake()).record(scene.record).build());
    assertEquals("LAUNCH_RECORD_REQUIRES_ISOLATION", failure.getMessage());
  }
}
