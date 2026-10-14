#!/usr/bin/env python3
"""Structural guard for native-dependency ownership/order, not ELF loading or Android execution."""
from pathlib import Path
import re
import sys
from verification_require import require

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/deepseekharness/app/runtime'
EMERGENCY = JAVA.parent / 'recovery/RecoveryRuntime.java'


def compact(source):
    return re.sub(r'\s+', '', source)


def ordered_once(source, anchors, message):
    text = compact(source)
    anchors = [compact(anchor) for anchor in anchors]
    require(all(text.count(anchor) == 1 for anchor in anchors), message + '_ANCHOR_COUNT')
    positions = [text.index(anchor) for anchor in anchors]
    require(positions == sorted(positions), message + '_ORDER')


def method_body(source, signature):
    index = source.find(signature)
    require(index >= 0, 'NATIVE_DEPENDENCY_METHOD_MISSING:' + signature)
    start = source.index('{', index)
    depth = 0
    for end in range(start, len(source)):
        depth += (source[end] == '{') - (source[end] == '}')
        if depth == 0:
            return source[start:end + 1]
    raise ValueError('NATIVE_DEPENDENCY_METHOD_UNCLOSED:' + signature)


def validate(bootstrap, native, toolchain, emergency):
    ensure = method_body(bootstrap, 'public void ensureRuntimeFiles()')
    require('nativeFiles.prepare();' in ensure, 'BOOTSTRAP_NATIVE_OWNER_NOT_CALLED')
    require(ensure.index('nativeFiles.prepare();') < ensure.index('ensureDshRuntimePatches();'),
            'BOOTSTRAP_NATIVE_PREPARATION_ORDER')
    preparation = method_body(native, 'void prepare()')
    require(not re.search(r'runtime\(|getContainerRuntime|"proot"|"proroot"', preparation),
            'NATIVE_PREPARATION_GATED_BY_USER_RUNTIME')
    for source, target in [('libtalloc.so', 'libtalloc.so.2'),
                           ('libandroidshmem.so', 'libandroid-shmem.so')]:
        copy = r'copy\(find\("' + re.escape(source) + r'"\),\s*new File\(lib,\s*"' + re.escape(target) + r'"\)\);'
        require(len(re.findall(copy, preparation)) == 1, 'NATIVE_DEPENDENCY_COPY_MISSING:' + target)
    for signature in ('public BoundedProcessRunner.Result runRecoveryMaintenance(',
                      'public BoundedProcessRunner.Result runPersonalMaintenance('):
        caller = method_body(bootstrap, signature)
        require('return recovery.run(command, onLine, timeoutMs);' in caller,
                'RECOVERY_TOOLCHAIN_ENTRY_NOT_DELEGATED:' + signature)
    launch = method_body(toolchain, 'BoundedProcessRunner.Result run(')
    require('runtime().id()' not in launch and 'getContainerRuntime' not in launch,
            'RECOVERY_LAUNCH_GATED_BY_USER_RUNTIME')
    require('new ContainerRuntime.Proot(' not in launch and 'builder.start()' not in launch,
            'RECOVERY_MUST_USE_ENGINE_SELECTOR_AND_SUPERVISOR')
    ordered_once(launch, [
        'requireOwner("独立恢复必须持有维护停止屏障");',
        'ports.open()',
        'File root = prepareRoot();',
        'nativeFiles.prepare();',
        'ContainerRuntime runtime = ports.engine(context, false).runtime;',
        'new RuntimeLauncher(context, root, root.getParentFile(), lib, tmp, ports)',
        'runtime = independent.preflight(runtime, false);',
        'runtime.baseArgv(root, false, null)',
        'runtime.applyEnv(builder, root.getParentFile(), lib, tmp, root);',
        'IsolatedInstallProcess.start(builder, tmp, context, null)',
        'BoundedProcessRunner.collect(process, timeoutMs, 256 * 1024, Compat::destroy, onLine)',
    ], 'RECOVERY_SELECTED_NATIVE_LAUNCH')
    run = compact(launch)
    require('source.getAbsolutePath()+":/run/dsha-maintenance-root"' in run
            and 'home.getAbsolutePath()+":/root"' in run
            and 'argv.add("/bin/bash");' in run,
            'RECOVERY_SOURCE_DATA_MUST_NOT_REPLACE_TOOL_ROOT')
    require('builder.environment().put("PATH","/usr/bin:/bin");' in run
            and 'if("proot".equals(runtime.id()))builder.environment().put("PROOT_L2S_DIR",'
            'newFile(root,".l2s").getAbsolutePath());' in run,
            'RECOVERY_PROOT_TOOL_ENVIRONMENT_BOUNDARY')
    require('work.retainUntilExit(process);' in run and 'ProcessTermination.exited(process)' in run,
            'RECOVERY_UNKNOWN_EXIT_MUST_RETAIN_WORK')
    preparation = compact(method_body(toolchain, 'private synchronized File prepareRoot()'))
    require('"recovery-maintenance-tools/"+java.util.UUID.randomUUID()' in preparation
            and 'TarGzipExtractor.extractSelected(' in preparation
            and 'RootfsInstaller.python(context,root);' in preparation,
            'RECOVERY_MAINTENANCE_REQUIRES_INDEPENDENT_SIGNED_TOOL_ROOT')

    # The independent emergency Web has its own root/HOME and the same selector,
    # not a hard-coded proot path or a formal-root compatibility shortcut.
    prepare = compact(method_body(emergency, 'public void prepare()'))
    constructor = compact(method_body(emergency, 'public RecoveryRuntime('))
    require('directory(files,"recovery-capsules")' in prepare
            and 'rootfs=newFile(capsule,"linux/ubuntu");' in prepare
            and 'home=directory(session,"home");' in constructor,
            'EMERGENCY_ROOT_AND_HOME_MUST_REMAIN_INDEPENDENT')
    verify = compact(method_body(emergency, 'private void verifyNativeLibraries()'))
    for name in ('libproot.so', 'libproot_legacy.so', 'libprootloader.so',
                 'libprootloader_legacy.so', 'libtalloc.so', 'libandroidshmem.so',
                 'libbxroot.so', 'libbxroot-runtime.so', 'libbxroot-ulx.so',
                 'libdsha-session.so', 'libtermux.so'):
        require('"' + name + '"' in verify, 'EMERGENCY_LAUNCHER_PROOF_MISSING:' + name)
    require('getJSONObject("launcherHashes")' in verify
            and 'getJSONObject(BuildConfig.LOW_ANDROID?"low":"standard")' in verify
            and 'if(!hashes.has(required))' in verify
            and 'verifyDigest(input,hashes.getString(name));' in verify,
            'EMERGENCY_LAUNCHERS_MUST_BE_VERIFIED_BY_FLAVOR')
    launch = method_body(emergency, 'public Process launch(')
    run = compact(launch)
    require('new ContainerRuntime.Proot(' not in launch and 'builder.start()' not in launch,
            'EMERGENCY_MUST_USE_ENGINE_SELECTOR_AND_SUPERVISOR')
    ordered_once(launch, [
        'verifyNativeLibraries();',
        'copyNative("libtalloc.so", new File(lib, "libtalloc.so.2"));',
        'copyNative("libandroidshmem.so", new File(lib, "libandroid-shmem.so"));',
        'if (engine == null)',
        'EngineSpec.select(context, engineSettings, false)',
        'RuntimeEngineQualification.fingerprint(',
        'RuntimeEngineQualification.check(',
        'var container = engine.runtime;',
        'container.baseArgv(rootfs, false, null)',
        'appendSessionBindings(argv, home, tmp);',
        'env.clear();',
        'container.applyEnv(builder, session, lib, tmp, rootfs);',
        'OwnedRuntimeSession.start(',
    ], 'EMERGENCY_SELECTED_NATIVE_LAUNCH')
    for boundary in ('env.put("HOME","/root");', 'env.put("DSH_HOME","/root/.dsh");',
                     'newFile(session,"native-session-id")'):
        require(boundary in run, 'EMERGENCY_PRIVATE_LAUNCH_BOUNDARY:' + boundary)
    session_binds = method_body(emergency, 'static void appendSessionBindings(')
    ordered_once(session_binds, [
        'argv.get(i + 1).startsWith("/storage/emulated/0:")',
        'bind(argv, home.getAbsolutePath(), "/root");',
        'bind(argv, tmp.getAbsolutePath(), "/tmp");',
    ], 'EMERGENCY_PRIVATE_SESSION_BINDING_ORDER')
    require(compact(session_binds).count('bind(argv,') == 2,
            'EMERGENCY_MUST_NOT_DUPLICATE_CONTAINER_BINDINGS')
    require(' --port 0"' in launch, 'EMERGENCY_DYNAMIC_PORT_REQUIRED')
    retry = compact(method_body(emergency, 'public boolean retryCompatible('))
    require('owned.confirmedTreeExit()' in retry
            and 'owned.commandWasNeverExecuted()' in retry
            and 'WebRuntimeFallback.shouldRetry(' in retry
            and 'neverExecuted,treeClosed,!neverExecuted)' in retry,
            'EMERGENCY_RETRY_REQUIRES_TRUSTED_NO_EXEC_AND_TREE_PROOF')
    ordered_once(retry, ['owned.closeConfirmed()',
                         'EngineSpec.select(context, engineSettings, true)',
                         'compatibleRetried = true;'], 'EMERGENCY_CLOSED_ONCE_RETRY')
    stop = compact(method_body(emergency, 'private static boolean stopRecorded('))
    require('if(!owned.closeConfirmed())returnfalse;' in stop
            and 'OwnedRuntimeSession.recordedExit(' in stop,
            'EMERGENCY_STOP_REQUIRES_LIVE_OR_DURABLE_OWNED_TREE_PROOF')


