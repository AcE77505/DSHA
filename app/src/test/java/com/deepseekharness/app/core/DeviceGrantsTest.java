package com.deepseekharness.app.core;

import android.content.SharedPreferences;
import com.deepseekharness.app.util.ScreenConfirmationPolicy;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class DeviceGrantsTest {
  private static SharedPreferences preferences(Map<String, Object> values) {
    return (SharedPreferences)
        Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getString":
                case "getBoolean":
                  return values.getOrDefault(args[0], args[1]);
                case "edit":
                  Map<String, Object> pending = new HashMap<>();
                  return Proxy.newProxyInstance(
                      SharedPreferences.Editor.class.getClassLoader(),
                      new Class<?>[] {SharedPreferences.Editor.class},
                      (editor, call, input) -> {
                        if (call.getName().startsWith("put")) {
                          pending.put((String) input[0], input[1]);
                          return editor;
                        }
                        if (call.getName().equals("commit")) {
                          values.putAll(pending);
                          return true;
                        }
                        throw new UnsupportedOperationException(call.getName());
                      });
                default:
                  throw new UnsupportedOperationException(method.getName());
              }
            });
  }

  @Test
  public void modeAndExactRulesPersistAcrossOwnersAndInvalidateBeforePublication() {
    var values = new HashMap<String, Object>();
    var prefs = preferences(values);
    AtomicInteger invalidations = new AtomicInteger();
    var grants =
        new DeviceGrants(
            prefs,
            () -> {
              assertEquals(
                  invalidations.get(), new DeviceGrants(prefs, () -> {}).screenPolicy().revision);
              invalidations.incrementAndGet();
            });
    assertEquals(ScreenConfirmationPolicy.Mode.MANUAL, grants.screenPolicy().mode);
    assertTrue(
        grants.setScreenPolicy(
            ScreenConfirmationPolicy.Mode.CUSTOM,
            Map.of("com.bank.pay", ScreenConfirmationPolicy.Rule.ALLOW)));
    assertTrue(new DeviceGrants(prefs, () -> {}).screenPolicy().allows("com.bank.pay"));
    assertTrue(
        grants.setScreenPolicy(
            ScreenConfirmationPolicy.Mode.ALLOW_ALL, grants.screenPolicy().rules));
    assertEquals(2, invalidations.get());
    assertTrue(grants.setScreenPolicy(ScreenConfirmationPolicy.Mode.MANUAL, Map.of()));
    assertFalse(grants.screenPolicy().allows("com.bank.pay"));
    assertEquals(3, grants.screenPolicy().revision);
  }
}
