package com.deepseekharness.app.util;

import java.util.List;

/** 固定的缓存路径名单；文件系统核验由 BackupFileSystem 完成，兼容 API 23。 */
public final class ScratchPaths {
  private ScratchPaths() {}

  public static final String GUEST_DIRECTORY = "/root/.cache/dsha";
  public static final String ROOTFS_DIRECTORY = "root/.cache/dsha";
  public static final List<String> DIRECTORY_WHITELIST = List.of(ROOTFS_DIRECTORY);
  public static final String GUEST_TMP = "tmp";
  public static final String OWNED_TMP_PREFIX = "dsha-tool-script.";
  public static final List<String> OWNED_FILE_WHITELIST =
      List.of(GUEST_TMP + "/" + OWNED_TMP_PREFIX);

  public static boolean declaredDirectory(String relative) {
    return DIRECTORY_WHITELIST.contains(relative);
  }

  public static boolean declaredOwnedDirectory(String directory) {
    return GUEST_TMP.equals(directory);
  }

  public static boolean ownedTemporary(String name) {
    return name != null
        && name.startsWith(OWNED_TMP_PREFIX)
        && name.length() > OWNED_TMP_PREFIX.length()
        && name.indexOf('/') < 0
        && name.indexOf('\\') < 0
        && name.indexOf('\0') < 0
        && name.indexOf('\r') < 0
        && name.indexOf('\n') < 0;
  }

  public static boolean declaredOwnedFile(String relative) {
    return relative != null
        && relative.startsWith(GUEST_TMP + "/")
        && ownedTemporary(relative.substring(GUEST_TMP.length() + 1));
  }
}
