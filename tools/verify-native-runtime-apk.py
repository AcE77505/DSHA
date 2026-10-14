"""Verify the actual signed/unsigned APK native closure against the current raw sources and bxroot lock."""
import argparse
import importlib.util
import json
from pathlib import Path
import zipfile
from native_runtime_proof import (ROOT, BXROOT_NAMES, PROOT_NAMES, digest, elf_info,
                                  source_native_members, dependency_native_members, load_bxroot_lock, infer_flavor)
from native_runtime_proof import packaged_native_bytes


def verify(path, flavor, root=ROOT):
    lock=load_bxroot_lock(root)
    sources=source_native_members(root,flavor)
    dependencies=dependency_native_members(root,flavor)
    if set(sources)&set(dependencies):
        raise ValueError('NATIVE_APK_DEPENDENCY_SOURCE_OVERLAP')
    rows={row['name']:row for row in lock['artifacts']}
    with zipfile.ZipFile(path) as apk:
        names=apk.namelist()
        if len(names)!=len(set(names)):
            raise ValueError('NATIVE_APK_DUPLICATE_MEMBER')
        if infer_flavor(apk)!=flavor:
            raise ValueError('NATIVE_APK_FLAVOR')
        if any(name.startswith('lib/') and not name.startswith('lib/arm64-v8a/') for name in names):
            raise ValueError('NATIVE_APK_OTHER_ABI')
        if any(name.startswith('lib/arm64-v8a/libproroot') for name in names):
            raise ValueError('NATIVE_APK_PROROOT_RETAINED')
        actual_bx={Path(name).name for name in names if name.startswith('lib/arm64-v8a/libbxroot')}
        if actual_bx!=set(BXROOT_NAMES):
            raise ValueError('NATIVE_APK_BXROOT_CLOSURE')
        actual_native={name for name in names if name.startswith('lib/') and not name.endswith('/')}
        expected_native={'lib/arm64-v8a/'+name for name in set(sources)|set(dependencies)}
        if actual_native!=expected_native:
            raise ValueError('NATIVE_APK_MEMBER_CLOSURE:'+','.join(sorted(actual_native^expected_native)))
        dependency_proofs=[]
        for name,row in sorted(dependencies.items()):
            content=apk.read('lib/arm64-v8a/'+name)
            if len(content)!=row['size'] or digest(content)!=row['sha256']:
                raise ValueError('NATIVE_APK_DEPENDENCY_CHANGED:'+name)
            dependency_proofs.append(row)
        proof_spec=importlib.util.spec_from_file_location('bxroot_native_projection',Path(root)/'tools/prepare-bxroot-native-proof.py')
        proof=importlib.util.module_from_spec(proof_spec);proof_spec.loader.exec_module(proof)
        source_proof=Path(root)/'app/src/main/assets/bxroot-native-lock.json'
        if apk.read('assets/bxroot-native-lock.json')!=source_proof.read_bytes():
            raise ValueError('NATIVE_APK_BXROOT_PROJECTION_BYTES')
        proof.verify_asset(apk.read('assets/bxroot-native-lock.json'),root)
        members=[];transforms=[]
        for name,source in sources.items():
            member='lib/arm64-v8a/'+name
            content=apk.read(member)
            expected,transformation=packaged_native_bytes(source,root)
            if digest(content)!=digest(expected):
                raise ValueError('NATIVE_APK_SOURCE_CHANGED:'+name)
            if transformation:transforms.append(transformation)
            if name in rows:
                row=rows[name]
                if digest(content)!=row['sha256'] or len(content)!=row['size']:
                    raise ValueError('NATIVE_APK_BXROOT_LOCK_CHANGED:'+name)
                info=elf_info(content)
                if info!=row['elf']:
                    raise ValueError('NATIVE_APK_BXROOT_ELF_CHANGED:'+name)
                if name in ('libbxroot.so','libbxroot-ulx.so'):
                    # ULX keeps the early signal handler alive while ld.so maps
                    # ET_EXEC guests at 0x400000; it must itself be static PIE.
                    required_type=3 if name=='libbxroot-ulx.so' else 2
                    if info['type']!=required_type or info['interp'] is not None or info['needed']:
                        raise ValueError('NATIVE_APK_BXROOT_STATIC_LAUNCHER:'+name)
                elif info['type']!=3 or info['interp'] is not None or 'libc.so.6' not in info['needed'] \
                        or not set(info['needed']).issubset({'libc.so.6','libdl.so.2','libpthread.so.0',
                                                           'librt.so.1','ld-linux-aarch64.so.1','libgcc_s.so.1'}):
                    raise ValueError('NATIVE_APK_BXROOT_GUEST_GLIBC_CLOSURE')
                members.append(dict(name=name,sha256=digest(content),bytes=len(content),elf=info))
        for row in lock.get('preservedProot',[]):
            source=Path(root)/row['path']
            if digest(source.read_bytes())!=row['sha256']:
                raise ValueError('NATIVE_PROOT_FALLBACK_SOURCE_CHANGED')
            if source.name in sources and apk.read('lib/arm64-v8a/'+source.name)!=packaged_native_bytes(source,root)[0]:
                raise ValueError('NATIVE_PROOT_FALLBACK_APK_CHANGED')
    return dict(flavor=flavor,apk=str(path),sha256=digest(Path(path).read_bytes()),
                bxrootSource=lock.get('source'),bxrootPolicy=lock['policy'],bxrootMembers=members,
                sourceNativeMembers=len(sources),fallback=PROOT_NAMES[flavor],status='PASS_NATIVE_BYTES_AND_ELF',
                dependencyNativeMembers=dependency_proofs,
                packagingTransforms=transforms,
                device='NOT_RUN_BY_THIS_TOOL',
                limitations=['ELF alignment is not execution on a physical 16 KiB kernel',
                             'No Android SELinux/seccomp, API23 or guest runtime execution is inferred'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apks',nargs=2,type=Path,help='Standard then Low actual release APKs')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    report=dict(schema=1,apks=[verify(path,flavor) for path,flavor in zip(args.apks,('standard','low'))])
    text=json.dumps(report,ensure_ascii=False,indent=2)+'\n'
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(text,encoding='utf8',newline='\n')
    print(text,end='')


if __name__=='__main__':
    main()
