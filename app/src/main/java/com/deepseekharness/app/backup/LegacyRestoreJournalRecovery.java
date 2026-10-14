package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** 旧恢复的宿主回切：当前内容先保留，原日志和所有候选不删除，不重放 restore。 */
public final class LegacyRestoreJournalRecovery {
  public static final String HOME = "legacy-restore-recovery";

  public interface ExitCheck {
    void verify() throws IOException;
  }

  public interface Fault {
    void boundary(String name) throws IOException;
  }

  private LegacyRestoreJournalRecovery() {}

  public static String pending(BackupFileSystem fs, File files) throws IOException {
    File home = new File(files, HOME);
    String type = fs.stat(home).type;
    if (!type.equals("MISSING") && !type.equals("DIRECTORY"))
      throw new IOException("LEGACY_RESTORE_RECOVERY_ROOT");
    if (type.equals("DIRECTORY")) {
      File active = fs.child(home, "active.json");
      if (!fs.stat(active).type.equals("MISSING")) {
        var record = BackupJson.read(fs.small(active, 4096), 4096);
        String sha = BackupJson.string(record, "journal");
        if (BackupJson.number(record, "version") != 1 || !sha.matches("[a-f0-9]{64}"))
          throw new IOException("LEGACY_RESTORE_ACTIVE_CHANGED");
        if (!terminal(fs, home, sha)) return sha;
      }
    }
    var journal = LegacyRestoreJournalReader.read(fs, files);
    if (journal == null) return null;
    if (type.equals("MISSING")) return journal.sha256;
    return terminal(fs, home, journal.sha256) ? null : journal.sha256;
  }

  private static boolean terminal(BackupFileSystem fs, File home, String sha) throws IOException {
    File operation = fs.child(home, sha);
    String type = fs.stat(operation).type;
    if (type.equals("MISSING")) return false;
    if (!type.equals("DIRECTORY")) throw new IOException("LEGACY_RESTORE_RECOVERY_TYPE");
    File terminal = fs.child(operation, "terminal.json");
    if (fs.stat(terminal).type.equals("MISSING")) return false;
    var record = BackupJson.read(fs.small(terminal, 4096), 4096);
    if (BackupJson.number(record, "version") != 1 || !sha.equals(record.get("journal")))
      throw new IOException("LEGACY_RESTORE_TERMINAL_CHANGED");
    if (!intentHash(fs, operation).equals(record.get("intent")))
      throw new IOException("LEGACY_RESTORE_TERMINAL_CHANGED");
    var plan =
        BackupJson.read(
            fs.small(fs.child(operation, "intent.json"), BackupLimits.MANIFEST),
            BackupLimits.MANIFEST);
    String workspace = BackupJson.string(plan, "workspace");
    byte[] source = fs.small(fs.child(operation, "journal-source.json"), BackupLimits.MANIFEST);
    var journal =
        LegacyRestoreJournalReader.parse(
            fs,
            home.getParentFile(),
            fs.child(operation, "journal-source.json"),
            source,
            workspace,
            false);
    if (!sha.equals(journal.sha256)
        || BackupJson.number(plan, "version") != 1
        || !sha.equals(plan.get("journal"))
        || !(plan.get("rows") instanceof List)
        || ((List<?>) plan.get("rows")).size() != journal.rows.size())
      throw new IOException("LEGACY_RESTORE_TERMINAL_CHANGED");
    boolean external = false;
    for (int i = 0; i < journal.rows.size(); i++) {
      var row = journal.rows.get(i);
      Object item = ((List<?>) plan.get("rows")).get(i);
      if (!(item instanceof Map)) throw new IOException("LEGACY_RESTORE_TERMINAL_CHANGED");
      @SuppressWarnings("unchecked")
      Map<String, Object> value = (Map<String, Object>) item;
      String kind = BackupJson.string(value, "kind"), action = BackupJson.string(value, "action");
      if (!Set.of("PRIVATE", "EXTERNAL", "UNKNOWN").contains(kind) || !row.kind.name().equals(kind))
        throw new IOException("LEGACY_RESTORE_TERMINAL_CHANGED");
      if (journal.complete) {
        if (!action.equals("COMMITTED_RETAINED"))
          throw new IOException("LEGACY_RESTORE_TERMINAL_CHANGED");
      } else if (kind.equals("EXTERNAL")) {
        if (!action.equals("RETAINED_EXTERNAL"))
          throw new IOException("LEGACY_RESTORE_TERMINAL_CHANGED");
        external = true;
      } else if (kind.equals("PRIVATE")) {
        if (action.equals("RESTORE_ORIGINAL")) {
          if (!row.existed
              || !validWitness(value.get("target"), true)
              || !validWitness(value.get("original"), false)
              || !doneMatches(
                  BackupJson.read(fs.small(fs.child(operation, "row-" + i + ".json"), 4096), 4096),
                  journal,
                  i,
                  action)) throw new IOException("LEGACY_RESTORE_TERMINAL_CHANGED");
        } else if (!action.equals("NOT_SWITCHED") || !notSwitched(row, value))
          throw new IOException("LEGACY_RESTORE_TERMINAL_CHANGED");
      } else throw new IOException("LEGACY_RESTORE_TERMINAL_CHANGED");
    }
    String expected =
        external
            ? "RETAINED_EXTERNAL"
            : journal.complete ? "COMMITTED_RETAINED" : "PRIVATE_RECOVERED";
    if (!expected.equals(record.get("result")))
      throw new IOException("LEGACY_RESTORE_TERMINAL_CHANGED");
    return true;
  }

