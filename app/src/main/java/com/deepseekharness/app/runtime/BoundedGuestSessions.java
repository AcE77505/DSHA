package com.deepseekharness.app.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ProcessIdentity;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Durable birth and owned-tree receipts. Historical SID evidence never retires a V2 unknown tree.
 */
public final class BoundedGuestSessions {
  private static final String HOME = "bounded-guest-active";
  private static final String PRE_DISPATCH_ORIGINALS = "bounded-guest-pre-dispatch-originals";
  private static final java.util.concurrent.ConcurrentHashMap<String, Operation> active =
      new java.util.concurrent.ConcurrentHashMap<>();

  private BoundedGuestSessions() {}

  public static final class Operation {
    private final BackupFileSystem fs;
    private final File home, entry;
    private final String id;
    private volatile boolean uncertain;

    private Operation(BackupFileSystem fs, File home, File entry, String id) {
      this.fs = fs;
      this.home = home;
      this.entry = entry;
      this.id = id;
    }

    public String id() {
      return id;
    }

    void nativeProtocol() throws IOException {
      fs.atomic(entry, "protocol", "DSHA_OWNED_TREE_V2\n".getBytes(StandardCharsets.US_ASCII));
      fs.syncDirectory(entry);
    }

    void retainCompletion() throws IOException {
      fs.atomic(entry, "retain", "RECORDED_OWNER\n".getBytes(StandardCharsets.US_ASCII));
    }

    void treeProof(int exitCode) throws IOException {
      if (exitCode < 0 || exitCode > 255) throw new IOException("OWNED_TREE_EXIT_CODE");
      String birth =
          new String(fs.small(fs.child(entry, "identity"), 128), StandardCharsets.US_ASCII);
      fs.atomic(
          entry,
          "tree-empty",
          (birth + "ECHILD " + exitCode + "\n").getBytes(StandardCharsets.US_ASCII));
      fs.syncDirectory(entry);
    }

    /** Called by the session launcher owner before the first guest handshake byte. */
    void beforeLaunch(ProcessIdentity identity) throws IOException {
      if (identity == null
          || !identity.ownsSession()
          || identity.parent != android.os.Process.myPid())
        throw new IOException("BOUNDED_GUEST_IDENTITY");
      for (String member : fs.list(entry))
        if (member.equals("identity")
            || member.startsWith("identity.tmp-")
            || member.startsWith("identity.previous"))
          throw new IOException("BOUNDED_GUEST_IDENTITY_ALREADY_WRITTEN");
      String value =
          id + "\n" + identity.pid + " " + identity.started + " " + identity.session + "\n";
      fs.atomic(entry, "identity", value.getBytes(StandardCharsets.US_ASCII));
      fs.syncDirectory(entry);
    }

    /** Only called after the owner confirmed the entire group and supervisor exited. */
    void completed() throws IOException {
      try {
        publishCompletion(fs, home.getParentFile(), entry, id);
        remove(entry, fs);
        fs.syncDirectory(home);
        active.remove(id, this);
      } catch (IOException failure) {
        uncertain = true;
        throw failure;
      }
    }

    void uncertain() {
      uncertain = true;
    }

    void completedBeforeDispatch() throws IOException {
      // 仅当前 owner 在没有 spawn，或从未尝试 START 且实际 launcher 已退出后调用。
      // 保留型调用者仍需可核验结果，不能因移除 active 而遗失“未派发”的关闭证明。
      File retain = fs.child(entry, "retain");
      if (!fs.stat(retain).type.equals("MISSING")) {
        if (!"RECORDED_OWNER\n".equals(new String(fs.small(retain, 64), StandardCharsets.US_ASCII)))
          throw new IOException("OWNED_TREE_RETAIN_RECORD");
        publishNotDispatched(fs, home.getParentFile(), id);
      }
      remove(entry, fs);
      fs.syncDirectory(home);
      active.remove(id, this);
    }
  }

