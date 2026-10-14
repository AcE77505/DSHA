package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.Ids;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, host-only observation. Never probes processes, repairs records, or authenticates closure. */
public final class HostRuntimeDiagnostics {
  private static final int DIRECTORY_LIMIT = 128, RECORD_LIMIT = 12, JSON_LIMIT = 65536;
  private static final int OUTPUT_LIMIT = 131072, EXCERPT_LIMIT = 1024;
  private static final Set<String> OUTCOMES = Set.of("READY", "UNSUPPORTED", "UNCONFIRMED");
  private static final Set<String> ACTIVE_MEMBERS =
      Set.of("intent", "retain", "protocol", "identity", "tree-empty");
  private static final Set<String> TRIAL_FAILURE_CODES =
      Set.of(
          "LAUNCH_RECORD_REQUIRES_ISOLATION",
          "TRIAL_SESSION_BINDING",
          "TRIAL_MARKER",
          "TRIAL_RUNTIME_UNCONFIRMED",
          "TRIAL_PROCESS_UNCONFIRMED",
          "TRIAL_WORK_RELEASE_FAILED",
          "OWNED_TREE_COMPLETION_UNCONFIRMED",
          "OWNED_TREE_NOT_DISPATCHED_UNCONFIRMED",
          "RUNTIME_CAPABILITY_UNCONFIRMED",
          "RUNTIME_CAPABILITY_TREE_UNCONFIRMED",
          "RUNTIME_CAPABILITY_TIMEOUT_NO_REPLAY",
          "RUNTIME_CAPABILITY_INPUT_CHANGED_NO_REPLAY",
          "CANCELLED",
          "PERMISSION_DENIED",
          "NO_SPACE",
          "SOURCE_NOT_DIRECTORY",
          "IO_FAILURE");
  private static final Set<String> FAILURE_CLASSES =
      Set.of(
          "java.io.IOException",
          "java.io.InterruptedIOException",
          "java.lang.InterruptedException",
          "java.lang.IllegalStateException",
          "java.lang.IllegalArgumentException",
          "java.lang.SecurityException",
          "java.lang.RuntimeException",
          "java.lang.NullPointerException",
          "java.lang.UnsupportedOperationException",
          "java.lang.Error",
          "java.lang.LinkageError",
          "java.lang.OutOfMemoryError",
          "java.lang.AssertionError",
          "android.system.ErrnoException");
  private static final String[] MARKERS = {
    "created",
    "verified",
    "switching",
    "files-committed",
    "settings-committed",
    "finalized",
    "rolling-back",
    "rolled-back",
    "previous-cleaned"
  };
  private static final Pattern CODE =
      Pattern.compile(
          "(?:^|: )(RUNTIME_CAPABILITY_(?:FAILED_NO_REPLAY:-?[0-9]{1,4}|TIMEOUT_NO_REPLAY|TREE_UNCONFIRMED|INPUT_CHANGED_NO_REPLAY|INTERRUPTED_NO_REPLAY))(?=$|[\\s:])");

  private HostRuntimeDiagnostics() {}

  public static String collect(BackupFileSystem fs, File files) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema", 1L);
    result.put("readOnly", true);
    result.put("currentLivenessChecked", false);
    result.put("historicalFailureOutputMayBeAbsent", true);
    result.put("missingCompletionDoesNotProveLive", true);
    result.put("capabilityReceipts", scan(fs, files, "runtime-capability-receipts", Kind.RECEIPT));
    result.put("boundedActive", scan(fs, files, "bounded-guest-active", Kind.ACTIVE));
    result.put("boundedCompleted", scan(fs, files, "bounded-guest-completed", Kind.COMPLETED));
    result.put("runtimeTrials", scan(fs, files, "runtime-trials", Kind.TRIAL));
    result.put(
        "boundedPreDispatchOriginals",
        rootCount(fs, files, "bounded-guest-pre-dispatch-originals"));
    result.put("runtimeOperations", scan(fs, files, PrivateRoots.RUNTIMES, Kind.OPERATION));
    result.put(
        "runtimeOperationsCompleted",
        scan(fs, files, PrivateRoots.RUNTIMES + "/completed", Kind.OPERATION));
    try {
      return new String(BackupJson.write(result, OUTPUT_LIMIT), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException unavailable) {
      return "{\"readOnly\":true,\"status\":\"OUTPUT_LIMIT_OR_UNAVAILABLE\"}";
    }
  }

