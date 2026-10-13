package com.deepseekharness.app.util;

/** 同一虚拟显示的根窗口排序；所有读屏与动作入口使用同一规则。 */
public final class VirtualScreenWindowPolicy {
  private VirtualScreenWindowPolicy() {}

  public static int rank(boolean focused, boolean active) {
    return focused ? 2 : active ? 1 : 0;
  }

  public static boolean approved(
      int display, String approvedPackage, int currentDisplay, String currentPackage) {
    return display > 0
        && display == currentDisplay
        && approvedPackage != null
        && !approvedPackage.isEmpty()
        && approvedPackage.equals(currentPackage);
  }
}
