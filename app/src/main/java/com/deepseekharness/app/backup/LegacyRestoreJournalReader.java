package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.GuestPaths;
import java.io.File;
import java.io.IOException;
import java.util.*;

/** 旧 Python 日志只读兼容层；日志中的路径从不直接成为宿主写入权限。 */
public final class LegacyRestoreJournalReader {
  public static final String JOURNAL =
      GuestPaths.ROOT_RELATIVE + "/root/.dsha-restore-journal.json";
  private static final String TOKEN = "[0-9]{8}-[0-9]{6}-[a-f0-9]{8}";

  public enum Kind {
    PRIVATE,
    EXTERNAL,
    UNKNOWN
  }

  public static final class Row {
    public final String dst, old, next;
    public final boolean existed;
    public final Kind kind;

    Row(String dst, String old, String next, boolean existed, Kind kind) {
      this.dst = dst;
      this.old = old;
      this.next = next;
      this.existed = existed;
      this.kind = kind;
    }
  }

  public final File journal;
  public final String sha256;
  public final boolean complete;
  public final List<Row> rows;

  private LegacyRestoreJournalReader(File journal, String sha, boolean complete, List<Row> rows) {
    this.journal = journal;
    this.sha256 = sha;
    this.complete = complete;
    this.rows = Collections.unmodifiableList(rows);
  }

  public static File locate(BackupFileSystem fs, File files) throws IOException {
    File at = files.getAbsoluteFile();
    if (!fs.stat(at).type.equals("DIRECTORY")) throw new IOException("LEGACY_RESTORE_FILES_TYPE");
    String[] parts = JOURNAL.split("/");
    for (int i = 0; i < parts.length; i++) {
      at = new File(at, parts[i]);
      String type = fs.stat(at).type;
      if (type.equals("MISSING")) return null;
      if (i + 1 < parts.length && !type.equals("DIRECTORY"))
        throw new IOException("LEGACY_RESTORE_JOURNAL_PARENT");
    }
    if (!fs.stat(at).type.equals("FILE")) throw new IOException("LEGACY_RESTORE_JOURNAL_TYPE");
    return fs.child(files, JOURNAL);
  }

  public static LegacyRestoreJournalReader read(BackupFileSystem fs, File files)
      throws IOException {
    return read(fs, files, "");
  }

  public static LegacyRestoreJournalReader read(
      BackupFileSystem fs, File files, String previousWorkspace) throws IOException {
    File journal = locate(fs, files);
    if (journal == null) return null;
    byte[] bytes = fs.small(journal, BackupLimits.MANIFEST);
    return parse(fs, files, journal, bytes, previousWorkspace, true);
  }

