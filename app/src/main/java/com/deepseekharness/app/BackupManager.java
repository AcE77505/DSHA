package com.deepseekharness.app;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.data.DownloadsExport;
import com.deepseekharness.app.util.BackupScope;
import com.deepseekharness.app.util.FileIntegrity;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Android 协调层：URI 输入输出与配置；归档、范围和事务由 Android 宿主核心完成。 */
public final class BackupManager {
  private BackupManager() {}

  private static final long MAX_ARCHIVE = 16L * 1024 * 1024 * 1024;
  private static volatile String error = "";
  public static final String LATEST_BACKUP_NAME = "DSHA-backup-latest.tar.gz";

  public static String lastError() {
    return SensitiveData.redact(error);
  }

  public static boolean isRestoring() {
    return com.deepseekharness.app.core.MaintenanceCoordinator.isExclusive();
  }

  /** 安全入口供页面任务使用：停止队列排空后才拿归档锁，避免运行任务互等。 */
  public interface DataOperation<T> {
    T run() throws Exception;
  }

  public static boolean isDataTaskOwner() {
    return com.deepseekharness.app.core.MaintenanceCoordinator.isOwner();
  }

  /** 快照/预检只取得数据锁，保留正在运行的 Web；引擎检测到数据变化时返回失败。 */
  public static <T> T runSnapshotTask(HarnessController controller, DataOperation<T> operation)
      throws Exception {
    return com.deepseekharness.app.core.MaintenanceCoordinator.snapshot(controller, operation::run);
  }

  public static <T> T runDataTask(HarnessController controller, DataOperation<T> operation)
      throws Exception {
    return com.deepseekharness.app.core.MaintenanceCoordinator.exclusive(
        controller, operation::run);
  }

  /** 主线程与安装入口的只读门控；调用方仍需原子取得 EnvironmentTaskGate.Lease 才能开始工作。 */
  public static boolean isEnvironmentTaskBusy() {
    return com.deepseekharness.app.core.MaintenanceCoordinator.isEnvironmentTaskBusy();
  }

  /** 与普通停止共用 PID 身份与同 UID 进程核验，未知读取错误仍阻止维护。 */
  public static void stopWebForMaintenance(HarnessController controller) throws Exception {
    com.deepseekharness.app.core.MaintenanceCoordinator.stopWeb(controller);
  }

  /** 新增的启动查询供安装/运行 worker 接入；未完成的磁盘事务必须先回滚。 */
  public static boolean hasPendingMaintenance(HarnessController controller) {
    return com.deepseekharness.app.core.MaintenanceCoordinator.pending(controller);
  }

  public static boolean hasPendingMaintenance(File filesDir) {
    return com.deepseekharness.app.core.MaintenanceCoordinator.pending(filesDir);
  }

  public static void recoverMaintenanceBeforeStart(HarnessController controller) throws Exception {
    if (!com.deepseekharness.app.core.MaintenanceCoordinator.pending(controller)) return;
    com.deepseekharness.app.core.MaintenanceCoordinator.exclusive(
        controller,
        () -> {
          com.deepseekharness.app.core.EnvironmentMaintenance.recover(controller);
          return null;
        });
  }