  public static Operation begin(File filesDir) throws IOException {
    return begin(new AndroidBackupFileSystem(), filesDir.getCanonicalFile());
  }

  static Operation begin(BackupFileSystem fs, File filesDir) throws IOException {
    File files = filesDir.getCanonicalFile();
    File home = fs.child(files, HOME);
    if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
    if (!fs.stat(home).type.equals("DIRECTORY")) throw new IOException("BOUNDED_GUEST_RECORD_ROOT");
    String id = UUID.randomUUID().toString();
    File entry = fs.child(home, id);
    fs.directory(entry);
    fs.atomic(entry, "intent", (id + "\n").getBytes(StandardCharsets.US_ASCII));
    fs.syncDirectory(home);
    Operation operation = new Operation(fs, home, entry, id);
    active.put(id, operation);
    return operation;
  }

  /** Read-only startup/maintenance gate: even an unfinished intent requires recovery. */
  public static String firstPending(File filesDir) throws IOException {
    return firstPending(new AndroidBackupFileSystem(), filesDir.getCanonicalFile());
  }

  /**
   * 仅恢复保留型 trial 在 nativeProtocol 持久门之前中断的准备记录。 protocol 必须先落盘才可能创建 control/spawn；原记录先整体保留，再发布同
   * UUID 完成回执。 只读 pending 不调用此方法，错误文案、空 SID 或新的试运行均不参与证明。
   */
  static boolean recoverTrialPreparation(BackupFileSystem fs, File filesDir, File operation)
      throws IOException {
    File files = filesDir.getCanonicalFile();
    TrialPaths.requirePayload(files, new File(operation, "payload"));
    if (!fs.stat(fs.child(operation, "closed")).type.equals("MISSING")) return false;
    String id = TrialSupport.boundSession(fs, operation);
    if (id.isEmpty()) return false;
    File bound = uniquelyBoundTrial(fs, files, id);
    if (bound == null || !bound.equals(operation.getAbsoluteFile())) return false;
    if (!operation
        .getName()
        .equals(
            new String(fs.small(fs.child(operation, "launched"), 128), StandardCharsets.US_ASCII)))
      return false;
    Operation running = active.get(id);
    if (running != null && !running.uncertain) return false;
    File completed = fs.child(files, "bounded-guest-completed");
    String completedType = fs.stat(completed).type;
    if (!completedType.equals("MISSING")) {
      if (!completedType.equals("DIRECTORY")) throw new IOException("OWNED_TREE_COMPLETION_ROOT");
      if (!fs.stat(fs.child(completed, id)).type.equals("MISSING")) return false;
    }
    File home = fs.child(files, HOME);
    String homeType = fs.stat(home).type;
    if (!homeType.equals("DIRECTORY")) {
      if (!homeType.equals("MISSING")) throw new IOException("BOUNDED_GUEST_RECORD_ROOT");
      return false;
    }
    File entry = fs.child(home, id);
    File originals = fs.child(files, PRE_DISPATCH_ORIGINALS);
    String originalsType = fs.stat(originals).type;
    if (!originalsType.equals("MISSING") && !originalsType.equals("DIRECTORY"))
      throw new IOException("BOUNDED_GUEST_PRE_DISPATCH_ORIGINALS_ROOT");
    File preserved = new File(originals, id);
    boolean hasActive = !fs.stat(entry).type.equals("MISSING");
    boolean hasPreserved =
        originalsType.equals("DIRECTORY") && !fs.stat(preserved).type.equals("MISSING");
    if (hasActive == hasPreserved) return false;
    File source = hasActive ? entry : preserved;
    if (!unpublishedRetainedPreparation(fs, source, id)) return false;
    // 重新核对绑定与全部成员；不能移动并发改写过的记录或借另一个 trial 的编号收尾。
    if (!id.equals(TrialSupport.boundSession(fs, operation))
        || !unpublishedRetainedPreparation(fs, source, id)) return false;
    if (hasActive) {
      if (originalsType.equals("MISSING")) fs.directory(originals);
      fs.move(entry, fs.child(originals, id));
      fs.syncDirectory(home);
      fs.syncDirectory(originals);
      fs.syncDirectory(files);
    }
    publishNotDispatched(fs, files, id);
    if (running != null) active.remove(id, running);
    return true;
  }

