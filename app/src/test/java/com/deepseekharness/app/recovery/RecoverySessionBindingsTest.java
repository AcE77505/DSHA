package com.deepseekharness.app.recovery;

import static org.junit.Assert.*;

import com.deepseekharness.app.runtime.ContainerRuntime;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RecoverySessionBindingsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static List<String> bindings(List<String> argv) {
    List<String> result = new ArrayList<>();
    for (int i = 0; i + 1 < argv.size(); i++)
      if (argv.get(i).equals("-b")) result.add(argv.get(++i));
    return result;
  }

  @Test
  public void independentHomeAndTmpFitTheActualNativeBoundedTable() throws Exception {
    List<String> argv = new ArrayList<>(List.of("bxroot", "-r", "/private/capsule/linux/ubuntu"));
    // 包含全部共享入口，覆盖真实 Android 上所有平台路径都存在时的最大基础清单。
    for (ContainerRuntime.Bind bind : ContainerRuntime.binds())
      argv.addAll(List.of("-b", bind.host() + ":" + bind.guest()));
    String l2s = "/private/capsule/linux/ubuntu/.l2s";
    argv.addAll(List.of("-b", l2s + ":" + l2s));
    File home = temporary.newFolder("session-home"), tmp = temporary.newFolder("session-tmp");
    RecoveryRuntime.appendSessionBindings(argv, home, tmp);
    List<String> mounted = bindings(argv);
    assertEquals(10, mounted.size());
    // 锁定原生 preload.c 的 MAX_BINDS=16；HOME 不能再被排到该表之外。
    List<String> visible = mounted.subList(0, Math.min(16, mounted.size()));
    assertTrue(visible.contains(home.getAbsolutePath() + ":/root"));
    assertTrue(visible.contains(tmp.getAbsolutePath() + ":/tmp"));
    assertEquals(1, mounted.stream().filter(value -> value.equals(l2s + ":" + l2s)).count());
    assertEquals(1, mounted.stream().filter(value -> value.equals("/proc:/proc")).count());
    assertFalse(mounted.stream().anyMatch(value -> value.startsWith("/storage/emulated/0:")));
  }

  @Test
  public void sessionBindingsPreserveTheSelectedContainersPlatformSet() throws Exception {
    List<String> argv = new ArrayList<>(List.of("proot", "-b", "/dev:/dev"));
    File home = temporary.newFolder("home"), tmp = temporary.newFolder("tmp");
    RecoveryRuntime.appendSessionBindings(argv, home, tmp);
    assertEquals(
        List.of("/dev:/dev", home.getAbsolutePath() + ":/root", tmp.getAbsolutePath() + ":/tmp"),
        bindings(argv));
  }

  @Test
  public void preparedProfileRemainsInTheIndependentSessionHome() throws Exception {
    File home = temporary.newFolder("independent-home"),
        tmp = temporary.newFolder("independent-tmp");
    String profile = "dsha-emergency-" + "a".repeat(32);
    File manifest = new File(home, ".dsh/profiles/" + profile + "/package.json");
    Files.createDirectories(manifest.getParentFile().toPath());
    String content =
        Files.readString(
                Path.of(
                    System.getProperty("dsha.assetsDir", "src/main/assets"),
                    "recovery-profile-package.json"))
            .replace("@PROFILE@", profile);
    Files.writeString(manifest.toPath(), content);
    List<String> argv = new ArrayList<>(List.of("bxroot", "-r", "/private/capsule/linux/ubuntu"));
    RecoveryRuntime.appendSessionBindings(argv, home, tmp);
    String homeBind =
        bindings(argv).stream().filter(value -> value.endsWith(":/root")).findFirst().orElseThrow();
    File actualHome = new File(homeBind.substring(0, homeBind.length() - ":/root".length()));
    assertEquals(
        content,
        Files.readString(
            new File(actualHome, ".dsh/profiles/" + profile + "/package.json").toPath()));
    assertEquals(home, actualHome);
  }
}
