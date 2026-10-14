package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.Ids;
import java.io.File;
import java.io.IOException;

/** 试运行记录归应用私有 files 根；候选 rootfs 的所在槽位不授予记录权限。 */
final class TrialPaths {
  private TrialPaths() {}

  static File home(File files) throws IOException {
    return new File(files.getCanonicalFile(), "runtime-trials");
  }

  static void requirePayload(File files, File payload) throws IOException {
    if (payload == null
        || !payload.getCanonicalFile().equals(payload.getAbsoluteFile())
        || !payload.getName().equals("payload")) throw new IOException("TRIAL_RECORD_DIRECTORY");
    File operation = payload.getParentFile();
    if (operation == null
        || !Ids.uuid(operation.getName())
        || !home(files).equals(operation.getParentFile()))
      throw new IOException("TRIAL_RECORD_DIRECTORY");
  }

  static void requireBinding(File files, File payload, String guest) throws IOException {
    requirePayload(files, payload);
    String expected =
        "/root/.dsha-runtime-trial-" + payload.getParentFile().getName().replace("-", "");
    if (!expected.equals(guest)) throw new IOException("TRIAL_DIRECTORY");
  }
}