  private static boolean unpublishedRetainedPreparation(BackupFileSystem fs, File entry, String id)
      throws IOException {
    if (!fs.stat(entry).type.equals("DIRECTORY")) return false;
    if (!new java.util.HashSet<>(fs.list(entry)).equals(java.util.Set.of("intent", "retain")))
      return false;
    return (id + "\n")
            .equals(new String(fs.small(fs.child(entry, "intent"), 64), StandardCharsets.US_ASCII))
        && "RECORDED_OWNER\n"
            .equals(new String(fs.small(fs.child(entry, "retain"), 64), StandardCharsets.US_ASCII));
  }

  private static File uniquelyBoundTrial(BackupFileSystem fs, File files, String sessionId)
      throws IOException {
    File trials = fs.child(files, "runtime-trials");
    String type = fs.stat(trials).type;
    if (type.equals("MISSING")) return null;
    if (!type.equals("DIRECTORY")) throw new IOException("TRIAL_DIRECTORY");
    List<String> names = fs.list(trials);
    if (names.size() > 64) throw new IOException("TRIAL_RETENTION_LIMIT");
    File selected = null;
    for (String id : names) {
      if (!com.deepseekharness.app.util.Ids.uuid(id)) throw new IOException("TRIAL_DIRECTORY");
      File operation = fs.child(trials, id);
      if (!fs.stat(operation).type.equals("DIRECTORY")) throw new IOException("TRIAL_DIRECTORY");
      if (!fs.stat(fs.child(operation, "closed")).type.equals("MISSING")) continue;
      if (!sessionId.equals(TrialSupport.boundSession(fs, operation))) continue;
      if (selected != null) throw new IOException("TRIAL_SESSION_BINDING_CONFLICT");
      selected = operation;
    }
    return selected;
  }

  static String firstPending(BackupFileSystem fs, File filesDir) throws IOException {
    String first = null;
    for (Inspection item : inspect(fs, filesDir)) {
      if (first == null && item.state.equals("INVALID")) first = item.id;
      if (first == null && item.state.equals("PENDING")) first = item.id;
    }
    return first;
  }

  public record Inspection(String id, String state, String reason) {}

  public static List<Inspection> inspect(File filesDir) throws IOException {
    return inspect(new AndroidBackupFileSystem(), filesDir.getCanonicalFile());
  }

  static List<Inspection> inspect(BackupFileSystem fs, File filesDir) throws IOException {
    java.util.ArrayList<Inspection> result = new java.util.ArrayList<>();
    java.util.Set<String> bootPending =
        new java.util.HashSet<>(BoundedGuestBootRecovery.pending(fs, filesDir.getCanonicalFile()));
    for (String id : bootPending)
      result.add(new Inspection(id, "PENDING", "BOOT_BOUNDARY_RECOVERY_UNCONFIRMED"));
    File home = fs.child(filesDir.getCanonicalFile(), HOME);
    if (fs.stat(home).type.equals("MISSING")) return List.copyOf(result);
    if (!fs.stat(home).type.equals("DIRECTORY")) throw new IOException("BOUNDED_GUEST_RECORD_ROOT");
    List<String> entries = fs.list(home);
    if (entries.size() > 128) throw new IOException("BOUNDED_GUEST_RECORD_LIMIT");
    for (String id : entries) {
      if (bootPending.contains(id)) continue;
      try {
        File entry = validateEntry(fs, home, id);
        Operation running = active.get(id);
        if (running != null && !running.uncertain) {
          result.add(new Inspection(id, "ACTIVE", "OWNED_CURRENT_INVOCATION"));
          continue;
        }
        String reason =
            fs.stat(fs.child(entry, "identity")).type.equals("MISSING")
                ? "LAUNCH_HANDSHAKE_NOT_RECORDED"
                : "RECORDED_SESSION_REQUIRES_REAP";
        result.add(new Inspection(id, "PENDING", reason));
      } catch (IOException | RuntimeException invalid) {
        result.add(
            new Inspection(
                id,
                "INVALID",
                com.deepseekharness.app.util.SensitiveData.redact(
                    String.valueOf(invalid.getMessage()))));
      }
    }
    return List.copyOf(result);
  }