def mutation_guards(sources):
    # Keep this structural test honest: representative unsafe regressions must fail.
    mutations = [
        (0, 'nativeFiles.prepare();', '', 'unprepared bootstrap'),
        (1, 'copy(find("libtalloc.so"), new File(lib, "libtalloc.so.2"));', '', 'missing proot dependency'),
        (2, 'ports.engine(context, false).runtime', 'new ContainerRuntime.Proot(context)', 'hard-coded maintenance engine'),
        (2, 'runtime = independent.preflight(runtime, false);', '', 'unqualified maintenance'),
        (2, 'IsolatedInstallProcess.start(builder, tmp, context, null)', 'builder.start()', 'unsupervised maintenance'),
        (2, 'work.retainUntilExit(process);', '', 'unknown maintenance exit releases work'),
        (3, 'bind(argv, home.getAbsolutePath(), "/root");', 'bind(argv, rootfs.getAbsolutePath(), "/root");', 'borrowed emergency home'),
        (3, 'appendSessionBindings(argv, home, tmp);', '', 'detached emergency session binds'),
        (3, 'bind(argv, tmp.getAbsolutePath(), "/tmp");', '', 'missing independent temporary root'),
        (3, 'bind(argv, home.getAbsolutePath(), "/root");', 'bind(argv, "/dev", "/dev"); bind(argv, home.getAbsolutePath(), "/root");', 'duplicated emergency platform binds'),
        (3, '"libdsha-session.so",', '', 'missing signed supervisor proof'),
        (3, 'owned.commandWasNeverExecuted()', 'explicitEngineFailure', 'stdout-based retry'),
        (3, 'if (!owned.closeConfirmed()) return false;', 'if (!owned.isAlive()) return false;', 'launcher-only stop'),
    ]
    for index, before, after, label in mutations:
        require(before in sources[index], 'MUTATION_ANCHOR_MISSING:' + label)
        candidate = list(sources)
        candidate[index] = candidate[index].replace(before, after, 1)
        try:
            validate(*candidate)
        except RuntimeError:
            continue
        raise RuntimeError('UNSAFE_NATIVE_BOUNDARY_MUTATION_ACCEPTED:' + label)
    return len(mutations)


def main():
    if sys.flags.optimize:
        raise SystemExit('TEST_ASSERTIONS_REQUIRE_UNOPTIMIZED_PYTHON')
    sources = tuple(path.read_text(encoding='utf-8') for path in (
        JAVA / 'ProotBootstrap.java', JAVA / 'RuntimeNativeFiles.java',
        JAVA / 'RecoveryToolchain.java', EMERGENCY))
    validate(*sources)
    mutations = mutation_guards(sources)
    print('PASS structure: unconditional proot dependencies; frozen bxroot/proot selection; '
          'independent recovery roots; supervised launch, trusted retry and exit barriers; '
          f'{mutations} unsafe mutations rejected (not ELF/Android execution)')


if __name__ == '__main__':
    main()
