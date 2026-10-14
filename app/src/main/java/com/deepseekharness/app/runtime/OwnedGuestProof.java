package com.deepseekharness.app.runtime;

/** 仅受信宿主监督的本轮树回执及 launcher 退出共同取得此证明。 */
public interface OwnedGuestProof {
  boolean confirmedTreeExit();

  /** 仅经本轮专用 control 停止；返回 true 仍须 owned 树与实际 launcher 均已确认退出。 */
  default boolean stopAndConfirm(long timeoutMs) {
    return false;
  }

  default boolean commandWasNeverExecuted() {
    return false;
  }

  default String runtimeMode() {
    return "UNKNOWN";
  }

  static String modeOf(Process process) {
    return process instanceof OwnedGuestProof owned ? owned.runtimeMode() : "UNKNOWN";
  }
}
