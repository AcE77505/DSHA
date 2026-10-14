package com.deepseekharness.app.core;

import android.content.Context;

/** 独立原生授权存储，不从环境、插件文件或备份自动恢复授权。 */
public final class DeviceGrants {
  private static final Object SCREEN_LOCK = new Object();
  private static final String SCREEN_POLICY = "screen_confirmation_v1";
  private final android.content.SharedPreferences preferences;
  private final Runnable invalidateScreen;

  public DeviceGrants(Context context) {
    Context app = context.getApplicationContext();
    invalidateScreen = () -> com.deepseekharness.app.HttpShellService.screenPolicyChanged(app);
    preferences = context.getSharedPreferences("dsha_device_grants", Context.MODE_PRIVATE);
  }

  DeviceGrants(android.content.SharedPreferences preferences, Runnable invalidateScreen) {
    this.preferences = preferences;
    this.invalidateScreen = invalidateScreen;
  }

  public boolean smsReadAllowed() {
    return preferences.getBoolean("sms_read", false);
  }

  public boolean setSmsReadAllowed(boolean allowed) {
    return preferences.edit().putBoolean("sms_read", allowed).commit();
  }

  public com.deepseekharness.app.util.ScreenConfirmationPolicy screenPolicy() {
    synchronized (SCREEN_LOCK) {
      try {
        String value = preferences.getString(SCREEN_POLICY, "");
        return com.deepseekharness.app.util.ScreenConfirmationPolicy.decode(value);
      } catch (Exception malformed) {
        // Corrupt or future preference data never silently enables control.
        return com.deepseekharness.app.util.ScreenConfirmationPolicy.manual();
      }
    }
  }

  public boolean setScreenPolicy(
      com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode mode,
      java.util.Map<String, com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule> rules) {
    synchronized (SCREEN_LOCK) {
      try {
        var next =
            new com.deepseekharness.app.util.ScreenConfirmationPolicy(
                mode, rules, Math.addExact(screenPolicy().revision, 1));
        String value = next.encode();
        // Fence queued operations and old notifications before publishing any new policy.
        invalidateScreen.run();
        return preferences.edit().putString(SCREEN_POLICY, value).commit();
      } catch (Exception invalid) {
        return false;
      }
    }
  }
}