  /**
   * Under an exclusive maintenance fence, remove only records whose whole session is proven empty.
   */
  public static void reapExited(File filesDir) throws IOException {
    reapExited(new AndroidBackupFileSystem(), filesDir.getCanonicalFile());
  }

  static void reapExited(BackupFileSystem fs, File filesDir) throws IOException {
    reapExited(
        fs, filesDir, BoundedGuestSessions::sessionEmpty, BoundedGuestSessions::leaderExited);
  }

  interface SessionProbe {
    boolean empty(int pid, long started) throws IOException;
  }

  static void reapExited(BackupFileSystem fs, File filesDir, SessionProbe probe)
      throws IOException {
    reapExited(fs, filesDir, probe, probe);
  }

  static void reapExited(
      BackupFileSystem fs, File filesDir, SessionProbe legacy, SessionProbe leader)
      throws IOException {
    reapExited(
        fs,
        filesDir,
        legacy,
        leader,
        () ->
            new String(
                fs.small(new File("/proc/sys/kernel/random/boot_id"), 64),
                StandardCharsets.US_ASCII));
  }

  static void reapExited(
      BackupFileSystem fs,
      File filesDir,
      SessionProbe legacy,
      SessionProbe leader,
      BoundedGuestBootRecovery.BootSource boots)
      throws IOException {
    File files = filesDir.getCanonicalFile();
    BoundedGuestBootRecovery.BindingGuard guard = id -> requireNoBootRecoveryBinding(fs, files, id);
    java.util.ArrayList<String> blocked =
        new java.util.ArrayList<>(
            BoundedGuestBootRecovery.recoverPreserved(fs, files, boots, guard));
    File home = fs.child(filesDir.getCanonicalFile(), HOME);
    if (fs.stat(home).type.equals("MISSING")) {
      for (String id : BoundedGuestBootRecovery.pending(fs, files))
        blocked.add(id + ":BOOT_BOUNDARY_RECOVERY_UNCONFIRMED");
      if (!blocked.isEmpty()) throw retained(blocked);
      return;
    }
    if (!fs.stat(home).type.equals("DIRECTORY")) throw new IOException("BOUNDED_GUEST_RECORD_ROOT");
    List<String> entries = fs.list(home);
    if (entries.size() > 128) throw new IOException("BOUNDED_GUEST_RECORD_LIMIT");
    for (String id : entries) {
      try {
        File entry = validateEntry(fs, home, id);
        File identity = fs.child(entry, "identity");
        if (fs.stat(identity).type.equals("MISSING")) {
          File trial = uniquelyBoundTrial(fs, filesDir.getCanonicalFile(), id);
          if (trial != null || !fs.stat(fs.child(entry, "retain")).type.equals("MISSING")) {
            if (trial == null || !recoverTrialPreparation(fs, filesDir, trial))
              throw new IOException("BOUNDED_GUEST_RETAINED_PREPARATION_UNCONFIRMED");
            continue;
          }
          // The launcher may not receive DSHA_START before this record is durable.
          remove(entry, fs);
          continue;
        }
        String value = new String(fs.small(identity, 128), StandardCharsets.US_ASCII);
        String[] lines = value.split("\n", -1);
        if (lines.length != 3
            || !id.equals(lines[0])
            || !lines[2].isEmpty()
            || !lines[1].matches("[1-9][0-9]* [1-9][0-9]* [1-9][0-9]*"))
          throw new IOException("BOUNDED_GUEST_IDENTITY_RECORD");
        String[] parts = lines[1].split(" ");
        int pid;
        long started;
        int session;
        try {
          pid = Integer.parseInt(parts[0]);
          started = Long.parseLong(parts[1]);
          session = Integer.parseInt(parts[2]);
        } catch (NumberFormatException invalid) {
          throw new IOException("BOUNDED_GUEST_IDENTITY_RECORD", invalid);
        }
        File protocol = fs.child(entry, "protocol");
        boolean v2 = !fs.stat(protocol).type.equals("MISSING");
        if (v2) {
          if (!"DSHA_OWNED_TREE_V2\n"
              .equals(new String(fs.small(protocol, 64), StandardCharsets.US_ASCII)))
            throw new IOException("OWNED_TREE_PROTOCOL");
          if (fs.stat(fs.child(entry, "tree-empty")).type.equals("MISSING")) {
            // Only a legacy ordinary record may acquire a reboot checkpoint. A
            // current owner or bound trial keeps its original closure contract.
            if (active.containsKey(id)) throw new IOException("BOUNDED_GUEST_CURRENT_OWNER");
            BoundedGuestBootRecovery.recover(fs, files, entry, id, boots, guard);
            continue;
          }
          String tree =
              new String(fs.small(fs.child(entry, "tree-empty"), 192), StandardCharsets.US_ASCII);
          if (!validTree(value, tree)) throw new IOException("OWNED_TREE_RECEIPT_MISSING");
        }
        if (pid < 2 || session != pid || !(v2 ? leader : legacy).empty(pid, started))
          throw new IOException("BOUNDED_GUEST_SESSION_LIVE_OR_UNKNOWN:" + id);
        publishCompletion(fs, home.getParentFile(), entry, id);
        remove(entry, fs);
      } catch (java.io.InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException | RuntimeException failure) {
        if (Thread.currentThread().isInterrupted()) {
          java.io.InterruptedIOException cancelled =
              new java.io.InterruptedIOException("CANCELLED");
          cancelled.initCause(failure);
          throw cancelled;
        }
        // 单条损坏记录不阻止清理后续已证明为空的会话。
        // 原件仍在活动目录并继续阻止维护；移动到隔离区
        // 不能证明未经核验的 guest 已经退出。
        blocked.add(
            id
                + ":"
                + com.deepseekharness.app.util.SensitiveData.redact(
                    String.valueOf(failure.getMessage())));
      }
    }
    fs.syncDirectory(home);
    for (String id : BoundedGuestBootRecovery.pending(fs, files))
      blocked.add(id + ":BOOT_BOUNDARY_RECOVERY_UNCONFIRMED");
    if (!blocked.isEmpty()) throw retained(blocked);
  }

