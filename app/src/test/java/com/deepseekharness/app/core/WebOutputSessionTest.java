package com.deepseekharness.app.core;

import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class WebOutputSessionTest {
  static final class Child extends Process {
    final InputStream input;
    final CountDownLatch exit = new CountDownLatch(1);

    Child(String text) {
      this(new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    Child(InputStream input) {
      this.input = input;
    }

    public InputStream getInputStream() {
      return input;
    }

    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    public int waitFor() throws InterruptedException {
      exit.await();
      return 7;
    }

    public int exitValue() {
      if (exit.getCount() != 0) throw new IllegalThreadStateException();
      return 7;
    }

    public void destroy() {
      exit.countDown();
    }
  }

  private static class Chunks extends InputStream {
    final byte[][] chunks;
    int chunk, offset;

    Chunks(String... values) {
      chunks = new byte[values.length][];
      for (int i = 0; i < values.length; i++)
        chunks[i] = values[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    void beforeChunk(int index) throws IOException {}

    @Override
    public int read(byte[] data, int start, int count) throws IOException {
      if (chunk == chunks.length) return -1;
      if (offset == 0) beforeChunk(chunk);
      int copied = Math.min(count, chunks[chunk].length - offset);
      System.arraycopy(chunks[chunk], offset, data, start, copied);
      offset += copied;
      if (offset == chunks[chunk].length) {
        chunk++;
        offset = 0;
      }
      return copied;
    }

    @Override
    public int read() {
      throw new AssertionError("output reader must use bounded bulk reads");
    }
  }

  private static final class PausedChunks extends Chunks {
    final CountDownLatch awaitingSecond = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    PausedChunks(String first, String second) {
      super(first, second);
    }

    @Override
    void beforeChunk(int index) throws IOException {
      if (index != 1) return;
      awaitingSecond.countDown();
      try {
        if (!release.await(2, TimeUnit.SECONDS))
          throw new IOException("TEST_OUTPUT_RELEASE_TIMEOUT");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("TEST_OUTPUT_INTERRUPTED");
      }
    }
  }

  private static String officialUrl() {
    return "http://127.0.0.1:45678/?token=" + "a".repeat(43);
  }

  private static Events drainDynamic(InputStream input) {
    Child child = new Child(input);
    child.exit.countDown();
    Events events = new Events();
    new WebOutputSession(0).drain(child, events);
    return events;
  }

  @Test
  public void anExtendedTokenSplitAfterItsFirst43CharactersNeverPublishesAuthentication() {
    String url = officialUrl();
    Events events = drainDynamic(new Chunks("dsh web: " + url, "a\n"));
    assertEquals("", events.auth);
    assertFalse(events.output.toString().contains("a".repeat(43)));
  }

  @Test
  public void aCorrectSplitStartupLineWaitsForLfBeforePublishing() throws Exception {
    String url = officialUrl();
    PausedChunks input = new PausedChunks("dsh web: " + url, "\n");
    Child child = new Child(input);
    child.exit.countDown();
    Events events = new Events();
    Thread reader = new Thread(() -> new WebOutputSession(0).drain(child, events));
    reader.start();
    try {
      assertTrue(input.awaitingSecond.await(2, TimeUnit.SECONDS));
      assertEquals("", events.auth);
    } finally {
      input.release.countDown();
      reader.join(2000);
    }
    assertFalse(reader.isAlive());
    assertEquals(url, events.auth);
  }

  @Test
  public void crLfMayCrossChunksButCrAloneDoesNotCompleteTheStartupLine() throws Exception {
    String url = officialUrl();
    PausedChunks input = new PausedChunks("dsh web: " + url + "\r", "\n");
    Child child = new Child(input);
    child.exit.countDown();
    Events events = new Events();
    Thread reader = new Thread(() -> new WebOutputSession(0).drain(child, events));
    reader.start();
    try {
      assertTrue(input.awaitingSecond.await(2, TimeUnit.SECONDS));
      assertEquals("", events.auth);
    } finally {
      input.release.countDown();
      reader.join(2000);
    }
    assertFalse(reader.isAlive());
    assertEquals(url, events.auth);
  }

  @Test
  public void eofCompletesOneUnterminatedValidLineButNeverAnExtendedToken() {
    String url = officialUrl();
    assertEquals(url, drainDynamic(new Chunks("dsh web: ", url)).auth);
    assertEquals("", drainDynamic(new Chunks("dsh web: " + url, "a")).auth);
  }

  @Test
  public void overlongStartupLineIsDiscardedWholeAndTheNextLineCanAuthenticate() {
    String url = officialUrl();
    String overlong = "dsh web: " + url + " " + "x".repeat(65_537) + "\n";
    assertEquals("", drainDynamic(new Chunks(overlong)).auth);
    Events recovered = drainDynamic(new Chunks(overlong, "dsh web: " + url + "\n"));
    assertEquals(url, recovered.auth);
    assertTrue(
        recovered
            .output
            .toString()
            .contains(com.deepseekharness.app.util.UiText.text("[已略过超长输出]")));
  }

  @Test
  public void truncatingALogWindowMustNotInventAnOfficialLineStart() {
    String header = "dsh web: " + officialUrl();
    // 原始物理行有普通日志前缀；旧64,384字滚动窗口恰好截掉它后不能变成官方行。
    String tail = header + " " + "x".repeat(64_384 - header.length() - 2) + "\n";
    assertEquals("", drainDynamic(new Chunks("prefix: " + tail)).auth);
  }

  static class Events implements WebOutputSession.Events {
    volatile boolean current = true;
    volatile String auth = "";
    volatile int code = -1;
    final StringBuilder output = new StringBuilder();

    public boolean current() {
      return current;
    }

    public void output(String s) {
      output.append(s);
    }

    public boolean authenticated() {
      return !auth.isEmpty();
    }

    public void authentication(String url) {
      auth = url;
    }

    public void readFailure(IOException e) {
      throw new AssertionError(e);
    }

    public void exited(int value) {
      code = value;
    }
  }

  @Test
  public void pipeEofDoesNotReportProcessExitAndTokenNeverEntersOutput() throws Exception {
    String token = "a".repeat(43), url = "http://127.0.0.1:45678/?token=" + token;
    Child process = new Child("dsh web: " + url + "\n");
    Events events = new Events();
    Thread thread = new Thread(() -> new WebOutputSession(0).drain(process, events));
    thread.start();
    for (int attempt = 0; attempt < 100 && events.auth.isEmpty(); attempt++) Thread.sleep(10);
    assertEquals(url, events.auth);
    assertEquals(-1, events.code);
    assertFalse(events.output.toString().contains(token));
    process.exit.countDown();
    thread.join(2000);
    assertFalse(thread.isAlive());
    assertEquals(7, events.code);
  }

  @Test
  public void staleOutputCannotPublishAuthenticationOrLogs() throws Exception {
    Child process = new Child("dsh web: http://127.0.0.1:45678/?token=" + "a".repeat(43) + "\n");
    process.exit.countDown();
    Events events = new Events();
    events.current = false;
    new WebOutputSession(0).drain(process, events);
    assertEquals("", events.auth);
    assertEquals("", events.output.toString());
    assertEquals(7, events.code);
  }

  @Test
  public void brokenOutputCallbackStillObservesExactProcessExit() throws Exception {
    Child process = new Child("ordinary process output\n");
    process.exit.countDown();
    boolean[] failed = {false};
    Events events =
        new Events() {
          public void output(String s) {
            throw new IllegalStateException("closed UI");
          }

          public void readFailure(IOException error) {
            failed[0] = true;
          }
        };
    new WebOutputSession(0).drain(process, events);
    assertTrue(failed[0]);
    assertEquals(7, events.code);
  }
}