  public static String recover(
      BackupFileSystem fs, File files, ExitCheck exit, Fault fault, BackupControl control)
      throws IOException {
    return recover(fs, files, "", exit, fault, control);
  }

  public static String recover(
      BackupFileSystem fs,
      File files,
      String previousWorkspace,
      ExitCheck exit,
      Fault fault,
      BackupControl control)
      throws IOException {
    return recover(fs, files, previousWorkspace, exit, fault, control, null);
  }

  /** 已完成或全外置旧日志只退引用；不能因另一真实事务存在而把两者永久互锁。 */
  public static boolean retireInert(
      BackupFileSystem fs, File files, ExitCheck exit, BackupControl control) throws IOException {
    var source = LegacyRestoreJournalReader.read(fs, files);
    if (source == null || !inert(source) || pending(fs, files) == null) return false;
    recover(fs, files, "", exit, null, control, source.sha256);
    return true;
  }

  private static boolean inert(LegacyRestoreJournalReader journal) {
    return journal.complete
        || journal.rows.stream()
            .allMatch(row -> row.kind == LegacyRestoreJournalReader.Kind.EXTERNAL);
  }

  private static String recover(
      BackupFileSystem fs,
      File files,
      String previousWorkspace,
      ExitCheck exit,
      Fault fault,
      BackupControl control,
      String expectedInertJournal)
      throws IOException {
    var journal = LegacyRestoreJournalReader.read(fs, files, previousWorkspace);
    String pending = pending(fs, files);
    if (pending == null) return "NO_LEGACY_RESTORE";
    if (journal == null || !pending.equals(journal.sha256))
      throw new IOException("LEGACY_RESTORE_JOURNAL_CHANGED_RETAINED");
    if (expectedInertJournal != null
        && (!expectedInertJournal.equals(journal.sha256) || !inert(journal)))
      throw new IOException("LEGACY_RESTORE_JOURNAL_CHANGED_RETAINED");
    exit.verify();
    Fault boundary = fault == null ? name -> {} : fault;
    File home = new File(files, HOME);
    if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
    File operation = fs.child(home, journal.sha256);
    if (fs.stat(operation).type.equals("MISSING")) fs.directory(operation);
    fs.atomic(
        home,
        "active.json",
        BackupJson.write(Map.of("version", 1L, "journal", journal.sha256), 4096));
    File displaced = fs.child(operation, "displaced");
    if (fs.stat(displaced).type.equals("MISSING")) fs.directory(displaced);
    File intent = fs.child(operation, "intent.json");
    Map<String, Object> plan;
    if (fs.stat(intent).type.equals("MISSING")) {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (var row : journal.rows) {
        control.check();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", row.kind.name());
        String action = journal.complete ? "COMMITTED_RETAINED" : "RETAINED_EXTERNAL";
        if (!journal.complete && row.kind == LegacyRestoreJournalReader.Kind.UNKNOWN)
          action = "UNKNOWN_RETAINED";
        if (!journal.complete && row.kind == LegacyRestoreJournalReader.Kind.PRIVATE) {
          File target = LegacyRestoreJournalReader.privatePath(fs, files, row.dst),
              old = LegacyRestoreJournalReader.privatePath(fs, files, row.old),
              next = LegacyRestoreJournalReader.privatePath(fs, files, row.next);
          String a = fs.stat(target).type, b = fs.stat(old).type, c = fs.stat(next).type;
          value.put("target", witness(fs, target, control));
          value.put("original", witness(fs, old, control));
          value.put("nextType", c);
          if (row.existed && !b.equals("MISSING")) action = "RESTORE_ORIGINAL";
          else if (!row.existed
              && a.equals("MISSING")
              && b.equals("MISSING")
              && c.equals("MISSING")) action = "NOT_SWITCHED";
          else if (b.equals("MISSING")
              && !c.equals("MISSING")
              && (row.existed ? !a.equals("MISSING") : a.equals("MISSING")))
            action = "NOT_SWITCHED";
          else action = "UNKNOWN_RETAINED";
        }
        value.put("action", action);
        rows.add(value);
      }
      plan =
          new LinkedHashMap<>(
              Map.of(
                  "version",
                  1L,
                  "journal",
                  journal.sha256,
                  "rows",
                  rows,
                  "workspace",
                  previousWorkspace == null ? "" : previousWorkspace));
      fs.atomic(operation, "journal-source.json", fs.small(journal.journal, BackupLimits.MANIFEST));
      fs.atomic(operation, "intent.json", BackupJson.write(plan, BackupLimits.MANIFEST));
      boundary.boundary("prepared");
    } else plan = BackupJson.read(fs.small(intent, BackupLimits.MANIFEST), BackupLimits.MANIFEST);
    if (BackupJson.number(plan, "version") != 1
        || !journal.sha256.equals(plan.get("journal"))
        || !(plan.get("rows") instanceof List)
        || ((List<?>) plan.get("rows")).size() != journal.rows.size()
        || !Objects.equals(
            plan.get("workspace"), previousWorkspace == null ? "" : previousWorkspace)
        || !journal.sha256.equals(
            BackupArchive.hex(
                BackupArchive.sha()
                    .digest(
                        fs.small(
                            fs.child(operation, "journal-source.json"), BackupLimits.MANIFEST)))))
      throw new IOException("LEGACY_RESTORE_INTENT_CHANGED");
    boolean unknown = false, retainedExternal = false;
    for (int i = journal.rows.size() - 1; i >= 0; i--) {
      control.check();
      exit.verify();
      var row = journal.rows.get(i);
      if (!(((List<?>) plan.get("rows")).get(i) instanceof Map))
        throw new IOException("LEGACY_RESTORE_INTENT_CHANGED");
      @SuppressWarnings("unchecked")
      Map<String, Object> value = (Map<String, Object>) ((List<?>) plan.get("rows")).get(i);
      if (!row.kind.name().equals(value.get("kind")))
        throw new IOException("LEGACY_RESTORE_INTENT_CHANGED");
      String action = BackupJson.string(value, "action");
      if (journal.complete && !action.equals("COMMITTED_RETAINED")
          || !journal.complete
              && row.kind == LegacyRestoreJournalReader.Kind.EXTERNAL
              && !action.equals("RETAINED_EXTERNAL")
          || !journal.complete
              && row.kind == LegacyRestoreJournalReader.Kind.UNKNOWN
              && !action.equals("UNKNOWN_RETAINED"))
        throw new IOException("LEGACY_RESTORE_INTENT_CHANGED");
      if (action.equals("UNKNOWN_RETAINED")) {
        unknown = true;
        continue;
      }
      if (action.equals("RETAINED_EXTERNAL")) {
        retainedExternal = true;
        continue;
      }
      if (action.equals("COMMITTED_RETAINED")) continue;
      if (action.equals("NOT_SWITCHED")) {
        if (row.kind != LegacyRestoreJournalReader.Kind.PRIVATE || !notSwitched(row, value))
          throw new IOException("LEGACY_RESTORE_INTENT_CHANGED");
        continue;
      }
      if (row.kind != LegacyRestoreJournalReader.Kind.PRIVATE || !action.equals("RESTORE_ORIGINAL"))
        throw new IOException("LEGACY_RESTORE_INTENT_CHANGED");
      if (action.equals("RESTORE_ORIGINAL") != row.existed)
        throw new IOException("LEGACY_RESTORE_INTENT_CHANGED");
      File target = LegacyRestoreJournalReader.privatePath(fs, files, row.dst),
          old = LegacyRestoreJournalReader.privatePath(fs, files, row.old),
          kept = fs.child(displaced, "row-" + i),
          done = fs.child(operation, "row-" + i + ".json");
      if (!fs.stat(done).type.equals("MISSING")) {
        var saved = BackupJson.read(fs.small(done, 4096), 4096);
        if (!doneMatches(saved, journal, i, action))
          throw new IOException("LEGACY_RESTORE_ROW_CHANGED");
        continue;
      }
      if (!(value.get("target") instanceof Map) || !(value.get("original") instanceof Map))
        throw new IOException("LEGACY_RESTORE_INTENT_CHANGED");
      @SuppressWarnings("unchecked")
      Map<String, Object> before = (Map<String, Object>) value.get("target"),
          original = (Map<String, Object>) value.get("original");
      if (before == null || original == null)
        throw new IOException("LEGACY_RESTORE_INTENT_CHANGED");
      boolean targetMissing = fs.stat(target).type.equals("MISSING"),
          oldMissing = fs.stat(old).type.equals("MISSING");
      if (action.equals("RESTORE_ORIGINAL") && oldMissing) {
        require(fs, target, original, control);
        require(fs, kept, before, control);
      } else {
        if (action.equals("RESTORE_ORIGINAL")) require(fs, old, original, control);
        if (!targetMissing) {
          require(fs, target, before, control);
          if (!fs.stat(kept).type.equals("MISSING"))
            throw new IOException("LEGACY_RESTORE_DISPLACED_CONFLICT");
          fs.move(target, kept);
          boundary.boundary("displaced-" + i);
        } else require(fs, kept, before, control);
        if (action.equals("RESTORE_ORIGINAL")) {
          require(fs, old, original, control);
          fs.move(old, target);
          boundary.boundary("restored-" + i);
        }
      }
      fs.atomic(
          operation,
          "row-" + i + ".json",
          BackupJson.write(
              Map.of(
                  "version",
                  1L,
                  "journal",
                  journal.sha256,
                  "action",
                  action,
                  "index",
                  (long) i,
                  "row",
                  rowFingerprint(journal.rows.get(i))),
              4096));
    }
    if (unknown) throw new IOException("LEGACY_RESTORE_UNKNOWN_RETAINED");
    // 最后再次核对原日志；只生成独立回执，从不删除或改写原日志。
    var current = LegacyRestoreJournalReader.read(fs, files);
    if (current == null || !journal.sha256.equals(current.sha256))
      throw new IOException("LEGACY_RESTORE_JOURNAL_CHANGED");
    exit.verify();
    String result =
        retainedExternal
            ? "RETAINED_EXTERNAL"
            : journal.complete ? "COMMITTED_RETAINED" : "PRIVATE_RECOVERED";
    fs.atomic(
        operation,
        "terminal.json",
        BackupJson.write(
            Map.of(
                "version",
                1L,
                "journal",
                journal.sha256,
                "result",
                result,
                "intent",
                intentHash(fs, operation)),
            4096));
    return result;
  }

