package com.deepseekharness.app.core;

/** 主界面与执行入口共用恢复状态；进入界面不会写入环境就绪标记。 */
public final class EnvironmentAccess {
  private EnvironmentAccess() {}

  public static boolean needsRecovery(HarnessController controller) {
    return !controller.isEnvironmentReady()
        || com.deepseekharness.app.core.MaintenanceCoordinator.pending(controller);
  }

  public static boolean runtimeLatest(HarnessController controller) {
    try {
      var installed = controller.proot().installedRuntimeDescriptor();
      return installed != null && installed.latest(controller.proot().expectedRuntimeDescriptor());
    } catch (java.io.IOException error) {
      return false;
    }
  }

  /** 同一 APK 安装只自动尝试一次；覆盖安装修复包后可重新尝试同一受管候选。 */
  public static String candidateAttemptKey(HarnessController controller)
      throws java.io.IOException {
    String candidate = "runtime:" + controller.proot().expectedRuntimeDescriptor().id();
    long updatedAt = 0;
    try {
      android.content.Context context = controller.context();
      updatedAt =
          context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
    } catch (android.content.pm.PackageManager.NameNotFoundException
        | RuntimeException unavailable) {
      // 无法读取本机包记录时沿用旧键，不能在每次打开页面时生成新的重试身份。
    }
    return com.deepseekharness.app.util.EnvironmentIdentity.attemptKey(candidate, updatedAt);
  }

  /** 返回 true 时启动前必须进入维护；新候选只自动尝试一次，健康兼容前代仍可使用。 */
  public static boolean requiresMaintenanceBeforeStart(HarnessController controller) {
    if (runtimeLatest(controller)) return false;
    try {
      String candidate = candidateAttemptKey(controller);
      return com.deepseekharness.app.util.EnvironmentIdentity.shouldBlockRuntimeStart(
          false,
          true,
          com.deepseekharness.app.core.MaintenanceCoordinator.isEnvironmentTaskBusy(),
          com.deepseekharness.app.core.MaintenanceCoordinator.pending(controller),
          candidate,
          controller
              .context()
              .getSharedPreferences(
                  "dsha_environment_upgrade", android.content.Context.MODE_PRIVATE)
              .getString("attempted_identity", ""));
    } catch (java.io.IOException error) {
      // 不能认证 APK 自带的运行时描述时，不得由 START_STICKY/看门狗启动旧 Web。
      return com.deepseekharness.app.util.EnvironmentIdentity.shouldBlockRuntimeStart(
          false, false, false, false, "", "");
    }
  }
}
