package com.deepseekharness.app.util;

/** Host data navigation stays available for rescue; entering a page never authorizes its jobs. */
public final class WorkspaceEntryPolicy {
  public enum Entry {
    HOST_DATA,
    CONFIG_RESET
  }

  private WorkspaceEntryPolicy() {}

  public static boolean enabled(Entry entry, boolean busy, boolean environmentRequiresRecovery) {
    return switch (entry) {
      case HOST_DATA -> true;
      case CONFIG_RESET -> !busy && !environmentRequiresRecovery;
    };
  }
}