  /** 安全归档永远位于 linux 之外，不导出公共存储，也不覆盖已有归档。 */
  public static String createMaintenanceBackup(HarnessController controller, File destination)
      throws Exception {
    if (!isDataTaskOwner()) throw new IOException("BACKUP_REQUIRES_MAINTENANCE");
    var fs = files();
    File privateFiles = controller.context().getFilesDir().getCanonicalFile();
    if (!com.deepseekharness.app.util.DocumentPaths.within(
            privateFiles, destination.getAbsoluteFile())
        || com.deepseekharness.app.util.DocumentPaths.within(
            controller.proot().getRootfsDir(), destination.getAbsoluteFile()))
      throw new IOException("BACKUP_PRIVATE_DESTINATION");
    var selection = new com.deepseekharness.app.backup.NativeDataLocations.Selection();
    selection.includeApiKey = controller.config().isBackupKey();
    var control = com.deepseekharness.app.core.BackupTask.currentControl(null);
    var located =
        new com.deepseekharness.app.backup.NativeDataLocations(controller.context())
            .locate(selection, control);
    File home = com.deepseekharness.app.backup.HostOperationArchive.reserve(fs, privateFiles);
    File operation = fs.child(home, UUID.randomUUID().toString());
    fs.directory(operation);
    File archive = fs.child(operation, "maintenance.dshdata");
    java.util.Map<String, Object> provenance = new java.util.LinkedHashMap<>();
    provenance.put("requestedScope", "application");
    provenance.put("createdAt", System.currentTimeMillis());
    provenance.put("appVersion", BuildConfig.VERSION_NAME);
    provenance.put("appCode", (long) BuildConfig.VERSION_CODE);
    provenance.put(
        "plugins", located.plugins == null ? java.util.Map.of() : located.plugins.description());
    var summary =
        com.deepseekharness.app.backup.HostSnapshot.create(
            fs,
            located.sources,
            operation,
            archive,
            provenance,
            false,
            control,
            located.plugins == null ? null : located.plugins::verify);
    fs.atomic(
        operation,
        "maintenance-snapshot.json",
        com.deepseekharness.app.backup.BackupJson.write(
            summary, com.deepseekharness.app.backup.BackupLimits.MANIFEST));
    FileIntegrity.Result expected;
    try (InputStream input = fs.read(archive, fs.stat(archive))) {
      expected = FileIntegrity.copy(input, null, MAX_ARCHIVE);
    }
    com.deepseekharness.app.backup.VerifiedFilePublication.publish(
        fs, archive, destination, expected);
    return expected.sha256;
  }

  /** 所有恢复目标由宿主受限映射决定；不执行旧 guest commit。 */
  public static String restoreWithinDataTask(HarnessController controller, PreparedRestore prepared)
      throws Exception {
    if (!isDataTaskOwner()) throw new IOException("RESTORE_REQUIRES_MAINTENANCE");
    var fs = files();
    var control = com.deepseekharness.app.core.BackupTask.currentControl(null);
    try (InputStream input = fs.read(prepared.archive, fs.stat(prepared.archive))) {
      if (!prepared.hash.equals(
          com.deepseekharness.app.backup.BackupArchive.digest(input, control)))
        throw new IOException("RESTORE_INPUT_CHANGED");
    }
    commitHostRestore(controller, fs, prepared.operation, prepared.plan, prepared.mapping, control);
    prepared.committed = true;
    return com.deepseekharness.app.util.UiText.choose(
        "数据已由 Android 恢复到私有目录；原件与外置工作区保留。",
        "Android restored data to private folders; originals and external workspaces are retained.");
  }

  /** 旧维护载荷先原生转换，不再执行 Python restore/recover。 */
  public static void restoreMaintenanceBackup(HarnessController controller, File archive)
      throws Exception {
    if (!isDataTaskOwner()) throw new IOException("RESTORE_REQUIRES_MAINTENANCE");
    try (PreparedRestore prepared =
        prepareRestore(controller.context(), controller, Uri.fromFile(archive))) {
      restoreWithinDataTask(controller, prepared);
    }
  }

  private static void commitHostRestore(
      HarnessController controller,
      com.deepseekharness.app.backup.BackupFileSystem fs,
      File operation,
      com.deepseekharness.app.backup.NativeRestorePlan plan,
      com.deepseekharness.app.backup.NativeRestoreTargets mapping,
      com.deepseekharness.app.backup.BackupControl control)
      throws Exception {
    var roots = plan.buildCandidates(true, control);
    File candidate = fs.child(operation, "candidate");
    mapping.prepareCredentialProjection(candidate, control);
    mapping.prepareLayout(roots, plan.before, candidate);
    mapping.prepareParents(roots);
    if (roots.contains("plugin-store") && plan.manifest.get("plugins") instanceof java.util.Map) {
      @SuppressWarnings("unchecked")
      java.util.Map<String, Object> graph =
          (java.util.Map<String, Object>) plan.manifest.get("plugins");
      new com.deepseekharness.app.backup.PluginRestoreGraph(
              fs,
              fs.child(candidate, "plugin-store"),
              new com.deepseekharness.app.backup.CurrentManagedPackages(controller.context())
                  ::verified,
              controller.proot().getRootfsDir())
          .rebuild(graph, control);
      mapping.prepareSystemPluginState(graph, roots, plan.before, candidate, control);
    }
    var proposed = mapping.proposedSettings(candidate, false);
    var transaction =
        new com.deepseekharness.app.backup.HostDataTransaction(
            fs, operation, mapping, mapping, null);
    transaction.prepare(roots, plan.before, proposed, control);
    transaction.commit(control);
    com.deepseekharness.app.HttpShellService.resetTokenAfterRestore(controller.context());
  }

