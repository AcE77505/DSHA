package com.deepseekharness.app.util;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 自动生成的白名单镜像；唯一输入 tools/pc-migration/portable-plan.json。 */
public final class PortableExportPlan {
  public static final String SOURCE_SHA256 =
      "0550da41cb5d2eb69b6a79da466ea50137dcdb5cbbab8515b711ea7dd7449182";
  private static final JsonObject SPEC =
      JsonParser.parseString(
              new String(
                  Base64.getDecoder()
                      .decode(
                          "ewogICJzY2hlbWFWZXJzaW9uIjogMSwKICAicGFja2FnZVByZWZpeCI6ICJEU0hBLXBvcnRhYmxlLSIsCiAgImV4dGVuc2lvbiI6ICIudGFyLmd6IiwKICAiY29tbWVudCI6ICLmiYvmnLrnq68gRFNIIOaVsOaNriDihpIg55S16ISR56uvIGRzaCDnmoTkvr/mkLrljIXnrZbnlaXjgILnmb3lkI3ljZXvvJrlj6rmiZPljIUgaW5jbHVkZSDliJflh7rnmoTot6/lvoTvvIzlhbblroPkuIDlvovkuI3ov5vljIXjgIIiLAogICJpbmNsdWRlIjogWwogICAgewogICAgICAicGF0aCI6ICJzZXNzaW9ucyIsCiAgICAgICJraW5kIjogImRpciIsCiAgICAgICJ3aHkiOiAi5Lya6K+d5pel5b+X77yIenN0ZCBqc29ubO+8jGhlYWRlciDoh6rluKYgY3dk77yM6Lev5b6E6Ieq5rS977yJ77ybc2Vzc2lvbi5sb2NrIOaYr+enn+e6puaWh+S7tu+8jOaBouWkjeerr+S8muiHquW3semHjeW7uu+8jOS4jei/m+WMhSIsCiAgICAgICJza2lwIjogWwogICAgICAgICJzZXNzaW9uLmxvY2siCiAgICAgIF0KICAgIH0sCiAgICB7CiAgICAgICJwYXRoIjogInN0b3JhZ2VzL3dvcmtzcGFjZS5qc29uIiwKICAgICAgImtpbmQiOiAiZmlsZSIsCiAgICAgICJ3aHkiOiAi5bel5L2c5Yy65rOo5YaM6KGo77yb5oGi5aSN5pe25oyJIC0td29ya3NwYWNlIOaUueWGmSBwYXRoIgogICAgfSwKICAgIHsKICAgICAgInBhdGgiOiAiYXR0YWNobWVudHMiLAogICAgICAia2luZCI6ICJkaXIiLAogICAgICAid2h5IjogIumZhOS7tuWvueixoeS7k++8iOWGheWuueWvu+WdgO+8iSIKICAgIH0sCiAgICB7CiAgICAgICJwYXRoIjogInByb2ZpbGVzL3twcm9maWxlfS9jb3JkaXMucGF0Y2gueW1sIiwKICAgICAgImtpbmQiOiAiZmlsZSIsCiAgICAgICJ3aHkiOiAi55So5oi36K6+572u5bGC77yI5qih5Z6LL+Wtl+WPty/kuLvpopjnrYnvvIkiCiAgICB9LAogICAgewogICAgICAicGF0aCI6ICJsbG0tZGVlcHNlZWsiLAogICAgICAia2luZCI6ICJkaXIiLAogICAgICAid2h5IjogIuaWh+S7tiBBUEkg57yT5a2Y77yI5oyJ6LSm5Y+35L2c55So5Z+f77yM6Z2e6K6+5aSH57uR5a6a77yJIgogICAgfQogIF0sCiAgImNyZWRlbnRpYWxzIjogewogICAgInBhdGgiOiAiLmNyZWRlbnRpYWxzLnlhbWwiLAogICAgImRlZmF1bHQiOiAiZXhjbHVkZSIsCiAgICAiZmxhZyI6ICItLXdpdGgtY3JlZGVudGlhbHMiLAogICAgInBydW5lUmVjb3JkUHJlZml4ZXMiOiBbCiAgICAgICJjbGllbnQtY29ubmVjdGlvbi8iCiAgICBdLAogICAgImZpbHRlciI6ICJjcmVkZW50aWFsLXlhbWwtZmlsdGVyLmNqcyIsCiAgICAid2h5IjogIum7mOiupOS4jeWQq+OAgueUqOaIt+aYvuW8j+imgeaxguaXtuS5n+W/hemhu+Wtl+autee6p+WJlOmZpCBjbGllbnQtY29ubmVjdGlvbi8q77yI5rWP6KeI5Zmo5Lya6K+d562+5ZCN5a+G6ZKl77yM6K6+5aSHK+acrOi9riBkc2gg57uR5a6a77yM6L+B5Yiw55S16ISR5peg5oSP5LmJ5LiU5pWP5oSf77yJ77yb5Y+q5L+d55WZIHJlZnMg6YeM55qE55So5oi3IEFQSSBLZXkg5byV55So44CCIgogIH0sCiAgImV4Y2x1ZGUiOiBbCiAgICB7CiAgICAgICJwYXRoIjogInN0b3JhZ2VzL3Nlc3Npb25fcHJvamNhY2hlIiwKICAgICAgIndoeSI6ICLmipXlvbHnvJPlrZjvvIzlj6/ph43lu7rvvJtyZWNvcmQuaWRlbnRpdHkuY3dkIOaYr+e7neWvuei3r+W+hO+8jOS4lCB2ZXJzaW9uIOe7keWumiBkc2gg54mI5pysIgogICAgfSwKICAgIHsKICAgICAgInBhdGgiOiAicHJvZmlsZXMvKi9ub2RlX21vZHVsZXMiLAogICAgICAid2h5IjogIuiuvuWkh+S+pyBwbnBtIOmTvuaOpe+8m1BDIOerr+eUsSBQQyDnmoQgZHNoIOiHquW3seijhSIKICAgIH0sCiAgICB7CiAgICAgICJwYXRoIjogInByb2ZpbGVzLyovcGFja2FnZS5qc29uIiwKICAgICAgIndoeSI6ICLlkKsgbGluazovcm9vdC9kc2hhLSog6K6+5aSH57ud5a+56Lev5b6E77yMUEMg5LiK5b+F54S25aSx5pWIIgogICAgfSwKICAgIHsKICAgICAgInBhdGgiOiAicHJvZmlsZXMvKi9wbnBtLXdvcmtzcGFjZS55YW1sIiwKICAgICAgIndoeSI6ICLlkIzkuIrvvIzorr7lpIfkvqcgcHJvZmlsZSDluIPlsYAiCiAgICB9LAogICAgewogICAgICAicGF0aCI6ICJub2RlX21vZHVsZXMiLAogICAgICAid2h5IjogIkRTSEEg5aGe6L+bICREU0hfSE9NRSDnmoTpmo/ljIXkvp3otZbvvJtQQyDnq68gZHNoIOiHquW4piIKICAgIH0sCiAgICB7CiAgICAgICJwYXRoIjogImNhY2hlIiwKICAgICAgIndoeSI6ICLlj6/lho3nlJ/vvIjpmYTku7bor7fmsYLlm77niYfnrYnvvIkiCiAgICB9LAogICAgewogICAgICAicGF0aCI6ICJfX3B5Y2FjaGVfXyIsCiAgICAgICJ3aHkiOiAi5Y+v5YaN55SfIgogICAgfSwKICAgIHsKICAgICAgInBhdGgiOiAiLmJyaWRnZV90b2tlbiAvIC5icmlkZ2VfaGVhZGVycyAvIC5icmlkZ2Vfc3RhdHVzIiwKICAgICAgIndoeSI6ICIzMDkwIOahpeeahOacrOacuuWFseS6q+WHreaNru+8m+azhOmcsuWNs+etieS6juS6pOWHuuiuvuWkh+aOp+WItuadgyIKICAgIH0sCiAgICB7CiAgICAgICJwYXRoIjogIi5hbm9ueW1vdXMtdXNlci1pZCIsCiAgICAgICJ3aHkiOiAiaGFybmVzcy1ob21lIOe6p+WMv+WQjei6q+S7ve+8m+i/geenu+S8muiuqeS4pOWPsOacuuWZqOWFseeUqOS4gOS4qiBpZO+8jFBDIOerr+mHjeaWsOeUn+aIkOabtOW5suWHgCIKICAgIH0sCiAgICB7CiAgICAgICJwYXRoIjogIi5ydW50aW1lLWRlc2NyaXB0b3IuanNvbiAvIC5ydW50aW1lLWhlYWx0aC5qc29uIC8gLnJ1bnRpbWUtbGlua3MtY2FjaGUuanNvbiIsCiAgICAgICJ3aHkiOiAi6K6+5aSH5L6n5Y+X566h6L+Q6KGM5pe25aWR57qm5LiO5YGl5bq35Zue5omnIgogICAgfSwKICAgIHsKICAgICAgInBhdGgiOiAiLmRzaGEtd2ViLnBpZCAvIC5kc2hhLXdlYi5pZGVudGl0eSAvIC5kc2hhLXN0b3BwZWQgLyAuZHNoYS1yYzEtbWlncmF0aW9uIC8gLm9mZmxpbmUtKiIsCiAgICAgICJ3aHkiOiAiRFNIQSDnlJ/lkb3lkajmnJ/kuI7oo4XmnLrnirbmgIEiCiAgICB9LAogICAgewogICAgICAicGF0aCI6ICJwbHVnaW4tYWN0aXZhdGlvbnMuanNvbiAvIHBsdWdpbi1zYWZlLW1vZGUuanNvbiAvIHBsdWdpbi1zb3VyY2VzLmpzb24gLyBwbHVnaW4taGlzdG9yeSAvIHBsdWdpbi1zcmMiLAogICAgICAid2h5IjogIuiuvuWkh+S+p+aPkuS7tueKtuaAgeS4jua6kOegge+8m1BDIOerr+S4jemcgOimgSIKICAgIH0sCiAgICB7CiAgICAgICJwYXRoIjogIioucHkgLyAqLmNqcyAvIHNjcmlwdC12ZXJzaW9uIC8gYnVpbHRpbi1wbHVnaW5zLmpzb24gLyBydW50aW1lLXBhdGNoZXMuanNvbiAvIHJ1bnRpbWUtdG9vbHMuanNvbiAvIHJlcGFpci1idWlsdGluLmxvZyIsCiAgICAgICJ3aHkiOiAi5YWo6YOo5pivIERTSEEg6ZqP5YyF6ISa5pys5LiO5Y+X566h6LWE5Lqn77yM5LiN5piv55So5oi35pWw5o2uIgogICAgfSwKICAgIHsKICAgICAgInBhdGgiOiAic2V0dGluZ3MueWFtbCIsCiAgICAgICJ3aHkiOiAicmMyIOW3suaXoOatpOaWh+S7tu+8m+iLpeWtmOWcqOWImeWxnuS6juaXp+agvOW8j++8jOeUsSBQQyDnq68gZHNoIOiHquW3sei/geenuyIKICAgIH0KICBdLAogICJyZXdyaXRlIjogewogICAgImFsd2F5cyI6IFsKICAgICAgewogICAgICAgICJ0YXJnZXQiOiAic3RvcmFnZXMvc2Vzc2lvbl9wcm9qY2FjaGUiLAogICAgICAgICJhY3Rpb24iOiAiZHJvcCIKICAgICAgfSwKICAgICAgewogICAgICAgICJ0YXJnZXQiOiAic3RvcmFnZXMvd29ya3NwYWNlLmpzb246dGFibGVzLndvcmtzcGFjZXMuKi5wYXRoIiwKICAgICAgICAiYWN0aW9uIjogInNldC10by13b3Jrc3BhY2UtYXJnIgogICAgICB9CiAgICBdLAogICAgInNlc3Npb25Dd2QiOiB7CiAgICAgICJkZWZhdWx0IjogImtlZXAiLAogICAgICAiZmxhZyI6ICItLXJld3JpdGUtY3dkIiwKICAgICAgIndoeSI6ICLkvJror53ml6Xlv5fmmK8genN0ZCDlpJrluKfvvIxoZWFkZXIg6YeM5pyJ57ud5a+5IGN3ZO+8jOS4lCBkc2gg55SoIHByb2plY3RLZXkoY3dkKSDmjqjlr7znm67lvZXlkI3lubblgZrkuIDoh7TmgKfmoLjpqozjgILpu5jorqTljp/moLfkv53nlZnvvIjpm7bmoLzlvI/po47pmanvvInjgILlvIAgLS1yZXdyaXRlLWN3ZCDml7bmiormlbTmo7Ugc2Vzc2lvbnMg6L2s5oiQ5piO5paHIHNlc3Npb24udk4uanNvbmwg5bm25pS55YaZIGhlYWRlci5jd2TvvIzlkIzml7blnKggcHJvZmlsZSBwYXRjaCDph4zliqAgc2Vzc2lvbi1wZXJzaXN0ZW5jZS1qc29ubCBjb21wcmVzc2lvbjogbm9uZSDigJTigJQgZHNoIOaLkue7neWQjOS4gOS4qiBzZXNzaW9ucyDmoLnmt7fnlKjkuKTnp43nvJbnoIHjgIIiCiAgICB9CiAgfQp9Cg=="),
                  StandardCharsets.UTF_8))
          .getAsJsonObject();
  public static final String ARCHIVE_PREFIX = "DSHA-portable-", ARCHIVE_EXTENSION = ".tar.gz";
  public static final String HOME_DIR = "home",
      WORKSPACE_DIR = "workspace",
      MANIFEST_NAME = "manifest.json";
  public static final String CREDENTIALS_FILE = ".credentials.yaml",
      MACHINE_RECORD_PREFIX = "client-connection/",
      PROJECTION_CACHE = "storages/session_projcache";

