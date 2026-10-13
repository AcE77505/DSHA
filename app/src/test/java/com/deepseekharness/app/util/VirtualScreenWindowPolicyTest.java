package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VirtualScreenWindowPolicyTest {
  @Test
  public void focusedWindowWinsConsistently() {
    assertTrue(
        VirtualScreenWindowPolicy.rank(true, false) > VirtualScreenWindowPolicy.rank(false, true));
    assertTrue(
        VirtualScreenWindowPolicy.rank(false, true) > VirtualScreenWindowPolicy.rank(false, false));
  }

  @Test
  public void approvalCannotFollowAChangedApplicationOrDisplay() {
    assertTrue(VirtualScreenWindowPolicy.approved(8, "app.a", 8, "app.a"));
    assertFalse(VirtualScreenWindowPolicy.approved(8, "app.a", 8, "app.b"));
    assertFalse(VirtualScreenWindowPolicy.approved(8, "app.a", 9, "app.a"));
    assertFalse(VirtualScreenWindowPolicy.approved(8, "", 8, "app.b"));
  }
}