  public static String backupToExternal(Context ctx, HarnessController controller) {
    return backupToExternal(ctx, controller, BackupScope.FULL);
  }

  public static String backupToExternal(Context ctx, HarnessController controller, int scope) {
    error = "";
    String operation = "";
    var jobs = com.deepseekharness.app.backup.NativeBackupJobs.get(ctx);
    try {
      if (isDataTaskOwner()) throw new IOException("HOST_BACKUP_OWNER_ALREADY_HELD");
      if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        throw new IOException("HOST_BACKUP_REQUIRES_WORKER");
      var selection = new com.deepseekharness.app.backup.NativeDataLocations.Selection();
      selection.scope =
          switch (scope) {
            case BackupScope.FULL -> "application";
            case BackupScope.SESSIONS -> "sessions";
            case BackupScope.SETTINGS -> "settings";
            case BackupScope.PLUGINS -> "plugins";
            default -> throw new IOException("UNKNOWN_SCOPE");
          };
      selection.includeApiKey = controller.config().isBackupKey();
      synchronized (jobs) {
        if (!jobs.exportToDownloads(selection, false)) throw new IOException("HOST_BACKUP_BUSY");
        operation = jobs.state().id;
      }
      for (; ; ) {
        var state = jobs.state();
        if (!operation.equals(state.id)) throw new IOException("HOST_BACKUP_OWNER_CHANGED");
        if (!state.busy) {
          if (!state.stage.equals("FINISHED")) throw new IOException(state.error);
          break;
        }
        Thread.sleep(50);
      }
      var fs = files();
      File owned =
          com.deepseekharness.app.backup.HostOperationArchive.locate(
              fs,
              com.deepseekharness.app.backup.HostOperationArchive.root(
                  ctx.getFilesDir().getCanonicalFile()),
              operation);
      var published =
          com.deepseekharness.app.backup.BackupJson.read(
              fs.small(fs.child(owned, "exported.json"), 16384), 16384);
      if (com.deepseekharness.app.backup.BackupJson.number(published, "version") != 1
          || !operation.equals(
              com.deepseekharness.app.backup.BackupJson.string(published, "operation")))
        throw new IOException("EXPORT_RECEIPT_CHANGED");
      return com.deepseekharness.app.backup.BackupJson.string(published, "uri")
          + "\nSHA-256: "
          + com.deepseekharness.app.backup.BackupJson.string(published, "sha256");
    } catch (InterruptedException cancelled) {
      synchronized (jobs) {
        if (!operation.isEmpty() && operation.equals(jobs.state().id) && jobs.state().busy)
          jobs.cancel();
      }
      Thread.currentThread().interrupt();
      error = "CANCELLED";
      return null;
    } catch (Exception failure) {
      error = safeError(failure);
      return null;
    }
  }

  private static com.deepseekharness.app.backup.BackupFileSystem files() {
    return new com.deepseekharness.app.backup.AndroidBackupFileSystem();
  }

  /** 普通启动只读发现旧日志，恢复须先经过原有停止屏障。 */
  public static void requireLegacyRestoreClosed(HarnessController controller) throws IOException {
    if (com.deepseekharness.app.backup.LegacyRestoreJournalRecovery.pending(
            files(), controller.context().getFilesDir().getCanonicalFile())
        != null) throw new IOException("LEGACY_RESTORE_RECOVERY_REQUIRED");
  }

  public static void recoverInterrupted(HarnessController controller) throws Exception {
    if (!isDataTaskOwner()) throw new IOException("LEGACY_RESTORE_REQUIRES_MAINTENANCE");
    File privateFiles = controller.context().getFilesDir().getCanonicalFile();
    com.deepseekharness.app.backup.LegacyRestoreJournalRecovery.recover(
        files(),
        privateFiles,
        controller.config().legacyRestorePreviousWorkdir(),
        () -> verifyLegacyExit(privateFiles),
        null,
        com.deepseekharness.app.core.BackupTask.currentControl(null));
  }