  private PortableExportPlan() {}

  public static List<String> included() {
    var rows = new ArrayList<String>();
    for (var entry : SPEC.getAsJsonArray("include"))
      rows.add(entry.getAsJsonObject().get("path").getAsString().replace("{profile}", "%s"));
    return List.copyOf(rows);
  }

  public static List<String[]> excluded() {
    var rows = new ArrayList<String[]>();
    for (var entry : SPEC.getAsJsonArray("exclude")) {
      var row = entry.getAsJsonObject();
      rows.add(new String[] {row.get("path").getAsString(), row.get("why").getAsString()});
    }
    return List.copyOf(rows);
  }

  public static boolean selectable(String relative) {
    if (relative == null
        || relative.isEmpty()
        || relative.length() > 2048
        || relative.startsWith("/")
        || relative.indexOf('\\') >= 0
        || relative.indexOf('\0') >= 0
        || relative.matches("^[A-Za-z]:.*")) return false;
    String[] parts = relative.split("/", -1);
    if (parts.length > 64) return false;
    for (String part : parts)
      if (part.isEmpty()
          || part.equals(".")
          || part.equals("..")
          || part.indexOf('\n') >= 0
          || part.indexOf('\r') >= 0
          || part.contains(":")
          || part.endsWith(".")
          || part.endsWith(" ")
          || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])([.].*)?")) return false;
    if (relative.equals(CREDENTIALS_FILE)) return true;
    if (relative.matches("profiles/[A-Za-z0-9][A-Za-z0-9_-]{0,79}/cordis\\.patch\\.yml"))
      return true;
    for (var entry : SPEC.getAsJsonArray("include")) {
      var row = entry.getAsJsonObject();
      String rule = row.get("path").getAsString();
      if (rule.contains("{profile}")) continue;
      if (relative.equals(rule)) return true;
      if (row.get("kind").getAsString().equals("dir") && relative.startsWith(rule + "/")) {
        if (row.has("skip"))
          for (var skipped : row.getAsJsonArray("skip"))
            for (String part : relative.substring(rule.length() + 1).split("/"))
              if (part.equals(skipped.getAsString())) return false;
        return true;
      }
    }
    return false;
  }

  public static boolean credentialsIncludedByDefault() {
    return false;
  }

  public static boolean mustPruneCredentialRecord(String name) {
    return name != null && name.startsWith(MACHINE_RECORD_PREFIX);
  }

  public static String archiveName(String id) {
    if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}"))
      throw new IllegalArgumentException("PORTABLE_IDENTIFIER");
    return ARCHIVE_PREFIX + id + ARCHIVE_EXTENSION;
  }

  public static boolean looksLikePortableArchive(String name) {
    if (name == null || !name.startsWith(ARCHIVE_PREFIX) || !name.endsWith(ARCHIVE_EXTENSION))
      return false;
    try {
      return name.equals(
          archiveName(
              name.substring(ARCHIVE_PREFIX.length(), name.length() - ARCHIVE_EXTENSION.length())));
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }
}