  private static IOException retained(List<String> blocked) {
    String detail = "BOUNDED_GUEST_RECORDS_RETAINED:" + String.join(",", blocked);
    if (blocked.stream().anyMatch(value -> value.contains("BOUNDED_GUEST_REAL_REBOOT_REQUIRED")))
      detail =
          String.join(
              "\n",
              detail,
              com.deepseekharness.app.util.UiText.choose(
                  "退出回执因中断缺失，需重启手机，再点“恢复中断维护”；原记录和个人文件保留。",
                  "Restart the phone, then recover maintenance. Originals and files are kept."));
    if (blocked.stream().anyMatch(value -> value.contains("BOUNDED_GUEST_BOOT_ID_")))
      detail =
          String.join(
              "\n",
              detail,
              com.deepseekharness.app.util.UiText.choose(
                  "无法读取或核验本机启动标识，无法确认重启边界；原记录和个人文件保留。",
                  "Cannot verify this boot. Original records and personal files are kept."));
    return new IOException(detail);
  }

  private static void requireNoBootRecoveryBinding(BackupFileSystem fs, File files, String id)
      throws IOException {
    File trials = fs.child(files, "runtime-trials");
    String trialType = fs.stat(trials).type;
    if (!trialType.equals("MISSING")) {
      if (!trialType.equals("DIRECTORY")) throw new IOException("TRIAL_DIRECTORY");
      List<String> entries = fs.list(trials);
      if (entries.size() > 64) throw new IOException("TRIAL_RETENTION_LIMIT");
      for (String trialId : entries) {
        if (!com.deepseekharness.app.util.Ids.uuid(trialId))
          throw new IOException("TRIAL_DIRECTORY");
        File trial = fs.child(trials, trialId);
        if (!fs.stat(trial).type.equals("DIRECTORY")) throw new IOException("TRIAL_DIRECTORY");
        if (id.equals(TrialSupport.boundSession(fs, trial)))
          throw new IOException("BOUNDED_GUEST_BOOT_BOUND_TRIAL");
      }
    }
    // Cold candidates also bind non-retained launch IDs. Their transaction must
    // retain its own recovery semantics rather than inheriting ordinary cleanup.
    File cold = fs.child(files, "cold-install-operations");
    String coldType = fs.stat(cold).type;
    if (coldType.equals("MISSING")) return;
    if (!coldType.equals("DIRECTORY")) throw new IOException("COLD_LAUNCH_RECORD");
    List<String> operations = fs.list(cold);
    for (String operation : operations) {
      if (!com.deepseekharness.app.util.Ids.uuid(operation))
        throw new IOException("COLD_LAUNCH_RECORD");
      File directory = fs.child(cold, operation);
      if (!fs.stat(directory).type.equals("DIRECTORY")) throw new IOException("COLD_LAUNCH_RECORD");
      File launches = fs.child(directory, "launches.json");
      if (fs.stat(launches).type.equals("MISSING")) continue;
      Object value =
          com.deepseekharness.app.backup.BackupJson.read(fs.small(launches, 16384), 16384)
              .get("ids");
      if (!(value instanceof java.util.List<?> ids) || ids.size() > 4)
        throw new IOException("COLD_LAUNCH_RECORD");
      for (Object launch : ids) {
        if (!(launch instanceof String)
            || (!com.deepseekharness.app.util.Ids.uuid((String) launch)
                && !launch.equals("untracked"))) throw new IOException("COLD_LAUNCH_RECORD");
        if (id.equals(launch)) throw new IOException("BOUNDED_GUEST_BOOT_BOUND_COLD_INSTALL");
      }
    }
  }

