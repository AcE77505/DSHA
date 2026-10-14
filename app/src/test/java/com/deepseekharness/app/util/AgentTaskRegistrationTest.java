package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class AgentTaskRegistrationTest {
  private static final String REAL = "11111111111111111111111111111111";
  private static final String FAKE = "22222222222222222222222222222222";

  @Test
  public void sharedTokenAndActualPortCannotOccupyRegistration() {
    var lease = new AgentTaskRegistration(3, "actual-instance");
    var env = lease.launchEnvironment();
    var ready =
        new PreviewPageSession.Identity(
            3, "actual-instance", "http://127.0.0.1:41821/auth?token=private");
    var snapshot = lease.ready(ready);
    var state = new AgentTaskState();
    assertFalse(snapshot.register(state, FAKE, 3, "actual-instance", "", 41821, 0));
    assertFalse(snapshot.register(state, FAKE, 3, "actual-instance", "0".repeat(64), 41821, 0));
    assertTrue(
        snapshot.register(state, REAL, 3, "actual-instance", env.get("DSH_TASK_TOKEN"), 41821, 1));
    assertTrue(state.matches(3, "actual-instance", REAL));
    assertFalse(state.matches(3, "actual-instance", FAKE));
    var key = new AgentTaskState.Key(3, "actual-instance", "session", 1, "a".repeat(32));
    assertTrue(state.event(REAL, key, 1, "running", "", 2));
    var row = state.snapshot(2).get(0);
    assertTrue(state.requestStop(key, row.action(), 3));
    assertNull(state.claim(FAKE, 3, "actual-instance"));
    assertNotNull(state.claim(REAL, 3, "actual-instance"));
    assertEquals("3", env.get("DSH_TASK_GENERATION"));
    assertEquals("actual-instance", env.get("DSH_TASK_INSTANCE"));
    assertEquals(64, env.get("DSH_TASK_TOKEN").length());
  }

  @Test
  public void wrongGenerationInstanceAndPortDoNotConsumeRealLease() {
    var lease = new AgentTaskRegistration(4, "new-instance");
    var nonce = lease.launchEnvironment().get("DSH_TASK_TOKEN");
    var snapshot =
        lease.ready(
            new PreviewPageSession.Identity(4, "new-instance", "http://127.0.0.1:43111/auth"));
    var state = new AgentTaskState();
    assertFalse(snapshot.register(state, FAKE, 3, "new-instance", nonce, 43111, 0));
    assertFalse(snapshot.register(state, FAKE, 4, "old-instance", nonce, 43111, 0));
    assertFalse(snapshot.register(state, FAKE, 4, "new-instance", nonce, 43110, 0));
    assertTrue(snapshot.register(state, REAL, 4, "new-instance", nonce, 43111, 1));
  }

  @Test
  public void revokedSnapshotAndOldGenerationNonceCannotRegister() {
    var old = new AgentTaskRegistration(3, "old-instance");
    var oldNonce = old.launchEnvironment().get("DSH_TASK_TOKEN");
    var oldSnapshot =
        old.ready(
            new PreviewPageSession.Identity(3, "old-instance", "http://127.0.0.1:41821/auth"));
    old.revoke();
    assertNull(
        old.ready(
            new PreviewPageSession.Identity(3, "old-instance", "http://127.0.0.1:41821/auth")));
    assertFalse(
        oldSnapshot.register(new AgentTaskState(), REAL, 3, "old-instance", oldNonce, 41821, 0));
    var current = new AgentTaskRegistration(4, "new-instance");
    var currentSnapshot =
        current.ready(
            new PreviewPageSession.Identity(4, "new-instance", "http://127.0.0.1:41821/auth"));
    var state = new AgentTaskState();
    assertFalse(currentSnapshot.register(state, FAKE, 4, "new-instance", oldNonce, 41821, 0));
    assertTrue(
        currentSnapshot.register(
            state,
            REAL,
            4,
            "new-instance",
            current.launchEnvironment().get("DSH_TASK_TOKEN"),
            41821,
            1));
  }

  @Test
  public void startingOrMismatchedReadyIdentityHasNoSnapshot() {
    var lease = new AgentTaskRegistration(3, "actual-instance");
    assertNull(lease.ready(null));
    assertNull(
        lease.ready(
            new PreviewPageSession.Identity(2, "actual-instance", "http://127.0.0.1:41821/auth")));
    assertNull(
        lease.ready(
            new PreviewPageSession.Identity(3, "other-instance", "http://127.0.0.1:41821/auth")));
  }
}
