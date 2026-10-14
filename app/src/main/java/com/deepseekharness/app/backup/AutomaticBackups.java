package com.deepseekharness.app.backup;

import android.app.job.*;
import android.content.*;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.AutomaticBackupPolicy;
import java.io.*;
import java.util.*;

/** 自动备份只存个人数据，采用已有 v5 加密归档；本机密钥不参与系统迁移。 */
public final class AutomaticBackups {
  public static final int JOB = 9137, NOW = 9138;
  private static final WorkerQuiescence PRODUCERS = new WorkerQuiescence();

  private AutomaticBackups() {}

  public static android.content.SharedPreferences prefs(Context context) {
    var saved = context.getSharedPreferences("dsha_automatic_backup", Context.MODE_PRIVATE);
    retireLegacyResetHold(saved);
    return saved;
  }

  /** 旧格式化入口已退休；只移除它的偏好 hold，不清任务、密钥、副本或真实事务。 */
  static boolean retireLegacyResetHold(android.content.SharedPreferences saved) {
    return !saved.contains("factoryResetPending")
        || saved.edit().remove("factoryResetPending").commit();
  }

  public static boolean enabled(Context context) {
    return enabled(prefs(context));
  }

  static boolean enabled(android.content.SharedPreferences saved) {
    // 删除旧 hold 的 commit 失败也不能复活已退休的格式化策略；之后可幂等重试。
    retireLegacyResetHold(saved);
    return saved.getBoolean("enabled", true);
  }

  static WorkerQuiescence.Lease beginProducer() {
    return PRODUCERS.tryBegin();
  }

  static void recordError(Context context, String error) {
    synchronized (AutomaticBackups.class) {
      prefs(context).edit().putString("error", error == null ? "" : error).commit();
    }
  }

  public static void schedule(Context context) {
    JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    if (scheduler == null) return;
    var settings = prefs(context);
    if (!settings.contains("anchor"))
      settings.edit().putLong("anchor", System.currentTimeMillis()).commit();
    if (!enabled(context)) {
      scheduler.cancel(JOB);
      scheduler.cancel(NOW);
      return;
    }
    if (scheduler.getAllPendingJobs().stream().noneMatch(job -> job.getId() == JOB))
      scheduler.schedule(
          new JobInfo.Builder(JOB, new ComponentName(context, AutomaticBackupService.class))
              .setPersisted(true)
              .setPeriodic(15 * 60_000L)
              .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
              .build());
  }

  public static boolean due(Context context) {
    var p = prefs(context);
    return enabled(context)
        && AutomaticBackupPolicy.due(
            p.getString("mode", "daily"),
            p.getInt("hours", 24),
            p.getInt("minute", 180),
            System.currentTimeMillis(),
            p.getLong("anchor", System.currentTimeMillis()),
            p.getLong("last", 0),
            p.getLong("stopped", 0),
            java.time.ZoneId.systemDefault());
  }

  public static void stopped(Context context) {
    synchronized (AutomaticBackups.class) {
      prefs(context).edit().putLong("stopped", System.currentTimeMillis()).commit();
    }
    schedule(context);
    if ("stop".equals(prefs(context).getString("mode", "daily"))) request(context, false);
  }

  public static void request(Context context, boolean manual) {
    if (!enabled(context)) return;
    JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    if (scheduler == null) return;
    android.os.PersistableBundle extras = new android.os.PersistableBundle();
    extras.putBoolean("manual", manual);
    scheduler.schedule(
        new JobInfo.Builder(NOW, new ComponentName(context, AutomaticBackupService.class))
            .setExtras(extras)
            .setMinimumLatency(1500)
            .build());
  }

  public static boolean idle(Context context) throws IOException {
    HarnessController c = HarnessController.get(context);
    return c.config().isWelcomed()
        && !NativeBackupJobs.get(context).state().busy
        && !c.isStarting()
        && !c.isStopping()
        && c.getWebAuthUrl().isEmpty()
        && !com.deepseekharness.app.core.RuntimeTasks.isBusy()
        && !com.deepseekharness.app.backup.HostMaintenancePending.blocked(context.getFilesDir())
        && c.isWebStoppedForMaintenance();
  }

  static boolean idleForOwner(Context context) throws IOException {
    HarnessController c = HarnessController.get(context);
    return !c.isStarting()
        && !c.isStopping()
        && c.getWebAuthUrl().isEmpty()
        && !com.deepseekharness.app.core.RuntimeTasks.hasOtherTasks()
        && c.isWebStoppedForMaintenance();
  }

  public static synchronized char[] password(Context context) throws IOException {
    var fs = new AndroidBackupFileSystem();
    File root = context.getNoBackupFilesDir().getCanonicalFile(),
        file = new File(root, "automatic-backup-key");
    var vault = new com.deepseekharness.app.data.KeyVault(context);
    if (fs.stat(file).type.equals("FILE"))
      return vault
          .decryptChecked(new String(fs.small(file, 8192), java.nio.charset.StandardCharsets.UTF_8))
          .toCharArray();
    if (!fs.stat(file).type.equals("MISSING")) throw new IOException("AUTOMATIC_KEY_UNAVAILABLE");
    for (var copy : NativeBackupJobs.get(context).verifiedCopies().valid)
      if (Boolean.TRUE.equals(copy.metadata.get("automatic")))
        throw new IOException("AUTOMATIC_KEY_MISSING");
    byte[] random = new byte[32];
    new java.security.SecureRandom().nextBytes(random);
    String plain =
        android.util.Base64.encodeToString(
            random, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
    String encrypted = vault.encrypt(plain);
    if (encrypted.isEmpty() || !plain.equals(vault.decryptChecked(encrypted)))
      throw new IOException("AUTOMATIC_KEY_UNAVAILABLE");
    fs.atomic(root, file.getName(), encrypted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return vault
        .decryptChecked(new String(fs.small(file, 8192), java.nio.charset.StandardCharsets.UTF_8))
        .toCharArray();
  }

  public static void completed(Context context, String id) {
    synchronized (AutomaticBackups.class) {
      prefs(context)
          .edit()
          .putLong("last", System.currentTimeMillis())
          .putString("lastId", id)
          .putString("error", "")
          .commit();
    }
  }

  /** 只轮换明确标记、已验证的自动产物，手动副本及未知记录不删除。 */
  public static void prune(Context context, BackupControl control) throws IOException {
    var fs = new AndroidBackupFileSystem();
    AutomaticBackupPruner.prune(fs, NativeBackupJobs.get(context).verifiedCopies().valid, control);
  }
}
