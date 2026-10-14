package com.deepseekharness.app.runtime;

/** 同一锁定的只读资格契约；主执行、安装和独立应急共享。 */
final class RuntimeEngineProbe {
  static String command() {
    return com.deepseekharness.app.util.RuntimeCapabilityProbe.command();
  }

  static String packages() {
    return com.deepseekharness.app.util.RuntimeCapabilityProbe.packages();
  }

  private RuntimeEngineProbe() {}
}
