package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.util.BuiltinPlugins;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BuiltinDependencyPreparationTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static final class Links extends JvmBackupFileSystem {
    final Map<File, String> targets = new HashMap<>();
    int created;

    @Override
    public Node stat(File file) throws IOException {
      return targets.containsKey(file)
          ? new Node("LINK", "signed-link:" + file, 1, 0, 1, 0700)
          : super.stat(file);
    }

    @Override
    public String readLink(File file) throws IOException {
      return targets.containsKey(file) ? targets.get(file) : super.readLink(file);
    }

    @Override
    public void symlink(String target, File link) throws IOException {
      if (!stat(link).type.equals("MISSING")) throw new IOException("LINK_CONFLICT");
      targets.put(link, target);
      created++;
    }
  }

  private File preparedRoot() throws Exception {
    File root = temporary.newFolder();
    Files.createDirectories(
        new File(root, "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules").toPath());
    for (String name : BuiltinPlugins.SIGNED_BUILTINS)
      Files.createDirectories(new File(root, BuiltinPlugins.entityDir(name).substring(1)).toPath());
    return root;
  }

  @Test
  public void everySignedEntityIncludingInternalGuardReceivesItsLockedDependencyLink()
      throws Exception {
    File root = preparedRoot();
    Links fs = new Links();
    RuntimeTools.prepareBuiltinDependencies(fs, root);
    assertEquals(BuiltinPlugins.SIGNED_BUILTINS.size(), fs.created);
    for (String name : BuiltinPlugins.SIGNED_BUILTINS)
      assertEquals(
          "../../usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules",
          fs.targets.get(
              new File(root, BuiltinPlugins.entityDir(name).substring(1) + "/node_modules")));
    RuntimeTools.prepareBuiltinDependencies(fs, root);
    assertEquals("已核验的依赖链接不重复写入", BuiltinPlugins.SIGNED_BUILTINS.size(), fs.created);
  }

  @Test
  public void internalGuardDependencyConflictCannotBeOverwritten() throws Exception {
    File root = preparedRoot();
    File original = new File(root, "root/dsha-destructive-guard/node_modules/retained-user-file");
    Files.createDirectories(original.getParentFile().toPath());
    Files.writeString(original.toPath(), "preserve");
    IOException failure =
        assertThrows(
            IOException.class, () -> RuntimeTools.prepareBuiltinDependencies(new Links(), root));
    assertEquals("BUNDLED_MODULES_CONFLICT:dsh-destructive-guard", failure.getMessage());
    assertEquals("preserve", Files.readString(original.toPath()));
  }
}
