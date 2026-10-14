#!/usr/bin/env python3
"""固定 bxroot 源码、DSHA overlay 和真实原生闭包；--check 无网络/构建副作用。"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import struct
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
NAMES = ("libbxroot.so", "libbxroot-runtime.so", "libbxroot-ulx.so")
COMMIT = "d5f9ac30d05f8fc4302465037b1195e3080f3a60"
ARCHIVE_SHA = "0682b2b868d7bfc2213e63c3736a967af355ea051459ea4b8107e27fa559124f"
PROOT_NAMES = {"libproot.so", "libprootloader.so", "libprootloader32.so", "libtalloc.so",
               "libandroidshmem.so", "libproot_legacy.so", "libprootloader_legacy.so"}

def sha(data):
    return hashlib.sha256(data).hexdigest()

def read_lock():
    return json.loads((HERE / "lock.json").read_text(encoding="utf-8"))

def elf(data):
    if not data.startswith(b"\x7fELF\x02\x01"):
        raise ValueError("BXROOT_ELF64_LE_REQUIRED")
    h = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    ph = [struct.unpack_from("<IIQQQQQQ", data, h[5] + i*h[9]) for i in range(h[10])]
    dynamic = []
    for p in ph:
        if p[0] == 2:
            for offset in range(p[2], p[2]+p[5], 16):
                tag = struct.unpack_from("<qQ", data, offset)
                dynamic.append(tag)
                if tag[0] == 0:
                    break
    needed = []
    strings = next((value for tag, value in dynamic if tag == 5), None)
    if strings is not None:
        offset = next(p[2]+strings-p[3] for p in ph if p[0] == 1 and p[3] <= strings < p[3]+p[5])
        for tag, value in dynamic:
            if tag == 1:
                start = offset+value
                needed.append(data[start:data.index(b"\0", start)].decode("ascii"))
    loads = [p for p in ph if p[0] == 1]
    for p in loads:
        if p[7] < 16384 or p[2] % 16384 != p[3] % 16384:
            raise ValueError("BXROOT_LOAD_ALIGNMENT")
    for size in (4096, 16384):
        for p in ph:
            if p[0] == 0x6474e552:
                lo = p[3] // size * size
                hi = (p[3]+p[6]+size-1)//size*size
                if not any(a[3]//size*size <= lo and (a[3]+a[6]+size-1)//size*size >= hi for a in loads):
                    raise ValueError("BXROOT_RELRO_UNMAPPED")
    return {"machine": h[2], "type": h[1], "entry": h[4],
            "loadAlign": [p[7] for p in loads],
            "interp": next((data[p[2]:p[2]+p[5]].rstrip(b"\0").decode("ascii") for p in ph if p[0] == 3), None),
            "needed": needed,
            "glibcVersions": sorted(set(x.decode() for x in re.findall(rb"GLIBC_[0-9]+\.[0-9]+", data)))}

def check():
    lock = read_lock()
    if lock["schema"] != 1 or tuple(x["name"] for x in lock["artifacts"]) != NAMES:
        raise ValueError("BXROOT_NATIVE_CLOSURE")
    if lock["source"]["commit"] != COMMIT or lock["source"]["archiveSha256"] != ARCHIVE_SHA:
        raise ValueError("BXROOT_FIXED_SOURCE_CHANGED")
    if {Path(x["path"]).name for x in lock["preservedProot"]} != PROOT_NAMES or len(lock["preservedProot"]) != len(PROOT_NAMES):
        raise ValueError("PRESERVED_PROOT_CLOSURE")
    if lock["policy"] != {"buildProfile":"default", "shmCopyoutVerified":0,
                          "shmClaimVerified":0,"reentry":False,"externalStub":False,"strictExec":True}:
        raise ValueError("BXROOT_POLICY_CHANGED")
    for item in lock["patches"]:
        if sha((ROOT/item["path"]).read_bytes()) != item["sha256"]:
            raise ValueError("BXROOT_OVERLAY_CHANGED:"+item["path"])
    for item in lock["artifacts"]:
        data = (ROOT/"app/src/main/jniLibs/arm64-v8a"/item["name"]).read_bytes()
        if sha(data) != item["sha256"] or len(data) != item["size"] or elf(data) != item["elf"]:
            raise ValueError("BXROOT_NATIVE_CHANGED:"+item["name"])
        if item["elf"]["machine"] != 183:
            raise ValueError("BXROOT_AARCH64_REQUIRED")
        if item["name"] == "libbxroot-ulx.so" and (item["elf"]["type"] != 3
                or item["elf"]["interp"] is not None or item["elf"]["needed"]):
            raise ValueError("BXROOT_ULX_STATIC_PIE_REQUIRED")
    for item in lock["preservedProot"]:
        if sha((ROOT/item["path"]).read_bytes()) != item["sha256"]:
            raise ValueError("PRESERVED_PROOT_CHANGED:"+item["path"])
    print("BXROOT_NATIVE_LOCK_PASS")

def prepare(destination, archive):
    lock = read_lock()
    data = archive.read_bytes() if archive else urllib.request.urlopen(lock["source"]["archiveUrl"], timeout=90).read()
    if sha(data) != lock["source"]["archiveSha256"]:
        raise ValueError("BXROOT_SOURCE_ARCHIVE_CHANGED")
    if destination.exists() and any(destination.iterdir()):
        raise ValueError("BXROOT_BUILD_SOURCE_MUST_BE_EMPTY")
    destination.mkdir(parents=True, exist_ok=True)
    prefix = "bxroot-"+lock["source"]["commit"]+"/"
    source_hashes = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        for member in tar:
            if not member.isfile():
                continue
            name = member.name.removeprefix(prefix)
            if name == member.name or ".." in Path(name).parts or Path(name).is_absolute():
                raise ValueError("BXROOT_SOURCE_PATH")
            content = tar.extractfile(member).read()
            path = destination/name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
            source_hashes[name] = sha(content)
    # 不导入 upstream 推送/部署工具；只执行锁定的本机构建和测试。
    import importlib.util
    spec = importlib.util.spec_from_file_location("bxroot_overlay", HERE/"overlay.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    patched = mod.apply(destination)
    (destination/"DSHA-SOURCE-PROOF.json").write_text(json.dumps({"source":lock["source"],
          "upstreamFiles":source_hashes,"patchedFiles":patched},indent=2)+"\n",encoding="utf-8")
    print("BXROOT_SOURCE_PREPARED", destination)

def record(directory, toolchain):
    lock = read_lock()
    items = []
    for name in NAMES:
        data = (directory/name).read_bytes()
        info = elf(data)
        if info["machine"] != 183 or (name != "libbxroot-runtime.so" and (info["interp"] or info["needed"])):
            raise ValueError("BXROOT_NATIVE_LINK_DOMAIN")
        items.append({"name":name,"sha256":sha(data),"size":len(data),"elf":info})
    metadata = (directory/"BUILD-METADATA").read_text()
    if "profile=default" not in metadata or "copyout_verified=0" not in metadata or "claim_verified=0" not in metadata:
        raise ValueError("BXROOT_BUILD_GATE")
    lock["artifacts"] = items
    lock["patches"] = [{"path":p.relative_to(ROOT).as_posix(),"sha256":sha(p.read_bytes())}
                       for p in sorted(HERE.iterdir()) if p.suffix in (".py", ".sh", ".c", ".S")]
    lock["build"] = json.loads(toolchain.read_text())
    for item in items:
        shutil.copyfile(directory/item["name"], ROOT/"app/src/main/jniLibs/arm64-v8a"/item["name"])
    (HERE/"lock.json").write_text(json.dumps(lock,indent=2)+"\n",encoding="utf-8")
    check()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check",action="store_true")
    parser.add_argument("--prepare-source",type=Path)
    parser.add_argument("--source-archive",type=Path)
    parser.add_argument("--record",type=Path)
    parser.add_argument("--toolchain",type=Path)
    args = parser.parse_args()
    if args.check: check()
    elif args.prepare_source: prepare(args.prepare_source,args.source_archive)
    elif args.record and args.toolchain: record(args.record,args.toolchain)
    else: parser.error("choose --check, --prepare-source or --record with --toolchain")

if __name__ == "__main__": main()
