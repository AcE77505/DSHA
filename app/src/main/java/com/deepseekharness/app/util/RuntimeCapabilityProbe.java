package com.deepseekharness.app.util;

/** 实际 guest glibc/statx/execveat 的无用户业务探针；只有明确能力拒绝可转兼容。 */
public final class RuntimeCapabilityProbe {
  /** Recovery deliberately contains Node and no Python; its qualification stays independent. */
  public enum Kind {
    PYTHON,
    NODE
  }

  public static String command() {
    String source =
        "import sys\n"
            + "print('DSHA_ENGINE_PROBE_STAGE=PYTHON',flush=True)\n"
            + "import ctypes,os\n"
            + "print('DSHA_ENGINE_PROBE_STAGE=IMPORTS',flush=True)\n"
            + "c=ctypes.CDLL(None,use_errno=True); c.syscall.restype=ctypes.c_long\n"
            + "c.gnu_get_libc_version.restype=ctypes.c_char_p\n"
            + "v=tuple(map(int,c.gnu_get_libc_version().decode().split('.')[:2]))\n"
            + "def unsupported(reason): print('DSHA_ENGINE_CAPABILITY_UNSUPPORTED '+reason,flush=True); sys.exit(78)\n"
            + "if v<(2,38): unsupported('glibc')\n"
            + "print('DSHA_ENGINE_PROBE_STAGE=GLIBC',flush=True)\n"
            + "buf=ctypes.create_string_buffer(512)\n"
            + "print('DSHA_ENGINE_PROBE_STAGE=STATX_BEGIN',flush=True)\n"
            + "r=c.syscall(291,-100,b'/bin/true',0,0x7ff,buf)\n"
            + "if r!=0: unsupported('statx errno='+str(ctypes.get_errno()))\n"
            + "print('DSHA_ENGINE_PROBE_STAGE=STATX_READY',flush=True)\n"
            + "print('DSHA_ENGINE_PROBE_STAGE=FORK_BEGIN',flush=True)\n"
            + "pid=os.fork()\n"
            + "if pid==0:\n"
            + " print('DSHA_ENGINE_PROBE_STAGE=EXECVEAT_CHILD',flush=True)\n"
            + " fd=os.open('/bin/true',os.O_RDONLY); a=(ctypes.c_char_p*2)(b'/bin/true',None); e=(ctypes.c_char_p*1)(None)\n"
            + " c.syscall(281,fd,b'',a,e,0x1000); print('DSHA_ENGINE_CAPABILITY_UNSUPPORTED execveat errno='+str(ctypes.get_errno()),flush=True); os._exit(78)\n"
            + "_,status=os.waitpid(pid,0)\n"
            + "if status!=0: unsupported('execveat status='+str(status))\n"
            + "print('DSHA_ENGINE_CAPABILITY_READY',flush=True)\n";
    // Bash's builtin runs before Python starts, so even an empty crash is localizable.
    return "printf 'DSHA_ENGINE_PROBE_STAGE=SHELL\\n'; /usr/bin/python3 -I -S -B -c "
        + com.deepseekharness.app.util.ShellQuote.arg(source);
  }

  public static String command(Kind kind) {
    if (kind == Kind.PYTHON) return command();
    if (kind != Kind.NODE) throw new IllegalArgumentException("RUNTIME_CAPABILITY_KIND");
    return "/usr/local/bin/node --no-addons --no-warnings -e " + ShellQuote.arg(nodeSource());
  }

  /** Exercises the recovery tools actually present, without creating a profile or writing data. */
  static String nodeSource() {
    return "const fs=require('node:fs'),cp=require('node:child_process');\n"
        + "function unsupported(reason){console.log('DSHA_ENGINE_CAPABILITY_UNSUPPORTED '+reason);process.exit(78)}\n"
        + "const version=process.report.getReport().header.glibcVersionRuntime;\n"
        + "const v=typeof version==='string'&&/^(\\d+)\\.(\\d+)/.exec(version);\n"
        + "if(!v||Number(v[1])<2||Number(v[1])===2&&Number(v[2])<38)unsupported('glibc');\n"
        + "if(!fs.statSync('/bin/true').isFile())unsupported('stat true');\n"
        + "const r=cp.spawnSync('/bin/true',[],{env:{},stdio:['ignore','pipe','pipe'],encoding:'utf8'});\n"
        + "if(r.stdout)process.stdout.write(r.stdout);if(r.stderr)process.stderr.write(r.stderr);\n"
        + "if(r.error){console.error('DSHA_ENGINE_NODE_SPAWN '+r.error.code);process.exit(79)}\n"
        + "if(r.signal||r.status!==0){console.error('DSHA_ENGINE_NODE_CHILD '+String(r.signal||r.status));process.exit(79)}\n"
        + "console.log('DSHA_ENGINE_NODE_CAPABILITY_READY');\n";
  }

  public static String marker(Kind kind) {
    if (kind == Kind.NODE) return "DSHA_ENGINE_NODE_CAPABILITY_READY";
    if (kind == Kind.PYTHON) return "DSHA_ENGINE_CAPABILITY_READY";
    throw new IllegalArgumentException("RUNTIME_CAPABILITY_KIND");
  }

  public static java.util.List<String> inputs(Kind kind, boolean packages) {
    if (kind == null || packages && kind != Kind.PYTHON)
      throw new IllegalArgumentException("RUNTIME_CAPABILITY_KIND");
    return java.util.List.of(
        "/bin/bash",
        "/bin/true",
        kind == Kind.NODE ? "/usr/local/bin/node" : "/usr/bin/python3",
        "/lib/ld-linux-aarch64.so.1",
        "/lib/aarch64-linux-gnu/libc.so.6");
  }

  public static String packages() {
    return command() + " && { " + packageTools() + "; }";
  }

  public static String packageTools() {
    return "set -e; found=0; for tool in /usr/sbin/ldconfig.real /sbin/ldconfig.real /usr/sbin/ldconfig /sbin/ldconfig; do "
        + "if [ -f \"$tool\" ]; then found=1; \"$tool\" --help || { printf 'DSHA_ENGINE_CAPABILITY_UNSUPPORTED ldconfig\\n'; exit 78; }; break; fi; done; "
        + "[ \"$found\" = 1 ] || { printf 'DSHA_ENGINE_CAPABILITY_UNSUPPORTED missing-ldconfig\\n'; exit 78; }; "
        + "for tool in /usr/bin/dpkg /usr/bin/dpkg-deb /bin/tar; do \"$tool\" --version || { printf 'DSHA_ENGINE_CAPABILITY_UNSUPPORTED package-tool\\n'; exit 78; }; done; "
        + "printf 'DSHA_ENGINE_PACKAGE_CAPABILITY_READY\\n'";
  }

  private RuntimeCapabilityProbe() {}
}
