package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.ScratchPaths;
import java.io.File;
import java.io.IOException;

/** lstat/NOFOLLOW 与精确白名单共用的缓存清理执行体，兼容 API 23。 */
public final class ScratchCleanup {
  private ScratchCleanup() {}

  public interface Sink {
    void record(String action, String relative, long bytes, String reason);
  }

  public static File resolve(BackupFileSystem fs, File root, String relative) throws IOException {
    if (!ScratchPaths.declaredDirectory(relative)) throw new IOException("SCRATCH_PATH_UNDECLARED");
    return checked(fs, root, relative, false);
  }

  public static File verifyOwnedDirectory(BackupFileSystem fs, File root, String relative)
      throws IOException {
    if (!ScratchPaths.declaredOwnedDirectory(relative))
      throw new IOException("SCRATCH_PATH_UNDECLARED");
    File target = checked(fs, root, relative, true);
    requireDirectory(fs, target, "SCRATCH_PARENT");
    return target;
  }

  public static File verifyOwnedFile(BackupFileSystem fs, File root, String relative)
      throws IOException {
    if (!ScratchPaths.declaredOwnedFile(relative)) throw new IOException("SCRATCH_PATH_UNDECLARED");
    File target = checked(fs, root, relative, true);
    String type = fs.stat(target).type;
    if (!type.equals("FILE") && !type.equals("LINK") && !type.equals("MISSING"))
      throw new IOException("SCRATCH_OWNED_FILE_TYPE");
    return target;
  }

  private static File checked(BackupFileSystem fs, File root, String relative, boolean finalLink)
      throws IOException {
    if (root == null) throw new IOException("SCRATCH_ROOT_MISSING");
    File at = root.getAbsoluteFile();
    requireDirectory(fs, at, "SCRATCH_ROOT");
    String[] parts = relative.split("/");
    for (int i = 0; i < parts.length; i++) {
      at = new File(at, parts[i]);
      if (i < parts.length - 1) requireDirectory(fs, at, "SCRATCH_PARENT");
      else if (!finalLink && fs.stat(at).type.equals("LINK"))
        throw new IOException("SCRATCH_PATH_LINK");
    }
    return at;
  }

  private static void requireDirectory(BackupFileSystem fs, File path, String prefix)
      throws IOException {
    String type = fs.stat(path).type;
    if (type.equals("LINK")) throw new IOException(prefix + "_LINK");
    if (!type.equals("DIRECTORY")) throw new IOException(prefix + "_TYPE");
  }

  public static void prepare(BackupFileSystem fs, File root) throws IOException {
    requireDirectory(fs, root, "SCRATCH_ROOT");
    File at = root;
    for (String part : ScratchPaths.ROOTFS_DIRECTORY.split("/")) {
      File next = new File(at, part);
      if (fs.stat(next).type.equals("MISSING")) {
        fs.directory(next);
        fs.syncDirectory(at);
      }
      requireDirectory(fs, next, "SCRATCH_PARENT");
      at = next;
    }
  }

  public static long run(BackupFileSystem fs, File rootfs, Sink sink) {
    long freed = 0;
    for (String relative : ScratchPaths.DIRECTORY_WHITELIST) {
      try {
        File target = resolve(fs, rootfs, relative);
        if (fs.stat(target).type.equals("MISSING")) {
          sink.record("absent", relative, 0, "");
          continue;
        }
        if (!fs.stat(target).type.equals("DIRECTORY")) throw new IOException("SCRATCH_PATH_TYPE");
        long before = bytes(fs, target, 0);
        fs.removeOwned(rootfs, relative);
        long released = Math.max(0, before - bytes(fs, target, 0));
        freed = Math.addExact(freed, released);
        sink.record("deleted", relative, released, "");
      } catch (IOException | ArithmeticException failure) {
        sink.record("skipped", relative, 0, code(failure));
      }
    }
    try {
      File directory = verifyOwnedDirectory(fs, rootfs, ScratchPaths.GUEST_TMP);
      for (String name : fs.list(directory)) {
        if (!ScratchPaths.ownedTemporary(name)) continue;
        String relative = ScratchPaths.GUEST_TMP + "/" + name;
        try {
          File target = verifyOwnedFile(fs, rootfs, relative);
          if (fs.stat(target).type.equals("MISSING")) {
            sink.record("absent", relative, 0, "");
            continue;
          }
          long before = bytes(fs, target, 0);
          fs.delete(target); // 专属前缀目录永不递归；链接仅删除链接本身。
          long released = Math.max(0, before - bytes(fs, target, 0));
          freed = Math.addExact(freed, released);
          sink.record("deleted", relative, released, "");
        } catch (IOException | ArithmeticException failure) {
          sink.record("failed", relative, 0, code(failure));
        }
      }
    } catch (IOException failure) {
      sink.record("skipped", ScratchPaths.GUEST_TMP, 0, code(failure));
    }
    return freed;
  }

  private static long bytes(BackupFileSystem fs, File file, int depth) throws IOException {
    if (depth > 64) throw new IOException("SCRATCH_DEPTH");
    var node = fs.stat(file);
    if (node.type.equals("FILE")) return Math.max(0, node.size);
    if (!node.type.equals("DIRECTORY")) return 0;
    long total = 0;
    for (String name : fs.list(file))
      total = Math.addExact(total, bytes(fs, new File(file, name), depth + 1));
    return total;
  }

  private static String code(Exception failure) {
    return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
  }
}
