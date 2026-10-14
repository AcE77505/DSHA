package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BxrootPrivateRootTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  /** 模拟 Android 授予的祖先别名，无需 Windows 创建链接权限。后代仍是真实临时目录。 */
  private static final class FrameworkFiles extends File {
    final File physical;
    int canonicalCalls;

    FrameworkFiles(File declared, File physical) {
      super(declared.getAbsolutePath());
      this.physical = physical;
    }

    @Override
    public File getCanonicalFile() {
      canonicalCalls++;
      return physical;
    }
  }

  private static final class Descendant extends File {
    Descendant(File root, String name) {
      super(root, name);
    }

    @Override
    public File getCanonicalFile() {
      throw new AssertionError("不能 canonical 任意 guest 后代");
    }
  }

  private static class AliasFs extends JvmBackupFileSystem {
    final File declared, physical;
    String rejectedLink;
    boolean differentAliasIdentity;
    File replacedIdentity;

    AliasFs(File declared, File physical) {
      this.declared = declared;
      this.physical = physical;
    }

    File map(File file) {
      String path = file.getAbsolutePath(), prefix = declared.getAbsolutePath();
      if (path.equals(prefix)) return physical;
      if (path.startsWith(prefix + File.separator))
        return new File(physical.getAbsolutePath() + path.substring(prefix.length()));
      return file;
    }

    @Override
    public Node stat(File file) throws IOException {
      File actual = map(file);
      if (rejectedLink != null) {
        File blocked = new File(physical, rejectedLink);
        if (actual.getPath().startsWith(blocked.getPath() + File.separator))
          throw new AssertionError("不能在被拒绝的链接下继续访问");
        if (actual.equals(blocked)) return new Node("LINK", "link", 1, 0, 1, 0700);
      }
      Node node = super.stat(actual);
      // Windows JDK 的 fileKey 可为空；通过窄接口提供 Android lstat 的 inode 变化。
      if (actual.equals(replacedIdentity))
        return new Node(
            node.type, node.key + "-replaced", node.size, node.modified, node.device, node.mode);
      if (differentAliasIdentity && file.getPath().startsWith(declared.getPath() + File.separator))
        return new Node(
            node.type, node.key + "-different", node.size, node.modified, node.device, node.mode);
      return node;
    }
  }

  private FrameworkFiles frameworkFiles() throws Exception {
    File physical = new File(temporary.newFolder("physical"), "files");
    Files.createDirectories(physical.toPath());
    File declared = new File(temporary.newFolder("framework-alias"), "files");
    return new FrameworkFiles(declared, physical.getCanonicalFile());
  }

  @Test
  public void systemAliasAndPhysicalSpellingResolveToTheSamePrivateRoot() throws Exception {
    FrameworkFiles files = frameworkFiles();
    File physicalRoot = new File(files.physical, "linux/ubuntu");
    Files.createDirectories(physicalRoot.toPath());
    var authority = new BxrootPrivateRoot(new AliasFs(files, files.physical), files);
    assertEquals(physicalRoot, authority.root(new Descendant(files, "linux/ubuntu")));
    assertEquals(physicalRoot, authority.root(new Descendant(files.physical, "linux/ubuntu")));
    authority.verify();
    assertEquals("仅解析框架授予的根一次", 1, files.canonicalCalls);
  }

  @Test
  public void coldManagedAndRecoveryRootsKeepTheirIndependentPhysicalLocation() throws Exception {
    FrameworkFiles files = frameworkFiles();
    var fs = new AliasFs(files, files.physical);
    var authority = new BxrootPrivateRoot(fs, files);
    for (String relative :
        List.of(
            "linux/ubuntu",
            "cold-install-operations/id/candidate/linux/ubuntu",
            "runtime-operations/id/candidate/linux/ubuntu",
            "recovery-capsules/id/linux/ubuntu")) {
      File expected = new File(files.physical, relative);
      Files.createDirectories(expected.toPath());
      assertEquals(expected, authority.root(new Descendant(files, relative)));
    }
    assertEquals(1, files.canonicalCalls);
  }

  @Test
  public void dataDomainAcceptsOnlyTheTwoSpellingsOfTheFrameworkFilesRoot() throws Exception {
    FrameworkFiles files = frameworkFiles();
    var authority = new BxrootPrivateRoot(new AliasFs(files, files.physical), files);
    assertEquals(files.physical, authority.dataDomain(files));
    assertEquals(files.physical, authority.dataDomain(files.physical));
    assertNull(authority.dataDomain(null));
    for (File bad :
        List.of(
            new File(files, "data"),
            new File(files, ".."),
            new File(files.getParentFile(), "files-other"),
            temporary.newFolder("external-domain")))
      assertThrows(IOException.class, () -> authority.dataDomain(bad));
  }

  @Test
  public void guestDirectoryLinksAreRejectedBeforeFollowingAnyDescendant() throws Exception {
    FrameworkFiles files = frameworkFiles();
    Files.createDirectories(new File(files.physical, "linux/ubuntu").toPath());
    for (String link : List.of("linux", "linux/ubuntu")) {
      var fs = new AliasFs(files, files.physical);
      fs.rejectedLink = link;
      var authority = new BxrootPrivateRoot(fs, files);
      assertThrows(IOException.class, () -> authority.root(new Descendant(files, "linux/ubuntu")));
    }
  }

  @Test
  public void traversalLookalikeAndOutsideRootsCannotGrantAuthority() throws Exception {
    FrameworkFiles files = frameworkFiles();
    var authority = new BxrootPrivateRoot(new AliasFs(files, files.physical), files);
    for (File bad :
        List.of(
            new Descendant(files, "linux/../ubuntu"),
            new Descendant(files, "linux/./ubuntu"),
            new Descendant(files, "../outside"),
            new File(files.getParentFile(), "files-other/linux/ubuntu"),
            new File(temporary.getRoot(), "outside"),
            files,
            new File("relative/linux/ubuntu")))
      assertThrows(IOException.class, () -> authority.root(bad));
    assertEquals(1, files.canonicalCalls);
  }

  @Test
  public void directoryIdentityMustAgreeAcrossBothRootSpellings() throws Exception {
    FrameworkFiles files = frameworkFiles();
    Files.createDirectories(new File(files.physical, "linux/ubuntu").toPath());
    var fs = new AliasFs(files, files.physical);
    var authority = new BxrootPrivateRoot(fs, files);
    fs.differentAliasIdentity = true;
    assertThrows(IOException.class, () -> authority.root(new Descendant(files, "linux/ubuntu")));
  }

  @Test
  public void verifiedDirectoriesAreRecheckedBeforeArgumentsCanBeUsed() throws Exception {
    FrameworkFiles files = frameworkFiles();
    File physicalRoot = new File(files.physical, "linux/ubuntu");
    Files.createDirectories(physicalRoot.toPath());
    var fs = new AliasFs(files, files.physical);
    var authority = new BxrootPrivateRoot(fs, files);
    authority.root(new Descendant(files, "linux/ubuntu"));
    fs.rejectedLink = "linux";
    assertThrows(IOException.class, authority::verify);
  }

  @Test
  public void replacingAValidatedDirectoryCannotReuseItsAuthority() throws Exception {
    FrameworkFiles files = frameworkFiles();
    File physicalRoot = new File(files.physical, "linux/ubuntu");
    Files.createDirectories(physicalRoot.toPath());
    var fs = new AliasFs(files, files.physical);
    var authority = new BxrootPrivateRoot(fs, files);
    authority.root(new Descendant(files, "linux/ubuntu"));
    File retained = new File(physicalRoot.getParentFile(), "ubuntu-retained");
    Files.move(physicalRoot.toPath(), retained.toPath());
    Files.createDirectory(physicalRoot.toPath());
    fs.replacedIdentity = physicalRoot;
    assertThrows(IOException.class, authority::verify);
    assertTrue(retained.isDirectory());
  }

  @Test
  public void frameworkFilesItselfCannotBeAnAppControlledLink() throws Exception {
    FrameworkFiles files = frameworkFiles();
    var fs =
        new AliasFs(files, files.physical) {
          @Override
          public BackupFileSystem.Node stat(File file) throws IOException {
            if (file.equals(files)) return new Node("LINK", "bad-files", 1, 0, 1, 0700);
            return super.stat(file);
          }
        };
    assertThrows(IOException.class, () -> new BxrootPrivateRoot(fs, files));
    assertEquals("files 末端链接必须在 canonical 之前拒绝", 0, files.canonicalCalls);
  }
}
