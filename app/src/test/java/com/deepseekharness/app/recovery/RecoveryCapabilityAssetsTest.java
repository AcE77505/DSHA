package com.deepseekharness.app.recovery;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.util.RuntimeCapabilityProbe;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.junit.Assume;
import org.junit.Test;

/** 用实际生成的固定应急证明检查引擎前置条件，不从正式已解压环境取文件。 */
public class RecoveryCapabilityAssetsTest {
  private Map<String, Object> descriptor() throws Exception {
    File assets = new File(System.getProperty("dsha.assetsDir", "src/main/assets"));
    File app = assets.getAbsoluteFile().getParentFile().getParentFile().getParentFile();
    File proof = new File(app, "build/generated/recoveryAssets/recovery-runtime.json");
    Assume.assumeTrue("独立应急资产尚未生成；正式资产门禁必须执行本检查", proof.isFile());
    return BackupJson.read(Files.readAllBytes(proof.toPath()), 8 * 1024 * 1024);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> rows(Map<String, Object> proof, String key) {
    Map<String, Map<String, Object>> result = new HashMap<>();
    for (Object item : (List<?>) proof.get(key)) {
      Map<String, Object> row = (Map<String, Object>) item;
      assertNull("证明不能包含重复路径", result.put((String) row.get("path"), row));
    }
    return result;
  }

  private static String resolve(String guest, Map<String, Map<String, Object>> links) {
    String path = guest.substring(1);
    for (int followed = 0; followed < 40; followed++) {
      boolean changed = false;
      String[] parts = path.split("/");
      String prefix = "";
      for (String part : parts) {
        prefix = prefix.isEmpty() ? part : prefix + "/" + part;
        Map<String, Object> link = links.get(prefix);
        if (link == null) continue;
        path = link.get("target") + path.substring(prefix.length());
        changed = true;
        break;
      }
      if (!changed) return path;
    }
    throw new AssertionError("应急证明中的链接无法解析: " + guest);
  }

  @Test
  public void lockedCapsuleContainsEveryActualNodeQualificationInput() throws Exception {
    Map<String, Object> proof = descriptor();
    var files = rows(proof, "files");
    var links = rows(proof, "links");
    for (String input : RuntimeCapabilityProbe.inputs(RuntimeCapabilityProbe.Kind.NODE, false)) {
      Map<String, Object> file = files.get(resolve(input, links));
      assertNotNull("固定应急舱缺少资格输入: " + input, file);
      assertTrue("资格输入必须非空: " + input, ((Number) file.get("bytes")).longValue() > 0);
      assertTrue(((String) file.get("sha256")).matches("[a-f0-9]{64}"));
    }
  }

  @Test
  public void lockedRecoveryRemainsUsableWithoutFormalPythonOverlay() throws Exception {
    Map<String, Object> proof = descriptor();
    var files = rows(proof, "files");
    var links = rows(proof, "links");
    assertFalse("历史固定应急归档不能被正式 Python 覆盖层替换", files.containsKey("usr/bin/python3.12"));
    assertFalse(files.containsKey(resolve("/usr/bin/python3", links)));
    assertTrue(files.containsKey(resolve("/usr/local/bin/node", links)));
    assertFalse(
        RuntimeCapabilityProbe.inputs(RuntimeCapabilityProbe.Kind.NODE, false)
            .contains("/usr/bin/python3"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RuntimeCapabilityProbe.inputs(RuntimeCapabilityProbe.Kind.NODE, true));
  }
}
