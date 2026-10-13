package com.deepseekharness.app.backup;

import java.io.IOException;
import java.util.Map;

/** 拒绝条件不变；私有诊断调用方可保存 fstat 证据，界面仍只收到固定错误码。 */
public final class OwnershipCheckFailure extends IOException {
  public final Map<String, Object> diagnostic;

  public OwnershipCheckFailure(
      String logicalPath, long expectedUid, long actualUid, int mode, long device, long inode) {
    super("RETIRED_DIRECTORY_NOT_OWNED");
    diagnostic =
        Map.of(
            "schema",
            1L,
            "path",
            logicalPath,
            "expectedUid",
            expectedUid,
            "actualUid",
            actualUid,
            "mode",
            mode,
            "device",
            device,
            "inode",
            inode);
  }
}
