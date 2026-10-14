// Fresh C and a real Linux kernel; the control channel is separate from business stdout/stdin.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { readFile, copyFile, chmod, mkdtemp, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { createInterface } from 'node:readline';
import { once } from 'node:events';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
assert.equal(process.platform, 'linux'); assert.equal(process.arch, 'x64');
assert.ok(Number(process.versions.node.split('.')[0]) >= 24);
const [inputBinary, source, expectedBinary, expectedSource, libraries] = process.argv.slice(2);
assert.equal(process.argv.length, 7);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
assert.equal(hash(await readFile(inputBinary)), expectedBinary); assert.equal(hash(await readFile(source)), expectedSource);
const root = await mkdtemp(join(resolve(tmpdir()), 'dsha-native-handshake-'));
const binary = join(root, 'session'); await copyFile(inputBinary, binary); await chmod(binary, 0o700);
const env = { ...process.env, LD_LIBRARY_PATH: libraries };
const kernel = async pid => {
  const text = await readFile(`/proc/${pid}/stat`, 'utf8');
  const fields = text.slice(text.lastIndexOf(')') + 1).trim().split(/\s+/);
  return { pid: Number(text.slice(0, text.indexOf(' '))), parent: Number(fields[1]), group: Number(fields[2]), session: Number(fields[3]), birth: fields[19] };
};
const controlName = 'dsha-handshake-' + randomUUID().replaceAll('-', '');
const events = [], lines = []; let control, errors = '', cleanupConfirmed = false;
const server = createServer(socket => { control = socket; createInterface({input:socket}).on('line', line => events.push(line)); });
server.listen({path:'\0'+controlName}); await once(server,'listening');
const guest = 'IFS= read -r input; [ "$input" = DSHA_START ] || exit 125; echo GUEST_STARTED:$$; sleep 30';
const child = spawn(binary,['--birth-handshake','--control='+controlName,'--','/bin/sh','-c',guest],{env});
const closed = once(child,'close'); child.stderr.on('data',b=>errors=(errors+b).slice(-4096));
const output = createInterface({input:child.stdout}); output.on('line',line=>lines.push(line));
async function until(predicate, description) {
  const deadline = performance.now()+6000;
  while (!await predicate()) { assert.ok(performance.now()<deadline,description+': '+errors); await new Promise(r=>setTimeout(r,10)); }
}
let birth, guestPid;
try {
  await until(()=>lines.length>0 && events.length>0,'native birth and control READY');
  assert.match(lines[0],/^DSHA_SESSION_STAT_V1 /); assert.equal(events[0],'READY DSHA_OWNED_TREE_V2');
  const text=lines[0].slice('DSHA_SESSION_STAT_V1 '.length), f=text.slice(text.lastIndexOf(')')+1).trim().split(/\s+/);
  const pid=Number(text.slice(0,text.indexOf(' '))); birth=f[19];
  assert.equal(pid,child.pid); assert.deepEqual([Number(f[1]),Number(f[2]),Number(f[3])],[process.pid,pid,pid]);
  assert.deepEqual(await kernel(pid),{pid,parent:process.pid,group:pid,session:pid,birth});
  child.stdin.write('DSHA_START\n'); await new Promise(r=>setTimeout(r,100));
  assert.equal(lines.length,1,'stdin cannot authorize guest launch');
  control.write('START\n'); await until(()=>lines.length>1 && events.includes('STARTED'),'guest after trusted START');
  assert.match(lines[1],/^GUEST_STARTED:[1-9][0-9]*$/); guestPid=Number(lines[1].slice('GUEST_STARTED:'.length));
  const guestState=await kernel(guestPid); assert.equal(guestState.parent,pid); assert.equal(guestState.session,pid);
  control.write('STOP\n'); await until(()=>events.includes('OWNED_TREE_EMPTY ECHILD'),'kernel ECHILD receipt');
  assert.ok(events.includes('COMMAND_EXITED 143')); control.write('COMMIT\n');
  const [code,signal]=await closed; assert.equal(code,143); assert.equal(signal,null);
  await assert.rejects(()=>kernel(guestPid),e=>e.code==='ENOENT');
  await assert.rejects(()=>kernel(pid),e=>e.code==='ENOENT'); cleanupConfirmed=true;
  const missing=spawn(binary,['--birth-handshake'],{env}); let diagnostic=''; missing.stderr.on('data',b=>diagnostic+=b);
  assert.equal((await once(missing,'close'))[0],125); assert.match(diagnostic,/DSHA_SESSION_USAGE/);
  assert.equal(hash(await readFile(source)),expectedSource); assert.equal(hash(await readFile(inputBinary)),expectedBinary);
  console.log(JSON.stringify({status:'PASS',platform:process.platform,arch:process.arch,sourceSha256:expectedSource,binarySha256:expectedBinary,cleanupConfirmed,
    checks:['self-pid-parent-group-session-birth','kernel-birth-reread','stdin-cannot-authorize-guest','trusted-control-START','COMMAND_EXITED-separate-from-ECHILD','supervisor-waits-for-COMMIT','guest-and-supervisor-reaped','missing-command-125'],
    scope:'fresh Linux x86_64 C and real kernel; Android device not tested'}));
} finally {
  if (!cleanupConfirmed && child.exitCode===null && birth && (await kernel(child.pid)).birth===birth) child.kill('SIGKILL');
  output.close(); control?.destroy(); server.close();
  if(cleanupConfirmed) await rm(root,{recursive:true,force:true});
}
