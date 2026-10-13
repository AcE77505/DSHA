#!/usr/bin/env python3
"""把唯一 JSON 策略嵌入纯 Java 镜像；--check 核对政策输入摘要，不依赖源码排版。"""
import argparse, base64, hashlib, json, re
from pathlib import Path
HERE=Path(__file__).resolve().parent
TARGET=HERE.parents[1]/'app/src/main/java/com/deepseekharness/app/util/PortableExportPlan.java'
def source():
    data=(HERE/'portable-plan.json').read_bytes();spec=json.loads(data)
    if spec['schemaVersion']!=1 or not all(r['kind'] in ('file','dir') for r in spec['include']):raise ValueError('PLAN_SCHEMA')
    return '''package com.deepseekharness.app.util;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** 自动生成的白名单镜像；唯一输入 tools/pc-migration/portable-plan.json。 */
public final class PortableExportPlan {
  public static final String SOURCE_SHA256 = "%s";
  private static final JsonObject SPEC = JsonParser.parseString(new String(Base64.getDecoder().decode("%s"), StandardCharsets.UTF_8)).getAsJsonObject();
  public static final String ARCHIVE_PREFIX = "DSHA-portable-", ARCHIVE_EXTENSION = ".tar.gz";
  public static final String HOME_DIR = "home", WORKSPACE_DIR = "workspace", MANIFEST_NAME = "manifest.json";
  public static final String CREDENTIALS_FILE = ".credentials.yaml", MACHINE_RECORD_PREFIX = "client-connection/", PROJECTION_CACHE = "storages/session_projcache";
  private PortableExportPlan() {}
  public static List<String> included() { var rows=new ArrayList<String>();for(var entry:SPEC.getAsJsonArray("include"))rows.add(entry.getAsJsonObject().get("path").getAsString().replace("{profile}","%%s"));return List.copyOf(rows); }
  public static List<String[]> excluded() { var rows=new ArrayList<String[]>();for(var entry:SPEC.getAsJsonArray("exclude")){var row=entry.getAsJsonObject();rows.add(new String[]{row.get("path").getAsString(),row.get("why").getAsString()});}return List.copyOf(rows); }
  public static boolean selectable(String relative) {
    if(relative==null||relative.isEmpty()||relative.length()>2048||relative.startsWith("/")||relative.indexOf('\\\\')>=0||relative.indexOf('\\0')>=0||relative.matches("^[A-Za-z]:.*"))return false;
    String[] parts=relative.split("/",-1);if(parts.length>64)return false;
    for(String part:parts)if(part.isEmpty()||part.equals(".")||part.equals("..")||part.indexOf('\\n')>=0||part.indexOf('\\r')>=0||part.contains(":")||part.endsWith(".")||part.endsWith(" ")||part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])([.].*)?"))return false;
    if(relative.equals(CREDENTIALS_FILE))return true;
    if(relative.matches("profiles/[A-Za-z0-9][A-Za-z0-9_-]{0,79}/cordis\\\\.patch\\\\.yml"))return true;
    for(var entry:SPEC.getAsJsonArray("include")){
      var row=entry.getAsJsonObject();String rule=row.get("path").getAsString();
      if(rule.contains("{profile}"))continue;
      if(relative.equals(rule))return true;
      if(row.get("kind").getAsString().equals("dir")&&relative.startsWith(rule+"/")){
        if(row.has("skip"))for(var skipped:row.getAsJsonArray("skip"))for(String part:relative.substring(rule.length()+1).split("/"))if(part.equals(skipped.getAsString()))return false;
        return true;
      }
    }
    return false;
  }
  public static boolean credentialsIncludedByDefault(){return false;}
  public static boolean mustPruneCredentialRecord(String name){return name!=null&&name.startsWith(MACHINE_RECORD_PREFIX);}
  public static String archiveName(String id){if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}"))throw new IllegalArgumentException("PORTABLE_IDENTIFIER");return ARCHIVE_PREFIX+id+ARCHIVE_EXTENSION;}
  public static boolean looksLikePortableArchive(String name){if(name==null||!name.startsWith(ARCHIVE_PREFIX)||!name.endsWith(ARCHIVE_EXTENSION))return false;try{return name.equals(archiveName(name.substring(ARCHIVE_PREFIX.length(),name.length()-ARCHIVE_EXTENSION.length())));}catch(IllegalArgumentException invalid){return false;}}
}
'''%(hashlib.sha256(data).hexdigest(),base64.b64encode(data).decode())
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args();value=source()
    if args.check:
        expected=re.search(r'SOURCE_SHA256 = "([a-f0-9]+)"',value).group(1)
        if not TARGET.is_file() or not re.search(r'SOURCE_SHA256\s*=\s*"'+expected+r'"',TARGET.read_text(encoding='utf-8')):raise ValueError('PORTABLE_PLAN_SOURCE_CHANGED')
        print('PASS portable policy input digest')
    else:TARGET.write_text(value,encoding='utf-8',newline='\n');print(TARGET)
