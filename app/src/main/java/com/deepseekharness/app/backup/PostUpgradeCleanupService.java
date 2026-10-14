package com.deepseekharness.app.backup;

import android.app.job.*;
import android.content.*;
import com.deepseekharness.app.BuildConfig;

/** 覆盖更新后的单次清理；运行中延后，绝不为清理终止用户任务。 */
public final class PostUpgradeCleanupService extends JobService {
  private static final int JOB = 9139, POLICY = 3;
  private static final WorkerQuiescence WORKERS = new WorkerQuiescence();

  public static void schedule(Context context) {
    var prefs = context.getSharedPreferences("dsha_storage_cleanup", MODE_PRIVATE);
    if (prefs.getInt("completedBuild", 0) >= BuildConfig.VERSION_CODE
        && prefs.getInt("policy", 0) >= POLICY) return;
    JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    if (scheduler == null) return;
    if (scheduler.getAllPendingJobs().stream().noneMatch(job -> job.getId() == JOB))
      scheduler.schedule(
          new JobInfo.Builder(JOB, new ComponentName(context, PostUpgradeCleanupService.class))
              .setMinimumLatency(30_000)
              .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
              .setPersisted(true)
              .build());
  }

  private void save(boolean success, String value, String detail) {
    synchronized (PostUpgradeCleanupService.class) {
      var edit = getSharedPreferences("dsha_storage_cleanup", MODE_PRIVATE).edit();
      if (success)
        edit.putInt("completedBuild", BuildConfig.VERSION_CODE)
            .putInt("policy", POLICY)
            .putLong("freedBytes", Long.parseLong(value))
            .putString("error", "")
            // 「删了什么/跳过什么/失败什么」跟随本任务既有的偏好通道留档，不新建渠道。
            .putString("detail", detail);
      else edit.putString("error", value);
      edit.commit();
    }
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    WorkerQuiescence.Lease lease = WORKERS.tryBegin();
    if (lease == null) return false;
    Thread worker =
        new Thread(
            () -> {
              try (WorkerQuiescence.Lease ignored = lease) {
                boolean retry = false;
                try {
                  var controller = com.deepseekharness.app.core.HarnessController.get(this);
                  if (!AutomaticBackups.idle(this) || !controller.isEnvironmentReady()) {
                    retry = true;
                    return;
                  }
                  var descriptor = controller.proot().installedRuntimeDescriptor();
                  if (!RuntimeDescriptor.healthy(
                      controller.proot().runtimeHealth(), descriptor.id())) {
                    retry = true;
                    return;
                  }
                  // 本任务没有 UI 侧诊断端口；明细跟 freedBytes 一起写进同一个偏好通道。
                  var report = StorageMaintenance.clean(this, null);
                  save(true, String.valueOf(report.bytes), report.detail);
                } catch (Exception error) {
                  save(false, NativeBackupJobs.code(error), "");
                } finally {
                  jobFinished(params, retry);
                }
              }
            },
            "post-upgrade-cleanup");
    try {
      worker.start();
      return true;
    } catch (RuntimeException | Error unavailable) {
      lease.close();
      save(false, "WORKER_UNAVAILABLE", "");
      return false;
    }
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return true;
  }
}