  public static boolean retireInertLegacyReference(HarnessController controller)
      throws IOException {
    if (!isDataTaskOwner()) throw new IOException("LEGACY_RESTORE_REQUIRES_MAINTENANCE");
    File privateFiles = controller.context().getFilesDir().getCanonicalFile();
    return com.deepseekharness.app.backup.LegacyRestoreJournalRecovery.retireInert(
        files(),
        privateFiles,
        () -> verifyLegacyExit(privateFiles),
        com.deepseekharness.app.core.BackupTask.currentControl(null));
  }

  private static void verifyLegacyExit(File privateFiles) throws IOException {
    if (com.deepseekharness.app.core.RuntimeTasks.hasOtherTasks())
      throw new IOException("LEGACY_RESTORE_PROCESS_EXIT_UNCONFIRMED");
    com.deepseekharness.app.runtime.BoundedGuestSessions.reapExited(privateFiles);
    if (com.deepseekharness.app.runtime.BoundedGuestSessions.firstPending(privateFiles) != null)
      throw new IOException("LEGACY_RESTORE_PROCESS_EXIT_UNCONFIRMED");
  }

  public static final class PreparedRestore implements AutoCloseable {
    final File archive, operation;
    final String hash;
    final com.deepseekharness.app.backup.NativeRestorePlan plan;
    final com.deepseekharness.app.backup.NativeRestoreTargets mapping;
    boolean committed;
    public final int scope;
    public final String summary;

    PreparedRestore(
        File archive,
        File operation,
        String hash,
        com.deepseekharness.app.backup.NativeRestorePlan plan,
        com.deepseekharness.app.backup.NativeRestoreTargets mapping,
        String summary) {
      this.archive = archive;
      this.operation = operation;
      this.hash = hash;
      this.plan = plan;
      this.mapping = mapping;
      this.summary = summary;
      this.scope = scopeOf(plan.manifest);
    }

    @Override
    public void close() throws IOException {
      var fs = files();
      File privateFiles = operation.getParentFile().getParentFile();
      if (com.deepseekharness.app.backup.HostPendingTransactions.pending(fs, privateFiles)
          .contains(operation)) return;
      com.deepseekharness.app.backup.RestoreStaging.clearSensitivePlaintext(fs, operation);
      recordPrepared(
          fs,
          operation,
          committed ? "FINISHED" : "CANCELLED_RETAINED",
          committed ? "RESTORED" : "CANCELLED",
          "");
    }
  }

  public static PreparedRestore prepareRestore(Context ctx, HarnessController controller, Uri uri)
      throws Exception {
    return prepareRestore(ctx, controller, uri, null);
  }

  /** 旧 API 的认证、转换和预检也全部由宿主处理。 */
  public static PreparedRestore prepareRestore(
      Context ctx, HarnessController controller, Uri uri, char[] password) throws Exception {
    if (uri == null || !("content".equals(uri.getScheme()) || "file".equals(uri.getScheme())))
      throw new IOException("BACKUP_INPUT_URI");
    var fs = files();
    var control = com.deepseekharness.app.core.BackupTask.currentControl(null);
    String name = uri.getLastPathSegment();
    if ("content".equals(uri.getScheme()))
      try (android.database.Cursor cursor =
          com.deepseekharness.app.backup.DocumentStreams.query(
              ctx.getContentResolver(),
              uri,
              new String[] {OpenableColumns.DISPLAY_NAME},
              control)) {
        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
      }
    File privateFiles = ctx.getFilesDir().getCanonicalFile();
    File home = com.deepseekharness.app.backup.HostOperationArchive.reserve(fs, privateFiles);
    File operation = fs.child(home, UUID.randomUUID().toString());
    fs.directory(operation);
    File input = fs.child(operation, "input.bin");
    try {
      try (InputStream source =
              "content".equals(uri.getScheme())
                  ? com.deepseekharness.app.backup.DocumentStreams.input(
                      ctx.getContentResolver(), uri, control)
                  : fs.read(new File(uri.getPath()), fs.stat(new File(uri.getPath())));
          java.io.OutputStream out = fs.create(input)) {
        FileIntegrity.copy(source, out, MAX_ARCHIVE);
      }
      File plain =
          com.deepseekharness.app.backup.NativeBackupInput.prepare(
              fs, operation, input, name, password, control);
      var mapping = new com.deepseekharness.app.backup.NativeRestoreTargets(ctx, operation);
      var plan =
          com.deepseekharness.app.backup.NativeRestorePlan.inspect(
              fs, operation, plain, mapping, java.util.Set.of("application"), control);
      String hash;
      try (InputStream source = fs.read(plain, fs.stat(plain))) {
        hash = com.deepseekharness.app.backup.BackupArchive.digest(source, control);
      }
      String summary =
          com.deepseekharness.app.util.UiText.format(
                  "文件数：%d · %s",
                  com.deepseekharness.app.backup.BackupJson.number(plan.manifest, "entries"),
                  HarnessController.fmtBytes(
                      com.deepseekharness.app.backup.BackupJson.number(plan.manifest, "bytes")))
              + "\n"
              + BackupScope.label(scopeOf(plan.manifest))
              + ((!plan.warnings.isEmpty() || "PARTIAL".equals(plan.manifest.get("integrity")))
                  ? com.deepseekharness.app.util.UiText.choose(
                      "\n部分内容需确认，请查看备份清单。", "\nSome content needs review; see the backup manifest.")
                  : "");
      return new PreparedRestore(plain, operation, hash, plan, mapping, summary);
    } catch (Exception failure) {
      try {
        com.deepseekharness.app.backup.RestoreStaging.clearSensitivePlaintext(fs, operation);
        recordPrepared(fs, operation, "FAILED_RETAINED", "FAILED", safeError(failure));
      } catch (IOException retained) {
        failure.addSuppressed(retained);
      }
      throw failure;
    }
  }

