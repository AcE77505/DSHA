package com.deepseekharness.app.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.FrameLayout;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.DocumentStreams;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    shadows = {UploadCacheIoShadows.CacheOs.class, UploadCacheIoShadows.CacheDescriptor.class})
public class UploadsFrameworkTest {
  Context context;
  SourceProvider provider;
  Uri uri = Uri.parse("content://dsha.uploads.test/private-name/secret-file");

  public static class SourceProvider extends ContentProvider {
    File file;
    int opens, missingOpens;
    int descriptorCloses;
    ParcelFileDescriptor lastDescriptor;
    Runnable afterOpen;
    long offset, length = -1;
    volatile boolean blockOpen;
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch attempted = new CountDownLatch(1);
    final CountDownLatch cancelled = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
      MatrixCursor result = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME});
      result.addRow(new Object[] {"selected.txt"});
      return result;
    }

    @Override
    public String getType(Uri u) {
      return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri u, ContentValues v) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri u, ContentValues v, String s, String[] a) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri u, String s, String[] a) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri u, String mode, CancellationSignal signal)
        throws FileNotFoundException {
      opens++;
      attempted.countDown();
      if (blockOpen) {
        signal.setOnCancelListener(
            () -> {
              cancelled.countDown();
              release.countDown();
            });
        entered.countDown();
        try {
          assertTrue(release.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        signal.throwIfCanceled();
      }
      if (opens <= missingOpens)
        throw new FileNotFoundException("content://private-provider/secret-name");
      if (afterOpen != null) afterOpen.run();
      lastDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
      return new AssetFileDescriptor(lastDescriptor, offset, length) {
        @Override
        public void close() throws IOException {
          descriptorCloses++;
          super.close();
        }
      };
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri u, String mode) throws FileNotFoundException {
      return openAssetFile(u, mode, new CancellationSignal());
    }
  }

  @Before
  public void setup() throws Exception {
    context = RuntimeEnvironment.getApplication();
    provider = new SourceProvider();
    provider.attachInfo(context, providerInfo());
    provider.file = File.createTempFile("dsha-upload-source-", ".bin");
    provider.file.deleteOnExit();
    Files.write(provider.file.toPath(), "prefixDATAtrailing".getBytes("UTF-8"));
    ShadowContentResolver.registerProviderInternal("dsha.uploads.test", provider);
  }

  private ProviderInfo providerInfo() {
    ProviderInfo info = new ProviderInfo();
    info.authority = "dsha.uploads.test";
    info.exported = true;
    return info;
  }

  static final class Counted extends View {
    int measures, layouts;

    Counted(Context context) {
      super(context);
    }

    @Override
    protected void onMeasure(int w, int h) {
      measures++;
      super.onMeasure(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
      layouts++;
    }
  }

  private void traverse(PictureInPictureFrame frame, int width, int height) {
    frame.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    frame.layout(0, 0, width, height);
  }

  @Test
  public void sameSizeNestedRequestCompletesAndIdleResizePreserveTheFrozenViewport() {
    PictureInPictureFrame frame = new PictureInPictureFrame(context);
    FrameLayout page = new FrameLayout(context);
    Counted child = new Counted(context);
    page.addView(child, new FrameLayout.LayoutParams(-1, -1));
    frame.addView(page);
    frame.restoreViewport(1440, 3016);
    frame.setCompact(true);
    traverse(frame, 432, 905);
    int measure = child.measures, layout = child.layouts;
    child.requestLayout();
    traverse(frame, 432, 905);
    assertEquals(measure + 1, child.measures);
    assertEquals(layout + 1, child.layouts);
    assertFalse(child.isLayoutRequested());
    assertFalse(page.isLayoutRequested());
    for (int i = 0; i < 5; i++) traverse(frame, 432, 905);
    assertEquals(measure + 1, child.measures);
    assertEquals(layout + 1, child.layouts);
    traverse(frame, 500, 900);
    assertEquals(measure + 1, child.measures);
    assertEquals(layout + 1, child.layouts);
    assertEquals(1440, page.getWidth());
    assertEquals(3016, page.getHeight());
    assertEquals(Math.min(500f / 1440, 900f / 3016), page.getScaleX(), 0.0001f);
    frame.setCompact(false);
    traverse(frame, 1440, 3016);
    assertEquals(1f, page.getScaleX(), 0);
    assertEquals(0f, page.getTranslationX(), 0);
    frame.setCompact(true);
    traverse(frame, 432, 905);
    child.requestLayout();
    traverse(frame, 432, 905);
    assertFalse(child.isLayoutRequested());
  }

  @Test
  public void realAssetDescriptorReadsOnlyTheDeclaredOffsetAndLength() throws Exception {
    provider.offset = 6;
    provider.length = 4;
    try (InputStream in =
        DocumentStreams.input(context.getContentResolver(), uri, new BackupControl(null))) {
      assertArrayEquals("DATA".getBytes("UTF-8"), in.readAllBytes());
    }
  }

  @Test
  public void cancellingDoesNotBlockTheCallerAndRejectsALateProviderResult() throws Exception {
    provider.blockOpen = true;
    BackupControl control = new BackupControl(null);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try (InputStream in =
                  DocumentStreams.input(context.getContentResolver(), uri, control)) {
                fail("unexpected stream");
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    worker.start();
    assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
    long start = System.nanoTime();
    control.cancel();
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
    // Provider 可忽略取消；不依赖 Robolectric Binder 能模拟跨进程取消，迟到结果仍必须关闭并拒绝。
    provider.release.countDown();
    worker.join(5000);
    assertFalse(worker.isAlive());
    assertTrue(failure.get() instanceof InterruptedIOException);
  }

  @Test
  public void optionalMetadataDoesNotRelaxStrictBackupQueries() throws Exception {
    SourceProvider empty =
        new SourceProvider() {
          @Override
          public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
            return null;
          }
        };
    empty.attachInfo(context, providerInfo());
    ShadowContentResolver.registerProviderInternal("dsha.uploads.test", empty);
    assertNull(
        DocumentStreams.optionalQuery(
            context.getContentResolver(),
            uri,
            new String[] {OpenableColumns.DISPLAY_NAME},
            new BackupControl(null)));
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                DocumentStreams.query(
                    context.getContentResolver(),
                    uri,
                    new String[] {OpenableColumns.DISPLAY_NAME},
                    new BackupControl(null)));
    assertEquals("SAF_UNREADABLE", error.getMessage());
  }

  @Test
  public void sourceOpenRetryCopiesTheExactAssetIntervalIntoAnOwnedBatch() throws Exception {
    provider.missingOpens = 1;
    provider.offset = 6;
    provider.length = 4;
    try (WebUploads.Session session = new WebUploads.Session(context.getCacheDir());
        WebUploads.Batch batch =
            session.copy(context, Arrays.asList(uri), new BackupControl(null))) {
      assertEquals(2, provider.opens);
      assertArrayEquals(
          "DATA".getBytes("UTF-8"), Files.readAllBytes(batch.files().get(0).toPath()));
      assertTrue(batch.commit());
      assertSourceDescriptorClosed();
    }
  }

  @Test
  public void permanentMissingSourceHasReadableFailureAndNoSensitiveDiagnostic() throws Exception {
    provider.missingOpens = 2;
    try (WebUploads.Session session = new WebUploads.Session(context.getCacheDir())) {
      IOException error =
          assertThrows(
              IOException.class,
              () -> session.copy(context, Arrays.asList(uri), new BackupControl(null)));
      assertTrue(error.getMessage().contains("暂不可读取"));
      assertEquals(2, provider.opens);
      for (ShadowLog.LogItem log : ShadowLog.getLogsForTag("DSHA")) {
        assertFalse(log.msg.contains("private-provider"));
        assertFalse(log.msg.contains("secret-name"));
        assertNull(log.throwable);
      }
    }
  }

  @Test
  public void outputOpenFailureClosesTheSourceDescriptor() throws Exception {
    try (WebUploads.Session session = new WebUploads.Session(context.getCacheDir())) {
      java.lang.reflect.Field field = WebUploads.Session.class.getDeclaredField("root");
      field.setAccessible(true);
      File root = (File) field.get(session);
      provider.afterOpen =
          () -> {
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
              java.nio.file.Path leaf =
                  paths
                      .filter(Files::isDirectory)
                      .filter(p -> p.getNameCount() == root.toPath().getNameCount() + 2)
                      .findFirst()
                      .get();
              Files.createFile(leaf.resolve("selected.txt"));
            } catch (IOException error) {
              throw new UncheckedIOException(error);
            }
          };
      IOException error =
          assertThrows(
              IOException.class,
              () -> session.copy(context, Arrays.asList(uri), new BackupControl(null)));
      assertEquals("UPLOAD_CACHE_FILE_UNSAFE", error.getMessage());
      assertEquals(1, provider.opens);
      assertSourceDescriptorClosed();
    }
  }

  private void assertSourceDescriptorClosed() {
    try {
      assertFalse(provider.lastDescriptor.getFileDescriptor().valid());
    } catch (IllegalStateException closed) {
      /* Robolectric 拒绝读取已关闭的描述符。 */
    }
  }

  @Test
  public void closingTheSessionDoesNotWaitForABlockedSourceAndRejectsItsLateResult()
      throws Exception {
    provider.blockOpen = true;
    WebUploads.Session session = new WebUploads.Session(context.getCacheDir());
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try (WebUploads.Batch batch =
                  session.copy(context, Arrays.asList(uri), new BackupControl(null))) {
                fail("unexpected batch");
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    worker.start();
    assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
    long start = System.nanoTime();
    session.close();
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
    provider.release.countDown();
    worker.join(5000);
    assertFalse(worker.isAlive());
    assertTrue(failure.get() instanceof InterruptedIOException);
  }

  @Test
  public void cancellingDuringTheRetryDelayDoesNotOpenTheSourceAgain() throws Exception {
    provider.missingOpens = 1;
    BackupControl control = new BackupControl(null);
    try (WebUploads.Session session = new WebUploads.Session(context.getCacheDir())) {
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread worker =
          new Thread(
              () -> {
                try (WebUploads.Batch batch = session.copy(context, Arrays.asList(uri), control)) {
                  fail("unexpected batch");
                } catch (Throwable error) {
                  failure.set(error);
                }
              });
      worker.start();
      assertTrue(provider.attempted.await(5, TimeUnit.SECONDS));
      Thread.sleep(75);
      control.cancel();
      worker.join(5000);
      assertFalse(worker.isAlive());
      assertEquals(1, provider.opens);
      assertTrue(failure.get() instanceof InterruptedIOException);
    }
  }

  @Test
  public void cancellingOneRequestPreservesPreviouslyAcceptedPageFiles() throws Exception {
    try (WebUploads.Session session = new WebUploads.Session(context.getCacheDir());
        WebUploads.Batch first =
            session.copy(context, Arrays.asList(uri), new BackupControl(null))) {
      assertTrue(first.commit());
      File accepted = first.files().get(0);
      BackupControl cancelled = new BackupControl(null);
      cancelled.cancel();
      assertThrows(
          InterruptedIOException.class, () -> session.copy(context, Arrays.asList(uri), cancelled));
      assertTrue(accepted.isFile());
      assertFalse(session.isClosed());
      assertEquals(1, provider.opens);
    }
  }
}