  private static File validateEntry(BackupFileSystem fs, File home, String id) throws IOException {
    if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
      throw new IOException("BOUNDED_GUEST_RECORD_ID");
    File entry = fs.child(home, id);
    if (!fs.stat(entry).type.equals("DIRECTORY"))
      throw new IOException("BOUNDED_GUEST_RECORD_TYPE");
    File intent = fs.child(entry, "intent");
    List<String> members = fs.list(entry);
    boolean hasIntent = !fs.stat(intent).type.equals("MISSING");
    if (hasIntent
        && !(id + "\n").equals(new String(fs.small(intent, 64), StandardCharsets.US_ASCII)))
      throw new IOException("BOUNDED_GUEST_INTENT");
    for (String name : members) {
      if (name.equals("intent")
          || name.equals("identity")
          || name.equals("protocol")
          || name.equals("tree-empty")
          || name.equals("retain")) continue;
      if (!knownTemporary(fs, entry, id, name))
        throw new IOException("BOUNDED_GUEST_RECORD_MEMBER");
    }
    if (!hasIntent && members.contains("identity"))
      throw new IOException("BOUNDED_GUEST_INTENT_MISSING");
    return entry;
  }

  private static boolean knownTemporary(BackupFileSystem fs, File entry, String id, String name)
      throws IOException {
    boolean intent = name.matches("intent\\.tmp-" + com.deepseekharness.app.util.Ids.UUID_PATTERN);
    boolean identity =
        name.matches("identity\\.tmp-" + com.deepseekharness.app.util.Ids.UUID_PATTERN);
    if (!intent && !identity) return false;
    File file = fs.child(entry, name);
    BackupFileSystem.Node node = fs.stat(file);
    if (!node.type.equals("FILE") || node.size > 128)
      throw new IOException("BOUNDED_GUEST_TEMP_TYPE_OR_SIZE");
    String value = new String(fs.small(file, 128), StandardCharsets.US_ASCII);
    String prefix = id + "\n";
    if (intent) return prefix.startsWith(value);
    if (value.length() <= prefix.length()) return prefix.startsWith(value);
    return value.startsWith(prefix) && value.substring(prefix.length()).matches("[0-9 ]*\\n?");
  }