  static LegacyRestoreJournalReader parse(
      BackupFileSystem fs,
      File files,
      File journal,
      byte[] bytes,
      String previousWorkspace,
      boolean checkLocations)
      throws IOException {
    Set<String> workspaceFiles = new HashSet<>();
    if (previousWorkspace != null && !previousWorkspace.isEmpty()) {
      try {
        String workspace = NativeConfigurationReset.normalizeWorkspace(previousWorkspace);
        workspaceFiles.add("/" + workspace + "/.env");
        workspaceFiles.add("/" + workspace + "/dsh-web.log");
      } catch (IOException outside) {
        /* 外置/未知工作目录不授予任何路径写权限。 */
      }
    }
    Map<String, Object> value = BackupJson.read(bytes, BackupLimits.MANIFEST);
    if (!(value.get("complete") instanceof Boolean) || !(value.get("steps") instanceof List))
      throw new IOException("LEGACY_RESTORE_JOURNAL_FORMAT");
    List<?> steps = (List<?>) value.get("steps");
    if (steps.isEmpty() || steps.size() > 2048)
      throw new IOException("LEGACY_RESTORE_JOURNAL_LIMIT");
    List<Row> rows = new ArrayList<>();
    Set<String> targets = new HashSet<>();
    String transactionToken = null;
    for (Object step : steps) {
      if (!(step instanceof Map)) throw new IOException("LEGACY_RESTORE_JOURNAL_FORMAT");
      Map<?, ?> row = (Map<?, ?>) step;
      if (!(row.get("existed") instanceof Boolean))
        throw new IOException("LEGACY_RESTORE_JOURNAL_FORMAT");
      String dst = path(row.get("dst")), old = path(row.get("old")), next = path(row.get("new"));
      String prefix = dst + ".pre-restore-";
      if (!old.startsWith(prefix)) throw new IOException("LEGACY_RESTORE_SLOT_NAME");
      String token = old.substring(prefix.length());
      if (!token.matches(TOKEN) || !next.equals(dst + ".restore-stage-" + token))
        throw new IOException("LEGACY_RESTORE_SLOT_NAME");
      if (transactionToken != null && !transactionToken.equals(token))
        throw new IOException("LEGACY_RESTORE_SLOT_TOKEN");
      transactionToken = token;
      String normalized = guest(files, dst);
      for (String seen : targets)
        if (normalized.equals(seen)
            || normalized.startsWith(seen + "/")
            || seen.startsWith(normalized + "/"))
          throw new IOException("LEGACY_RESTORE_OVERLAPPING_TARGETS");
      targets.add(normalized);
      Kind kind =
          external(normalized)
              ? Kind.EXTERNAL
              : legal(normalized, workspaceFiles) ? Kind.PRIVATE : Kind.UNKNOWN;
      rows.add(new Row(dst, old, next, (Boolean) row.get("existed"), kind));
    }
    // 全部行通过结构检查后才核对私有父链；外部/未知路径连 stat 都不做。
    List<Row> checked = new ArrayList<>();
    for (Row row : rows) {
      Kind kind = row.kind;
      if (kind == Kind.PRIVATE && checkLocations && !Boolean.TRUE.equals(value.get("complete"))) {
        for (String path : List.of(row.dst, row.old, row.next)) {
          try {
            File target = privatePath(fs, files, path);
            var state = fs.stat(target);
            String type = state.type;
            long device = fs.stat(files).device;
            if (fs.stat(target.getParentFile()).device != device
                || (!type.equals("MISSING") && state.device != device)) kind = Kind.UNKNOWN;
            if (type.equals("LINK")) {
              // 私有路径的链接/挂载缺少旧事务前身份，不能凭目标文本解除真实未完成屏障。
              kind = Kind.UNKNOWN;
            } else if (!Set.of("FILE", "DIRECTORY", "MISSING").contains(type)) kind = Kind.UNKNOWN;
          } catch (IOException unsafe) {
            kind = Kind.UNKNOWN;
          }
        }
      }
      checked.add(new Row(row.dst, row.old, row.next, row.existed, kind));
    }
    return new LegacyRestoreJournalReader(
        journal,
        BackupArchive.hex(BackupArchive.sha().digest(bytes)),
        (Boolean) value.get("complete"),
        checked);
  }

  private static String path(Object value) throws IOException {
    if (!(value instanceof String)) throw new IOException("LEGACY_RESTORE_PATH");
    String path = (String) value;
    if (!path.startsWith("/")
        || path.length() > 2048
        || path.indexOf('\\') >= 0
        || path.indexOf('\0') >= 0) throw new IOException("LEGACY_RESTORE_PATH");
    BackupLimits.path(path.substring(1));
    return path;
  }

  static String guest(File files, String path) {
    String root = GuestPaths.rootfs(files).getAbsolutePath().replace(File.separatorChar, '/');
    String mirror = GuestDataResolver.hostMirrorRelative(path, root);
    return mirror == null ? path : mirror;
  }

  private static boolean external(String path) {
    return path.matches("^/(?:sdcard|storage|mnt|proc|sys|dev|system|apex)(?:/.*)?$");
  }

  private static boolean legal(String path, Set<String> workspaceFiles) {
    if (path.equals("/root/.dsh")) return true;
    if (path.matches("^/root/\\.dsh/(?:sessions|storages|attachments|settings\\.yaml)$"))
      return true;
    // 旧引擎只恢复这两个工作目录文件；不会为任意旧 registry 目录授予写权限。
    return workspaceFiles.contains(path);
  }

  static File privatePath(BackupFileSystem fs, File files, String path) throws IOException {
    String guest = guest(files, path);
    if (!guest.startsWith("/root/")) throw new IOException("LEGACY_RESTORE_PRIVATE_PATH");
    return fs.child(files, GuestPaths.ROOT_RELATIVE + guest);
  }
}
