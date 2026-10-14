package com.deepseekharness.app.core;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Latest maintenance probe evidence lives outside rootfs and is never confused with a cold-install receipt. */
public final class InstallProbeDiagnostics {
  private static final String NAME = "install-probe-latest.txt";

  private InstallProbeDiagnostics() {}

  public static synchronized void record(Context context, String evidence) {
    try {
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      File folder =
          com.deepseekharness.app.util.ColdInstallPackages.privateFiles(
              fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
      String safe = com.deepseekharness.app.util.SensitiveData.redact(evidence);
      if (safe.length() > 56_000)
        safe =
            safe.substring(0, 2_000)
                + "\n[EARLIER_OUTPUT_OMITTED]\n"
                + safe.substring(safe.length() - 48_000);
      fs.atomic(folder, NAME, safe.getBytes(StandardCharsets.UTF_8));
    } catch (Exception failure) {
      DiagnosticLog.record(
          context, "INSTALL_PROBE_EVIDENCE_FAILED", failure.getClass().getSimpleName());
    }
  }

  public static synchronized String read(Context context) {
    try {
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      File folder =
          com.deepseekharness.app.util.ColdInstallPackages.privateFiles(
              fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
      File file = fs.child(folder, NAME);
      if (fs.stat(file).type.equals("MISSING")) {
        file = fs.child(folder, NAME + ".previous");
        if (fs.stat(file).type.equals("MISSING")) return "";
      }
      return new String(fs.small(file, 256 * 1024), StandardCharsets.UTF_8);
    } catch (Exception failure) {
      return "INSTALL_PROBE_EVIDENCE_UNAVAILABLE";
    }
  }
}
