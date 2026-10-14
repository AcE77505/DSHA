#!/usr/bin/env python3
"""Compile current export sources and run v5/SAF regressions without an APK or device.

Requires JDK17, existing StandardRelease dependency classes/R.jar, Android37,
the cached APK backup dependencies (their repository lock is verified), JUnit4.13.2,
Robolectric4.15.1 (native-dist-compat1.0.17), monitor1.8.0,
espresso-idling-resource3.6.1, and offline
android-all-instrumented SDK23/34 r1-i7 jars. No dependency download is performed.
Linux fixtures and framework copies live on /tmp; --require-posix rejects skips.
SAF is a fake DocumentsProvider using real framework calls. Windows models FD
identity; Linux obtains real opened host-FD metadata through /proc/self/fd.
These are software fixtures, not Android/FUSE/cloud-provider acceptance.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BACKUP = ROOT / 'app/src/main/java/com/deepseekharness/app/backup'
TESTS = ROOT / 'app/src/test/java/com/deepseekharness/app/backup'
MAIN_NAMES = ['BackupFileSystem', 'AndroidBackupFileSystem', 'OwnedBackupArtifact',
              'BackupPathFailure', 'BackupArchive', 'BackupErrorCode', 'HostSnapshot',
              'FileBackupSource', 'GuestDataResolver', 'NativeDataLocations',
              'NativePluginGraph', 'NewDocumentExport', 'SafExportDirectory',
              'DocumentStreams', 'PortableBackupEnvelope', 'NativeBackupJobs',
              'NativeBackupInput', 'MaintenanceDataSnapshot']
TEST_NAMES = ['ApplicationExportSafetyTest', 'NewDocumentExportTest',
              'SafExportDirectoryTest', 'BackupArchiveTest', 'BackupErrorCodeTest',
              'HostSnapshotTest', 'PasswordFreeBackupExportTest',
              'PortableBackupEnvelopeTest', 'LegacyTarReaderTest',
              'GuestDataResolverTest', 'NativePluginGraphTest']
RUNNER = '''import org.junit.runner.*;
import org.junit.runner.notification.*;
public final class ExportJUnitRunner {
  private static void source(String type, String expected) throws Exception {
    Class<?> loaded = Class.forName(type);
    String location = loaded.getProtectionDomain().getCodeSource().getLocation().toString();
    if (!location.endsWith("/" + expected)) throw new IllegalStateException("DEPENDENCY_DRIFT:" + type + ":" + location);
    System.out.println("LOADED_CODE_SOURCE: " + type + " " + location);
  }
  public static void main(String[] names) throws Exception {
    source("org.robolectric.RobolectricTestRunner", "robolectric-4.15.1.jar");
    source("org.robolectric.manifest.AndroidManifest", "resources-4.15.1.jar");
    source("com.google.gson.Gson", "gson-2.13.1.jar");
    source("org.bouncycastle.jce.provider.BouncyCastleProvider", "bcprov-jdk15to18-1.85.2.jar");
    source("org.yaml.snakeyaml.Yaml", "snakeyaml-2.4.jar");
    Class<?>[] classes = new Class<?>[names.length];
    for (int i = 0; i < names.length; i++) classes[i] = Class.forName(names[i]);
    JUnitCore core = new JUnitCore(); final int[] assumptions = {0};
    core.addListener(new RunListener() {
      @Override public void testAssumptionFailure(Failure f) {
        assumptions[0]++; System.out.println("ASSUMPTION_SKIP: " + f.getDescription() + " : " + f.getMessage());
      }
    });
    Result result = core.run(classes);
    for (Failure f : result.getFailures()) System.out.println(f.getTrace());
    System.out.println("RESULT: tests=" + result.getRunCount() + " failures=" + result.getFailureCount()
        + " ignored=" + result.getIgnoreCount() + " assumptions=" + assumptions[0]);
    System.exit(result.wasSuccessful() ? 0 : 1);
  }
}
'''


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def argfile(path, values):
    path.write_text('\n'.join('"' + str(v).replace('\\', '\\\\').replace('"', '\\"')
                              + '"' for v in values) + '\n',
                    encoding='mbcs' if os.name == 'nt' else 'utf-8')


def invoke(command, output, filename):
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True,
                            encoding='utf-8', errors='replace', timeout=600)
    (output / filename).write_text(result.stdout + result.stderr, encoding='utf-8')
    print(result.stdout + result.stderr, end='')
    return result.returncode


def cached(cache, group, module, version, extension='jar'):
    hits = sorted((cache / group / module / version).glob('**/' + module + '-' + version + '.' + extension))
    if not hits:
        raise ValueError('OFFLINE_DEPENDENCY_MISSING:' + group + ':' + module + ':' + version)
    return hits[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--jdk', type=Path, help='JDK17 root; defaults to JAVA_HOME or javac on PATH')
    parser.add_argument('--gradle-home', type=Path, default=Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')))
    parser.add_argument('--android-sdk', type=Path, default=Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', '/opt/android-sdk'))))
    parser.add_argument('--compiled-main', type=Path,
                        default=ROOT / 'app/build/intermediates/javac/standardRelease/compileStandardReleaseJavaWithJavac/classes',
                        help='Existing compiled dependencies; the 19 export production sources are recompiled')
    parser.add_argument('--robolectric-cache', type=Path,
                        default=Path.home() / '.m2/repository/org/robolectric/android-all-instrumented',
                        help='Offline Maven SDK23/34 instrumented jar cache')
    parser.add_argument('--output', type=Path,
                        default=ROOT / 'app/build/application-export' / ('windows' if os.name == 'nt' else 'linux'))
    parser.add_argument('--require-posix', action='store_true',
                        help='Require Linux /tmp with real identities/links and zero assumptions/ignored tests')
    args = parser.parse_args()
    driver_before = digest(Path(__file__))
    if sys.flags.optimize:
        raise ValueError('TEST_REQUIRES_PYTHON_ASSERTIONS')
    if args.require_posix and (not sys.platform.startswith('linux') or not Path('/proc/self/fd').is_dir()):
        raise ValueError('POSIX_LINUX_REQUIRED')
    if sys.platform.startswith('linux'):
        filesystem = subprocess.check_output(['stat', '-f', '-c', '%T', '/tmp'], text=True).strip()
        if filesystem in {'9p', 'drvfs', 'fuseblk'}:
            raise ValueError('REAL_LINUX_TMP_REQUIRED:' + filesystem)
    args.output.mkdir(parents=True, exist_ok=True)
    jdk = args.jdk or (Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME')
                      else Path(shutil.which('javac') or '/missing-javac').resolve().parent.parent)
    suffix = '.exe' if os.name == 'nt' else ''
    java, javac = jdk / ('bin/java' + suffix), jdk / ('bin/javac' + suffix)
    version = subprocess.run([str(java), '-version'], capture_output=True, text=True, timeout=30)
    if version.returncode or not re.search(r'version "17(?:[."])', version.stderr + version.stdout):
        raise ValueError('JDK17_REQUIRED')
    if not args.compiled_main.is_dir():
        raise ValueError('STANDARD_RELEASE_DEPENDENCY_CLASSES_MISSING')
    android = args.android_sdk / 'platforms/android-37.0/android.jar'
    if not android.is_file():
        raise ValueError('ANDROID37_MISSING')
    rjars = list(ROOT.glob('app/build/intermediates/compile_and_runtime_r_class_jar/standardRelease/**/R.jar'))
    if not rjars:
        raise ValueError('STANDARD_RELEASE_R_JAR_MISSING')
    rjar = max(rjars, key=lambda p: p.stat().st_mtime_ns)
    spec = importlib.util.spec_from_file_location('export_unit_dependencies', ROOT / 'tools/run-unit-tests.py')
    unit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(unit)
    unit.ensure_utf8_locale()
    locked, coordinates = unit.locked_backup_classpath(args.gradle_home)
    cache = args.gradle_home / 'caches/modules-2/files-2.1'
    selected = [cached(cache, 'junit', 'junit', '4.13.2'),
                cached(cache, 'org.hamcrest', 'hamcrest-core', '1.3')]
    selected += sorted(args.gradle_home.glob('caches/9.3.1/transforms/*/workspace/transformed/*/jars/classes.jar'))
    selected += [p for p in sorted(cache.glob('**/*.jar'))
                 if not p.name.endswith(('-sources.jar', '-javadoc.jar'))
                 and 'android-all-instrumented' not in p.parts
                 and not any(g in p.parts and n in p.parts for g, n in coordinates)
                 and ('org.robolectric' not in p.parts or '4.15.1' in p.parts
                      or ('nativeruntime-dist-compat' in p.parts and '1.0.17' in p.parts))]
    # No private source/result artifacts are inputs to this gate.
    sources = [BACKUP / (name + '.java') for name in MAIN_NAMES]
    sources += [ROOT / 'app/src/main/java/com/deepseekharness/app/ui/NativeDataActivity.java']
    sources += [TESTS / (name + '.java') for name in TEST_NAMES] + [TESTS / 'JvmBackupFileSystem.java']
    before = {p.relative_to(ROOT).as_posix(): digest(p) for p in sources}
    temporary_root = '/tmp' if sys.platform.startswith('linux') else None
    with tempfile.TemporaryDirectory(prefix='dsha-export-gate-', dir=temporary_root) as directory:
        work = Path(directory)
        framework = work / 'framework'; framework.mkdir()
        dependency_hashes = {}
        for sdk in ('6.0.1_r3-robolectric-r1-i7', '14-robolectric-10818077-i7'):
            source = args.robolectric_cache / sdk / ('android-all-instrumented-' + sdk + '.jar')
            if not source.is_file():
                raise ValueError('OFFLINE_ANDROID_FRAMEWORK_MISSING:' + sdk)
            shutil.copyfile(source, framework / source.name)
            dependency_hashes[str(source)] = digest(source)
            if digest(framework / source.name) != dependency_hashes[str(source)]:
                raise ValueError('OFFLINE_FRAMEWORK_COPY_CHANGED:' + sdk)
        aars = [cached(cache, 'androidx.test', 'monitor', '1.8.0', 'aar'),
                cached(cache, 'androidx.test.espresso', 'espresso-idling-resource', '3.6.1', 'aar')]
        for aar in aars:
            target = framework / (aar.stem + '.jar')
            with zipfile.ZipFile(aar) as archive:
                target.write_bytes(archive.read('classes.jar'))
            selected.append(target)
            dependency_hashes[str(aar)] = digest(aar)
        cp = [android, args.compiled_main, rjar] + list(map(Path, locked)) + selected
        cp = list(dict.fromkeys(p.resolve() for p in cp))
        for path in cp:
            if path.is_file():
                dependency_hashes[str(path)] = digest(path)
        (args.output / 'dependency-paths.json').write_text(json.dumps(list(map(str, cp)), indent=2) + '\n')
        runner = work / 'ExportJUnitRunner.java'; runner.write_text(RUNNER, encoding='utf-8')
        classes = work / 'classes'; classes.mkdir()
        compile_args = work / 'javac.args'
        argfile(compile_args, ['-encoding', 'UTF-8', '--release', '17', '-cp', os.pathsep.join(map(str, cp)),
                               '-d', classes] + sources + [runner])
        compile_code = invoke([str(javac), '-J-Dfile.encoding=UTF-8', '@' + str(compile_args)], args.output, 'compile.log')
        code = compile_code
        if not compile_code:
            java_args = work / 'java.args'
            argfile(java_args, ['--add-opens=java.base/java.io=ALL-UNNAMED', '-Dfile.encoding=UTF-8',
                               '-Djava.io.tmpdir=' + str(work), '-Drobolectric.offline=true',
                               '-Drobolectric.dependency.dir=' + str(framework), '-cp',
                               os.pathsep.join(map(str, [classes] + cp)), 'ExportJUnitRunner']
                    + ['com.deepseekharness.app.backup.' + name for name in TEST_NAMES])
            code = invoke([str(java), '@' + str(java_args)], args.output, 'junit.log')
    after = {p.relative_to(ROOT).as_posix(): digest(p) for p in sources}
    summary = None if compile_code else re.search(r'RESULT: tests=(\d+) failures=(\d+) ignored=(\d+) assumptions=(\d+)',
                                                  (args.output / 'junit.log').read_text(encoding='utf-8'))
    counts = dict(zip(['tests', 'failures', 'ignored', 'assumptions'], map(int, summary.groups()))) if summary else None
    driver_after = digest(Path(__file__))
    loaded = [] if compile_code else re.findall(r'^LOADED_CODE_SOURCE: (.+)$', (args.output / 'junit.log').read_text(encoding='utf-8'), re.MULTILINE)
    passed = (code == 0 and before == after and driver_before == driver_after
              and len(loaded) == 5 and counts is not None and counts['tests'] >= 85
              and (not args.require_posix or counts['ignored'] == counts['assumptions'] == 0))
    (args.output / 'receipt.json').write_text(json.dumps({
        'status': 'PASS_APPLICATION_EXPORT' if passed else 'FAIL_APPLICATION_EXPORT',
        'exitCode': code, 'sourceStable': before == after, 'sourceSha256': after,
        'driverSha256': driver_after, 'driverStable': driver_before == driver_after,
        'dependencySha256': dependency_hashes, 'loadedCodeSources': loaded,
        'junit': counts, 'requirePosix': args.require_posix,
        'sources': list(before), 'testClasses': TEST_NAMES,
        'scope': __doc__, 'jdk': version.stderr.strip(),
        'fixtureFilesystem': filesystem if sys.platform.startswith('linux') else 'Windows FD identity model',
    }, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return 0 if passed else code or 2


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print('APPLICATION_EXPORT_GATE_ERROR: ' + str(error), file=sys.stderr)
        raise SystemExit(2)
