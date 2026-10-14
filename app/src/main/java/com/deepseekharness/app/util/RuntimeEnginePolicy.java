package com.deepseekharness.app.util;

/** 两种新执行方式；历史值只用于诊断，不能成为执行权限。 */
public final class RuntimeEnginePolicy {
  public static final int MIGRATION = 172;

  private RuntimeEnginePolicy() {}

  public static String requested(Object value, Object migration) {
    if (!(migration instanceof Number) || ((Number) migration).intValue() < MIGRATION)
      return "bxroot";
    return "proot".equals(value) ? "proot" : "bxroot";
  }

  public static String require(String value) {
    if (!"bxroot".equals(value) && !"proot".equals(value))
      throw new IllegalArgumentException("RUNTIME_ENGINE");
    return value;
  }

  public static boolean bxrootKernel(String release) {
    if (release == null) return false;
    java.util.regex.Matcher match =
        java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)(?:[.-].*)?$").matcher(release);
    if (!match.matches()) return false;
    try {
      int major = Integer.parseInt(match.group(1)), minor = Integer.parseInt(match.group(2));
      return major > 4 || major == 4 && minor >= 11;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  /** 仅明确的引擎诊断；普通退出、超时、插件/配置错误都不构成重试依据。 */
  public static boolean engineFailure(String output) {
    if (output == null) return false;
    return output.contains("[bxroot-launcher] 错误")
        || output.contains("DSHA_BXROOT_ENGINE_FAILURE")
        || output.contains("bxroot: engine failure")
        || output.contains("[bxroot-ulx] fatal")
        || output.contains("bxroot-ulx: ") && output.contains("failed");
  }

  /** Only a read-only probe whose owned tree and launcher have already exited may use this. */
  public static boolean readOnlyProbeEngineFailure(String output) {
    return engineFailure(output)
        || output != null
            && java.util.regex.Pattern.compile(
                    "^bxroot-ulx: 装载 /[^\\r\\n]+ 失败: [^\\r\\n]+$",
                    java.util.regex.Pattern.MULTILINE)
                .matcher(output)
                .find();
  }
}
