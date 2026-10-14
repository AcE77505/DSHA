package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.Compat;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** One argv/env/process construction path; callers retain lifecycle and exit-proof ownership. */
final class RuntimeLauncher {
  private final Context context;
  private final File rootfs, base, lib, tmp;
  private final RuntimeHostPorts ports;

  RuntimeLauncher(
      Context context, File rootfs, File base, File lib, File tmp, RuntimeHostPorts ports) {
    this.context = context;
    this.rootfs = rootfs;
    this.base = base;
    this.lib = lib;
    this.tmp = tmp;
    this.ports = java.util.Objects.requireNonNull(ports);
  }

  ProcessBuilder builder(String command, LaunchSpec spec) throws IOException {
    try (RuntimeHostPorts.Scope ignored = ports.open()) {
      try {
        RuntimeTools.prepareResolver(context, rootfs, ports.settings());
      } catch (IOException error) {
        ports.record("DNS_PREPARE", error.getClass().getSimpleName());
      }
      List<String> argv = spec.runtime.baseArgv(rootfs, spec.hardlinks, spec.dataDomain);
      bindPreloads(argv);
      for (var bind : spec.binds) {
        argv.add("-b");
        argv.add(bind.host() + ":" + bind.guest());
      }
      argv.add("/bin/bash");
      argv.add("-c");
      argv.add(command);
      ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
      if (!spec.isolated && !spec.pipedInput) Compat.redirectStdinDevNull(builder);
      environment(builder, spec);
      if (spec.coldTrace)
        ports.record(
            "EXEC",
            "runtime="
                + spec.runtime.id()
                + " isolated="
                + spec.isolated
                + " prootNoSeccomp="
                + "1".equals(builder.environment().get("PROOT_NO_SECCOMP"))
                + " staticLoader="
                + builder.environment().containsKey("PROROOT_STUB_LOADER")
                + " argv="
                + argv.subList(0, argv.size() - 3));
      return builder;
    }
  }

