package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class RuntimeEnginePolicyTest {
  @Test
  public void everyLegacyChoiceMigratesBeforeNewExplicitProotPersists() {
    for (Object value : new Object[] {null, "proot", "proroot", "bxroot", "", 42, false})
      assertEquals("bxroot", RuntimeEnginePolicy.requested(value, 171));
    assertEquals("proot", RuntimeEnginePolicy.requested("proot", 172));
    assertEquals("bxroot", RuntimeEnginePolicy.requested("proroot", 172));
  }

  @Test
  public void kernelFloorIsIndependentOfAndroidApiAndUnknownIsNotSupported() {
    for (String release : new String[] {"3.18.140", "4.4.302", "4.10.9", "unknown", ""})
      assertFalse(RuntimeEnginePolicy.bxrootKernel(release));
    for (String release : new String[] {"4.11.0", "4.19.0-vendor", "5.10.198-android", "6.18.33.2"})
      assertTrue(RuntimeEnginePolicy.bxrootKernel(release));
  }

  @Test
  public void genericCrashAndPluginFailureCannotBecomeEngineEvidence() {
    assertFalse(RuntimeEnginePolicy.engineFailure("node exited 139"));
    assertFalse(RuntimeEnginePolicy.engineFailure("PLUGIN_FAILED plugin X"));
    assertTrue(
        RuntimeEnginePolicy.engineFailure("DSHA_BXROOT_ENGINE_FAILURE: DSHA_BXROOT_UNSUPPORTED"));
  }

  @Test
  public void translatedUlxLoadErrorIsLimitedToReadOnlyQualification() {
    String output =
        "bxroot-ulx: 装载 /data/user/0/com.dsh.client/files/recovery/linux/ubuntu/lib/ld-linux-aarch64.so.1 失败: Permission denied\n";
    assertFalse(RuntimeEnginePolicy.engineFailure(output));
    assertTrue(RuntimeEnginePolicy.readOnlyProbeEngineFailure(output));
    for (String unrelated :
        new String[] {
          "node exited 126",
          "bxroot-ulx: 装载 relative 失败: Permission denied",
          "PLUGIN_FAILED 装载 /tmp/plugin 失败: unknown",
          "bxroot-ulx: 装载 /tmp/loader\n失败: unknown"
        }) {
      assertFalse(RuntimeEnginePolicy.readOnlyProbeEngineFailure(unrelated));
    }
  }

  @Test
  public void recoveryNodeQualificationNeverRequiresPythonOrPackageInstall() {
    var node = RuntimeCapabilityProbe.Kind.NODE;
    assertTrue(RuntimeCapabilityProbe.inputs(node, false).contains("/usr/local/bin/node"));
    assertFalse(RuntimeCapabilityProbe.inputs(node, false).contains("/usr/bin/python3"));
    assertThrows(IllegalArgumentException.class, () -> RuntimeCapabilityProbe.inputs(node, true));
    assertEquals("DSHA_ENGINE_NODE_CAPABILITY_READY", RuntimeCapabilityProbe.marker(node));
    assertTrue(
        RuntimeCapabilityProbe.inputs(RuntimeCapabilityProbe.Kind.PYTHON, false)
            .contains("/usr/bin/python3"));
  }

  @Test
  public void packageProbeDoesNotInstallAndRunsRealStaticCandidate() {
    String command = RuntimeCapabilityProbe.packages();
    assertTrue(command.contains("ldconfig.real"));
    assertTrue(command.contains("--help"));
    assertTrue(command.contains("execveat") && command.contains("statx"));
    assertFalse(command.contains("dpkg -i") || command.contains("apt install"));
  }

  @Test
  public void pythonProbeReportsReadOnlyStagesWithoutChangingSuccessMarker() {
    String command = RuntimeCapabilityProbe.command();
    String[] stages = {
      "SHELL",
      "PYTHON",
      "IMPORTS",
      "GLIBC",
      "STATX_BEGIN",
      "STATX_READY",
      "FORK_BEGIN",
      "EXECVEAT_CHILD"
    };
    int last = -1;
    for (String stage : stages) {
      int position = command.indexOf("DSHA_ENGINE_PROBE_STAGE=" + stage);
      assertTrue("stage ordering: " + stage, position > last);
      last = position;
    }
    assertTrue(command.indexOf("/usr/bin/python3") > command.indexOf("PROBE_STAGE=SHELL"));
    assertTrue(command.indexOf("DSHA_ENGINE_CAPABILITY_READY") > last);
    assertEquals(
        "DSHA_ENGINE_CAPABILITY_READY",
        RuntimeCapabilityProbe.marker(RuntimeCapabilityProbe.Kind.PYTHON));
    assertFalse(
        RuntimeEnginePolicy.readOnlyProbeEngineFailure("DSHA_ENGINE_PROBE_STAGE=STATX_BEGIN\n"));
  }
}
