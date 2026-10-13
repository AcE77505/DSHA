#!/usr/bin/env python3
"""仅编译本组生产源与关联测试；未改依赖用现有正式编译类，不生成 R 或 APK。"""
import hashlib, importlib.util, json, os, subprocess, tempfile, zipfile, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('unit_runner',ROOT/'tools/run-unit-tests.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
production={
 'backup':['BackupFileSystem','AndroidBackupFileSystem','AndroidTreeFileSystem','OwnershipCheckFailure','FactoryReset','ColdInstallTransaction','TreeDigestCache','NativeRestorePlan'],
 'runtime':['OwnedRuntimeSession','IsolatedInstallProcess','ColdToolsInstaller'],
 'recovery':['RecoveryRuntime'],
 'util':['BoundedProcessRunner','ColdInstallPlan','PortableExportPlan']}
tests={
 'backup':['JvmBackupFileSystem','BackupArchiveTest','NativeRestorePlanTest','ColdInstallTransactionTest','TreeDigestCacheTest','OwnershipCheckFailureTest'],
 'runtime':['OwnedRuntimeSessionTest','IsolatedInstallProcessTest'],
 'util':['PortableExportPlanTest','BoundedProcessRunnerTest','ColdInstallPlanTest']}
def main():
    sys.stdout.reconfigure(encoding='utf-8')
    gh=runner.gradle_user_home();aj=runner.android_jar();locked,coordinates=runner.locked_backup_classpath(gh)
    jars=[str(p) for p in gh.glob('caches/modules-2/files-2.1/**/*.jar') if 'sources' not in p.name and 'javadoc' not in p.name and not any(g in p.parts and n in p.parts for g,n in coordinates)]
    old=ROOT/'app/build/intermediates/javac/standardRelease/compileStandardReleaseJavaWithJavac/classes'
    if not old.is_dir():raise ValueError('UNCHANGED_PRODUCTION_DEPENDENCIES_MISSING')
    junit=runner.find_jar(gh,'junit-4.13.2');hamcrest=runner.find_jar(gh,'hamcrest-core-1.3')
    cp=[str(aj),str(old),str(junit),str(hamcrest)]+locked+jars
    inputs=[ROOT/'app/src/main/java/com/deepseekharness/app'/package/(name+'.java') for package,names in production.items() for name in names]
    test_inputs=[ROOT/'app/src/test/java/com/deepseekharness/app'/package/(name+'.java') for package,names in tests.items() for name in names]
    target=ROOT/'artifacts/pr171/data';target.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='dsha-data-java-') as temporary:
        work=Path(temporary);runner._javac([str(p) for p in inputs],work/'main',cp)
        runner._javac([str(p) for p in test_inputs],work/'test',[str(work/'main')]+cp)
        classes=['com.deepseekharness.app.'+package+'.'+name for package,names in tests.items() for name in names if name.endswith('Test')]
        # 子进程测试会传递 java.class.path；用标准 manifest jar 避免 Windows argv 32K 上限。
        urls=[Path(p).resolve().as_uri()+('/' if Path(p).is_dir() else '') for p in [str(work/'test'),str(work/'main')]+cp]
        value='Class-Path: '+' '.join(urls);lines=[]
        while len(value)>70:lines.append(value[:70]);value=' '+value[70:]
        lines.append(value);manifest='Manifest-Version: 1.0\r\n'+'\r\n'.join(lines)+'\r\n\r\n'
        jar=work/'classpath.jar'
        with zipfile.ZipFile(jar,'w') as archive:archive.writestr('META-INF/MANIFEST.MF',manifest)
        args=work/'run.args';runner.write_argfile(args,['-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Ddsha.assetsDir='+str(ROOT/'app/src/main/assets'),'-cp',str(jar),'org.junit.runner.JUnitCore']+classes,encoding='mbcs' if os.name=='nt' else 'utf-8')
        result=subprocess.run(['java','-Dfile.encoding=UTF-8','@'+str(args)],capture_output=True,text=True,encoding='utf-8',errors='replace')
        (target/'java-tests.log').write_text(result.stdout+result.stderr,encoding='utf-8')
        (target/'java-tests.json').write_text(json.dumps({'sourceHashes':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs+test_inputs},'classes':classes,'exit':result.returncode,'scope':'current changed production sources; unchanged production dependencies from existing StandardRelease compile; no APK or Android device'},indent=2),encoding='utf-8')
        print(result.stdout+result.stderr);return result.returncode
if __name__=='__main__':raise SystemExit(main())
