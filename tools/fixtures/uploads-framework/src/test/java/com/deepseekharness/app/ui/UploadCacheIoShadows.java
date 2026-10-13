package com.deepseekharness.app.ui;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.IdentityHashMap;
import java.util.Map;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowParcelFileDescriptor;

/**
 * Robolectric 默认 dup 会复制到别的临时文件，无法验证缓存输出资源作用域。
 * 本夹具只用宿主文件模拟打开/dup/关闭；不将它称为 Android NOFOLLOW 或 FD 数量验收。
 */
public final class UploadCacheIoShadows {
  private static final Map<FileDescriptor, RandomAccessFile> handles = new IdentityHashMap<>();
  private static final Map<FileDescriptor, File> paths = new IdentityHashMap<>();

  @Implements(Os.class)
  public static class CacheOs {
    @Implementation
    protected static synchronized FileDescriptor open(String path, int flags, int mode)
        throws ErrnoException {
      File file = new File(path);
      try {
        if ((flags & OsConstants.O_NOFOLLOW) != 0 && Files.isSymbolicLink(file.toPath()))
          throw new ErrnoException("open", OsConstants.ELOOP);
        if ((flags & OsConstants.O_EXCL) != 0) {
          if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new ErrnoException("open", OsConstants.EEXIST);
          Files.createFile(file.toPath());
        }
        RandomAccessFile handle = new RandomAccessFile(file, "rw");
        FileDescriptor fd = handle.getFD();
        handles.put(fd, handle);
        paths.put(fd, file);
        return fd;
      } catch (IOException error) {
        throw new ErrnoException("open", OsConstants.EIO, error);
      }
    }

    @Implementation
    protected static synchronized void close(FileDescriptor fd) throws ErrnoException {
      RandomAccessFile handle = handles.remove(fd);
      paths.remove(fd);
      if (handle != null)
        try {
          handle.close();
        } catch (IOException error) {
          throw new ErrnoException("close", OsConstants.EIO, error);
        }
    }
  }

  @Implements(ParcelFileDescriptor.class)
  public static class CacheDescriptor extends ShadowParcelFileDescriptor {
    @Implementation
    protected static ParcelFileDescriptor dup(FileDescriptor fd) throws IOException {
      File path = paths.get(fd);
      return path == null
          ? ShadowParcelFileDescriptor.dup(fd)
          : ParcelFileDescriptor.open(path, ParcelFileDescriptor.MODE_READ_WRITE);
    }
  }
}
