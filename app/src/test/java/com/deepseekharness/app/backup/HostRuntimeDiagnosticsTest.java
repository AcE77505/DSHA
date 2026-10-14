package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class HostRuntimeDiagnosticsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();
  private static final String ID = "11111111-1111-4111-8111-111111111111";
  private static final String OTHER = "22222222-2222-4222-8222-222222222222";
  private static final String BIRTH = ID + "\n123 456 123\n";

  private File root() throws IOException {
    return temporary.newFolder();
  }

  private static void write(File root, String path, String value) throws IOException {
    File file = new File(root, path);
    Files.createDirectories(file.toPath().getParent());
    Files.writeString(file.toPath(), value, StandardCharsets.UTF_8);
  }

  private static Map<String, Object> receipt() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", 2L);
    value.put("engine", "bxroot");
    value.put("inputSha256", "a".repeat(64));
    value.put("qualification", "PYTHON");
    value.put("packageTools", false);
    value.put("outcome", "UNCONFIRMED");
    value.put("ownedTreeEmpty", false);
    value.put("launcherExited", false);
    value.put("commandExit", 139L);
    value.put("sessionRecord", ID);
    return value;
  }

  private static void receipt(File files, Map<String, Object> value) throws IOException {
    write(
        files,
        "runtime-capability-receipts/" + ID + ".json",
        new String(BackupJson.write(value, 65536), StandardCharsets.UTF_8));
  }

  private String collect(File files) {
    return HostRuntimeDiagnostics.collect(fs, files);
  }

  @Test
  public void schema2ExportsOnlyQualificationExcerptAndKnownStatus() throws Exception {
    File files = root();
    var value = receipt();
    value.put(
        "head", "probe failed api_key=credential-value\nAuthorization: Bearer hidden-value\n");
    value.put("tail", "DSHA_ENGINE_CAPABILITY_UNSUPPORTED statx errno=1");
    value.put("arguments", "private-argv-do-not-export");
    value.put("environment", "private-env-do-not-export");
    value.put("userText", "private-conversation-do-not-export");
    receipt(files, value);
    String output = collect(files);
    assertTrue(output.contains("RECORDED_SCHEMA2"));
    assertTrue(output.contains("statx errno=1"));
    assertTrue(output.contains("\"commandExit\":139"));
    assertTrue(output.contains("\"recordedClosed\":false"));
    assertFalse(output.contains("credential-value"));
    assertFalse(output.contains("hidden-value"));
    assertFalse(output.contains("private-"));
    assertTrue(output.contains("\"currentLivenessChecked\":false"));
  }

  @Test
  public void causeMessagesReduceToExceptionClassAndCanonicalCode() throws Exception {
    File files = root();
    var value = receipt();
    value.put(
        "failure",
        List.of(
            "java.io.IOException: RUNTIME_CAPABILITY_FAILED_NO_REPLAY:139 private-body",
            "java.lang.IllegalStateException: private-user-message",
            "java.io.IOException: RUNTIME_CAPABILITY_TREE_UNCONFIRMED"));
    receipt(files, value);
    String output = collect(files);
    assertTrue(output.contains("java.io.IOException"));
    assertTrue(output.contains("RUNTIME_CAPABILITY_FAILED_NO_REPLAY:139"));
    assertTrue(output.contains("RUNTIME_CAPABILITY_TREE_UNCONFIRMED"));
    assertFalse(output.contains("private-body"));
    assertFalse(output.contains("private-user-message"));
  }

  @Test
  public void legacyAndMissingReceiptsDoNotInventHistoricalFailureOrClosure() throws Exception {
    File files = root();
    String absent = collect(files);
    assertTrue(absent.contains("\"historicalFailureOutputMayBeAbsent\":true"));
    assertFalse(absent.contains("\"recordedClosed\":true"));
    receipt(
        files,
        Map.of(
            "schema",
            1L,
            "engine",
            "bxroot",
            "supported",
            true,
            "head",
            "old-user-body-not-exported"));
    String legacy = collect(files);
    assertTrue(legacy.contains("LEGACY_NO_FAILURE_DETAIL"));
    assertFalse(legacy.contains("old-user-body"));
  }

  @Test
  public void v2ActiveIdentityAndTreeAreRecordedWithoutProcessRevalidation() throws Exception {
    File files = root();
    String prefix = "bounded-guest-active/" + ID + "/";
    write(files, prefix + "intent", ID + "\n");
    write(files, prefix + "identity", BIRTH);
    write(files, prefix + "protocol", "DSHA_OWNED_TREE_V2\n");
    write(files, prefix + "tree-empty", BIRTH + "ECHILD 139\n");
    write(files, prefix + "retain", "RECORDED_OWNER\n");
    String output = collect(files);
    assertTrue(output.contains("\"leaderPid\":123"));
    assertTrue(output.contains("\"birthTicks\":456"));
    assertTrue(output.contains("\"treeEmpty\":\"VALID_RECORDED\""));
    assertTrue(output.contains("\"currentClosure\":\"NOT_CHECKED\""));
    assertFalse(output.contains("\"recordedClosed\":true"));
  }

  @Test
  public void legacyActiveOrInvalidIdentityCannotBecomeV2TreeProof() throws Exception {
    File files = root();
    String prefix = "bounded-guest-active/" + ID + "/";
    write(files, prefix + "intent", ID + "\n");
    write(files, prefix + "identity", BIRTH);
    write(files, prefix + "tree-empty", BIRTH + "ECHILD 0\n");
    String legacy = collect(files);
    assertTrue(legacy.contains("LEGACY_OR_UNRECORDED"));
    assertTrue(legacy.contains("\"treeEmpty\":\"INVALID\""));
    write(files, prefix + "protocol", "DSHA_OWNED_TREE_V2\n");
    write(files, prefix + "identity", ID + "\n123 456 124\n");
    assertTrue(collect(files).contains("\"identity\":\"INVALID\""));
  }

  @Test
  public void completedProtocolsAreExactAndNotDispatchedIsSeparate() throws Exception {
    File files = root();
    write(
        files,
        "bounded-guest-completed/" + ID,
        "DSHA_OWNED_TREE_V2_CLOSED\n" + BIRTH + "ECHILD 0\nLAUNCHER_EXITED\n");
    write(
        files,
        "bounded-guest-completed/" + OTHER,
        "DSHA_OWNED_TREE_V2_NOT_DISPATCHED\n" + OTHER + "\nLAUNCHER_ABSENT\n");
    String output = collect(files);
    assertTrue(output.contains("V2_CLOSED"));
    assertTrue(output.contains("V2_NOT_DISPATCHED"));
    assertFalse(output.contains("\"format\":\"INVALID\""));
    write(
        files,
        "bounded-guest-completed/" + ID,
        "DSHA_OWNED_TREE_V2_CLOSED\n" + BIRTH + "ECHILD 256\nLAUNCHER_EXITED\n");
    assertTrue(collect(files).contains("\"format\":\"INVALID\""));
  }

  @Test
  public void runtimeOperationExportsMarkersCountsAndNeverMetadataBodies() throws Exception {
    File files = root();
    String prefix = "host-runtime-operations/" + ID + "/";
    write(files, prefix + "created", ID + "\ncreated\n");
    write(files, prefix + "rolled-back", ID + "\nrolled-back\n");
    write(files, prefix + "finalized", OTHER + "\nfinalized\n");
    write(files, prefix + "runtime-mapping.json", "private-mapping-target");
    write(files, prefix + "plan.json", "private-settings-and-paths");
    write(files, prefix + "candidate/private-user-filename", "private-body");
    String output = collect(files);
    assertTrue(output.contains("RECORDED_ROLLED_BACK"));
    assertTrue(output.contains("\"finalized\":\"INVALID\""));
    assertTrue(output.contains("\"memberCount\":1"));
    assertFalse(output.contains("private-"));
  }

  @Test
  public void linkNodesAreNeverListedOrRead() throws Exception {
    File files = root();
    receipt(files, receipt());
    String output =
        HostRuntimeDiagnostics.collect(
            new JvmBackupFileSystem() {
              @Override
              public Node stat(File file) throws IOException {
                if (file.getName().equals(ID + ".json")
                    || file.getName().equals("bounded-guest-active"))
                  return new Node("LINK", "link", 10, 0, 0, 0700);
                return super.stat(file);
              }

              @Override
              public InputStream read(File file, Node expected) throws IOException {
                if (file.getName().equals(ID + ".json")) throw new AssertionError("read a link");
                return super.read(file, expected);
              }

              @Override
              public List<String> list(File file) throws IOException {
                if (file.getName().equals("bounded-guest-active"))
                  throw new AssertionError("listed a link");
                return super.list(file);
              }
            },
            files);
    assertTrue(output.contains("\"type\":\"LINK\""));
    assertFalse(output.contains("RECORDED_SCHEMA2"));
  }

  @Test
  public void oversizedFilesAndUnknownNamesAreBoundedAndDoNotLeakNames() throws Exception {
    File files = root();
    write(files, "runtime-capability-receipts/" + ID + ".json", "secret-body".repeat(7000));
    write(files, "bounded-guest-active/private-user-name", "private-body");
    String output = collect(files);
    assertTrue(output.contains("UNAVAILABLE"));
    assertTrue(output.contains("\"unknownNameCount\":1"));
    assertFalse(output.contains("secret-body"));
    assertFalse(output.contains("private-user-name"));
  }

  @Test
  public void directoryLimitPreventsUnboundedRecordReads() throws Exception {
    File files = root();
    File home = new File(files, "bounded-guest-completed");
    assertTrue(home.mkdir());
    for (int i = 0; i < 129; i++)
      write(home, String.format("%08d-1111-4111-8111-111111111111", i), "private-body");
    assertTrue(collect(files).contains("DIRECTORY_LIMIT"));
    assertFalse(collect(files).contains("private-body"));
  }

  @Test
  public void readsDuringUnfinishedMaintenanceNeverMutateAnyRoot() throws Exception {
    File files = root();
    write(files, "host-runtime-operations/" + ID + "/created", ID + "\ncreated\n");
    receipt(files, receipt());
    String output = HostRuntimeDiagnostics.collect(new ReadOnlyFileSystem(), files);
    assertTrue(output.contains("UNFINISHED_OR_UNKNOWN"));
    assertTrue(output.contains("RECORDED_SCHEMA2"));
    assertTrue(new File(files, "host-runtime-operations/" + ID + "/created").isFile());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void recordAndExcerptLimitsRemainExplicit() throws Exception {
    File files = root();
    var value = receipt();
    value.put("head", "A".repeat(4000));
    value.put("tail", "B".repeat(4000));
    value.put("failure", List.of("private.SecretException: private-body"));
    for (int i = 0; i < 13; i++)
      write(
          files,
          "runtime-capability-receipts/"
              + String.format("%08d-1111-4111-8111-111111111111.json", i),
          new String(BackupJson.write(value, 65536), StandardCharsets.UTF_8));
    String output = collect(files);
    var report = BackupJson.read(output.getBytes(StandardCharsets.UTF_8), 131072);
    var section = (Map<String, Object>) report.get("capabilityReceipts");
    assertEquals(1L, section.get("omittedCount"));
    var entries = (List<Map<String, Object>>) section.get("entries");
    assertEquals(12, entries.size());
    for (var entry : entries) {
      assertEquals(1024, ((String) entry.get("head")).length());
      assertEquals(1024, ((String) entry.get("tail")).length());
    }
    assertFalse(output.contains("private.SecretException"));
    assertFalse(output.contains("private-body"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void trialBindingAndExactIntentRetainMembersExposeOnlyRecordedC4Evidence()
      throws Exception {
    File files = root();
    String active = "bounded-guest-active/" + ID + "/";
    write(files, active + "intent", ID + "\n");
    write(files, active + "retain", "RECORDED_OWNER\n");
    String trial = "runtime-trials/" + OTHER + "/";
    write(files, trial + "native-session-id", ID);
    write(files, trial + "launched", OTHER);
    write(
        files,
        trial + "failure.json",
        "{\"error\":\"LAUNCH_RECORD_REQUIRES_ISOLATION\","
            + "\"output\":\"private-script-output\",\"exitCode\":\"running-or-not-started\"}");
    Map<String, Object> report =
        BackupJson.read(collect(files).getBytes(StandardCharsets.UTF_8), 131072);
    var sessions =
        (List<Map<String, Object>>) ((Map<?, ?>) report.get("boundedActive")).get("entries");
    var entry = sessions.get(0);
    assertEquals(true, entry.get("membersComplete"));
    assertEquals(true, entry.get("exactIntentAndRetainOnly"));
    assertEquals(List.of("intent", "retain"), entry.get("knownMembers"));
    assertEquals(0L, entry.get("unknownMemberCount"));
    assertEquals("MISSING", entry.get("identity"));
    assertEquals("MISSING", entry.get("treeEmpty"));
    assertEquals("MISSING", entry.get("protocolType"));
    assertEquals("LEGACY_OR_UNRECORDED", entry.get("protocol"));
    var trials =
        (List<Map<String, Object>>) ((Map<?, ?>) report.get("runtimeTrials")).get("entries");
    var trialEntry = trials.get(0);
    assertEquals(OTHER, trialEntry.get("trialUUID"));
    assertEquals(ID, ((Map<?, ?>) trialEntry.get("native-session-id")).get("id"));
    assertEquals("VALID_RECORDED", ((Map<?, ?>) trialEntry.get("launched")).get("status"));
    assertEquals("MISSING", ((Map<?, ?>) trialEntry.get("closed")).get("status"));
    assertEquals("LAUNCH_RECORD_REQUIRES_ISOLATION", trialEntry.get("failureCode"));
    assertEquals("NOT_CHECKED", trialEntry.get("currentClosure"));
    assertFalse(collect(files).contains("private-script-output"));
    assertFalse(collect(files).contains("running-or-not-started"));
  }

  @Test
  public void unknownActiveMembersAreCountedWithoutExportingTheirNames() throws Exception {
    File files = root();
    String active = "bounded-guest-active/" + ID + "/";
    write(files, active + "intent", ID + "\n");
    write(files, active + "retain", "RECORDED_OWNER\n");
    write(files, active + "private-user-filename", "private-user-body");
    String output = collect(files);
    assertTrue(output.contains("\"unknownMemberCount\":1"));
    assertTrue(output.contains("\"exactIntentAndRetainOnly\":false"));
    assertFalse(output.contains("private-user-"));
  }

  @Test
  public void trialMarkersAndNativeBindingRejectNewlinesAndWrongIds() throws Exception {
    File files = root();
    String trial = "runtime-trials/" + OTHER + "/";
    write(files, trial + "native-session-id", ID + "\n");
    write(files, trial + "launched", OTHER + "\n");
    write(files, trial + "closed", ID);
    write(files, trial + "failure.json", "{\"error\":\"PRIVATE_USER_PATH_OR_BODY\"}");
    String output = collect(files);
    assertTrue(output.contains("\"native-session-id\":{\"type\":\"FILE\",\"status\":\"INVALID\"}"));
    assertTrue(output.contains("\"launched\":{\"type\":\"FILE\",\"status\":\"INVALID\"}"));
    assertTrue(output.contains("\"closed\":{\"type\":\"FILE\",\"status\":\"INVALID\"}"));
    assertTrue(output.contains("RECORDED_OTHER"));
    assertFalse(output.contains("PRIVATE_USER_PATH_OR_BODY"));
  }

  @Test
  public void trialDiagnosticDoesNotOpenIntentPayloadOrScripts() throws Exception {
    File files = root();
    String trial = "runtime-trials/" + OTHER + "/";
    write(files, trial + "native-session-id", ID);
    write(files, trial + "intent.json", "private-intent-user-path");
    write(files, trial + "payload/script.sh", "private-script-body");
    String output =
        HostRuntimeDiagnostics.collect(
            new JvmBackupFileSystem() {
              @Override
              public InputStream read(File file, Node expected) throws IOException {
                if (file.getName().equals("intent.json") || file.getPath().contains("payload"))
                  throw new AssertionError("read trial payload or intent");
                return super.read(file, expected);
              }

              @Override
              public List<String> list(File file) throws IOException {
                if (file.getPath().contains("payload")) throw new AssertionError("listed payload");
                return super.list(file);
              }
            },
            files);
    assertTrue(output.contains("runtimeTrials"));
    assertFalse(output.contains("private-"));
    assertFalse(output.contains("script.sh"));
  }

  @Test
  public void oversizedActiveMemberSetCannotClaimExactIntentAndRetainOnly() throws Exception {
    File files = root();
    String active = "bounded-guest-active/" + ID + "/";
    write(files, active + "intent", ID + "\n");
    write(files, active + "retain", "RECORDED_OWNER\n");
    for (int i = 0; i < 127; i++) write(files, active + "private-" + i, "private-body");
    String output = collect(files);
    assertTrue(output.contains("\"membersComplete\":false"));
    assertTrue(output.contains("\"exactIntentAndRetainOnly\":false"));
    assertFalse(output.contains("private-"));
  }

  @Test
  public void preDispatchOriginalsStayPrivateAndOnlyRootTypeAndCountAreObserved() throws Exception {
    File files = root();
    String originals = "bounded-guest-pre-dispatch-originals";
    write(files, originals + "/private-original-name/identity", "private-original-body");
    assertTrue(PrivateRoots.top(originals));
    String output =
        HostRuntimeDiagnostics.collect(
            new JvmBackupFileSystem() {
              @Override
              public InputStream read(File file, Node expected) throws IOException {
                if (file.getPath().contains(originals))
                  throw new AssertionError("read retained original");
                return super.read(file, expected);
              }

              @Override
              public List<String> list(File file) throws IOException {
                if (file.getPath().contains(originals) && !file.getName().equals(originals))
                  throw new AssertionError("listed retained original");
                return super.list(file);
              }
            },
            files);
    assertTrue(
        output.contains(
            "\"boundedPreDispatchOriginals\":{\"type\":\"DIRECTORY\",\"entryCount\":1,\"limited\":false}"));
    assertFalse(output.contains("private-original"));
  }

  private static final class ReadOnlyFileSystem extends JvmBackupFileSystem {
    @Override
    public OutputStream create(File file) {
      throw new AssertionError("create");
    }

    @Override
    public void directory(File file) {
      throw new AssertionError("directory");
    }

    @Override
    public void move(File a, File b) {
      throw new AssertionError("move");
    }

    @Override
    public void delete(File file) {
      throw new AssertionError("delete");
    }

    @Override
    public void syncDirectory(File file) {
      throw new AssertionError("sync");
    }

    @Override
    public void mode(File file, int mode) {
      throw new AssertionError("chmod");
    }

    @Override
    public void symlink(String target, File link) {
      throw new AssertionError("symlink");
    }

    @Override
    public void atomic(File directory, String name, byte[] bytes) {
      throw new AssertionError("atomic");
    }
  }
}
