"""Project the pinned bxroot native bytes into a signed APK asset; no device execution."""
import argparse
import json
from pathlib import Path
from source_text import write_text, matches_text
from native_runtime_proof import ROOT, digest, load_bxroot_lock


def projection(root=ROOT):
    lock=load_bxroot_lock(root)
    native={row['name']:row['sha256'] for row in lock['artifacts']}
    for name,sha in native.items():
        path=Path(root)/'app/src/main/jniLibs/arm64-v8a'/name
        if not path.is_file() or digest(path.read_bytes())!=sha:
            raise ValueError('BXROOT_PROJECTION_NATIVE_CHANGED:'+name)
    return dict(schema=1,sourceCommit=lock['source']['commit'],
                sourceLockSha256=digest((Path(root)/'tools/bxroot/lock.json').read_bytes()),
                nativeHashes=dict(sorted(native.items())))


def verify_asset(content, root=ROOT):
    if json.loads(content)!=projection(root):
        raise ValueError('BXROOT_NATIVE_PROJECTION_CHANGED')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    mode=parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--write',action='store_true');mode.add_argument('--check',action='store_true')
    args=parser.parse_args()
    content=json.dumps(projection(),ensure_ascii=False,indent=2)+'\n'
    target=ROOT/'app/src/main/assets/bxroot-native-lock.json'
    if args.check:
        if not matches_text(target,content,encoding='utf8'):
            raise ValueError('BXROOT_NATIVE_PROJECTION_STALE')
    else:
        write_text(target,content,encoding='utf8')
    print('PASS exact bxroot native lock projection; no device execution')


if __name__=='__main__':
    main()
