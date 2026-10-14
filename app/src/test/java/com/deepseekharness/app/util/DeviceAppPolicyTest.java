package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceAppPolicyTest {
  @Test
  public void privilegedInventoryMustContainTheActualCallingUid() {
    String users = "package:example.app uid:1012345\npackage:com.dsh.client uid:1012346\n";
    String systems = "package:android uid:1001000\n";
    assertNotNull(DeviceAppPolicy.snapshot(users, systems, "com.dsh.client", 10, 1012346));
    assertThrows(
        IllegalArgumentException.class,
        () -> DeviceAppPolicy.snapshot(users, systems, "com.dsh.client", 10, 1012347));
    assertThrows(
        IllegalArgumentException.class,
        () -> DeviceAppPolicy.snapshot(users, systems, "com.dsh.client", 10, 12346));
  }

  @Test
  public void explicitCurrentUserCannotStopOtherProfilesOrAcceptContaminatedInventory() {
    var apps =
        DeviceAppPolicy.snapshot(
            "package:example.app uid:1012345\npackage:com.dsh.client uid:1012346\n",
            "package:android uid:1001000\n",
            "com.dsh.client",
            10);
    assertEquals(
        java.util.List.of("example.app"),
        apps.targets(DeviceShellPolicy.inspect("kill 42"), "PID UID NAME\n42 1012345 example.app"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apps.targets(
                DeviceShellPolicy.inspect("kill 42"), "PID UID NAME\n42 12345 example.app"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceAppPolicy.snapshot(
                "package:example.app uid:12345\npackage:com.dsh.client uid:1012346",
                "package:android uid:1001000",
                "com.dsh.client",
                10));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DeviceAppPolicy.snapshot(
                "package:com.dsh.client uid:1012346\nError: user 999 unavailable",
                "package:android uid:1001000",
                "com.dsh.client",
                10));
  }

  @org.junit.Test
  public void clonedAppsResolveAllUidsAndProtectSystemProfiles() {
    DeviceAppPolicy.Snapshot snapshot =
        DeviceAppPolicy.snapshot(
            "package:example.app uid:12345,99912345\npackage:com.dsh.clienu uid:13333,99913333\n",
            "package:android uid:1000,99901000\n",
            "com.dsh.clienu");
    org.junit.Assert.assertEquals(
        java.util.Collections.singletonList("example.app"),
        snapshot.targets(
            DeviceShellPolicy.inspect("kill 42"), "PID UID NAME\n42 99912345 example.app\n"));
    org.junit.Assert.assertTrue(snapshot.grouped().contains("uid=12345,99912345"));
    try {
      snapshot.targets(
          DeviceShellPolicy.inspect("kill 42 43"),
          "PID UID NAME\n42 99912345 example.app\n43 99901000 system_server\n");
      org.junit.Assert.fail();
    } catch (IllegalArgumentException expected) {
    }
    DeviceAppPolicy.Snapshot shared =
        DeviceAppPolicy.snapshot(
            "package:example.shared uid:22222,99901000\npackage:com.dsh.clienu uid:13333\n",
            "package:android uid:1000,99901000\n",
            "com.dsh.clienu");
    try {
      shared.targets(DeviceShellPolicy.inspect("am force-stop example.shared"), "");
      org.junit.Assert.fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  private static final String USERS =
      "package:example.app uid:10123\npackage:other.app uid:10124\npackage:com.dsh.client uid:10125\n";
  private static final String SYSTEMS =
      "package:android uid:1000\npackage:com.android.systemui uid:10101\n";

  @Test
  public void groupsAreCompleteAndOnlyUserTargetsPass() {
    DeviceAppPolicy.Snapshot apps = DeviceAppPolicy.snapshot(USERS, SYSTEMS, "com.dsh.client");
    assertEquals(5, apps.apps.size());
    assertTrue(apps.grouped().contains("[用户应用]"));
    assertTrue(apps.grouped().contains("[系统应用]"));
    assertEquals(
        java.util.Arrays.asList("example.app"),
        apps.targets(DeviceShellPolicy.inspect("am force-stop example.app"), ""));
    for (String name :
        new String[] {"android", "com.android.systemui", "com.dsh.client", "absent.app"}) {
      try {
        apps.targets(DeviceShellPolicy.inspect("am force-stop " + name), "");
        fail(name);
      } catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test
  public void pidIsMappedToOneUserPackageInsteadOfSignalledDirectly() {
    DeviceAppPolicy.Snapshot apps = DeviceAppPolicy.snapshot(USERS, SYSTEMS, "com.dsh.client");
    String processes =
        "PID UID NAME\n1 0 init\n1234 1000 system_server\n1235 10123 example.app:worker\n1236 10125 libproroot-bridge.so\n";
    assertEquals(
        java.util.Arrays.asList("example.app"),
        apps.targets(DeviceShellPolicy.inspect("kill -9 1235"), processes));
    for (String target : new String[] {"1234", "1236", "9999", "1235 1234"}) {
      try {
        apps.targets(DeviceShellPolicy.inspect("kill -9 " + target), processes);
        fail(target);
      } catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test
  public void systemSharedUidAndMalformedInventoryDeny() {
    DeviceAppPolicy.Snapshot apps =
        DeviceAppPolicy.snapshot(
            USERS + "package:shared.user uid:10101\n", SYSTEMS, "com.dsh.client");
    try {
      apps.targets(DeviceShellPolicy.inspect("am force-stop shared.user"), "");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    for (String output :
        new String[] {"", "garbage", "package:android uid:0\npackage:android uid:1"}) {
      try {
        DeviceAppPolicy.snapshot(USERS, output, "com.dsh.client");
        fail(output);
      } catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test
  public void ambiguousSharedUidNeedsPackageName() {
    DeviceAppPolicy.Snapshot apps =
        DeviceAppPolicy.snapshot(
            USERS + "package:shared.user uid:10123\n", SYSTEMS, "com.dsh.client");
    try {
      apps.targets(DeviceShellPolicy.inspect("kill 1235"), "PID UID NAME\n1235 10123 worker");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void cloneSelfAndAllItsSharedUidsAreProtected() {
    var apps =
        DeviceAppPolicy.snapshot(
            "package:com.dsh.clienu uid:12345,99912345\npackage:shared.app uid:99912345\npackage:user.app uid:22222\n",
            SYSTEMS,
            "com.dsh.clienu");
    for (String command :
        new String[] {"am force-stop com.dsh.clienu", "am force-stop shared.app", "kill 42"})
      assertThrows(
          IllegalArgumentException.class,
          () ->
              apps.targets(
                  DeviceShellPolicy.inspect(command), "PID UID NAME\n42 99912345 cloned-worker\n"));
    assertEquals(
        java.util.List.of("user.app"),
        apps.targets(DeviceShellPolicy.inspect("am force-stop user.app"), ""));
    assertThrows(IllegalArgumentException.class, () -> DeviceAppPolicy.snapshot(USERS, SYSTEMS));
    assertThrows(
        IllegalArgumentException.class,
        () -> DeviceAppPolicy.snapshot(USERS, SYSTEMS, "com.dsh.clienu"));
  }
}