  private static Map<String, Object> witness(BackupFileSystem fs, File path, BackupControl control)
      throws IOException {
    var node = fs.stat(path);
    return Map.of(
        "type", node.type, "key", node.key, "digest", BackupTree.digest(fs, path, control));
  }

  private static String intentHash(BackupFileSystem fs, File operation) throws IOException {
    return BackupArchive.hex(
        BackupArchive.sha()
            .digest(fs.small(fs.child(operation, "intent.json"), BackupLimits.MANIFEST)));
  }

  private static String rowFingerprint(LegacyRestoreJournalReader.Row row) throws IOException {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("dst", row.dst);
    value.put("old", row.old);
    value.put("new", row.next);
    value.put("existed", row.existed);
    return BackupArchive.hex(BackupArchive.sha().digest(BackupJson.write(value, 8192)));
  }

  private static boolean doneMatches(
      Map<String, Object> saved, LegacyRestoreJournalReader journal, int index, String action)
      throws IOException {
    return BackupJson.number(saved, "version") == 1
        && journal.sha256.equals(saved.get("journal"))
        && action.equals(saved.get("action"))
        && BackupJson.number(saved, "index") == index
        && rowFingerprint(journal.rows.get(index)).equals(saved.get("row"));
  }

  private static boolean validWitness(Object object, boolean missing) {
    if (!(object instanceof Map)) return false;
    Map<?, ?> value = (Map<?, ?>) object;
    return value.size() == 3
        && ("FILE".equals(value.get("type"))
            || "DIRECTORY".equals(value.get("type"))
            || missing && "MISSING".equals(value.get("type")))
        && value.get("key") instanceof String
        && value.get("digest") instanceof String
        && ((String) value.get("digest")).matches("[a-f0-9]{64}");
  }

  private static boolean notSwitched(
      LegacyRestoreJournalReader.Row row, Map<String, Object> value) {
    if (!validWitness(value.get("target"), true)
        || !validWitness(value.get("original"), true)
        || !(value.get("nextType") instanceof String)
        || !Set.of("FILE", "DIRECTORY", "MISSING").contains(value.get("nextType"))) return false;
    Map<?, ?> target = (Map<?, ?>) value.get("target"), old = (Map<?, ?>) value.get("original");
    if (!old.get("type").equals("MISSING")) return false;
    boolean targetMissing = target.get("type").equals("MISSING"),
        nextMissing = value.get("nextType").equals("MISSING");
    return !row.existed && targetMissing && nextMissing
        || !nextMissing && (row.existed ? !targetMissing : targetMissing);
  }

  private static void require(
      BackupFileSystem fs, File path, Map<String, Object> expected, BackupControl control)
      throws IOException {
    if (!witness(fs, path, control).equals(expected))
      throw new IOException("LEGACY_RESTORE_SOURCE_CHANGED");
  }
}
