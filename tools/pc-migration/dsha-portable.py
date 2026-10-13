#!/usr/bin/env python3
"""白名单便携导出；核对源和副本实际字节，不执行 HOME 内的脚本。"""
import argparse, datetime, hashlib, json, os, re, shutil, stat, subprocess, sys, tarfile, tempfile, uuid
from pathlib import Path
HERE = Path(__file__).resolve().parent
POLICY = json.loads((HERE/'portable-plan.json').read_text(encoding='utf-8'))
SKIP = {'.git','node_modules','__pycache__','.venv','venv','.gradle','build','.cache','.npm','.pnpm-store','target'}
def linked(node):
    return stat.S_ISLNK(node.st_mode) or bool(getattr(node,'st_file_attributes',0)&getattr(stat,'FILE_ATTRIBUTE_REPARSE_POINT',1024))
def read_fd(file):
    flags=os.O_RDONLY|getattr(os,'O_NOFOLLOW',0)|getattr(os,'O_NONBLOCK',0)
    if os.open not in os.supports_dir_fd:
        checked_root(file.parent)
        return os.open(file,flags)
    absolute=file.absolute();directory=os.open(absolute.anchor,flags|os.O_DIRECTORY)
    try:
        for part in absolute.parts[1:-1]:
            next_fd=os.open(part,flags|os.O_DIRECTORY,dir_fd=directory);os.close(directory);directory=next_fd
        return os.open(absolute.name,flags,dir_fd=directory)
    finally:os.close(directory)
def checked_root(value,create=False):
    target=Path(value).expanduser().absolute();current=Path(target.anchor)
    for part in target.parts[1:]:
        if part in ('.','..'):raise ValueError('UNSAFE_ROOT')
        current/=part
        try:n=current.lstat()
        except FileNotFoundError:
            if not create:raise ValueError('SOURCE_MISSING')
            current.mkdir(mode=0o700);n=current.lstat()
        if linked(n) or not stat.S_ISDIR(n.st_mode):raise ValueError('DIRECTORY_LINK_OR_SPECIAL')
    return target
def source_file(root,relative):
    current=root;parts=relative.split('/')
    for i,part in enumerate(parts):
        if not part or part in ('.','..') or '\\' in part:raise ValueError('UNSAFE_PATH')
        current/=part;n=current.lstat()
        if linked(n) or i<len(parts)-1 and not stat.S_ISDIR(n.st_mode):raise ValueError('SOURCE_LINK')
    return current
def inventory(root,skips=frozenset()):
    rows=[];total=0
    fields=lambda n:(n.st_dev,n.st_ino,n.st_size,n.st_mtime_ns,n.st_ctime_ns)
    def visit(file,relative):
        nonlocal total
        before=file.lstat()
        if linked(before):raise ValueError('SOURCE_LINK_OR_SPECIAL')
        if len(rows)>=100000 or len(file.parts)-len(root.parts)>64:raise ValueError('TREE_LIMIT')
        if stat.S_ISDIR(before.st_mode):
            rows.append([relative,'dir'])
            for child in sorted(file.iterdir()):
                if child.name not in skips:visit(child,str(child.relative_to(root)).replace(os.sep,'/'))
        elif stat.S_ISREG(before.st_mode):
            total+=before.st_size
            if total>16*1024**3:raise ValueError('BYTE_LIMIT')
            fd=read_fd(file);digest=hashlib.sha256()
            with os.fdopen(fd,'rb') as handle:
                if fields(os.fstat(handle.fileno()))!=fields(before):raise ValueError('SOURCE_CHANGED')
                for block in iter(lambda:handle.read(65536),b''):digest.update(block)
                if fields(os.fstat(handle.fileno()))!=fields(before):raise ValueError('SOURCE_CHANGED')
            if fields(file.lstat())!=fields(before):raise ValueError('SOURCE_CHANGED')
            rows.append([relative,'file',before.st_size,digest.hexdigest()])
        else:raise ValueError('SOURCE_LINK_OR_SPECIAL')
    visit(root,'');return rows
def copy_verified(source,target,skips=frozenset()):
    before=inventory(source,skips)
    for relative,kind,*payload in before:
        dst=target/relative
        if kind=='dir':dst.mkdir(mode=0o700,parents=True,exist_ok=True)
        else:
            dst.parent.mkdir(mode=0o700,parents=True,exist_ok=True);src=source if not relative else source_file(source,relative)
            fd=read_fd(src)
            with os.fdopen(fd,'rb') as reader,dst.open('xb') as writer:shutil.copyfileobj(reader,writer,65536);writer.flush();os.fsync(writer.fileno())
            os.chmod(dst,0o700 if src.stat().st_mode&0o100 else 0o600)
    if inventory(source,skips)!=before or inventory(target)!=before:raise ValueError('COPY_CHECKSUM')
def file_hash(file):
    digest=hashlib.sha256()
    with file.open('rb') as handle:
        for block in iter(lambda:handle.read(65536),b''):digest.update(block)
    return digest.hexdigest()