  void environment(ProcessBuilder builder, LaunchSpec spec) throws IOException {
    spec.runtime.applyEnv(builder, base, lib, tmp, rootfs);
    if ("proot".equals(spec.runtime.id()) && !spec.hardlinks) {
      File l2s = new File(rootfs, ".l2s");
      if (!l2s.isDirectory() && !l2s.mkdirs())
        throw new IOException("PROOT_L2S_DIRECTORY_UNAVAILABLE");
      builder.environment().put("PROOT_L2S_DIR", l2s.getAbsolutePath());
    }
    builder.environment().put("HOME", "/root");
    builder
        .environment()
        .put("PATH", "/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
    builder.environment().put("TMPDIR", "/tmp");
    builder.environment().put("DEBIAN_FRONTEND", "noninteractive");
    RuntimeTools.applyEnvironment(rootfs, builder.environment(), ports.settings());
    if (context != null) {
      RuntimeLauncherAssets.prepare(context);
      String bridge = "--require=" + RuntimeLauncherAssets.GUEST + "/bridge-token-compat.cjs";
      String previous = builder.environment().getOrDefault("NODE_OPTIONS", "");
      if (!java.util.Arrays.asList(previous.split("\\s+")).contains(bridge))
        builder
            .environment()
            .put("NODE_OPTIONS", bridge + (previous.isEmpty() ? "" : " " + previous));
    }
    if (spec.coldMode != null)
      com.deepseekharness.app.util.ColdInstallPlan.applyEnvironment(
          builder.environment(), spec.coldMode);
    builder.environment().putAll(spec.environment);
    if ("bxroot".equals(spec.runtime.id())) {
      spec.runtime.applyEnv(builder, base, lib, tmp, rootfs);
      builder
          .environment()
          .keySet()
          .removeIf(key -> key.startsWith("PROROOT_") || key.startsWith("PROOT_"));
      builder.environment().put("DSHA_BXROOT_STRICT_EXEC", "1");
      builder.environment().put("BXROOT_AUTO_HOST", "1");
      builder.environment().put("BXROOT_HOST_PATH", "");
      builder.environment().put("BXROOT_REENTRY", "0");
      builder.environment().remove("BXROOT_NO_ULX");
      builder.environment().remove("BXROOT_STUB_LOADER_EXEC");
    } else if ("proot".equals(spec.runtime.id())) {
      builder
          .environment()
          .keySet()
          .removeIf(key -> key.startsWith("PROROOT_") || key.startsWith("BXROOT_"));
      builder.environment().remove("DSHA_BXROOT_STRICT_EXEC");
      builder.environment().remove("LD_PRELOAD");
      spec.runtime.applyEnv(builder, base, lib, tmp, rootfs);
      if (!spec.hardlinks)
        builder.environment().put("PROOT_L2S_DIR", new File(rootfs, ".l2s").getAbsolutePath());
      if (spec.coldMode != null)
        com.deepseekharness.app.util.ColdInstallPlan.applyEnvironment(
            builder.environment(), spec.coldMode);
    }
    builder.environment().put("DSHA_RUNTIME_ENGINE", spec.runtime.id());
    // 固定清理契约不接受调用方环境覆盖。
    builder
        .environment()
        .put("DSHA_CACHE_DIR", com.deepseekharness.app.util.ScratchPaths.GUEST_DIRECTORY);
  }

  Process start(String command, LaunchSpec spec) throws IOException {
    ProcessBuilder builder;
    try {
      spec = capable(spec);
      builder = builder(command, spec);
    } catch (IOException | RuntimeException | Error failure) {
      // 尚未调用 spawn；仅此准备阶段可以发布本轮“未派发”证明。
      if (spec.record != null)
        try {
          spec.record.completedBeforeDispatch();
        } catch (IOException closing) {
          spec.record.uncertain();
          failure.addSuppressed(closing);
        }
      throw failure;
    }
    // START may reach the guest before the Java start call returns or throws.
    ports.businessDispatched();
    return IsolatedInstallProcess.start(builder, tmp, context, spec.record);
  }

  ContainerRuntime preflight(ContainerRuntime runtime, boolean packages) throws IOException {
    return capable(LaunchSpec.runtime(runtime).dataDomain(null).hardlinks(false).build(), packages)
        .runtime;
  }

  private LaunchSpec capable(LaunchSpec spec) throws IOException {
    return capable(spec, false);
  }

  private LaunchSpec capable(LaunchSpec spec, boolean packages) throws IOException {
    if (!"bxroot".equals(spec.runtime.id())) return spec;
    String fingerprint = RuntimeEngineQualification.fingerprint(context, rootfs, packages);
    String key = fingerprint + (packages ? ":bxroot:packages" : ":bxroot");
    if (ports.rootProbed(key)) return spec;
    // 只读探针独立数据域，不加载 profile、用户命令或个人数据绑定。
    if (RuntimeEngineQualification.check(
        context,
        rootfs,
        tmp,
        packages,
        fingerprint,
        (command, scratch) ->
            RuntimeEngineQualification.probeBuilder(
                context, spec.runtime, rootfs, scratch, command))) {
      ports.rootProbedAdd(key);
      return spec;
    }
    return copyWithRuntime(spec, ports.capabilityFallback(context));
  }

  private static LaunchSpec copyWithRuntime(LaunchSpec value, ContainerRuntime runtime) {
    var copy =
        LaunchSpec.runtime(runtime)
            .dataDomain(value.dataDomain)
            .hardlinks(value.hardlinks)
            .isolated(value.isolated)
            .record(value.record)
            .pipedInput(value.pipedInput)
            .environment(value.environment)
            .coldTrace(value.coldTrace)
            .coldMode(value.coldMode);
    for (var bind : value.binds) copy.bind(bind.host(), bind.guest());
    return copy.build();
  }

  void bindPreloads(List<String> argv) throws IOException {
    if (context == null) return;
    File host = RuntimeLauncherAssets.prepare(context);
    argv.add("-b");
    argv.add(host.getAbsolutePath() + ":" + RuntimeLauncherAssets.GUEST);
  }
}
