"""Complete public source and offline input byte snapshot shared by host/package receipts."""
import hashlib
from pathlib import Path
import subprocess


def public_source_path(name):
    parts=name.replace('\\','/').split('/')
    return 'private-device-audits' not in parts and 'history' not in parts


def digest(path):
    value=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):value.update(block)
    return value.hexdigest()


def snapshot(root):
    root=Path(root)
    names=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=root).decode().split('\0')
    selected={}
    for name in sorted(set(names)-{''}):
        path=root/name
        if path.is_file() and public_source_path(name) and (name.startswith(('app/src/','tools/','gradle/','ci/','.github/','agent-skills/','website/'))
                or name in ('app/build.gradle','build.gradle','settings.gradle','gradle.properties','build.sh','gradlew','gradlew.bat')):
            selected[name]=digest(path)
    for pattern in ('*.bin','*.sha256','*.version','*.layout'):
        for path in (root/'app/src/main/assets').glob(pattern):selected[path.relative_to(root).as_posix()]=digest(path)
    # Managed asset trees include generated node_modules members ignored by Git.
    # Their physical bytes still affect both APK contents and runtime identity.
    if (root/'app/src/main/assets/managed-runtime-inputs.json').is_file():
        from runtime_input_contract import asset_paths, load
        spec=load(root)
        assets=root/'app/src/main/assets'
        # Source-only CI does not download offline inputs. Snapshot all present
        # files; package/descriptor gates separately require the full recipe.
        physical={**spec,
                  'assetFiles':[name for name in spec['assetFiles'] if (assets/name).is_file()],
                  'installs':[row for row in spec['installs'] if (assets/row['asset']).is_file()]}
        for path in asset_paths(root,physical):
            selected[path.relative_to(root).as_posix()]=digest(path)
    return selected
