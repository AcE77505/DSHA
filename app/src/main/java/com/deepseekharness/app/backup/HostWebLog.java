package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Guest stdout has one bounded, private host sink; workspace paths never become log outputs. */
public final class HostWebLog {
  public static final String HOME = "startup-web-logs";
  public static final int LIMIT = 256 * 1024;
  private final BackupFileSystem fs;
  private final File home, directory;
  private final BackupFileSystem.Node homeBorn, born;
  private BackupFileSystem.Node published;
  private byte[] tail = new byte[0];

  private HostWebLog(BackupFileSystem fs, File home, File directory) throws IOException {
    this.fs = fs;
    this.home = home;
    this.directory = directory;
    homeBorn = fs.stat(home);
    born = fs.stat(directory);
    fs.atomic(directory, "stdout.log", tail);
    published = fs.stat(fs.child(directory, "stdout.log"));
  }

  public static HostWebLog create(BackupFileSystem fs, File files) throws IOException {
    File home = fs.child(files, HOME);
    if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
    if (!fs.stat(home).type.equals("DIRECTORY") || fs.stat(home).device != fs.stat(files).device)
      throw new IOException("WEB_LOG_PRIVATE_ROOT");
    File directory = fs.child(home, UUID.randomUUID().toString());
    fs.directory(directory);
    return new HostWebLog(fs, home, directory);
  }

  private File checked() throws IOException {
    var outer = fs.stat(home);
    if (!outer.type.equals("DIRECTORY")
        || !outer.key.equals(homeBorn.key)
        || outer.device != homeBorn.device) throw new IOException("WEB_LOG_PARENT_CHANGED");
    fs.child(home, directory.getName());
    var parent = fs.stat(directory);
    if (!parent.type.equals("DIRECTORY")
        || !parent.key.equals(born.key)
        || parent.device != born.device) throw new IOException("WEB_LOG_PARENT_CHANGED");
    File log = fs.child(directory, "stdout.log");
    var current = fs.stat(log);
    if (!current.type.equals("FILE") || !published.same(current) || current.device != born.device)
      throw new IOException("WEB_LOG_CHANGED_RETAINED");
    if (!fs.stat(fs.child(directory, "stdout.log.previous")).type.equals("MISSING"))
      throw new IOException("WEB_LOG_INCOMPLETE_PUBLICATION_RETAINED");
    return log;
  }

  public synchronized void append(String redacted) throws IOException {
    if (redacted == null || redacted.isEmpty()) return;
    if (!Arrays.equals(fs.small(checked(), LIMIT), tail))
      throw new IOException("WEB_LOG_CHANGED_RETAINED");
    byte[] chunk =
        com.deepseekharness.app.util.SensitiveData.redact(
                com.deepseekharness.app.util.DshAuthUrl.redact(redacted))
            .getBytes(StandardCharsets.UTF_8);
    int length = Math.min(LIMIT, tail.length + chunk.length);
    byte[] next = new byte[length];
    int fromChunk = Math.min(length, chunk.length), fromTail = length - fromChunk;
    System.arraycopy(tail, tail.length - fromTail, next, 0, fromTail);
    System.arraycopy(chunk, chunk.length - fromChunk, next, fromTail, fromChunk);
    // A truncated tail begins at a complete UTF-8 code point.
    int first = 0;
    while (first < next.length && (next[first] & 0xc0) == 0x80) first++;
    if (first > 0) next = Arrays.copyOfRange(next, first, next.length);
    fs.atomic(directory, "stdout.log", next);
    tail = next;
    published = fs.stat(fs.child(directory, "stdout.log"));
  }

  public synchronized String read() throws IOException {
    byte[] saved = fs.small(checked(), LIMIT);
    if (!Arrays.equals(saved, tail)) throw new IOException("WEB_LOG_CHANGED_RETAINED");
    return new String(saved, StandardCharsets.UTF_8);
  }

  /** Fixture/diagnostic identity only; writers keep this object instead of accepting caller paths. */
  public File directory() {
    return directory;
  }

  public static void saveFailure(BackupFileSystem fs, File files, String redacted)
      throws IOException {
    checkedFailure(fs, files);
    byte[] bytes = redacted.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 384 * 1024)
      bytes = Arrays.copyOfRange(bytes, bytes.length - 384 * 1024, bytes.length);
    fs.atomic(files, "last-startup-failure.log", bytes);
  }

  public static String readFailure(BackupFileSystem fs, File files) throws IOException {
    return new String(fs.small(checkedFailure(fs, files), 384 * 1024), StandardCharsets.UTF_8);
  }

  private static File checkedFailure(BackupFileSystem fs, File files) throws IOException {
    File result = fs.child(files, "last-startup-failure.log");
    long device = fs.stat(files).device;
    for (String name :
        java.util.List.of("last-startup-failure.log", "last-startup-failure.log.previous")) {
      var node = fs.stat(fs.child(files, name));
      if (!node.type.equals("MISSING") && (!node.type.equals("FILE") || node.device != device))
        throw new IOException("WEB_FAILURE_LOG_UNSAFE_RETAINED");
    }
    return result;
  }
}
