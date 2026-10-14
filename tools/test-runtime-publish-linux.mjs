// Real Linux syscall/flock fixture; no Android device and no process.platform mocks.
import assert from 'node:assert/strict';
import { mkdtemp,writeFile,readFile,readdir,lstat,symlink,rm,statfs,chmod } from 'node:fs/promises';
import { createHash,randomUUID } from 'node:crypto';
import { dirname,join,resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
if(process.platform!=='linux')throw Error('This fixture requires a real Linux Node runtime');
// Android's fake guest uid 0 has no host root DAC bypass. A root-only Linux
// test hides the immutable 0400 object -> alias copy/open permission failure.
if(process.getuid()===0){
 const child=spawnSync('runuser',['-u','nobody','--',process.execPath,...process.argv.slice(1)],{
  env:{...process.env,TMPDIR:'/tmp'},encoding:'utf8',maxBuffer:16*1024*1024,
 });
 process.stdout.write(child.stdout||'');process.stderr.write(child.stderr||'');
 if(child.error)throw child.error;
 if(child.signal)throw Error('Non-root publisher fixture terminated: '+child.signal);
 process.exit(child.status??1);
}
assert.notEqual(process.getuid(),0,'Permission regression must execute as a real non-root user');
const helper=resolve(process.argv[2]||'app/src/main/assets/runtime-fs/index.js');
const {createPublisher,publishExclusive}=await import(pathToFileURL(helper));
const fallback=createPublisher(async()=>{throw Object.assign(Error('forced old kernel'),{code:'ENOSYS'});});
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const temporaryBase=resolve(tmpdir());
const root=await mkdtemp(join(temporaryBase,'dsha-publish-real-'));let assertions=0;
const filesystem=await statfs(root);
try{
 const source=join(root,'source'),bytes=Buffer.alloc(200*1024,0x71);await writeFile(source,bytes);
 await publishExclusive(source,join(root,'native'));assert.equal(hash(await readFile(join(root,'native'))),hash(bytes));assertions++;
 assert.equal(hash(await readFile(source)),hash(bytes));assert.equal((await lstat(join(root,'native'))).isSymbolicLink(),false);assertions++;
 // Match saveFileStream: first publish a staged 0600 file, make its object
 // immutable, then publish multiple aliases without changing the object.
 const staged=join(root,'staged'),object=join(root,'immutable-object');
 await writeFile(staged,bytes,{mode:0o600});await publishExclusive(staged,object);await chmod(object,0o400);
 const original=await lstat(object);
 assert.equal(original.mode&0o777,0o400);assertions++;
 for(const [name,publisher]of [['immutable-alias-a',publishExclusive],['immutable-alias-b',publishExclusive],['immutable-alias-old-kernel',fallback]]){
  const target=join(root,name);await publisher(object,target,true);
  assert.equal(hash(await readFile(target)),hash(bytes));assert.equal((await lstat(target)).mode&0o777,0o400);
  assert.equal(hash(await readFile(object)),hash(bytes));const current=await lstat(object);
  assert.equal(current.mode&0o777,0o400);assert.equal(current.ino,original.ino);assertions++;
 }
 await assert.rejects(publishExclusive(object,join(root,'immutable-alias-a')),{code:'EEXIST'});
 assert.equal(hash(await readFile(join(root,'immutable-alias-a'))),hash(bytes));
 assert.equal((await lstat(object)).mode&0o777,0o400);assertions++;
 for(let n=0;n<100;n++)await fallback(source,join(root,'target-'+n),true);
 let names=await readdir(root);assert.equal(names.filter(name=>name.endsWith('.lock')).length,1);assert.equal(names.filter(name=>name.endsWith('.tmp')).length,0);assertions++;
 const lock=await lstat(join(root,'.dsha-publish.lock'));
 const result=await Promise.allSettled(Array.from({length:32},()=>fallback(source,join(root,'concurrent'),true)));
 assert.equal(result.filter(item=>item.status==='fulfilled').length,1);assert.ok(result.filter(item=>item.status==='rejected').every(item=>item.reason.code==='EEXIST'));assertions++;
 assert.equal((await lstat(join(root,'.dsha-publish.lock'))).ino,lock.ino);assertions++;
 await assert.rejects(fallback(source,join(root,'native'),true),{code:'EEXIST'});assert.equal(hash(await readFile(source)),hash(bytes));assertions++;
 const cancelled=new AbortController();cancelled.abort();await assert.rejects(fallback(source,join(root,'cancelled'),true,cancelled.signal),{name:'AbortError'});
 assert.equal((await readdir(root)).includes('cancelled'),false);assertions++;
 const linked=join(root,'attachments');await symlink(root,linked);await fallback(source,join(linked,'image-alias.png'),true);
 assert.equal(hash(await readFile(join(root,'image-alias.png'))),hash(bytes));assert.equal(hash(await readFile(source)),hash(bytes));assertions++;
 const bad=join(root,'bad');await writeFile(bad,'foreign lock');const attack=join(root,'attack');await import('node:fs/promises').then(fs=>fs.mkdir(attack));await symlink(bad,join(attack,'.dsha-publish.lock'));
 await assert.rejects(fallback(source,join(attack,'target'),true),{code:'ELOOP'});assert.equal(await readFile(bad,'utf8'),'foreign lock');assertions++;
 console.log(JSON.stringify({status:'PASS',platform:process.platform,arch:process.arch,node:process.version,uid:process.getuid(),assertions,readonlyObjectMode:'0400',readonlyAliases:3,distinctPublications:100,concurrentPublishers:32,lockInode:String(lock.ino),temporaryBase,filesystemType:String(filesystem.type),note:'Real non-root Linux host filesystem; does not claim Android FUSE/device validation or power-loss durability'}));
}finally{assert.equal(dirname(root),temporaryBase);await rm(root,{recursive:true,force:true});}
