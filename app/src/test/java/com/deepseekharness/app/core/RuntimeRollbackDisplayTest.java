package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.ManagedRuntimeLayout;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RuntimeRollbackDisplayTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final BackupFileSystem fs = new JvmBackupFileSystem();
  private static final String RUNTIME = ManagedRuntimeLayout.ROOT + ManagedRuntimeLayout.DSH;

  private RuntimeDescriptor descriptor() throws Exception {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("version", 1L);
    value.put("baseVersion", "10");
    value.put("dshVersion", "0.2.0-rc.2");
    value.put("launcherContract", "DSHA_ARM64_V2");
    value.put("bridgeProtocol", 2L);
    value.put("dataRead", List.of("dsh-v3"));
    value.put("dataWrite", "dsh-v3");
    value.put("inputs", Map.of("runtime", "a".repeat(64)));
    value.put("runtimeId", RuntimeDescriptor.calculateId(value));
    return new RuntimeDescriptor(value);
  }

  private ManagedRuntimeTransaction failed(boolean mayRecover) throws Exception {
    File files = temporary.getRoot();
    var task = ManagedRuntimeTransaction.create(fs, files);
    File entry = new File(task.stage(), RUNTIME + "/lib/bin.js");
    Files.createDirectories(entry.getParentFile().toPath());
    Files.writeString(entry.toPath(), "candidate");
    task.prepare(List.of(RUNTIME), descriptor(), null, null, new BackupControl(null));
    assertThrows(
        IOException.class,
        () ->
            task.commit(
                new BackupControl(null),
                () -> {
                  throw new IOException("RUNTIME_CAPABILITY_FAILED_NO_REPLAY:132");
                },
                () -> mayRecover));
    return task;
  }

  private String detail(File operation, boolean english) {
    return (english
            ? "Runtime update did not complete; the previous runtime and personal data were retained:"
            : "运行时更新未完成，原运行时及个人数据已保留：")
        + "\n"
        + operation.getAbsolutePath()
        + "\nRUNTIME_CAPABILITY_UNCONFIRMED";
  }

  @Test
  public void aClosedFailureDisplaysOnlyItsOwnRecordedRollbackWithoutWriting() throws Exception {
    var task = failed(true);
    BackupFileSystem readOnly =
        (BackupFileSystem)
            Proxy.newProxyInstance(
                BackupFileSystem.class.getClassLoader(),
                new Class[] {BackupFileSystem.class},
                (proxy, method, args) -> {
                  if (!List.of("stat", "child", "small", "read", "readLink", "list")
                      .contains(method.getName()))
                    throw new AssertionError("display must be read-only: " + method.getName());
                  try {
                    return method.invoke(fs, args);
                  } catch (InvocationTargetException error) {
                    throw error.getCause();
                  }
                });
    assertTrue(
        EnvironmentMaintenance.runtimeRollbackRecorded(
            readOnly, temporary.getRoot(), detail(task.directory(), false)));
    assertTrue(
        EnvironmentMaintenance.runtimeRollbackRecorded(
            readOnly, temporary.getRoot(), detail(task.directory(), true)));
  }

  @Test
  public void noPendingOperationCannotStandInForAMissingOrDifferentRollback() throws Exception {
    var previous = failed(true);
    var current = failed(false);
    assertTrue(
        EnvironmentMaintenance.runtimeRollbackRecorded(
            fs, temporary.getRoot(), detail(previous.directory(), false)));
    assertFalse(
        EnvironmentMaintenance.runtimeRollbackRecorded(
            fs, temporary.getRoot(), detail(current.directory(), false)));
    assertFalse(
        EnvironmentMaintenance.runtimeRollbackRecorded(
            fs,
            temporary.getRoot(),
            detail(
                new File(
                    current.directory().getParentFile(), java.util.UUID.randomUUID().toString()),
                false)));
    assertFalse(
        EnvironmentMaintenance.runtimeRollbackRecorded(fs, temporary.getRoot(), "pending=false"));
    assertFalse(
        EnvironmentMaintenance.runtimeRollbackRecorded(
            fs,
            temporary.getRoot(),
            "unrelated failure\n" + previous.directory() + "\nRUNTIME_CAPABILITY_UNCONFIRMED"));
  }

  @Test
  public void incompleteOrContradictoryMarkersNeverClaimRollback() throws Exception {
    var task = failed(true);
    String failure = detail(task.directory(), false);
    File marker = new File(task.directory(), "rolled-back");
    Files.writeString(marker.toPath(), "another operation\nrolled-back\n");
    assertFalse(EnvironmentMaintenance.runtimeRollbackRecorded(fs, temporary.getRoot(), failure));
    Files.writeString(marker.toPath(), task.directory().getName() + "\nrolled-back\n");
    TransactionMarkers.write(fs, task.directory(), "finalized", "TEST_MARKER");
    assertFalse(EnvironmentMaintenance.runtimeRollbackRecorded(fs, temporary.getRoot(), failure));
  }

  @Test
  public void matchingMarkerWithoutTheSameJournalIdentityIsUnknown() throws Exception {
    var task = failed(true);
    File plan = new File(task.directory(), "plan.json");
    Map<String, Object> value =
        BackupJson.read(Files.readAllBytes(plan.toPath()), BackupLimits.MANIFEST);
    value.put("id", java.util.UUID.randomUUID().toString());
    Files.write(plan.toPath(), BackupJson.write(value, BackupLimits.MANIFEST));
    assertFalse(
        EnvironmentMaintenance.runtimeRollbackRecorded(
            fs, temporary.getRoot(), detail(task.directory(), false)));
    value.put("id", task.directory().getName());
    value.put("roots", List.of());
    Files.write(plan.toPath(), BackupJson.write(value, BackupLimits.MANIFEST));
    assertFalse(
        EnvironmentMaintenance.runtimeRollbackRecorded(
            fs, temporary.getRoot(), detail(task.directory(), false)));
  }

  @Test
  public void outsideOrTraversedPathsCannotBorrowARecordedRollback() throws Exception {
    var task = failed(true);
    assertFalse(
        EnvironmentMaintenance.runtimeRollbackRecorded(
            fs,
            temporary.getRoot(),
            detail(new File(task.directory(), "../" + task.directory().getName()), false)));
    assertFalse(
        EnvironmentMaintenance.runtimeRollbackRecorded(
            fs, temporary.newFolder(), detail(task.directory(), false)));
  }
}
