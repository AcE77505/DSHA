package com.deepseekharness.app.backup;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

/** Used only by the background export worker; picker selection alone never creates a document. */
public final class SafExportDirectory implements NewDocumentExport.Documents {
  private final ContentResolver resolver;
  private final Uri directory;
  private final String id;
  private final String authority;

  public SafExportDirectory(ContentResolver resolver, Uri directory) throws IOException {
    if (directory == null
        || !"content".equals(directory.getScheme())
        || directory.getQuery() != null
        || directory.getFragment() != null) throw new IOException("EXPORT_DIRECTORY");
    this.resolver = resolver;
    this.directory = directory;
    authority = directory.getAuthority();
    if (authority == null || authority.isEmpty()) throw new IOException("EXPORT_DIRECTORY");
    try {
      id = DocumentsContract.getTreeDocumentId(directory);
      List<String> parts = directory.getPathSegments();
      if (id == null
          || id.isEmpty()
          || parts.size() != 2
          || !parts.get(0).equals("tree")
          || !parts.get(1).equals(id)) throw new IOException("EXPORT_DIRECTORY");
    } catch (RuntimeException invalid) {
      throw new IOException("EXPORT_DIRECTORY", invalid);
    }
  }

  @Override
  public Set<String> existingIds(BackupControl control) throws IOException {
    Set<String> ids = new HashSet<>();
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(directory, id);
    try (Cursor cursor =
        DocumentStreams.query(
            resolver,
            children,
            new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID},
            control)) {
      while (cursor.moveToNext()) {
        control.check();
        String child = cursor.getString(0);
        if (child == null
            || child.isEmpty()
            || !ids.add(child)
            || ids.size() > BackupLimits.ENTRIES) throw new IOException("SAF_DIRECTORY_IDS");
      }
    }
    return ids;
  }

  @Override
  public NewDocumentExport.Document create(String name, BackupControl control) throws IOException {
    control.check();
    Uri created;
    try {
      created =
          DocumentsContract.createDocument(
              resolver,
              DocumentsContract.buildDocumentUriUsingTree(directory, id),
              com.deepseekharness.app.util.BackupFileNames.MIME_TYPE,
              name);
    } catch (IOException | RuntimeException unknown) {
      throw new IOException("SAF_CREATE_RESULT_UNKNOWN", unknown);
    }
    if (created == null || !"content".equals(created.getScheme()))
      throw new IOException("SAF_CREATE_RESULT_UNKNOWN");
    try {
      NewDocumentExport.Document document =
          new NewDocumentExport.Document(created, DocumentsContract.getDocumentId(created));
      uri(document, control);
      return document;
    } catch (RuntimeException unknown) {
      throw new IOException("SAF_CREATE_RESULT_UNKNOWN", unknown);
    }
  }

  private Uri uri(NewDocumentExport.Document document, BackupControl control) throws IOException {
    if (!(document.handle instanceof Uri)) throw new IOException("SAF_DOCUMENT_IDENTITY");
    Uri value = (Uri) document.handle;
    try {
      if (!"content".equals(value.getScheme())
          || !authority.equals(value.getAuthority())
          || value.getQuery() != null
          || value.getFragment() != null
          || !document.id.equals(DocumentsContract.getDocumentId(value)))
        throw new IOException("SAF_DOCUMENT_SCOPE_UNCONFIRMED");
      List<String> parts = value.getPathSegments();
      boolean plain =
          parts.size() == 2 && parts.get(0).equals("document") && parts.get(1).equals(document.id);
      boolean tree =
          parts.size() == 4
              && parts.get(0).equals("tree")
              && parts.get(1).equals(id)
              && parts.get(2).equals("document")
              && parts.get(3).equals(document.id);
      if ((!plain && !tree) || !existingIds(control).contains(document.id))
        throw new IOException("SAF_DOCUMENT_SCOPE_UNCONFIRMED");
    } catch (RuntimeException invalid) {
      throw new IOException("SAF_DOCUMENT_SCOPE_UNCONFIRMED", invalid);
    }
    return value;
  }

  @Override
  public NewDocumentExport.Metadata metadata(
      NewDocumentExport.Document document, BackupControl control) throws IOException {
    try (Cursor cursor =
        DocumentStreams.query(
            resolver,
            uri(document, control),
            new String[] {
              DocumentsContract.Document.COLUMN_DOCUMENT_ID,
              DocumentsContract.Document.COLUMN_DISPLAY_NAME,
              DocumentsContract.Document.COLUMN_SIZE
            },
            control)) {
      if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1) || cursor.isNull(2))
        throw new IOException("SAF_DOCUMENT_METADATA");
      var metadata =
          new NewDocumentExport.Metadata(
              cursor.getString(0), cursor.getString(1), cursor.getLong(2));
      if (cursor.moveToNext()) throw new IOException("SAF_DOCUMENT_METADATA");
      return metadata;
    }
  }

  @Override
  public String objectIdentity(NewDocumentExport.Document document, BackupControl control)
      throws IOException {
    return DocumentStreams.objectIdentity(resolver, uri(document, control), control);
  }

  @Override
  public OutputStream checkedOutput(
      NewDocumentExport.Document document, String expectedIdentity, BackupControl control)
      throws IOException {
    return DocumentStreams.checkedOutput(
        resolver, uri(document, control), expectedIdentity, control);
  }

  @Override
  public InputStream input(NewDocumentExport.Document document, BackupControl control)
      throws IOException {
    return DocumentStreams.input(resolver, uri(document, control), control);
  }
}
