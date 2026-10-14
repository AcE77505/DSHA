package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Map;

public final class ScreenConfirmationPolicyTest {
  @Test
  public void allModesCoverOrdinarySensitiveAndUnknownPackages() {
    for (String pkg : new String[] {"app.normal", "com.bank.pay", ""}) {
      assertFalse(ScreenConfirmationPolicy.manual().allows(pkg));
      assertTrue(
          new ScreenConfirmationPolicy(ScreenConfirmationPolicy.Mode.ALLOW_ALL, Map.of(), 1)
              .allows(pkg));
    }
    var custom =
        new ScreenConfirmationPolicy(
            ScreenConfirmationPolicy.Mode.CUSTOM,
            Map.of(
                "com.bank.pay",
                ScreenConfirmationPolicy.Rule.ALLOW,
                "app.normal",
                ScreenConfirmationPolicy.Rule.ASK),
            2);
    assertTrue(custom.allows("com.bank.pay"));
    for (String pkg :
        new String[] {"app.normal", "com.bank.pay.clone", "com.Bank.pay", "unknown.pkg", ""})
      assertFalse(custom.allows(pkg));
  }

  @Test
  public void rulesPersistButDoNotCreateRunAuthority() {
    var original =
        new ScreenConfirmationPolicy(
            ScreenConfirmationPolicy.Mode.CUSTOM,
            Map.of("com.bank.pay", ScreenConfirmationPolicy.Rule.ALLOW),
            17);
    var restored = ScreenConfirmationPolicy.decode(original.encode());
    assertEquals(17, restored.revision);
    assertTrue(restored.allows("com.bank.pay"));
    assertFalse(new ScreenSessionGrant().allowed(9));
  }

  @Test
  public void futureCorruptDuplicateAndInvalidRulesFailClosed() {
    for (String value :
        new String[] {
          null,
          "",
          "2\nALLOW_ALL\n1\n",
          "1\nALLOW_ALL\n-1\n",
          "1\nCUSTOM\n1\napp.a=ALLOW\napp.a=ASK\n",
          "1\nCUSTOM\n1\n*=ALLOW\n",
          "1\nALLOW_ALL\n1",
          "1\nBOGUS\n1\n"
        }) {
      var policy = ScreenConfirmationPolicy.decode(value);
      assertEquals(ScreenConfirmationPolicy.Mode.MANUAL, policy.mode);
      assertFalse(policy.allows("app.a"));
    }
  }
}
