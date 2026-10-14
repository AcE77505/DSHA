package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Probe private disposable paths before choosing one runtime for the package write. */
public final class ColdInstallPlan {
  public static final String PROBE_READY = "DSHA_COLD_RUNTIME_READY";
  public static final String INSTALL_READY = "DSHA_UBUNTU_TOOLS_READY";

  private ColdInstallPlan() {}

  public enum Mode {
    BXROOT("bxroot", false),
    PROROOT("proroot", false),
    PROROOT_DYNAMIC("proroot", false),
    PROOT("proot", false),
    PROOT_COMPAT("proot", true);
    public final String runtime;
    public final boolean noSeccomp;

    Mode(String runtime, boolean noSeccomp) {
      this.runtime = runtime;
      this.noSeccomp = noSeccomp;
    }

    public boolean staticLoader(boolean configured) {
      return this == PROROOT_DYNAMIC ? false : configured;
    }
  }

  public static final class Observation {
    public final int exitCode;
    public final boolean timedOut, exited;
    public final String output, diagnostic;

    public Observation(
        int exitCode, boolean timedOut, boolean exited, String output, String diagnostic) {
      this.exitCode = exitCode;
      this.timedOut = timedOut;
      this.exited = exited;
      this.output = output;
      this.diagnostic = diagnostic;
    }

    public boolean ready(String marker) {
      return exited
          && !timedOut
          && exitCode == 0
          && output != null
          && ("\n" + output + "\n").contains("\n" + marker + "\n");
    }
  }

  public interface Runner {
    Observation run(Mode mode, boolean probe) throws IOException, InterruptedException;
  }

  public interface Recorder {
    void record(Mode mode, boolean probe, Observation observation);
  }

  public interface CheckedAction {
    void run() throws IOException;
  }

  /** An unavailable durable selection must never publish a new environment-ready marker. */
  public static void publishReady(CheckedAction selectRuntime, CheckedAction publishMarker)
      throws IOException {
    selectRuntime.run();
    publishMarker.run();
  }

  public static List<Mode> modes(
      boolean preferProroot, boolean prorootAvailable, boolean isolated, boolean noSeccomp) {
    List<Mode> modes = new ArrayList<>();
    if (preferProroot && prorootAvailable && isolated) modes.add(Mode.BXROOT);
    modes.add(noSeccomp ? Mode.PROOT_COMPAT : Mode.PROOT);
    return List.copyOf(modes);
  }

  /** At most two probes and one package installation; no retry after package writes. */
  public static Mode run(List<Mode> modes, Runner runner, Recorder recorder)
      throws IOException, InterruptedException {
    if (modes.isEmpty()
        || modes.size() > 2
        || modes.stream().anyMatch(mode -> !mode.runtime.matches("bxroot|proot"))
        || modes.stream().distinct().count() != modes.size())
      throw new IllegalArgumentException("COLD_RUNTIME_PLAN");
    for (Mode mode : modes) {
      Observation probe = runner.run(mode, true);
      recorder.record(mode, true, probe);
      if (!probe.exited) throw new IOException("COLD_INSTALL_PROCESS_EXIT_UNCONFIRMED");
      if (probe.timedOut) throw new IOException("COLD_RUNTIME_PROBE_TIMED_OUT_NO_REPLAY");
      if (!probe.ready(PROBE_READY)) {
        if (mode == Mode.BXROOT && capabilityFailure(probe)) continue;
        throw new IOException("COLD_RUNTIME_PROBE_FAILED_NO_REPLAY:" + probe.diagnostic);
      }
      Observation installed = runner.run(mode, false);
      recorder.record(mode, false, installed);
      if (!installed.exited) throw new IOException("COLD_INSTALL_PROCESS_EXIT_UNCONFIRMED");
      if (!installed.ready(INSTALL_READY))
        throw new IOException(
            "COLD_INSTALL_POSTCHECK_FAILED: " + mode + "\n" + installed.diagnostic);
      return mode;
    }
    throw new IOException("COLD_RUNTIME_PROBE_FAILED");
  }

  /** 已在制备时完成真实 dpkg；本机只试运行一次核对，不再安装第二遍。 */
  public static Mode runConfigured(List<Mode> modes, Runner runner, Recorder recorder)
      throws IOException, InterruptedException {
    return runConfigured(modes, false, runner, recorder);
  }

  /** 仅未显式选方式的首次签名覆盖层可尝试修复静态 loader 的已确认特定退出。 */
  public static boolean allowDynamicLoaderRetry(
      boolean freshCandidate, boolean automatic, boolean forceProot, boolean staticLoader) {
    return false;
  }

