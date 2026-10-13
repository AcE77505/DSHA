package com.deepseekharness.app.ui;

import com.deepseekharness.app.backup.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Genuine production parser/sockets/persistence. JVM files model the Android syscall adapter. */
public final class BrowserOriginRestoreServerTest {
  private static final String NONCE = "a".repeat(64), TARGET = "http://127.0.0.1:12345/";
  private static int checks;

  private record Response(int code, String headers, byte[] body) {}

  private static void check(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    checks++;
  }

  private static Response raw(BrowserOriginRestoreServer server, String header, byte[] body)
      throws Exception {
    int port = URI.create(server.origin()).getPort();
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write(header.getBytes(StandardCharsets.ISO_8859_1));
      if (body != null) socket.getOutputStream().write(body);
      socket.shutdownOutput();
      byte[] answer = socket.getInputStream().readAllBytes();
      String text = new String(answer, StandardCharsets.ISO_8859_1);
      int end = text.indexOf("\r\n\r\n");
      if (end < 0) return new Response(0, "", answer);
      return new Response(
          Integer.parseInt(text.split(" ", 3)[1]),
          text.substring(0, end),
          Arrays.copyOfRange(answer, end + 4, answer.length));
    }
  }

  private static Response call(
      BrowserOriginRestoreServer server, String method, String path, byte[] body) throws Exception {
    boolean upload =
        method.equals("POST")
            && (path.equals("/manifest") || path.startsWith("/part/") || path.equals("/captured"));
    return raw(
        server,
        method
            + " "
            + path
            + " HTTP/1.1\r\nHost: "
            + URI.create(server.origin()).getAuthority()
            + "\r\nOrigin: "
            + (upload ? server.origin() : TARGET.substring(0, TARGET.length() - 1))
            + "\r\nX-DSHA-Restore: "
            + NONCE
            + "\r\nContent-Length: "
            + (body == null ? 0 : body.length)
            + "\r\n\r\n",
        body);
  }

  private static JSONObject manifest(BrowserOriginRestoreServer server, int size) throws Exception {
    JSONArray images = new JSONArray(), parts = new JSONArray();
    if (size > 0) {
      parts.put(size);
      images.put(
          new JSONObject()
              .put("id", "fixture")
              .put("revision", "revision")
              .put("bytes", size)
              .put(
                  "files",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("part", 0)
                              .put("name", "image.png")
                              .put("type", "image/png")
                              .put("lastModified", 1)
                              .put("bytes", size))));
    }
    return new JSONObject()
        .put("version", 1)
        .put("source", server.origin())
        .put("rows", new JSONArray())
        .put("images", images)
        .put("parts", parts)
        .put("unknown", new JSONArray());
  }

  private static byte[] json(JSONObject value) {
    return value.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static BrowserOriginRestoreServer server(
      Path root,
      BackupFileSystem fs,
      AtomicBoolean current,
      AtomicInteger captured,
      AtomicReference<JSONObject> result,
      java.util.function.LongSupplier clock)
      throws Exception {
    Files.createDirectories(root);
    return new BrowserOriginRestoreServer(
        fs,
        root.toFile(),
        0,
        TARGET,
        "SIGNED_RESTORE_DOCUMENT",
        clock,
        current::get,
        captured::incrementAndGet,
        result::set,
        NONCE);
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Files.createDirectories(root);
    AtomicBoolean current = new AtomicBoolean(true);
    AtomicInteger captured = new AtomicInteger();
    AtomicReference<JSONObject> result = new AtomicReference<>();
    try (var server =
        server(
            root.resolve("normal"),
            new JvmBackupFileSystem(),
            current,
            captured,
            result,
            () -> System.nanoTime() / 1_000_000)) {
      String host = URI.create(server.origin()).getAuthority();
      Response html =
          raw(
              server,
              "GET /__dsha/browser-origin-restore HTTP/1.1\r\nHost: " + host + "\r\n\r\n",
              null);
      check(
          html.code == 200
              && html.headers.contains("Cache-Control: no-store")
              && !new String(html.body).contains(NONCE),
          "unsigned HTML reveals no transfer nonce");
      check(
          raw(server, "GET /manifest HTTP/1.1\r\nHost: " + host + "\r\n\r\n", null).code == 403,
          "nonce mandatory");
      check(
          raw(
                      server,
                      "GET /lease HTTP/1.1\r\nHost: foreign\r\nX-DSHA-Restore: "
                          + NONCE
                          + "\r\n\r\n",
                      null)
                  .code
              == 403,
          "Host pinned");
      check(
          raw(
                      server,
                      "GET /lease HTTP/1.1\r\nHost: "
                          + host
                          + "\r\nOrigin: http://evil.invalid\r\nX-DSHA-Restore: "
                          + NONCE
                          + "\r\n\r\n",
                      null)
                  .code
              == 403,
          "Origin pinned");
      check(
          raw(
                      server,
                      "POST /manifest HTTP/1.1\r\nHost: "
                          + host
                          + "\r\nhost: "
                          + host
                          + "\r\nContent-Length: 0\r\n\r\n",
                      null)
                  .code
              == 400,
          "duplicate header refused");
      check(
          raw(
                      server,
                      "POST /manifest HTTP/1.1\r\nHost: "
                          + host
                          + "\r\nOrigin: "
                          + server.origin()
                          + "\r\nX-DSHA-Restore: "
                          + NONCE
                          + "\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n",
                      null)
                  .code
              == 400,
          "TE refused");
      check(
          raw(
                      server,
                      "POST /manifest HTTP/1.1\r\nHost: "
                          + host
                          + "\r\nOrigin: "
                          + server.origin()
                          + "\r\nX-DSHA-Restore: "
                          + NONCE
                          + "\r\nContent-Length: 268435457\r\n\r\n",
                      null)
                  .code
              == 400,
          "body declaration budget");
      check(
          raw(
                      server,
                      "GET /lease HTTP/1.1\r\nHost: "
                          + host
                          + "\r\nX-Large: "
                          + "x".repeat(17000)
                          + "\r\n\r\n",
                      null)
                  .code
              == 400,
          "actual header byte budget");
      check(
          call(server, "POST", "/manifest", json(manifest(server, 3))).code == 200,
          "manifest accepted");
      check(call(server, "POST", "/part/1", new byte[] {1, 2, 3}).code == 409, "part order pinned");
      check(
          call(server, "POST", "/part/../0", new byte[] {1, 2, 3}).code == 409,
          "part traversal refused");
      check(
          call(server, "POST", "/part/0", new byte[] {1, 2}).code == 400, "part length must match");
      check(
          call(server, "POST", "/captured", new byte[0]).code == 409 && captured.get() == 0,
          "partial copy never ready");
      check(
          call(server, "POST", "/part/0", new byte[] {1, 2, 3}).code == 200,
          "bounded part persisted");
      check(
          call(server, "POST", "/captured", new byte[0]).code == 200 && captured.get() == 1,
          "readback completed capture");
      Response saved = call(server, "GET", "/manifest", null);
      JSONObject snapshot = new JSONObject(new String(saved.body, StandardCharsets.UTF_8));
      String expected =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[] {1, 2, 3}));
      check(snapshot.getJSONArray("partSha256").getString(0).equals(expected), "actual saved SHA");
      check(
          call(server, "POST", "/verify/0", new byte[] {1, 2, 3}).code == 200,
          "Blob readback SHA accepts exact bytes");
      check(
          call(server, "POST", "/verify/0", new byte[] {1, 2, 4}).code == 409,
          "Blob readback SHA refuses changed bytes");
      check(
          call(server, "POST", "/verify/0", new byte[] {1}).code == 409,
          "different-length target image is a conflict");
      String identity =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(saved.body));
      JSONObject partial =
          new JSONObject()
              .put("applied", 1)
              .put("conflicts", 2)
              .put("unknown", 3)
              .put("snapshot", identity)
              .put("candidate", "b".repeat(64));
      check(
          call(
                      server,
                      "POST",
                      "/finished",
                      json(new JSONObject(partial.toString()).put("snapshot", "wrong")))
                  .code
              == 409,
          "completion bound to actual manifest identity");
      check(
          call(server, "POST", "/finished", json(partial)).code == 200
              && result.get().getInt("conflicts") == 2
              && result.get().getInt("unknown") == 3,
          "completion retains partial counts");
      current.set(false);
      Thread.sleep(250);
      boolean stopped = false;
      try {
        call(server, "GET", "/lease", null);
      } catch (IOException expectedClose) {
        stopped = true;
      }
      check(stopped, "generation cancels listener");
    }
    current.set(true);
    captured.set(0);
    BackupFileSystem corrupt =
        new JvmBackupFileSystem() {
          @Override
          public OutputStream create(File file) throws IOException {
            OutputStream real = super.create(file);
            if (!file.getName().equals("part-0")) return real;
            return new FilterOutputStream(real) {
              @Override
              public void close() throws IOException {
                super.close();
                Files.write(file.toPath(), new byte[] {9, 9, 9});
              }
            };
          }
        };
    try (var server =
        server(
            root.resolve("corrupt"),
            corrupt,
            current,
            captured,
            result,
            () -> System.nanoTime() / 1_000_000)) {
      check(
          call(server, "POST", "/manifest", json(manifest(server, 3))).code == 200,
          "corrupt fixture planned");
      check(
          call(server, "POST", "/part/0", new byte[] {1, 2, 3}).code == 400,
          "persistent readback corruption refused");
      check(
          call(server, "POST", "/captured", new byte[0]).code == 409 && captured.get() == 0,
          "corrupt retained original is not success");
    }
    AtomicLong ticks = new AtomicLong();
    try (var server =
        server(
            root.resolve("deadline"),
            new JvmBackupFileSystem(),
            current,
            captured,
            result,
            ticks::get)) {
      try (Socket socket = new Socket("127.0.0.1", URI.create(server.origin()).getPort())) {
        socket.setSoTimeout(1500);
        socket.getOutputStream().write("GET /lease HTTP/1.1\r\n".getBytes());
        Thread.sleep(80);
        ticks.set(15001);
        check(
            socket.getInputStream().read() == -1,
            "header total deadline closes a blocked read independently");
      }
    }
    // A blocked writer has no SO_TIMEOUT: the independent monotonic watchdog must close it.
    ticks.set(0);
    captured.set(0);
    result.set(null);
    try (var server =
        server(
            root.resolve("slow-reader"),
            new JvmBackupFileSystem(),
            current,
            captured,
            result,
            ticks::get)) {
      byte[] large = new byte[8 * 1024 * 1024];
      check(
          call(server, "POST", "/manifest", json(manifest(server, large.length))).code == 200,
          "slow reader fixture planned");
      check(
          call(server, "POST", "/part/0", large).code == 200,
          "large fixture uses genuine bounded Java copy");
      check(call(server, "POST", "/captured", new byte[0]).code == 200, "large fixture captured");
      try (Socket socket = new Socket()) {
        socket.setReceiveBufferSize(4096);
        socket.connect(new InetSocketAddress("127.0.0.1", URI.create(server.origin()).getPort()));
        socket.setSoTimeout(5000);
        String request =
            "GET /part/0 HTTP/1.1\r\nHost: "
                + URI.create(server.origin()).getAuthority()
                + "\r\nOrigin: "
                + TARGET.substring(0, TARGET.length() - 1)
                + "\r\nX-DSHA-Restore: "
                + NONCE
                + "\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        int matched = 0;
        while (matched < 4) {
          int b = socket.getInputStream().read();
          if (b < 0) throw new EOFException();
          matched =
              (matched == 0 && b == 13)
                  ? 1
                  : (matched == 1 && b == 10)
                      ? 2
                      : (matched == 2 && b == 13) ? 3 : (matched == 3 && b == 10) ? 4 : 0;
        }
        Thread.sleep(120);
        ticks.set(120001);
        Thread.sleep(250);
        byte[] remaining = socket.getInputStream().readAllBytes();
        check(
            remaining.length < large.length, "total transfer deadline closes blocked socket write");
      }
      check(
          call(server, "GET", "/lease", null).code == 200 && result.get() == null,
          "timed-out reader never registers restore success");
      current.set(false);
      Thread.sleep(250);
      boolean stopped = false;
      try {
        call(server, "GET", "/lease", null);
      } catch (IOException closed) {
        stopped = true;
      }
      check(stopped, "generation independent listener revocation");
    }
    current.set(true);
    Path budget = root.resolve("budget");
    Files.createDirectories(budget.resolve("host-browser-origin-restores"));
    for (int i = 0; i < BackupLimits.HOST_OPERATION_ACTIVE_RECORDS; i++)
      Files.createDirectories(budget.resolve("host-browser-origin-restores/pending-" + i));
    boolean blocked = false;
    try (var unused =
        server(
            budget,
            new JvmBackupFileSystem(),
            current,
            captured,
            result,
            () -> System.nanoTime() / 1_000_000)) {
    } catch (IOException limit) {
      blocked = limit.getMessage().equals("RESTORE_BUDGET_ACTIVE_RECORDS");
    }
    check(blocked, "existing active record budget, no deletion");
    check(
        Files.list(budget.resolve("host-browser-origin-restores")).count()
            == BackupLimits.HOST_OPERATION_ACTIVE_RECORDS,
        "budget preserves all pending candidates");
    System.out.println(
        "PASS browser origin real Java HTTP/parser/JVM file adapter: "
            + checks
            + " checks; Android NOFOLLOW/fsync/WebView/Gecko APIs not claimed");
  }
}
