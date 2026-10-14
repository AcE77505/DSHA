package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class LegacyResetHoldRetirementTest {
  private static final class Store {
    final Map<String, Object> values = new LinkedHashMap<>();
    final List<String> removed = new ArrayList<>();
    boolean commit = true;
    int commits;
    final SharedPreferences prefs;

    Store() {
      SharedPreferences.Editor editor =
          (SharedPreferences.Editor)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {SharedPreferences.Editor.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("remove")) {
                      removed.add((String) args[0]);
                      return proxy;
                    }
                    if (method.getName().equals("commit")) {
                      commits++;
                      if (commit) for (String key : removed) values.remove(key);
                      return commit;
                    }
                    throw new AssertionError(
                        "Retirement must not clear or rewrite " + method.getName());
                  });
      prefs =
          (SharedPreferences)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {SharedPreferences.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("contains")) return values.containsKey(args[0]);
                    if (method.getName().equals("edit")) return editor;
                    if (method.getName().equals("getBoolean"))
                      return values.getOrDefault(args[0], args[1]);
                    throw new AssertionError("Unexpected preference read: " + method.getName());
                  });
    }
  }

  @Test
  public void legacyHoldRetiresIdempotentlyWithoutClearingTasksBackupsKeysOrOtherSettings() {
    Store store = new Store();
    store.values.put("factoryResetPending", true);
    store.values.put("enabled", true);
    store.values.put("lastId", "manual-backup");
    store.values.put("task", "DSHA_FACTORY_RESET_V1:INTERRUPTED");
    store.values.put("automatic-backup-key", "original cipher");
    store.values.put("legacy-restore", "real pending journal");
    Map<String, Object> expected = new LinkedHashMap<>(store.values);
    expected.remove("factoryResetPending");

    assertTrue(AutomaticBackups.enabled(store.prefs));
    assertTrue(AutomaticBackups.retireLegacyResetHold(store.prefs));
    assertEquals(expected, store.values);
    assertEquals(List.of("factoryResetPending"), store.removed);
    assertEquals(1, store.commits);
  }

  @Test
  public void failedHoldRemovalDoesNotPermanentlyDisableTheRetiredBackupPolicy() {
    Store store = new Store();
    store.values.put("factoryResetPending", true);
    store.commit = false;
    assertTrue(AutomaticBackups.enabled(store.prefs));
    assertEquals(true, store.values.get("factoryResetPending"));
    store.commit = true;
    assertTrue(AutomaticBackups.enabled(store.prefs));
    assertFalse(store.values.containsKey("factoryResetPending"));
  }

  @Test
  public void retirementDoesNotOverrideTheUsersDisabledAutomaticBackupPreference() {
    Store store = new Store();
    store.values.put("factoryResetPending", true);
    store.values.put("enabled", false);
    assertFalse(AutomaticBackups.enabled(store.prefs));
    assertEquals(false, store.values.get("enabled"));
    assertFalse(store.values.containsKey("factoryResetPending"));
  }
}