  public static Mode runConfigured(
      List<Mode> modes, boolean allowDynamicLoaderRetry, Runner runner, Recorder recorder)
      throws IOException, InterruptedException {
    if (modes.isEmpty()
        || modes.size() > 2
        || modes.stream().anyMatch(mode -> !mode.runtime.matches("bxroot|proot"))
        || modes.stream().distinct().count() != modes.size())
      throw new IllegalArgumentException("COLD_RUNTIME_PLAN");
    for (Mode mode : modes) {
      Observation result = runner.run(mode, true);
      recorder.record(mode, true, result);
      if (!result.exited) throw new IOException("COLD_INSTALL_PROCESS_EXIT_UNCONFIRMED");
      if (result.ready(PROBE_READY) && result.ready(INSTALL_READY)) return mode;
      if (result.timedOut) throw new IOException("COLD_CONFIGURED_TIMED_OUT_NO_REPLAY");
      if (!(mode == Mode.BXROOT && capabilityFailure(result)))
        throw new IOException("COLD_CONFIGURED_CHECK_FAILED_NO_REPLAY:" + result.diagnostic);
    }
    throw new IOException("COLD_CONFIGURED_CHECK_FAILED");
  }

  private static boolean capabilityFailure(Observation result) {
    return result.exited
        && !result.timedOut
        && (RuntimeEnginePolicy.engineFailure(result.output)
            || result.output != null
                && result.output.contains("DSHA_ENGINE_CAPABILITY_UNSUPPORTED"));
  }

  private static boolean staticSignalStackFailure(Observation result) {
    String output = result.output;
    return result.exited
        && !result.timedOut
        && result.exitCode == 139
        && output != null
        && output.contains("Fatal glibc error")
        && output.contains("sysconf_sigstksz")
        && (output.contains("minsigstksz") || output.contains("minsigstacksize"))
        && output.contains("assertion failed")
        && (output.contains("!= 0") || output.contains("!=0"));
  }

  public static void applyEnvironment(Map<String, String> environment, Mode mode) {
    if (mode.noSeccomp) environment.put("PROOT_NO_SECCOMP", "1");
    else environment.remove("PROOT_NO_SECCOMP");
  }

  public static String probeScript() {
    return "set -eu\nprintf '\\nDSHA_PROBE_STAGE=SHELL\\n'\nprobe=\"$1\"\n"
        + "[ ! -e \"$probe\" ] && [ ! -L \"$probe\" ] || exit 64\n"
        + "/bin/mkdir -- \"$probe\"\n"
        + "printf 'DSHA_PROBE_STAGE=MKDIR\\n'\n"
        + "trap '/bin/rm -f -- \"$probe/plain\"; /bin/rmdir -- \"$probe\"' EXIT\n"
        + "/bin/bash -c 'printf DSHA_COLD_PROBE' > \"$probe/plain\"\n"
        + "printf 'DSHA_PROBE_STAGE=BASH\\n'\n"
        + "/usr/bin/python3 -I -S -c "
        + ShellQuote.arg(
            "import sys; print('DSHA_PROBE_STAGE=PYTHON', flush=True); "
                + "import hashlib, os, sqlite3, ssl; print('DSHA_PROBE_STAGE=IMPORTS', flush=True); "
                + "p=sys.argv[1]; assert open(p,'rb').read()==b'DSHA_COLD_PROBE'; "
                + "f=open(p,'wb'); f.write(b'DSHA_COLD_VERIFIED'); f.flush(); os.fsync(f.fileno()); f.close(); "
                + "print('DSHA_PROBE_STAGE=FSYNC', flush=True); "
                + "assert open(p,'rb').read()==b'DSHA_COLD_VERIFIED'; assert hashlib.sha256(b'probe').digest(); "
                + "assert sqlite3.sqlite_version; assert ssl.OPENSSL_VERSION")
        + " \"$probe/plain\"\n"
        + RuntimeCapabilityProbe.packageTools()
        + "\n"
        + "printf '\\n"
        + PROBE_READY
        + "\\n'\n";
  }

  public static String probeCommand(String guest) {
    if (guest == null || !guest.matches("/root/\\.dsha-cold-probe-[a-f0-9-]{36}"))
      throw new IllegalArgumentException("COLD_PROBE_PATH");
    return "/bin/bash -c "
        + ShellQuote.arg(probeScript())
        + " dsha-cold-probe "
        + ShellQuote.arg(guest);
  }

  public static String installCommand(String guest) {
    if (guest == null || !guest.matches("/root/\\.dsha-bundled-tools-[a-f0-9-]{36}"))
      throw new IllegalArgumentException("COLD_PACKAGE_PATH");
    return "/bin/bash /root/dsh-bin/install-ubuntu-tools " + ShellQuote.arg(guest);
  }
}
