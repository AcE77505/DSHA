import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {run,parseArgs} from './dsha-portable-restore.mjs';
import {HERE,projectKey,inventory,sanitizedCredentials,yamlDocument,extractPackage,commit,allowedHome,recover,atomicJson} from './portable-core.mjs';
const fixture=fs.mkdtempSync(path.join(os.tmpdir(),'dsha-portable-tests-'));let checks=0;
function test(fn){fn();checks++;}
function makeTar(rows){const chunks=[];for(const [name,bytes=Buffer.alloc(0),kind='0']of rows){const h=Buffer.alloc(512);h.write(name);h.write('0000600\0',100);h.write('0000000\0',108);h.write('0000000\0',116);h.write(bytes.length.toString(8).padStart(11,'0')+'\0',124);h.fill(32,148,156);h[156]=kind.charCodeAt(0);h.write('ustar\0',257);const sum=h.reduce((a,b)=>a+b,0);h.write(sum.toString(8).padStart(6,'0')+'\0 ',148);chunks.push(h,bytes,Buffer.alloc((512-bytes.length%512)%512));}chunks.push(Buffer.alloc(1024));return zlib.gzipSync(Buffer.concat(chunks));}
const manifest={kind:'dsha-portable-package',schemaVersion:1,source:{profile:'web',dshVersion:'0.2.0-rc.2'},options:{workspaces:[]}};
async function rejectsRows(rows,code){const pkg=path.join(fixture,`bad-${checks}.tar.gz`),staging=fs.mkdtempSync(path.join(fixture,'stage-'));fs.writeFileSync(pkg,makeTar([['home',undefined,'5'],['manifest.json',Buffer.from(JSON.stringify(manifest))],...rows]));await assert.rejects(extractPackage(pkg,staging),code);checks++;}
try{
  test(()=>assert.throws(()=>parseArgs(['apply','--package','x','--rewrite-cwd']),/CWD_MAPPING_REQUIRED/));
  test(()=>assert.throws(()=>parseArgs(['apply','--merge']),/UNKNOWN_ARGUMENT/));
  for(const hostile of ['x.credentials.yaml','storages/workspace.json/evil','profiles/a/b/cordis.patch.yml','.bridge_token'])test(()=>assert.equal(allowedHome(hostile),false));
  for(const text of ['version: 1\nrecords:\n  "client-connection/browser-session": {payload: secret}\nrefs: {}\n','{version: 1, records: {client-connection/browser-session: {payload: secret}}, refs: {}}','version: 1\nrecords:\n    client-connection/browser-session: {payload: secret}\nrefs: {}'])test(()=>assert.equal(sanitizedCredentials(text).includes('secret'),false));
  test(()=>assert.throws(()=>sanitizedCredentials('version: 1\nrecords: &r {}\nrefs: *r'),/YAML_ALIAS/));
  test(()=>assert.throws(()=>sanitizedCredentials('version: 1\nunknown: secret'),/CREDENTIAL_SCHEMA/));
  test(()=>assert.equal(sanitizedCredentials('version: 1\nrecords: {client-connection/first: {payload: secret1}, client-connection/second: {payload: secret2}}\nrefs: {client-connection/key: secret3}\n').includes('secret'),false));
  await rejectsRows([['home/.bridge_token',Buffer.from('secret')]],/HOME_POLICY/);
  await rejectsRows([['home/node_modules',undefined,'5']],/HOME_POLICY/);
  await rejectsRows([['home/sessions/link',undefined,'2']],/TAR_LINK_OR_SPECIAL/);
  await rejectsRows([['home/sessions/../secret',Buffer.from('secret')]],/UNSAFE_PATH/);
  await rejectsRows([['home/sessions/X',Buffer.from('a')],['home/sessions/x',Buffer.from('b')]],/TAR_DUPLICATE/);
  for(const patch of ['[]\n','[ {id: old, config: {x: 1}} ]\n','- id: old\n  config: {x: 1}\n','# empty\n']){
    const home=fs.mkdtempSync(path.join(fixture,'source-')),out=fs.mkdtempSync(path.join(fixture,'out-')),target=path.join(fixture,'restore-'+checks),cwd=path.join(fixture,'work-'+checks);
    const session=path.join(home,'sessions',projectKey(cwd),'fixture-id');fs.mkdirSync(session,{recursive:true});const body=Buffer.from('\n{"type":"synthetic","text":"保留事件"}\n');fs.writeFileSync(path.join(session,'session.v4.jsonl.zstd'),zlib.zstdCompressSync(Buffer.concat([Buffer.from(JSON.stringify({id:'fixture-id',cwd})),body])));fs.writeFileSync(path.join(session,'note.txt'),'sidecar');fs.mkdirSync(path.join(home,'profiles','web'),{recursive:true});fs.writeFileSync(path.join(home,'profiles','web','cordis.patch.yml'),patch);
    const before=inventory(home).digest;const stdout=execFileSync(process.env.DSHA_PYTHON||'python',[path.join(HERE,'dsha-portable.py'),'export','--home',home,'--out',out],{encoding:'utf8'});const pkg=JSON.parse(stdout).package;
    const report=await run(parseArgs(['apply','--package',pkg,'--home',target,'--workspace-dir',cwd,'--rewrite-cwd']));assert.equal(report.result.state,'committed');checks++;
    assert.equal(inventory(home).digest,before);checks++;
    assert.deepEqual(fs.readFileSync(path.join(target,'sessions',projectKey(cwd),'fixture-id','session.v4.jsonl')).subarray(fs.readFileSync(path.join(target,'sessions',projectKey(cwd),'fixture-id','session.v4.jsonl')).indexOf(10)),body);checks++;
    assert.equal(fs.readFileSync(path.join(target,'sessions',projectKey(cwd),'fixture-id','note.txt'),'utf8'),'sidecar');checks++;
    const rows=yamlDocument(fs.readFileSync(path.join(target,'profiles','web','cordis.patch.yml'),'utf8'),true).toJSON();assert.equal(rows.find(r=>r.id==='session-persistence-jsonl').config.root,path.join(target,'sessions'));checks++;
  }
  const target=path.join(fixture,'rollback-original'),candidate=path.join(fixture,'rollback-candidate');fs.mkdirSync(target);fs.mkdirSync(candidate);fs.writeFileSync(path.join(target,'old'),'old');fs.writeFileSync(path.join(candidate,'new'),'new');const digest=inventory(target).digest;
  test(()=>assert.throws(()=>commit([{target,candidate}],path.join(fixture,'journal.json'),()=>{throw Error('injected');}),/injected/));test(()=>assert.equal(inventory(target).digest,digest));test(()=>assert.equal(fs.readFileSync(path.join(candidate,'new'),'utf8'),'new'));
  // 多工作区和同名session id必须独立；缺落点仅plan可读，apply在目标修改前拒绝。
  const multi=fs.mkdtempSync(path.join(fixture,'multi-')),out=fs.mkdtempSync(path.join(fixture,'multi-out-')),home=path.join(multi,'home'),destination=path.join(multi,'restored'),base=path.join(multi,'desktop');fs.mkdirSync(home);fs.mkdirSync(destination);fs.writeFileSync(path.join(destination,'sentinel'),'old');
  const workspace=[path.join(multi,'项目甲'),path.join(multi,'项目乙')];
  for(let i=0;i<2;i++){fs.mkdirSync(workspace[i]);fs.writeFileSync(path.join(workspace[i],'file.txt'),'source-'+i);const dir=path.join(home,'sessions',projectKey(workspace[i]),'same-id');fs.mkdirSync(dir,{recursive:true});fs.writeFileSync(path.join(dir,'session.v4.jsonl.zstd'),zlib.zstdCompressSync(Buffer.from(JSON.stringify({id:'same-id',cwd:workspace[i]})+'\n')));}
  const multiPkg=JSON.parse(execFileSync(process.env.DSHA_PYTHON||'python',[path.join(HERE,'dsha-portable.py'),'export','--home',home,'--out',out,...workspace.flatMap(w=>['--with-workspace',w])],{encoding:'utf8'})).package;
  const old=inventory(destination).digest;const preview=await run(parseArgs(['plan','--package',multiPkg,'--home',destination]));assert(preview.applyArguments.includes('<destination>'));checks++;
  await assert.rejects(run(parseArgs(['apply','--package',multiPkg,'--home',destination])),/WORKSPACE_DESTINATION_REQUIRED/);checks++;
  assert.equal(inventory(destination).digest,old);checks++;
  const complete=await run(parseArgs(['apply','--package',multiPkg,'--home',destination,'--workspace-dir',base,'--rewrite-cwd']));assert.equal(complete.result.rows.length,3);checks++;
  for(let i=0;i<2;i++){assert.equal(fs.readFileSync(path.join(base,`ws${i+1}`,'file.txt'),'utf8'),'source-'+i);assert(fs.existsSync(path.join(destination,'sessions',projectKey(path.join(base,`ws${i+1}`)),'same-id','session.v4.jsonl')));checks+=2;}
  // 模拟进程在候选发布后退出；后来修改过的目标不被回滚。
  const operations=path.join(fixture,'.dsha-portable-operations');fs.mkdirSync(operations,{recursive:true});const crashTarget=path.join(fixture,'crash-target'),crashCandidate=path.join(fixture,'.dsha-portable-candidate-11111111-1111-1111-1111-111111111111'),backup=crashTarget+'.before-restore-22222222-2222-2222-2222-222222222222';fs.mkdirSync(crashTarget);fs.writeFileSync(path.join(crashTarget,'old'),'old');fs.mkdirSync(crashCandidate);fs.writeFileSync(path.join(crashCandidate,'new'),'new');const row={target:crashTarget,candidate:crashCandidate,backup,before:inventory(crashTarget).digest,after:inventory(crashCandidate).digest,stage:'published'};const journal=path.join(operations,'33333333-3333-3333-3333-333333333333.json');fs.renameSync(crashTarget,backup);fs.renameSync(crashCandidate,crashTarget);atomicJson(journal,{kind:'DSHA_PC_RESTORE_V2',rows:[row],state:'prepared'});assert.equal(recover(journal).state,'recovered');assert.equal(fs.readFileSync(path.join(crashTarget,'old'),'utf8'),'old');checks+=2;
  console.log(`PASS portable migration ${checks} assertions; synthetic archive/candidate fixtures only`);
}finally{if(process.env.DSHA_KEEP_FIXTURES)console.log(fixture);else{const resolved=path.resolve(fixture);if(!resolved.startsWith(path.resolve(os.tmpdir())+path.sep)||!path.basename(resolved).startsWith('dsha-portable-tests-'))throw Error('TEST_CLEANUP_BOUNDARY');fs.rmSync(resolved,{recursive:true,force:true});}}
