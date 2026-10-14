package com.deepseekharness.app.runtime;

import android.content.Context;
import java.io.File;

/** 一次调用冻结的请求/实际引擎与选择原因；不持有用户命令或凭据。 */
public final class EngineSpec {
  public final String requested, effective, reason;
  public final ContainerRuntime runtime;

  private EngineSpec(String requested, ContainerRuntime runtime, String reason) {
    this.requested = requested;
    this.runtime = runtime;
    this.effective = runtime.id();
    this.reason = reason;
  }

  public static EngineSpec select(
      Context context, RuntimeHostPorts.Settings settings, boolean compatibility) {
    String requested = settings.engine;
    String release = android.system.Os.uname().release;
    if (release == null || !release.matches("^[0-9]+\\.[0-9]+.*"))
      throw new IllegalStateException("RUNTIME_KERNEL_EVIDENCE_UNKNOWN");
    boolean oldKernel = !com.deepseekharness.app.util.RuntimeEnginePolicy.bxrootKernel(release);
    if (compatibility || "proot".equals(requested) || oldKernel) {
      File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
      ContainerRuntime runtime =
          new ContainerRuntime.Proot(
              context,
              new File(
                  nativeDir,
                  com.deepseekharness.app.BuildConfig.LOW_ANDROID
                      ? "libproot_legacy.so"
                      : "libproot.so"),
              settings);
      return prepared(
          requested,
          runtime,
          compatibility
              ? "CONFIRMED_ENGINE_RETRY"
              : oldKernel && !"proot".equals(requested) ? "KERNEL_UNSUPPORTED" : "EXPLICIT_PROOT");
    }
    return prepared(requested, new BxrootRuntime(context), "DEFAULT_BXROOT");
  }

  private static EngineSpec prepared(String requested, ContainerRuntime runtime, String reason) {
    try {
      runtime.prepare();
      return new EngineSpec(requested, runtime, reason);
    } catch (Exception failure) {
      // 缺库/损坏不是能力探针；不得借兼容选择遮掩 APK 输入异常。
      throw new IllegalStateException("RUNTIME_ENGINE_UNAVAILABLE: " + runtime.id(), failure);
    }
  }
}