def main():
    sys.stdout.reconfigure(encoding='utf-8');sys.stderr.reconfigure(encoding='utf-8')
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('command',choices=['plan','export']);parser.add_argument('--home',default=os.environ.get('DSH_HOME') or str(Path.home()/'.dsh'));parser.add_argument('--profile',default='web');parser.add_argument('--out');parser.add_argument('--with-workspace',action='append',default=[]);parser.add_argument('--with-credentials',action='store_true');args=parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}',args.profile):raise ValueError('UNSAFE_PROFILE')
    home=checked_root(args.home);workspaces=[checked_root(w) for w in args.with_workspace];included=[]
    if len(set(workspaces))!=len(workspaces):raise ValueError('WORKSPACE_DUPLICATE')
    for rule in POLICY['include']:
        relative=rule['path'].format(profile=args.profile)
        try:source=source_file(home,relative)
        except FileNotFoundError:continue
        kind=source.lstat().st_mode
        if rule['kind']=='dir' and not stat.S_ISDIR(kind) or rule['kind']=='file' and not stat.S_ISREG(kind):raise ValueError('SOURCE_TYPE')
        included.append((relative,source,set(rule.get('skip',[]))))
    if args.command=='plan':print(json.dumps({'home':str(home),'include':[r for r,_,_ in included],'workspaces':[str(w) for w in workspaces],'credentials':args.with_credentials},ensure_ascii=False));return
    out=checked_root(args.out or '/sdcard/Download/DSHA',True)
    for source in [home]+workspaces:
        if out==source or source in out.parents:raise ValueError('OUTPUT_SOURCE_OVERLAP')
    name='DSHA-portable-'+uuid.uuid4().hex+'.tar.gz'
    with tempfile.TemporaryDirectory(prefix='dsha-portable-') as temporary:
        stage=Path(temporary)/'pkg';stage.mkdir();(stage/'home').mkdir()
        for relative,source,skips in included:
            target=stage/'home'/relative;target.parent.mkdir(parents=True,exist_ok=True);copy_verified(source,target,skips)
        if args.with_credentials:
            credential=source_file(home,'.credentials.yaml')
            if credential.stat().st_size>1024**2:raise ValueError('CREDENTIAL_LIMIT')
            with os.fdopen(read_fd(credential),'rb') as reader:
                if not stat.S_ISREG(os.fstat(reader.fileno()).st_mode):raise ValueError('CREDENTIAL_TYPE')
                data=reader.read(1024**2+1)
                if len(data)>1024**2:raise ValueError('CREDENTIAL_LIMIT')
            filtered=subprocess.run(['node',str(HERE/'portable-yaml.mjs')],input=data,capture_output=True,timeout=60,check=True);target=stage/'home'/'.credentials.yaml';target.write_bytes(filtered.stdout);os.chmod(target,0o600)
        for i,workspace in enumerate(workspaces):
            target=stage/'workspace' if len(workspaces)==1 else stage/'workspace'/('ws'+str(i+1));target.parent.mkdir(parents=True,exist_ok=True);copy_verified(workspace,target,SKIP)
        manifest={'schemaVersion':2,'kind':'dsha-portable-package','createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'source':{'dshHome':str(home),'dshVersion':'unknown','profile':args.profile},'options':{'workspaces':[str(w) for w in workspaces],'withCredentials':args.with_credentials,'rewriteCwd':False},'excluded':POLICY['exclude']}
        (stage/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');(stage/'README-在电脑上恢复.md').write_text('npm ci --ignore-scripts；先 plan --package <包> --home <目标> --workspace-dir <目录>，再 apply。多工作区各自落入ws1/ws2；旧home、原包与回执保留。默认不恢复凭据。\n',encoding='utf-8');before=inventory(stage);archive=Path(temporary)/name
        with tarfile.open(archive,'w:gz',format=tarfile.PAX_FORMAT) as writer:
            for child in sorted(stage.iterdir()):writer.add(child,arcname=child.name,recursive=True)
        if inventory(stage)!=before:raise ValueError('STAGE_CHANGED')
        if archive.stat().st_size>4*1024**3:raise ValueError('PACKAGE_LIMIT')
        target=out/name
        with archive.open('rb') as reader,target.open('xb') as writer:shutil.copyfileobj(reader,writer,65536);writer.flush();os.fsync(writer.fileno())
        digest=file_hash(archive)
        if file_hash(target)!=digest:raise ValueError('OUTPUT_CHECKSUM')
        with (out/(name+'.sha256')).open('x',encoding='ascii') as receipt:receipt.write(digest+'  '+name+'\n')
        print(json.dumps({'package':str(target),'sha256':digest,'workspaces':len(workspaces)},ensure_ascii=False))
if __name__=='__main__':
    try:main()
    except Exception as error:print('导出失败：'+type(error).__name__+': '+str(error),file=sys.stderr);sys.exit(2)