  private enum Kind {
    RECEIPT,
    ACTIVE,
    COMPLETED,
    OPERATION,
    TRIAL
  }

  private static Map<String, Object> rootCount(BackupFileSystem fs, File files, String relative) {
    Map<String, Object> summary = new LinkedHashMap<>();
    try {
      File home = fs.child(files.getAbsoluteFile(), relative);
      String type = fs.stat(home).type;
      summary.put("type", type);
      if (type.equals("DIRECTORY")) {
        int count = fs.list(home).size();
        summary.put("entryCount", (long) Math.min(count, DIRECTORY_LIMIT + 1));
        summary.put("limited", count > DIRECTORY_LIMIT);
      }
    } catch (IOException | RuntimeException unavailable) {
      summary.put("type", "UNAVAILABLE");
    }
    return summary;
  }

  private static Map<String, Object> scan(
      BackupFileSystem fs, File files, String relative, Kind kind) {
    Map<String, Object> summary = new LinkedHashMap<>();
    List<Map<String, Object>> entries = new ArrayList<>();
    summary.put("entries", entries);
    try {
      File home = fs.child(files.getAbsoluteFile(), relative);
      String type = fs.stat(home).type;
      summary.put("type", type);
      if (!type.equals("DIRECTORY")) return summary;
      List<String> names = fs.list(home);
      summary.put("entryCount", (long) names.size());
      if (names.size() > DIRECTORY_LIMIT) {
        summary.put("status", "DIRECTORY_LIMIT");
        return summary;
      }
      List<String> selected = new ArrayList<>();
      int unknown = 0;
      for (String name : names) {
        boolean valid =
            kind == Kind.RECEIPT
                ? name.matches("(?:" + Ids.UUID_PATTERN + "|[a-f0-9]{64})\\.json")
                : name.matches(Ids.UUID_PATTERN);
        if (valid) selected.add(name);
        else if (!(kind == Kind.OPERATION && name.equals("completed"))) unknown++;
      }
      // A bounded metadata sort favors recent records without reading omitted payloads.
      Map<String, Long> times = new LinkedHashMap<>();
      for (String name : selected) {
        try {
          times.put(name, fs.stat(fs.child(home, name)).modified);
        } catch (IOException | RuntimeException ignored) {
          times.put(name, 0L);
        }
      }
      selected.sort(
          (a, b) -> {
            int newer = Long.compare(times.get(b), times.get(a));
            return newer == 0 ? a.compareTo(b) : newer;
          });
      summary.put("unknownNameCount", (long) unknown);
      summary.put("omittedCount", (long) Math.max(0, selected.size() - RECORD_LIMIT));
      for (String name : selected.subList(0, Math.min(selected.size(), RECORD_LIMIT))) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", name);
        if (kind == Kind.ACTIVE || kind == Kind.COMPLETED || kind == Kind.TRIAL)
          item.put("currentClosure", "NOT_CHECKED");
        entries.add(item);
        try {
          File entry = fs.child(home, name);
          String node = fs.stat(entry).type;
          item.put("type", node);
          if (kind == Kind.RECEIPT && node.equals("FILE")) receipt(fs, entry, item);
          else if (kind == Kind.ACTIVE && node.equals("DIRECTORY")) active(fs, entry, name, item);
          else if (kind == Kind.COMPLETED && node.equals("FILE")) completed(fs, entry, name, item);
          else if (kind == Kind.OPERATION && node.equals("DIRECTORY"))
            operation(fs, entry, name, item);
          else if (kind == Kind.TRIAL && node.equals("DIRECTORY")) trial(fs, entry, name, item);
        } catch (IOException | RuntimeException unavailable) {
          item.put("status", "UNAVAILABLE");
          item.put("exceptionClass", unavailable.getClass().getSimpleName());
        }
      }
      summary.put("status", "OBSERVED");
    } catch (IOException | RuntimeException unavailable) {
      summary.put("status", "UNAVAILABLE");
      summary.put("exceptionClass", unavailable.getClass().getSimpleName());
    }
    return summary;
  }

  private static void receipt(BackupFileSystem fs, File file, Map<String, Object> out)
      throws IOException {
    Map<String, Object> value = BackupJson.read(fs.small(file, JSON_LIMIT), JSON_LIMIT);
    Object schema = value.get("schema");
    if (!(schema instanceof Long) || ((Long) schema != 1 && (Long) schema != 2)) {
      out.put("status", "UNKNOWN_SCHEMA");
      return;
    }
    out.put("schema", schema);
    if (!"bxroot".equals(value.get("engine"))) {
      out.put("status", "UNKNOWN_ENGINE");
      return;
    }
    out.put("engine", "bxroot");
    if ((Long) schema == 1) {
      out.put("status", "LEGACY_NO_FAILURE_DETAIL");
      copyBoolean(value, out, "supported");
      return;
    }
    if (!Set.of("PYTHON", "NODE").contains(value.get("qualification"))
        || !OUTCOMES.contains(value.get("outcome"))
        || !(value.get("inputSha256") instanceof String)
        || !((String) value.get("inputSha256")).matches("[a-f0-9]{64}")
        || !(value.get("packageTools") instanceof Boolean)) {
      out.put("status", "INVALID_SCHEMA2");
      return;
    }
    out.put("status", "RECORDED_SCHEMA2");
    for (String key : List.of("qualification", "outcome", "inputSha256", "packageTools"))
      out.put(key, value.get(key));
    for (String key :
        List.of("supported", "ownedTreeEmpty", "launcherExited", "timedOut", "truncated"))
      copyBoolean(value, out, key);
    for (String key : List.of("checkedAt", "commandExit", "totalBytes"))
      if (value.get(key) instanceof Long) out.put(key, value.get(key));
    out.put(
        "recordedClosed",
        Boolean.TRUE.equals(value.get("ownedTreeEmpty"))
            && Boolean.TRUE.equals(value.get("launcherExited")));
    if (value.get("sessionRecord") instanceof String
        && ((String) value.get("sessionRecord")).matches(Ids.UUID_PATTERN))
      out.put("sessionRecord", value.get("sessionRecord"));
    for (String key : List.of("head", "tail")) {
      Object text = value.get(key);
      if (text instanceof String && ((String) text).length() <= 4000) {
        String safe = SensitiveData.redact((String) text);
        safe = safe.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "?");
        out.put(
            key,
            safe.length() <= EXCERPT_LIMIT
                ? safe
                : key.equals("tail")
                    ? safe.substring(safe.length() - EXCERPT_LIMIT)
                    : safe.substring(0, EXCERPT_LIMIT));
      }
    }
    List<Map<String, Object>> failures = new ArrayList<>();
    if (value.get("failure") instanceof List)
      for (Object text : (List<?>) value.get("failure")) {
        if (failures.size() == 4) break;
        if (!(text instanceof String) || ((String) text).length() > 1000) continue;
        Map<String, Object> failure = new LinkedHashMap<>();
        int separator = ((String) text).indexOf(':');
        String className = separator < 0 ? (String) text : ((String) text).substring(0, separator);
        failure.put("exceptionClass", FAILURE_CLASSES.contains(className) ? className : "other");
        var code = CODE.matcher((String) text);
        if (code.find()) failure.put("code", code.group(1));
        failures.add(failure);
      }
    out.put("failures", failures);
  }

  private static void copyBoolean(Map<String, Object> from, Map<String, Object> to, String key) {
    if (from.get(key) instanceof Boolean) to.put(key, from.get(key));
  }

  private static String small(BackupFileSystem fs, File directory, String name, int limit)
      throws IOException {
    return new String(fs.small(fs.child(directory, name), limit), StandardCharsets.US_ASCII);
  }

  private static String marker(BackupFileSystem fs, File directory, String name, String expected)
      throws IOException {
    File file = fs.child(directory, name);
    String type = fs.stat(file).type;
    if (type.equals("MISSING")) return "MISSING";
    if (!type.equals("FILE")) return type;
    return expected.equals(small(fs, directory, name, 256)) ? "VALID_RECORDED" : "INVALID";
  }

  private static String identity(String id, String text, Map<String, Object> out) {
    String[] lines = text.split("\n", -1);
    if (lines.length != 3
        || !id.equals(lines[0])
        || !lines[2].isEmpty()
        || !lines[1].matches("[1-9][0-9]* [1-9][0-9]* [1-9][0-9]*")) return "INVALID";
    try {
      String[] fields = lines[1].split(" ");
      int pid = Integer.parseInt(fields[0]), sid = Integer.parseInt(fields[2]);
      long birth = Long.parseLong(fields[1]);
      if (pid < 2 || pid != sid || birth <= 0) return "INVALID";
      out.put("leaderPid", (long) pid);
      out.put("birthTicks", birth);
      out.put("session", (long) sid);
      return "VALID_RECORDED";
    } catch (NumberFormatException invalid) {
      return "INVALID";
    }
  }

  private static boolean tree(String birth, String text) {
    if (!text.startsWith(birth)) return false;
    String suffix = text.substring(birth.length());
    return suffix.matches("ECHILD [0-9]{1,3}\\n")
        && Integer.parseInt(suffix.substring(7).trim()) <= 255;
  }

  private static void active(BackupFileSystem fs, File entry, String id, Map<String, Object> out)
      throws IOException {
    List<String> names = fs.list(entry);
    boolean complete = names.size() <= DIRECTORY_LIMIT;
    out.put("membersComplete", complete);
    out.put("memberCount", (long) Math.min(names.size(), DIRECTORY_LIMIT + 1));
    List<String> known = new ArrayList<>();
    int unknown = 0;
    if (complete) {
      for (String name : names)
        if (ACTIVE_MEMBERS.contains(name)) known.add(name);
        else unknown++;
      java.util.Collections.sort(known);
      out.put("knownMembers", known);
      out.put("unknownMemberCount", (long) unknown);
    }
    out.put(
        "exactIntentAndRetainOnly",
        complete
            && names.size() == 2
            && unknown == 0
            && known.contains("intent")
            && known.contains("retain"));
    out.put("intent", marker(fs, entry, "intent", id + "\n"));
    out.put("protocolType", fs.stat(fs.child(entry, "protocol")).type);
    String protocol = marker(fs, entry, "protocol", "DSHA_OWNED_TREE_V2\n");
    out.put("protocol", protocol.equals("MISSING") ? "LEGACY_OR_UNRECORDED" : protocol);
    out.put("retain", marker(fs, entry, "retain", "RECORDED_OWNER\n"));
    String treeType = fs.stat(fs.child(entry, "tree-empty")).type;
    out.put("treeEmpty", treeType);
    File file = fs.child(entry, "identity");
    String type = fs.stat(file).type;
    if (!type.equals("FILE")) {
      out.put("identity", type);
      return;
    }
    String birth = small(fs, entry, "identity", 128);
    String state = identity(id, birth, out);
    out.put("identity", state);
    out.put(
        "treeEmpty",
        treeType.equals("FILE")
            ? protocol.equals("VALID_RECORDED")
                    && state.equals("VALID_RECORDED")
                    && tree(birth, small(fs, entry, "tree-empty", 192))
                ? "VALID_RECORDED"
                : "INVALID"
            : treeType);
    out.put("currentClosure", "NOT_CHECKED");
  }

  private static void trial(BackupFileSystem fs, File entry, String id, Map<String, Object> out)
      throws IOException {
    out.put("trialUUID", id);
    for (String name : List.of("launched", "closed")) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("type", fs.stat(fs.child(entry, name)).type);
      value.put("status", marker(fs, entry, name, id));
      out.put(name, value);
    }
    Map<String, Object> bound = new LinkedHashMap<>();
    File nativeSession = fs.child(entry, "native-session-id");
    String type = fs.stat(nativeSession).type;
    bound.put("type", type);
    if (type.equals("FILE")) {
      String session = small(fs, entry, "native-session-id", 128);
      bound.put("status", Ids.uuid(session) ? "VALID_RECORDED" : "INVALID");
      if (Ids.uuid(session)) bound.put("id", session);
    } else bound.put("status", type);
    out.put("native-session-id", bound);
    File failure = fs.child(entry, "failure.json");
    String failureType = fs.stat(failure).type;
    out.put("failureType", failureType);
    if (failureType.equals("FILE")) {
      Map<String, Object> record = BackupJson.read(fs.small(failure, JSON_LIMIT), JSON_LIMIT);
      Object error = record.get("error");
      out.put(
          "failureCode",
          error instanceof String && TRIAL_FAILURE_CODES.contains(error)
              ? error
              : "RECORDED_OTHER");
    }
  }

  private static void completed(BackupFileSystem fs, File file, String id, Map<String, Object> out)
      throws IOException {
    String text = new String(fs.small(file, 256), StandardCharsets.US_ASCII);
    if (text.equals("DSHA_OWNED_TREE_V2_NOT_DISPATCHED\n" + id + "\nLAUNCHER_ABSENT\n")) {
      out.put("protocol", "V2_NOT_DISPATCHED");
      out.put("format", "VALID_RECORDED");
      return;
    }
    String prefix = "DSHA_OWNED_TREE_V2_CLOSED\n", suffix = "LAUNCHER_EXITED\n";
    if (!text.startsWith(prefix) || !text.endsWith(suffix)) {
      out.put("format", "INVALID");
      return;
    }
    String body = text.substring(prefix.length(), text.length() - suffix.length());
    int end = body.indexOf('\n', body.indexOf('\n') + 1);
    String birth = end < 0 ? "" : body.substring(0, end + 1);
    out.put("protocol", "V2_CLOSED");
    out.put(
        "format",
        identity(id, birth, out).equals("VALID_RECORDED") && tree(birth, body)
            ? "VALID_RECORDED"
            : "INVALID");
    out.put("currentClosure", "NOT_CHECKED");
  }

  private static void operation(BackupFileSystem fs, File entry, String id, Map<String, Object> out)
      throws IOException {
    Map<String, Object> markers = new LinkedHashMap<>();
    for (String name : MARKERS) markers.put(name, marker(fs, entry, name, id + "\n" + name + "\n"));
    out.put("markers", markers);
    String state = "UNFINISHED_OR_UNKNOWN";
    if ("VALID_RECORDED".equals(markers.get("rolled-back"))) state = "RECORDED_ROLLED_BACK";
    if ("VALID_RECORDED".equals(markers.get("finalized")))
      state =
          state.equals("RECORDED_ROLLED_BACK")
              ? "CONFLICTING_TERMINAL_MARKERS"
              : "RECORDED_FINALIZED";
    out.put("state", state);
    for (String name :
        List.of("runtime-mapping.json", "plan.json", "health.json", "archive-seal.json"))
      out.put(name, fs.stat(fs.child(entry, name)).type);
    for (String name : List.of("candidate", "previous", "failed", "stage")) {
      File child = fs.child(entry, name);
      String type = fs.stat(child).type;
      Map<String, Object> content = new LinkedHashMap<>();
      content.put("type", type);
      if (type.equals("DIRECTORY")) {
        int count = fs.list(child).size();
        content.put("memberCount", (long) Math.min(count, DIRECTORY_LIMIT));
        content.put("limited", count > DIRECTORY_LIMIT);
      }
      out.put(name, content);
    }
  }
}
