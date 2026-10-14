package com.deepseekharness.app.backup;

import java.io.*;
import java.util.Set;
import java.util.HashSet;

/** Creates only after source verification; failed external output is retained because SAF has no CAS delete. */
public final class NewDocumentExport {
  public static final class Document {
    public final Object handle;
    public final String id;

    public Document(Object handle, String id) {
      this.handle = handle;
      this.id = id;
    }
  }

  public static final class Metadata {
    public final String id, name;
    public final long size;

    public Metadata(String id, String name, long size) {
      this.id = id;
      this.name = name;
      this.size = size;
    }
  }

  public interface Documents {
    Set<String> existingIds(BackupControl control) throws IOException;

    Document create(String name, BackupControl control) throws IOException;

    Metadata metadata(Document document, BackupControl control) throws IOException;

    default String objectIdentity(Document document, BackupControl control) throws IOException {
      return "";
    }

    OutputStream checkedOutput(Document document, String objectIdentity, BackupControl control)
        throws IOException;

    InputStream input(Document document, BackupControl control) throws IOException;
  }

  public static final class Retained extends IOException {
    public final Document document;
    public final long acknowledgedBytes;
    public final String requestedName;

    Retained(IOException failure, Document document, String name, long acknowledgedBytes) {
      super(BackupErrorCode.from(failure), failure);
      this.document = document;
      this.requestedName = name;
      this.acknowledgedBytes = acknowledgedBytes;
    }
  }

  public static final class Result {
    public final Document document;
    public final boolean readback;

    public Result(Document document, boolean readback) {
      this.document = document;
      this.readback = readback;
    }
  }

  private NewDocumentExport() {}

  public static Result write(
      BackupFileSystem fs,
      File source,
      String expected,
      String name,
      Documents documents,
      BackupControl control)
      throws IOException {
    if (name == null
        || !name.matches("DSHA-data-v5-[a-f0-9-]{36}\\.tar\\.gz")
        || expected == null
        || !expected.matches("[a-f0-9]{64}")) throw new IOException("EXPORT_DESTINATION");
    control.check();
    var before = fs.stat(source);
    if (!before.type.equals("FILE")) throw new IOException("EXPORT_SOURCE_TYPE");
    try (InputStream input = fs.read(source, before)) {
      if (!expected.equals(BackupArchive.digest(input, control)))
        throw new IOException("EXPORT_SOURCE_CHANGED");
    }
    Set<String> listed = documents.existingIds(control);
    if (listed == null || listed.size() > BackupLimits.ENTRIES)
      throw new IOException("SAF_DIRECTORY_IDS");
    Set<String> existing = new HashSet<>(listed);
    control.check();
    Document created;
    try {
      created = documents.create(name, control);
    } catch (IOException unknown) {
      throw new Retained(unknown, null, name, 0);
    }
    // A provider reusing an existing ID is not a creation proof, even when the file is empty.
    if (created == null
        || created.id == null
        || created.id.isEmpty()
        || existing.contains(created.id)) throw new IOException("DESTINATION_NOT_NEW");
    String objectIdentity = "";
    long acknowledgedBytes = 0;
    try {
      Metadata fresh = documents.metadata(created, control);
      if (!created.id.equals(fresh.id) || !name.equals(fresh.name) || fresh.size != 0)
        throw new IOException("DESTINATION_NOT_NEW");
      objectIdentity = documents.objectIdentity(created, control);
      if (objectIdentity == null || objectIdentity.isEmpty())
        throw new IOException("SAF_OBJECT_IDENTITY_UNAVAILABLE");
      control.check();
      try (InputStream input = fs.read(source, before);
          OutputStream output = documents.checkedOutput(created, objectIdentity, control)) {
        if (output == null) throw new IOException("SAF_WRITE_FAILED");
        java.security.MessageDigest copied = BackupArchive.sha();
        byte[] buffer = new byte[65536];
        int n;
        while ((n = input.read(buffer)) != -1) {
          control.check();
          output.write(buffer, 0, n);
          acknowledgedBytes =
              BackupLimits.add(acknowledgedBytes, n, BackupLimits.BYTES + 256L * 1024 * 1024);
          copied.update(buffer, 0, n);
        }
        output.flush();
        control.check();
        if (!expected.equals(BackupArchive.hex(copied.digest())))
          throw new IOException("EXPORT_SOURCE_CHANGED");
      }
      boolean readable = true;
      String actual;
      try (InputStream input = documents.input(created, control)) {
        if (input == null) throw new IOException("SAF_UNREADABLE");
        actual = BackupArchive.digest(input, control);
      } catch (IOException unavailable) {
        if (unavailable instanceof InterruptedIOException || control.isCancelled())
          throw unavailable;
        readable = false;
        actual = expected;
      }
      if (!expected.equals(actual)) throw new IOException("EXPORT_DIGEST_MISMATCH");
      control.check();
      return new Result(created, readable);
    } catch (IOException failure) {
      throw new Retained(failure, created, name, acknowledgedBytes);
    }
  }
}