  private static void remove(File entry, BackupFileSystem fs) throws IOException {
    validateEntry(fs, entry.getParentFile(), entry.getName());
    for (String marker : new String[] {"protocol", "tree-empty", "retain"}) {
      File file = fs.child(entry, marker);
      if (!fs.stat(file).type.equals("MISSING")) fs.delete(file);
    }
    File identity = fs.child(entry, "identity"), intent = fs.child(entry, "intent");
    if (!fs.stat(identity).type.equals("MISSING")) fs.delete(identity);
    for (String name : fs.list(entry))
      if (name.startsWith("intent.tmp-") || name.startsWith("identity.tmp-"))
        fs.delete(fs.child(entry, name));
    if (!fs.stat(intent).type.equals("MISSING")) fs.delete(intent);
    fs.syncDirectory(entry);
    fs.delete(entry);
  }

  private static void publishCompletion(BackupFileSystem fs, File files, File entry, String id)
      throws IOException {
    File retain = fs.child(entry, "retain");
    if (fs.stat(retain).type.equals("MISSING")) return;
    if (!"RECORDED_OWNER\n".equals(new String(fs.small(retain, 64), StandardCharsets.US_ASCII)))
      throw new IOException("OWNED_TREE_RETAIN_RECORD");
    String birth =
        new String(fs.small(fs.child(entry, "identity"), 128), StandardCharsets.US_ASCII);
    String tree =
        new String(fs.small(fs.child(entry, "tree-empty"), 192), StandardCharsets.US_ASCII);
    if (!"DSHA_OWNED_TREE_V2\n"
            .equals(
                new String(fs.small(fs.child(entry, "protocol"), 64), StandardCharsets.US_ASCII))
        || !validTree(birth, tree)) throw new IOException("OWNED_TREE_COMPLETION_UNCONFIRMED");
    File home = completionHome(fs, files);
    fs.atomic(
        home,
        id,
        ("DSHA_OWNED_TREE_V2_CLOSED\n" + tree + "LAUNCHER_EXITED\n")
            .getBytes(StandardCharsets.US_ASCII));
    fs.syncDirectory(home);
  }

  private static File completionHome(BackupFileSystem fs, File files) throws IOException {
    File home = fs.child(files, "bounded-guest-completed");
    if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
    if (!fs.stat(home).type.equals("DIRECTORY"))
      throw new IOException("OWNED_TREE_COMPLETION_ROOT");
    return home;
  }

  private static void publishNotDispatched(BackupFileSystem fs, File files, String id)
      throws IOException {
    File completed = completionHome(fs, files);
    fs.atomic(
        completed,
        id,
        ("DSHA_OWNED_TREE_V2_NOT_DISPATCHED\n" + id + "\nLAUNCHER_ABSENT\n")
            .getBytes(StandardCharsets.US_ASCII));
    fs.syncDirectory(completed);
  }