  private static void recordPrepared(
      com.deepseekharness.app.backup.BackupFileSystem fs,
      File operation,
      String stage,
      String result,
      String failure)
      throws IOException {
    var record = new java.util.LinkedHashMap<String, Object>();
    record.put("version", 1L);
    record.put("id", operation.getName());
    record.put("busy", false);
    record.put("stage", stage);
    record.put("result", result);
    record.put("error", failure);
    record.put("artifact", "");
    record.put("entries", 0L);
    record.put("bytes", 0L);
    record.put("updatedAt", System.currentTimeMillis());
    fs.atomic(
        operation,
        "operation.json",
        com.deepseekharness.app.backup.BackupJson.write(record, 16384));
  }

  public static String restorePrepared(HarnessController controller, PreparedRestore prepared)
      throws Exception {
    return com.deepseekharness.app.core.MaintenanceCoordinator.exclusive(
        controller, () -> restoreWithinDataTask(controller, prepared));
  }

  public static String restoreFromBackup(Context ctx, HarnessController controller, Uri uri)
      throws Exception {
    try (PreparedRestore prepared = prepareRestore(ctx, controller, uri)) {
      return restorePrepared(controller, prepared);
    }
  }

  public static String restoreFromBackup(Context ctx, HarnessController controller, File file)
      throws Exception {
    return restoreFromBackup(ctx, controller, Uri.fromFile(file));
  }

  private static int scopeOf(java.util.Map<String, Object> manifest) {
    var scopes = new java.util.HashSet<String>();
    if (manifest.get("roots") instanceof java.util.List)
      for (Object value : (java.util.List<?>) manifest.get("roots"))
        if (value instanceof java.util.Map
            && ((java.util.Map<?, ?>) value).get("scope") instanceof String)
          scopes.add((String) ((java.util.Map<?, ?>) value).get("scope"));
    if (scopes.size() == 1
        && java.util.Set.of("sessions", "settings", "plugins").contains(scopes.iterator().next()))
      return BackupScope.fromId(scopes.iterator().next());
    return BackupScope.FULL;
  }

  public static String exportToDownloads(Context ctx, File source, String name) {
    try (com.deepseekharness.app.core.RuntimeTasks work =
        com.deepseekharness.app.core.RuntimeTasks.begin("数据维护")) {
      return DownloadsExport.write(ctx, source, name).uri.toString();
    } catch (Exception e) {
      error = safeError(e);
      return null;
    }
  }

  public static String safeError(Exception e) {
    String message =
        SensitiveData.redact(e.getMessage() == null ? e.toString() : e.getMessage()).trim();
    // Android/ROM 的 SecurityException 通常只有这句无上下文正文；收敛为
    // 稳定错误码后，维护页才能给出可执行入口，也不会把权限失败误显示成完成。
    if (message.equalsIgnoreCase("Permission denied")) message = "PERMISSION_DENIED";
    return message.length() < 800 ? message : message.substring(message.length() - 800);
  }
}
