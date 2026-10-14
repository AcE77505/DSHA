package com.deepseekharness.app.backup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Stable diagnostic identity only; neither a source path nor an arbitrary message suffix is public. */
public final class BackupPathFailure extends IOException {
  private static final Set<String> CODES =
      Set.of(
          "DUPLICATE_PATH",
          "PATH_TYPE_CONFLICT",
          "SOURCE_NOT_DIRECTORY",
          "PARENT_LINK",
          "PARENT_LINK_OR_MISSING");
  private static final Set<String> ROOTS =
      Set.of(
          "dsh",
          "sessions",
          "storages",
          "attachments",
          "profiles",
          "native-settings",
          "native-in-place",
          "legacy-plugins");

  private BackupPathFailure(String message) {
    super(message);
  }

  public static BackupPathFailure at(String code, String root, String relative) throws IOException {
    if (!CODES.contains(code)) throw new IOException("BACKUP_DIAGNOSTIC_CODE");
    String key = BackupLimits.collisionKey(root, relative);
    return new BackupPathFailure(
        code + ":" + safeRoot(root) + ":" + hash("backup-path-v1\0" + key));
  }

  static String safeRoot(String root) {
    return ROOTS.contains(root)
            || root.matches("(?:data|package|project|local|profile-config)-[a-f0-9]{20}")
        ? root
        : "root-" + hash("backup-root-v1\0" + root);
  }

  private static String hash(String value) {
    return BackupArchive.hex(BackupArchive.sha().digest(value.getBytes(StandardCharsets.UTF_8)))
        .substring(0, 20);
  }

  static boolean safeMessage(String message) {
    if (message == null) return false;
    String[] parts = message.split(":", -1);
    return parts.length == 3
        && CODES.contains(parts[0])
        && (ROOTS.contains(parts[1])
            || parts[1].matches("(?:data|package|project|local|profile-config|root)-[a-f0-9]{20}"))
        && parts[2].matches("[a-f0-9]{20}");
  }

  static String source(IOException error, String root, String relative) {
    String code = BackupErrorCode.from(error);
    try {
      return CODES.contains(code) ? at(code, root, relative).getMessage() : code;
    } catch (IOException invalidIdentity) {
      return code;
    }
  }
}
