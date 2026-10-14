package com.deepseekharness.app.runtime;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 容器运行时抽象：由冻结 EngineSpec 统一选择 bxroot 或 proot。
 *
 * <p>生产实现：
 * <ul>
 *   <li>{@link BxrootRuntime} —— 固定的独立 Android ULX 闭包，默认路径；</li>
 *   <li>{@link Proot} —— Termux proot，手动兼容及明确资格失败后的唯一自动兼容选择。</li>
 * </ul>
 *
 * <p>两个引擎均由宿主 subreaper 证明 owned 树空及 launcher 退出；引擎退出码不能替代该屏障。
 */
public interface ContainerRuntime {

  String id();

  String displayName();

  boolean available();

  String unavailableReason();

  /** 组装进入 rootfs 的命令前缀（不含最终要跑的 /bin/bash …）。 */
  List<String> baseArgv(File rootfsDir, boolean hardlinkSupported);

  /** Null dataDomain is an explicit independent rescue domain, never inferred from rootfs parents. */
  List<String> baseArgv(File rootfsDir, boolean hardlinkSupported, File dataDomain);

  /** 设置进程环境（LD_LIBRARY_PATH、TMPDIR 之类）。 */
  void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir)
      throws java.io.IOException;

  default void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir, File rootfs)
      throws java.io.IOException {
    applyEnv(pb, baseDir, libDir, tmpDir);
  }

  /** 核对签名闭包。失败保留错误；只有隔离资格探针的明确拒绝可选择兼容引擎。 */
  void prepare() throws Exception;

  // ==================================================================

  /** Termux proot，APK 内置。 */
  class Proot implements ContainerRuntime {
    private final File nativeLibProot;
    private final Context context;
    private final RuntimeHostPorts.Settings explicitSettings;

    public Proot(Context ctx, File nativeLibProot) {
      this(ctx, nativeLibProot, null);
    }

    public Proot(Context ctx, File nativeLibProot, RuntimeHostPorts.Settings settings) {
      this.nativeLibProot = nativeLibProot;
      this.context = ctx;
      this.explicitSettings = settings;
    }

    @Override
    public String id() {
      return "proot";
    }

    @Override
    public String displayName() {
      return com.deepseekharness.app.util.UiText.text("proot（内置，稳定）");
    }

    @Override
    public boolean available() {
      return nativeLibProot != null && nativeLibProot.isFile() && nativeLibProot.length() > 0;
    }

    @Override
    public String unavailableReason() {
      return available()
          ? ""
          : com.deepseekharness.app.util.UiText.text("APK 内的 libproot.so 缺失（安装包可能损坏，建议重装）");
    }

    @Override
    public List<String> baseArgv(File rootfsDir, boolean hardlinkSupported) {
      if (context == null) throw new IllegalStateException("DATA_DOMAIN_CONTEXT_MISSING");
      return baseArgv(rootfsDir, hardlinkSupported, context.getFilesDir());
    }

    @Override
    public List<String> baseArgv(File rootfsDir, boolean hardlinkSupported, File dataDomain) {
      List<String> argv = new ArrayList<>();
      argv.add(nativeLibProot.getAbsolutePath());
      // 只有文件系统不支持硬链接时才需要 link2symlink 模拟（会破坏 dsh write 工具）。
      // Android app 私有目录（/data/…，ext4/f2fs）本来就支持硬链接，扩展纯属多余。
      if (!hardlinkSupported) {
        argv.add("--link2symlink");
        // L2S 链保存宿主绝对路径，容器必须能按同一路径访问，否则 dpkg 的 chown/stat 报 ENOENT。
        File l2s = new File(rootfsDir, ".l2s");
        l2s.mkdirs();
        argv.add("-b");
        argv.add(l2s.getAbsolutePath() + ":" + l2s.getAbsolutePath());
      }
      argv.add("-L");
      argv.add("--kill-on-exit");
      argv.add("-0");
      argv.add("--rootfs=" + rootfsDir.getAbsolutePath());
      argv.add("--cwd=/root");
      for (Bind b : binds()) {
        if (!new File(b.host()).exists()) {
          if (context != null)
            RuntimeHostPorts.fromOwner(context.getApplicationContext())
                .record("BIND_SKIPPED", b.host() + ": missing or inaccessible");
          continue;
        }
        argv.add("-b");
        argv.add(b.host().equals(b.guest()) ? b.host() : b.host() + ":" + b.guest());
      }
      if (dataDomain != null) UserDataBindings.append(argv, rootfsDir, dataDomain);
      return argv;
    }

    @Override
    public void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir)
        throws java.io.IOException {
      prepare();
      File directory = nativeLibProot.getParentFile();
      String loader =
          nativeLibProot.getName().equals("libproot_legacy.so")
              ? "libprootloader_legacy.so"
              : "libprootloader.so";
      if (!new File(libDir, "libtalloc.so.2").isFile()
          || !new File(libDir, "libandroid-shmem.so").isFile())
        throw new java.io.IOException("PROOT_DEPENDENCIES_MISSING");
      RuntimeHostPorts.Settings settings =
          explicitSettings == null
              ? RuntimeHostPorts.fromOwner(context.getApplicationContext()).settings()
              : explicitSettings;
      if (settings.disableProotSeccomp) pb.environment().put("PROOT_NO_SECCOMP", "1");
      else pb.environment().remove("PROOT_NO_SECCOMP");
      pb.environment().put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
      pb.environment().put("PROOT_LOADER", new File(directory, loader).getAbsolutePath());
      File loader32 = new File(directory, "libprootloader32.so");
      if (loader32.isFile()) pb.environment().put("PROOT_LOADER_32", loader32.getAbsolutePath());
      else pb.environment().remove("PROOT_LOADER_32");
      pb.environment()
          .put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":" + directory.getAbsolutePath());
    }

    @Override
    public void prepare() throws java.io.IOException {
      if (!available()) throw new java.io.IOException("PROOT_EXECUTABLE_MISSING");
      String loader =
          nativeLibProot.getName().equals("libproot_legacy.so")
              ? "libprootloader_legacy.so"
              : "libprootloader.so";
      if (!new File(nativeLibProot.getParentFile(), loader).isFile())
        throw new java.io.IOException("PROOT_LOADER_MISSING");
    }
  }

  /** 历史诊断名称保留；build172 不含可执行 proroot 实现。 */
  @Deprecated
  class Proroot implements ContainerRuntime {
    public Proroot(Context context, File directory) {}

    public Proroot(Context context, File directory, boolean staticLoader) {}

    public static File defaultDir(Context context) {
      return new File(context.getApplicationInfo().nativeLibraryDir);
    }

    public String id() {
      return "proroot";
    }

    public String displayName() {
      return "proroot (retired)";
    }

    public boolean available() {
      return false;
    }

    public String unavailableReason() {
      return "PROROOT_RETIRED";
    }

    public void prepare() throws java.io.IOException {
      throw new java.io.IOException("PROROOT_RETIRED");
    }

    public List<String> baseArgv(File root, boolean links) {
      throw new IllegalStateException("PROROOT_RETIRED");
    }

    public List<String> baseArgv(File root, boolean links, File domain) {
      throw new IllegalStateException("PROROOT_RETIRED");
    }

    public void applyEnv(ProcessBuilder builder, File base, File lib, File tmp)
        throws java.io.IOException {
      throw new java.io.IOException("PROROOT_RETIRED");
    }
  }

  /**
   * 两个运行时共用的 bind 列表，写在一处（避免「改了 proot 忘了改 proroot」）。
   * Bundled Termux Python 要用 Android 的 linker 和 APEX 库，所以映射 /system、/apex。
   *
   * <p>注意：<b>不</b>映射 /linkerconfig —— app 进程对它无权限（selinux），
   * proot 每次启动都会打 {@code can't sanitize binding "/linkerconfig": Permission denied}，
   * 污染终端/日志；而且 bind 失败 = 从没绑上，映射它是纯负收益。
   */
  record Bind(String host, String guest) {}

  static java.util.List<Bind> binds() {
    return java.util.List.of(
        new Bind("/dev", "/dev"),
        new Bind("/dev/urandom", "/dev/random"),
        new Bind("/proc", "/proc"),
        new Bind("/sys", "/sys"),
        new Bind("/system", "/system"),
        new Bind("/apex", "/apex"),
        new Bind("/proc/self/fd", "/dev/fd"),
        new Bind("/storage/emulated/0", "/sdcard"),
        new Bind("/storage/emulated/0", "/storage/emulated/0"));
  }
}
