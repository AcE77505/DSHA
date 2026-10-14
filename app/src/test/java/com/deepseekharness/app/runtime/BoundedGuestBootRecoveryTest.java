package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BoundedGuestBootRecoveryTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final String BOOT_A = "11111111-1111-4111-8111-111111111111\n";
  private static final String BOOT_B = "22222222-2222-4222-8222-222222222222\n";

  private record Fixture(File files, File entry, String id, JvmBackupFileSystem fs) {}

  private Fixture fixture(JvmBackupFileSystem fs) throws Exception {
    File files = temporary.newFolder();
    return fixture(fs, files);
  }

  private Fixture fixture(JvmBackupFileSystem fs, File files) throws Exception {
    File home = new File(files, "bounded-guest-active");
    if (!home.exists()) fs.directory(home);
    String id = UUID.randomUUID().toString();
    File entry = new File(home, id);
    fs.directory(entry);
    write(fs, new File(entry, "intent"), id + "\n");
    write(fs, new File(entry, "identity"), id + "\n3151 172477833 3151\n");
    write(fs, new File(entry, "protocol"), "DSHA_OWNED_TREE_V2\n");
    return new Fixture(files, entry, id, fs);
  }

  private static void write(BackupFileSystem fs, File file, String value) throws IOException {
    try (OutputStream out = fs.create(file)) {
      out.write(value.getBytes(StandardCharsets.US_ASCII));
    }
  }

  private static File journal(Fixture f) {
    return new File(f.files, BoundedGuestBootRecovery.HOME + "/" + f.id);
  }

  private static void reap(Fixture f, String boot) throws IOException {
    BoundedGuestSessions.reapExited(
        f.fs,
        f.files,
        (pid, born) -> {
          throw new AssertionError("A V2 unknown tree cannot use SID evidence");
        },
        (pid, born) -> {
          throw new AssertionError("A V2 unknown tree cannot use leader absence");
        },
        () -> boot);
  }

  private static IOException checkpoint(Fixture f) throws IOException {
    IOException error = assertThrows(IOException.class, () -> reap(f, BOOT_A));
    assertTrue(error.getMessage(), error.getMessage().contains("REAL_REBOOT_REQUIRED"));
    assertTrue(f.entry.isDirectory());
    assertNotNull(BoundedGuestSessions.firstPending(f.fs, f.files));
    return error;
  }

  private static void closed(Fixture f) throws IOException {
    assertNull(BoundedGuestSessions.firstPending(f.fs, f.files));
    assertFalse(f.entry.exists());
    File original = new File(journal(f), "original");
    assertTrue(original.isDirectory());
    assertEquals(Set.of("identity", "intent", "protocol"), new HashSet<>(f.fs.list(original)));
    assertArrayEquals(
        (f.id + "\n3151 172477833 3151\n").getBytes(StandardCharsets.US_ASCII),
        f.fs.small(new File(original, "identity"), 128));
    String proof = Files.readString(new File(journal(f), "closed").toPath());
    assertTrue(proof.startsWith("DSHA_BOOT_BOUNDARY_CLOSED_V1\n" + f.id + "\n"));
    assertFalse(proof.contains("ECHILD"));
    assertFalse(proof.contains("LAUNCHER_EXITED"));
    assertFalse(proof.contains("NOT_DISPATCHED"));
    assertThrows(
        IOException.class, () -> BoundedGuestSessions.recordedCompletion(f.fs, f.files, f.id));
    assertFalse(new File(f.files, "bounded-guest-completed/" + f.id).exists());
  }

  @Test
  public void inventoryIsReadOnlyAndDoesNotAcquireCheckpoint() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    assertEquals(f.id, BoundedGuestSessions.firstPending(f.fs, f.files));
    assertFalse(new File(f.files, BoundedGuestBootRecovery.HOME).exists());
  }

  @Test
  public void sameBootAlwaysBlocksAndCheckpointBytesDoNotChange() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    checkpoint(f);
    byte[] before = Files.readAllBytes(new File(journal(f), "checkpoint").toPath());
    for (int n = 0; n < 3; n++) checkpoint(f);
    assertArrayEquals(before, Files.readAllBytes(new File(journal(f), "checkpoint").toPath()));
    assertFalse(new File(journal(f), "closed").exists());
  }

  @Test
  public void realBootChangePreservesWholeOriginalAndIsNotTaskCompletion() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    String key = f.fs.stat(f.entry).key;
    checkpoint(f);
    reap(f, BOOT_B);
    closed(f);
    assertEquals(key, f.fs.stat(new File(journal(f), "original")).key);
    reap(f, BOOT_B);
    closed(f);
  }

  @Test
  public void differentBootDoesNotSkipInitialCheckpoint() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    assertThrows(IOException.class, () -> reap(f, BOOT_B));
    assertTrue(f.entry.isDirectory());
    assertFalse(new File(journal(f), "closed").exists());
  }

  @Test
  public void retainedAndUnknownMembersNeverAcquireCheckpoint() throws Exception {
    for (String name : List.of("retain", "unknown", "tree-empty")) {
      Fixture f = fixture(new JvmBackupFileSystem());
      write(f.fs, new File(f.entry, name), "foreign\n");
      assertThrows(IOException.class, () -> reap(f, BOOT_A));
      assertTrue(f.entry.isDirectory());
      assertFalse(journal(f).exists());
    }
  }

  @Test
  public void currentOwnerNeverAcquiresBootCheckpoint() throws Exception {
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    File files = temporary.newFolder();
    var operation = BoundedGuestSessions.begin(fs, files);
    operation.uncertain();
    File entry = new File(files, "bounded-guest-active/" + operation.id());
    write(fs, new File(entry, "identity"), operation.id() + "\n3151 172477833 3151\n");
    write(fs, new File(entry, "protocol"), "DSHA_OWNED_TREE_V2\n");
    Fixture f = new Fixture(files, entry, operation.id(), fs);
    assertThrows(IOException.class, () -> reap(f, BOOT_A));
    assertFalse(journal(f).exists());
  }

  @Test
  public void closedTrialBindingStillExcludesBootRecovery() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    File trials = new File(f.files, "runtime-trials");
    f.fs.directory(trials);
    File trial = new File(trials, UUID.randomUUID().toString());
    f.fs.directory(trial);
    write(f.fs, new File(trial, "native-session-id"), f.id);
    write(f.fs, new File(trial, "closed"), "CLOSED\n");
    assertThrows(IOException.class, () -> reap(f, BOOT_A));
    assertFalse(journal(f).exists());
  }

  @Test
  public void coldCandidateLaunchBindingExcludesBootRecovery() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    File cold = new File(f.files, "cold-install-operations");
    f.fs.directory(cold);
    File operation = new File(cold, UUID.randomUUID().toString());
    f.fs.directory(operation);
    write(f.fs, new File(operation, "launches.json"), "{\"ids\":[\"" + f.id + "\"]}");
    assertThrows(IOException.class, () -> reap(f, BOOT_A));
    assertFalse(journal(f).exists());
  }

  @Test
  public void malformedBootNeverAcquiresCheckpoint() throws Exception {
    for (String value :
        List.of(
            "",
            BOOT_A.trim(),
            BOOT_A + "extra",
            "not-a-kernel-id\n",
            BOOT_A.toUpperCase(Locale.ROOT))) {
      if (value.equals(BOOT_A)) continue;
      Fixture f = fixture(new JvmBackupFileSystem());
      assertThrows(IOException.class, () -> reap(f, value));
      assertFalse(journal(f).exists());
    }
  }

  @Test
  public void malformedIdentityAndProtocolNeverAcquireCheckpoint() throws Exception {
    for (String member : List.of("identity", "protocol", "intent")) {
      Fixture f = fixture(new JvmBackupFileSystem());
      f.fs.delete(new File(f.entry, member));
      write(f.fs, new File(f.entry, member), "invalid\n");
      assertThrows(IOException.class, () -> reap(f, BOOT_A));
      assertFalse(journal(f).exists());
    }
  }

  @Test
  public void changedBytesAfterCheckpointKeepOriginalActive() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    checkpoint(f);
    File identity = new File(f.entry, "identity");
    f.fs.delete(identity);
    write(f.fs, identity, f.id + "\n3151 172477834 3151\n");
    assertThrows(IOException.class, () -> reap(f, BOOT_B));
    assertTrue(f.entry.isDirectory());
    assertFalse(new File(journal(f), "closed").exists());
  }

  @Test
  public void sameBytesOnDifferentFileIdentityStillFailClosed() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    checkpoint(f);
    File old = new File(f.entry, "intent"), retained = new File(f.files, "old-intent");
    f.fs.move(old, retained);
    write(f.fs, old, f.id + "\n");
    assertThrows(IOException.class, () -> reap(f, BOOT_B));
    assertTrue(f.entry.isDirectory());
  }

  @Test
  public void memberAddedAfterCheckpointBlocksCrossBoot() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    checkpoint(f);
    write(f.fs, new File(f.entry, "retain"), "RECORDED_OWNER\n");
    assertThrows(IOException.class, () -> reap(f, BOOT_B));
    assertTrue(f.entry.isDirectory());
  }

  @Test
  public void sourceFileAndCheckpointRootLinksAreRejected() throws Exception {
    org.junit.Assume.assumeFalse(
        "Real symbolic links require the Linux host fixture on this Windows account",
        System.getProperty("os.name").startsWith("Windows"));
    Fixture f = fixture(new JvmBackupFileSystem());
    File intent = new File(f.entry, "intent");
    File other = new File(f.files, "source");
    write(f.fs, other, f.id + "\n");
    f.fs.delete(intent);
    f.fs.symlink(other.getAbsolutePath(), intent);
    assertThrows(IOException.class, () -> reap(f, BOOT_A));
    assertFalse(journal(f).exists());
    Fixture g = fixture(new JvmBackupFileSystem());
    File foreign = temporary.newFolder();
    g.fs.symlink(foreign.getAbsolutePath(), new File(g.files, BoundedGuestBootRecovery.HOME));
    assertThrows(IOException.class, () -> reap(g, BOOT_A));
    assertTrue(g.entry.isDirectory());
    assertEquals(0, Objects.requireNonNull(foreign.list()).length);
  }

  @Test
  public void malformedCheckpointAndClosureNeverClearPending() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    checkpoint(f);
    File cp = new File(journal(f), "checkpoint");
    f.fs.delete(cp);
    write(f.fs, cp, "bad\n");
    assertThrows(IOException.class, () -> reap(f, BOOT_B));
    assertTrue(f.entry.isDirectory());
    assertNotNull(BoundedGuestSessions.firstPending(f.fs, f.files));
    Fixture g = fixture(new JvmBackupFileSystem());
    checkpoint(g);
    reap(g, BOOT_B);
    File proof = new File(journal(g), "closed");
    g.fs.delete(proof);
    write(g.fs, proof, "DSHA_OWNED_TREE_V2_CLOSED\n");
    assertThrows(IOException.class, () -> BoundedGuestSessions.firstPending(g.fs, g.files));
  }

  @Test
  public void partialAtomicCheckpointAndProofFilesDoNotAuthorizeClosure() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    checkpoint(f);
    write(
        f.fs,
        new File(journal(f), "closed.tmp-" + UUID.randomUUID()),
        "DSHA_BOOT_BOUNDARY_CLOSED_V1\n");
    assertNotNull(BoundedGuestSessions.firstPending(f.fs, f.files));
    reap(f, BOOT_B);
    closed(f);
    assertTrue(new File(journal(f), "closed").isFile());
  }

  @Test
  public void unknownAtomicTemporaryBytesKeepBarrier() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    checkpoint(f);
    write(f.fs, new File(journal(f), "closed.tmp-" + UUID.randomUUID()), "foreign");
    assertThrows(IOException.class, () -> BoundedGuestSessions.firstPending(f.fs, f.files));
    assertThrows(IOException.class, () -> reap(f, BOOT_B));
    assertTrue(f.entry.isDirectory());
  }

  @Test
  public void closedHistoryIsNotALifetimeRecoveryBudget() throws Exception {
    JvmBackupFileSystem fs = new JvmBackupFileSystem();
    File files = temporary.newFolder();
    for (int n = 0; n < 130; n++) {
      Fixture f = fixture(fs, files);
      assertThrows(
          IOException.class,
          () -> BoundedGuestBootRecovery.recover(fs, files, f.entry, f.id, () -> BOOT_A, id -> {}));
      BoundedGuestBootRecovery.recover(fs, files, f.entry, f.id, () -> BOOT_B, id -> {});
    }
    Fixture next = fixture(fs, files);
    checkpoint(next);
    reap(next, BOOT_B);
    closed(next);
    assertEquals(131, fs.list(new File(files, BoundedGuestBootRecovery.HOME)).size());
  }

  @Test
  public void movedOriginalWithoutProofRemainsPendingAndResumes() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    checkpoint(f);
    f.fs.move(f.entry, new File(journal(f), "original"));
    assertNotNull(BoundedGuestSessions.firstPending(f.fs, f.files));
    assertThrows(IOException.class, () -> reap(f, BOOT_A));
    reap(f, BOOT_B);
    closed(f);
  }

  @Test
  public void orphanCheckpointWithNoOriginalStillBlocksReap() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    checkpoint(f);
    f.fs.move(f.entry, new File(f.files, "unrelated-retained-entry"));
    assertNotNull(BoundedGuestSessions.firstPending(f.fs, f.files));
    assertThrows(IOException.class, () -> reap(f, BOOT_B));
  }

  @Test
  public void bootProofCannotBeConsumedAsAnOldTaskCompletionEvenIfMisplaced() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    checkpoint(f);
    reap(f, BOOT_B);
    closed(f);
    File completions = new File(f.files, "bounded-guest-completed");
    f.fs.directory(completions);
    write(
        f.fs,
        new File(completions, f.id),
        Files.readString(new File(journal(f), "closed").toPath()));
    assertThrows(
        IOException.class, () -> BoundedGuestSessions.recordedCompletion(f.fs, f.files, f.id));
  }

  @Test
  public void partialFirstCheckpointCanBeRetriedAndStillRequiresReboot() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    f.fs.directory(new File(f.files, BoundedGuestBootRecovery.HOME));
    f.fs.directory(journal(f));
    write(
        f.fs,
        new File(journal(f), "checkpoint.tmp-" + UUID.randomUUID()),
        "DSHA_BOOT_BOUNDARY_CHECKPOINT_V1\n");
    checkpoint(f);
    reap(f, BOOT_B);
    closed(f);
  }

  @Test
  public void cancelledMaintenanceDoesNotWriteCheckpoint() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedIOException.class, () -> reap(f, BOOT_A));
    } finally {
      Thread.interrupted();
    }
    assertTrue(f.entry.isDirectory());
    assertFalse(journal(f).exists());
  }

  @Test
  public void unreadableKernelBootIdDoesNotWriteCheckpoint() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                BoundedGuestSessions.reapExited(
                    f.fs,
                    f.files,
                    (pid, born) -> true,
                    (pid, born) -> true,
                    () -> {
                      throw new IOException("BOOT_READ_DENIED");
                    }));
    assertTrue(error.getMessage().contains("BOUNDED_GUEST_BOOT_ID_UNAVAILABLE"));
    assertFalse(error.getMessage().contains("REAL_REBOOT_REQUIRED"));
    assertTrue(f.entry.isDirectory());
    assertFalse(journal(f).exists());
  }

  @Test
  public void kernelBootChangeDuringOneCallIsRejectedBeforeMove() throws Exception {
    Fixture f = fixture(new JvmBackupFileSystem());
    checkpoint(f);
    int[] calls = {0};
    assertThrows(
        IOException.class,
        () ->
            BoundedGuestSessions.reapExited(
                f.fs,
                f.files,
                (pid, born) -> true,
                (pid, born) -> true,
                () -> calls[0]++ == 0 ? BOOT_B : BOOT_A));
    assertTrue(f.entry.isDirectory());
    assertFalse(new File(journal(f), "original").exists());
  }

  private static final class FaultFs extends JvmBackupFileSystem {
    int failAt = -1, events;
    boolean after;

    void before() throws IOException {
      if (!after && ++events == failAt) throw new IOException("INJECTED");
    }

    void after() throws IOException {
      if (after && ++events == failAt) throw new IOException("INJECTED");
    }

    @Override
    public void directory(File file) throws IOException {
      before();
      super.directory(file);
      after();
    }

    @Override
    public void move(File from, File to) throws IOException {
      before();
      super.move(from, to);
      after();
    }

    @Override
    public void syncDirectory(File file) throws IOException {
      before();
      super.syncDirectory(file);
      after();
    }

    @Override
    public void atomic(File dir, String name, byte[] bytes) throws IOException {
      before();
      super.atomic(dir, name, bytes);
      after();
    }
  }

  @Test
  public void everyCheckpointMutationInterruptionCanRetryWithoutReleasingBarrier()
      throws Exception {
    FaultFs count = new FaultFs();
    Fixture baseline = fixture(count);
    count.events = 0;
    checkpoint(baseline);
    int boundaries = count.events;
    for (boolean after : List.of(false, true))
      for (int fail = 1; fail <= boundaries + 3; fail++) {
        FaultFs fs = new FaultFs();
        Fixture f = fixture(fs);
        fs.events = 0;
        fs.failAt = fail;
        fs.after = after;
        assertThrows(IOException.class, () -> reap(f, BOOT_A));
        assertTrue(f.entry.isDirectory());
        assertNotNull(BoundedGuestSessions.firstPending(fs, f.files));
        fs.failAt = -1;
        checkpoint(f);
        reap(f, BOOT_B);
        closed(f);
      }
  }

  @Test
  public void everyArchiveAndProofInterruptionRecoversIdempotently() throws Exception {
    FaultFs count = new FaultFs();
    Fixture baseline = fixture(count);
    checkpoint(baseline);
    count.events = 0;
    reap(baseline, BOOT_B);
    int boundaries = count.events;
    for (boolean after : List.of(false, true))
      for (int fail = 1; fail <= boundaries + 3; fail++) {
        FaultFs fs = new FaultFs();
        Fixture f = fixture(fs);
        checkpoint(f);
        fs.events = 0;
        fs.failAt = fail;
        fs.after = after;
        try {
          reap(f, BOOT_B);
        } catch (IOException expected) {
        }
        boolean active = f.entry.isDirectory(),
            original = new File(journal(f), "original").isDirectory();
        assertTrue(active ^ original);
        if (BoundedGuestSessions.firstPending(fs, f.files) == null)
          assertTrue(new File(journal(f), "closed").isFile());
        fs.failAt = -1;
        reap(f, BOOT_B);
        closed(f);
        reap(f, BOOT_B);
        closed(f);
      }
  }
}