  static boolean recordedCompletion(BackupFileSystem fs, File files, String id) throws IOException {
    String text =
        new String(
            fs.small(fs.child(files, "bounded-guest-completed/" + id), 256),
            StandardCharsets.US_ASCII);
    if (text.equals("DSHA_OWNED_TREE_V2_NOT_DISPATCHED\n" + id + "\nLAUNCHER_ABSENT\n"))
      return true;
    if (!text.matches(
        "DSHA_OWNED_TREE_V2_CLOSED\\n"
            + java.util.regex.Pattern.quote(id)
            + "\\n[1-9][0-9]* [1-9][0-9]* [1-9][0-9]*\\nECHILD [0-9]{1,3}\\nLAUNCHER_EXITED\\n"))
      throw new IOException("OWNED_TREE_COMPLETION_RECORD_INVALID");
    String[] rows = text.split("\n");
    if (!rows[2].split(" ")[0].equals(rows[2].split(" ")[2])
        || Integer.parseInt(rows[3].substring(7)) > 255)
      throw new IOException("OWNED_TREE_COMPLETION_RECORD_INVALID");
    return true;
  }

  static void completeRecorded(BackupFileSystem fs, File files, File entry, String id)
      throws IOException {
    publishCompletion(fs, files, entry, id);
    remove(entry, fs);
    fs.syncDirectory(entry.getParentFile());
  }

  static boolean validTree(String birth, String tree) {
    if (!tree.startsWith(birth + "ECHILD ")
        || !tree.substring(birth.length()).matches("ECHILD [0-9]{1,3}\n")) return false;
    return Integer.parseInt(tree.substring(birth.length() + 7).trim()) <= 255;
  }

  private static boolean sessionEmpty(int session, long started) throws IOException {
    File[] entries = new File("/proc").listFiles();
    if (entries == null) throw new IOException("BOUNDED_GUEST_PROC_UNREADABLE");
    for (File entry : entries) {
      String name = entry.getName();
      if (!name.matches("[1-9][0-9]*")) continue;
      int pid;
      try {
        pid = Integer.parseInt(name);
      } catch (NumberFormatException ignored) {
        continue;
      }
      int actual = NativeProcess.sessionId(pid);
      if (actual == -OsConstants.ESRCH) continue;
      if (actual < 0) {
        if (pid == session || owned(entry))
          throw new IOException("BOUNDED_GUEST_SESSION_UNREADABLE");
        continue;
      }
      if (actual != session) continue;
      if (!owned(entry)) throw new IOException("BOUNDED_GUEST_SESSION_OWNER_UNKNOWN");
      ProcessIdentity member =
          ProcessIdentity.inSession(Compat.readAll(new File(entry, "stat")), pid, session);
      if (member == null) throw new IOException("BOUNDED_GUEST_SESSION_IDENTITY_UNKNOWN");
      if (pid == session && member.started != started)
        throw new IOException("BOUNDED_GUEST_LEADER_REUSED");
      if (!member.exited()) return false;
    }
    return true;
  }

  static boolean leaderExited(int pid, long started) {
    try {
      var current =
          com.deepseekharness.app.util.WebPidIdentity.parse(
              Compat.readAll(new File("/proc/" + pid + "/stat")), pid);
      return current != null && (current.started != started || current.exited());
    } catch (IOException unavailable) {
      try {
        Os.kill(pid, 0);
        return false;
      } catch (ErrnoException absent) {
        return absent.errno == OsConstants.ESRCH;
      }
    }
  }

  private static boolean owned(File proc) throws IOException {
    try {
      return Os.stat(proc.getAbsolutePath()).st_uid == android.os.Process.myUid();
    } catch (ErrnoException error) {
      if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH) return false;
      throw new IOException("BOUNDED_GUEST_OWNER_UNREADABLE", error);
    }
  }
}
