package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class WebRuntimeFallbackTest {
  @Test
  public void onlyExplicitPreauthEngineFailureWithClosedOwnedTreeRetriesOnce() {
    assertTrue(
        WebRuntimeFallback.shouldRetry(
            "bxroot", false, false, false, true, 139, true, true, false));
    assertFalse(
        WebRuntimeFallback.shouldRetry("bxroot", true, false, false, true, 139, true, true, false));
    assertFalse(
        WebRuntimeFallback.shouldRetry("proot", false, false, false, true, 139, true, true, false));
    assertFalse(
        WebRuntimeFallback.shouldRetry(
            "proroot", false, false, false, true, 139, true, true, false));
  }

  @Test
  public void unknownBusinessPluginTimeoutAndGenericExitNeverReplay() {
    assertFalse(
        WebRuntimeFallback.shouldRetry(
            "bxroot", false, false, false, true, 139, false, true, false));
    assertFalse(
        WebRuntimeFallback.shouldRetry(
            "bxroot", false, false, false, true, 139, true, false, false));
    assertFalse(
        WebRuntimeFallback.shouldRetry("bxroot", false, false, false, true, 139, true, true, true));
    assertFalse(
        WebRuntimeFallback.shouldRetry("bxroot", false, true, false, true, 139, true, true, false));
    assertFalse(
        WebRuntimeFallback.shouldRetry("bxroot", false, false, true, true, 139, true, true, false));
    assertFalse(
        WebRuntimeFallback.shouldRetry(
            "bxroot", false, false, false, true, null, true, true, false));
    assertFalse(WebRuntimeFallback.shouldRetry("bxroot", false, false, false, true, 0));
  }
}
