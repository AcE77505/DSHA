#!/usr/bin/env python3
"""Real AArch64 engine probes; the runner must supply native or binfmt execution."""
import argparse,json,os,subprocess,tempfile,platform,socket,struct
from pathlib import Path

def main():
    p=argparse.ArgumentParser();p.add_argument('--native',type=Path,required=True);p.add_argument('--rootfs',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--supervisor',type=Path);p.add_argument('--case',action='append');a=p.parse_args()
    l2s=a.rootfs/'.l2s';l2s.mkdir(exist_ok=True)
    env=dict(os.environ,DSHA_BXROOT_STRICT_EXEC='1',BXROOT_NO_LIVEPATCH='1',BXROOT_LIB_PATH=str(a.native/'libbxroot-runtime.so'),BXROOT_TMP_DIR='/tmp',BXROOT_L2S_DIR=str(l2s),BXROOT_REENTRY='0',BXROOT_AUTO_HOST='0')
    for k in list(env):
        if k.startswith('PROROOT_') or k.startswith('PROOT_'):del env[k]
    rows=[]
    # WSL may tear down the test kernel between calls. Register only our own
    # transient binfmt entry, then remove it in finally; no host config files change.
    registration=Path('/proc/sys/fs/binfmt_misc/dsha-bxroot-local-test')
    registered=False
    if platform.machine()!='aarch64':
        if registration.exists(): raise RuntimeError('BINFORMAT_TEST_NAME_ALREADY_OWNED')
        conf=Path('/usr/lib/binfmt.d/qemu-aarch64.conf').read_text().replace(':qemu-aarch64:',':dsha-bxroot-local-test:')
        Path('/proc/sys/fs/binfmt_misc/register').write_text(conf)
        registered=True
    def run(name,command,expect=0,marker=None):
        if a.case and name not in a.case:return
        argv=[str(a.native/'libbxroot.so'),'-r',str(a.rootfs),'-0','-l','-w','/root','--kill-on-exit','-b','/proc:/proc','-b','/dev:/dev','-b',str(l2s)+':'+str(l2s),'/bin/bash','-c',command]
        events=[]
        if a.supervisor:
            control_name='dsha-bxroot-'+os.urandom(16).hex()
            with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as server:
                server.bind('\0'+control_name);server.listen(1);server.settimeout(8)
                process=subprocess.Popen([str(a.supervisor),'--control='+control_name,'--']+argv,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
                first=process.stdout.readline().decode();assert first.startswith('DSHA_SESSION_STAT_V1 '+str(process.pid)+' '),first
                control,_=server.accept()
                with control:
                    control.settimeout(40)
                    peer=struct.unpack('3i',control.getsockopt(socket.SOL_SOCKET,socket.SO_PEERCRED,12));assert peer[0]==process.pid and peer[1]==os.getuid(),peer
                    reader=control.makefile('rb');assert reader.readline().strip()==b'READY DSHA_OWNED_TREE_V2'
                    control.sendall(b'START\n');assert reader.readline().strip()==b'STARTED'
                    while True:
                        line=reader.readline().decode().strip();events.append(line)
                        if line=='OWNED_TREE_EMPTY ECHILD':break
                        if not line or line.startswith('UNCONFIRMED'):raise RuntimeError('OWNED_TREE_UNCONFIRMED '+repr(events))
                    control.sendall(b'COMMIT\n')
                    output=process.communicate(timeout=8)[0].decode();code=process.returncode
                    expect=expect if expect>=0 else 128-expect
                    assert 'COMMAND_EXITED '+str(code) in events,events
        else:
            r=subprocess.run(argv,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=40,text=True);output=r.stdout;code=r.returncode
        ok=code==expect and (marker is None or marker in output)
        rows.append({'name':name,'exit':code,'expectedExit':expect,'passed':ok,'controlEvents':events,'output':output[-12000:]})
        print(name,'PASS' if ok else 'FAIL',flush=True)
        if not ok:raise RuntimeError(name+': '+output)
    try:
        run('guest-rootfs','test -e /DSHA_BXROOT_GUEST_MARKER && test ! -e /DSHA_BXROOT_HOST_MARKER && printf ROOTFS_OK',marker='ROOTFS_OK')
        run('exit-propagation','exit 7',expect=7)
        run('shell-subprocess','/bin/sh -c "printf CHILD_OK"',marker='CHILD_OK')
        run('write-read-rename','printf payload > /tmp/dsha-a; mv /tmp/dsha-a /tmp/dsha-b; test "$(cat /tmp/dsha-b)" = payload && rm /tmp/dsha-b && printf FILE_OK',marker='FILE_OK')
        run('absolute-symlink','ln -sf /DSHA_BXROOT_GUEST_MARKER /tmp/dsha-link; cat /tmp/dsha-link; rm /tmp/dsha-link',marker='GUEST_MARKER')
        run('l2s','printf HARDLINK > /tmp/dsha-original; ln /tmp/dsha-original /tmp/dsha-alias; test "$(cat /tmp/dsha-alias)" = HARDLINK; rm /tmp/dsha-original /tmp/dsha-alias; printf L2S_OK',marker='L2S_OK')
        run('node-worker','node -e \'const {Worker}=require("worker_threads"); let n=0; for(let i=0;i<4;i++){let w=new Worker(`require("crypto").pbkdf2Sync("a","b",100,16,"sha256")`,{eval:true}); w.on("exit",c=>{if(c)process.exit(c);if(++n===4)console.log("WORKERS_OK")})}\'',marker='WORKERS_OK')
        run('raw-statx','/tmp/dsha-probe raw-stat',marker='RAW_STATX_OK')
        run('unix-socket','/tmp/dsha-probe unix-socket',marker='UNIX_SOCKET_OK')
        run('shm-gates','/tmp/dsha-probe shm-gates',marker='SHM_DEFAULT_ENOSYS_OK')
        # QEMU's host /proc includes its own helper thread. The production
        # single-thread requirement correctly declines whole static loading.
        # Test the actual patched scanner/dispatcher separately in AArch64.
        if platform.machine()=='aarch64':
            run('static-readable-subset','/tmp/dsha-static-reader',marker='GUEST_MARKER')
        else:
            run('static-emulator-thread-view-rejected','/tmp/dsha-static-reader',expect=126,marker='DSHA_BXROOT_ENGINE_FAILURE')
        run('actual-static-scanner-dispatch','/tmp/dsha-static-source-test',marker='PATCHED_STATIC_SCAN_DISPATCH_OK')
        run('static-spawn-attrs-rejected','/tmp/dsha-probe static-spawn-attrs',marker='STATIC_SPAWN_REJECT_OK')
        run('static-multithread-exec-rejected','/tmp/dsha-probe static-exec-threads',marker='STATIC_THREAD_REJECT_OK')
        run('static-opaque-rejected','/tmp/dsha-probe static-opaque',marker='STATIC_OPAQUE_REJECT_OK')
        if platform.machine()=='aarch64':
            run('static-nested-exec-rejected','/tmp/dsha-static-exec',marker='STATIC_EXEC_REJECT_OK')
        else:
            run('static-nested-emulator-declined','/tmp/dsha-static-exec',expect=126,marker='DSHA_BXROOT_ENGINE_FAILURE')
        run('dynamic-trampoline-failure-rejected','/tmp/dsha-probe trampoline-failure',marker='TRAMPOLINE_REJECT_OK')
        run('missing-exec-preserves-errno','/tmp/dsha-probe missing-exec',marker='MISSING_EXEC_ENOENT_OK')
        run('strict-survives-env-i','/usr/bin/env -i /bin/sh -c "/tmp/dsha-probe trampoline-failure"',marker='TRAMPOLINE_REJECT_OK')
        run('strict-survives-empty-env-exec','/tmp/dsha-probe empty-env-descendant',marker='TRAMPOLINE_REJECT_OK')
        run('strict-survives-node-empty-env','node -e \'const r=require("child_process").spawnSync("/tmp/dsha-probe",["trampoline-failure"],{env:{},encoding:"utf8"});process.stdout.write(r.stdout||"");process.stderr.write(r.stderr||"");process.exit(r.status===null?99:r.status)\'',marker='TRAMPOLINE_REJECT_OK')
        run('strict-survives-unset','/tmp/dsha-probe strict-unset',marker='STRICT_UNSET_REJECT_OK')
        run('unresolved-fd-execveat-rejected','/tmp/dsha-probe unresolved-fd',marker='UNRESOLVED_FD_REJECT_OK')
        env['BXROOT_NO_ULX']='1'
        env['DSHA_BXROOT_STRICT_EXEC']='0'
        try: run('initial-ulx-disabled-rejected','printf SHOULD_NOT_RUN',expect=126,marker='DSHA_BXROOT_ENGINE_FAILURE')
        finally:
            del env['BXROOT_NO_ULX'];env['DSHA_BXROOT_STRICT_EXEC']='1'
        for name,ending,code in [('return','exit 7',7),('sigkill','kill -KILL $$',-9)]:
            if a.case and 'kill-on-exit-'+name not in a.case:continue
            run('kill-on-exit-'+name,'sleep 30 & p=$!; cat /proc/$p/stat > /tmp/dsha-kox.stat; echo $p > /tmp/dsha-kox.pid; '+ending,expect=code)
            pid=int((a.rootfs/'tmp/dsha-kox.pid').read_text());before=(a.rootfs/'tmp/dsha-kox.stat').read_text()
            live=Path('/proc')/str(pid)/'stat'
            # A reused PID is a different process, not a surviving child.
            same=False;state=None
            if live.exists():
                now=live.read_text();fields=now.rsplit(')',1)[-1].split();state=fields[0]
                same=fields[19]==before.rsplit(')',1)[-1].split()[19] and state not in ('Z','X')
            rows.append({'name':'owned-child-no-longer-running-'+name,'passed':not same,'pid':pid,'remainingProcState':state,'fullyReaped':state is None})
            if same:raise RuntimeError('owned child remains: '+str(pid))
    finally:
        a.output.write_text(json.dumps({'scope':'AARCH64_LINUX_LOCAL_EMULATION','supervisor':str(a.supervisor) if a.supervisor else None,'androidDevice':'NOT_RUN','results':rows},indent=2)+'\n')
        if registered and registration.exists(): registration.write_text('-1')
if __name__=='__main__':main()
