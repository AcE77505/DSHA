#!/usr/bin/env python3
"""生成完整随包 CLI；仅下载锁定 yaml 原件并验 SHA512，不运行 npm 或生命周期脚本。"""
import argparse, base64, hashlib, io, json, tarfile, urllib.request, stat, os
from pathlib import Path
HERE=Path(__file__).resolve().parent
def prepare(output):
    lock=json.loads((HERE/'package-lock.json').read_text(encoding='utf-8'))['packages']['node_modules/yaml']
    vendor=HERE/'vendor/yaml-2.9.1.tgz'
    if os.path.lexists(vendor):
        if vendor.is_symlink() or not vendor.is_file():raise ValueError('PORTABLE_VENDOR_LINK')
        data=vendor.read_bytes()
    else:
        data=urllib.request.urlopen(lock['resolved'],timeout=60).read(4*1024*1024)
    if lock['integrity']!='sha512-'+base64.b64encode(hashlib.sha512(data).digest()).decode():raise ValueError('PORTABLE_YAML_INTEGRITY')
    if not os.path.lexists(vendor):
        vendor.parent.mkdir(parents=True,exist_ok=True)
        with vendor.open('xb') as handle:handle.write(data)
    members={}
    for name in ('dsha-portable.py','dsha-portable-restore.mjs','portable-core.mjs','portable-yaml.mjs','portable-plan.json','package.json','package-lock.json','README.md'):
        members['pc-migration/'+name]=(HERE/name).read_bytes()
    with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as archive:
        for member in archive:
            parts=Path(member.name).parts
            if not parts or parts[0]!='package' or any(p in ('.','..') for p in parts) or member.name.startswith('/') or '\\' in member.name:raise ValueError('YAML_ARCHIVE_PATH')
            name='pc-migration/node_modules/yaml/'+ '/'.join(parts[1:])
            if member.isdir():continue
            elif member.isfile() and member.size<=1024**2:
                if name in members:raise ValueError('YAML_ARCHIVE_DUPLICATE')
                with archive.extractfile(member) as stream:members[name]=stream.read()
            else:raise ValueError('YAML_ARCHIVE_TYPE')
    members['dsha-portable']=b'#!/bin/sh\nexec /usr/bin/python3 /usr/local/share/dsha/pc-migration/dsha-portable.py "$@"\n'
    expected=set(members)
    def linked(file):
        node=file.lstat()
        return file.is_symlink() or bool(getattr(node,'st_file_attributes',0)&getattr(stat,'FILE_ATTRIBUTE_REPARSE_POINT',1024))
    destination=output/'pc-migration'
    for ancestor in [output]+list(output.parents):
        if os.path.lexists(ancestor) and linked(ancestor):raise ValueError('PORTABLE_OUTPUT_LINK')
    if os.path.lexists(destination):
        if linked(destination) or not destination.is_dir():raise ValueError('PORTABLE_OUTPUT_LINK')
        for file in destination.rglob('*'):
            if linked(file):raise ValueError('PORTABLE_OUTPUT_LINK')
            relative=str(file.relative_to(output)).replace('\\','/')
            if file.is_file() and relative not in expected:raise ValueError('PORTABLE_OUTPUT_UNKNOWN_MEMBER')
            if file.is_dir() and not any(name.startswith(relative+'/') for name in expected):raise ValueError('PORTABLE_OUTPUT_UNKNOWN_DIRECTORY')
    output.mkdir(parents=True,exist_ok=True)
    for relative,content in sorted(members.items()):
        target=output/relative;target.parent.mkdir(parents=True,exist_ok=True)
        if os.path.lexists(target) and (linked(target) or not target.is_file()):raise ValueError('PORTABLE_OUTPUT_LINK')
        if not target.exists() or target.read_bytes()!=content:target.write_bytes(content)
    rows={name:hashlib.sha256(content).hexdigest() for name,content in sorted(members.items())}
    manifest=output/'portable-tools.manifest.json'
    if os.path.lexists(manifest) and (linked(manifest) or not manifest.is_file()):raise ValueError('PORTABLE_OUTPUT_LINK')
    manifest.write_text(json.dumps({'schema':1,'yamlIntegrity':lock['integrity'],'files':rows},indent=2)+'\n',encoding='utf-8',newline='\n')
    return rows
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();print('prepared',len(prepare(args.output.resolve())),'locked CLI files')
