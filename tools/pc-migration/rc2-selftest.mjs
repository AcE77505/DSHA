// 真实、完整内容核验的锁定 rc2；只在隔离目录建立/读取会话，不启动模型或网页。
import {testRuntime} from '../test-runtime-fixture.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {execFileSync} from 'node:child_process';
import {HERE,inventory,rewrite} from './portable-core.mjs';
import {parseArgs,run} from './dsha-portable-restore.mjs';
process.env.NARB_DISABLE_NATIVE_CACHE='1';
const runtime=testRuntime('raw'),require=createRequire(path.join(runtime,'package.json'));
const boot=await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-app-boot')).href);
const {Context}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);
const {default:Persistence}=await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-session-persistence-jsonl')).href);
const {SESSION_FORMAT_VERSION}=await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-session')).href);
const fixture=fs.mkdtempSync(path.join(os.tmpdir(),'dsha-portable-rc2-'));let checks=0;
try {
  for(const text of ['[]\n','[{id: old, config: {x: 1}}]\n','- id: old\n  config: {x: 1}\n','# empty\n']){
    const home=fs.mkdtempSync(path.join(fixture,'patch-')),patch=path.join(home,'profiles/web/cordis.patch.yml');fs.mkdirSync(path.dirname(patch),{recursive:true});fs.writeFileSync(patch,text);
    rewrite(home,home,'web',new Map());
    const rows=boot.loadOverlayPatches('portable-fixture',patch),entries=boot.composeEntries([[{insert:[{id:'session-persistence-jsonl',name:'@deepseek-ai/dsh-session-persistence-jsonl',config:{root:'original',compression:'zstd'}}]}],rows]);
    assert.equal(entries.find(r=>r.id==='session-persistence-jsonl').config.root,path.join(home,'sessions'));assert.equal(entries.find(r=>r.id==='session-persistence-jsonl').config.compression,'none');checks+=2;
  }
  const home=path.join(fixture,'source-home'),sourceCwd=path.join(fixture,'source-work'),target=path.join(fixture,'desktop-home'),targetCwd=path.join(fixture,'desktop-work'),out=path.join(fixture,'export');fs.mkdirSync(home);fs.mkdirSync(sourceCwd);fs.mkdirSync(out);
  const id=crypto.randomUUID(),ctx=new Context(),store=new Persistence(ctx,{root:path.join(home,'sessions'),compression:'zstd'});
  const handle=await store.create({version:SESSION_FORMAT_VERSION,id,createdAt:0,cwd:sourceCwd,isSeeded:false});await handle.flush();await handle.close();await ctx.fiber.dispose();
  const before=inventory(home).digest,pkg=JSON.parse(execFileSync(process.env.DSHA_PYTHON||'python',[path.join(HERE,'dsha-portable.py'),'export','--home',home,'--out',out],{encoding:'utf8'})).package;
  await run(parseArgs(['apply','--package',pkg,'--home',target,'--workspace-dir',targetCwd,'--rewrite-cwd']));assert.equal(inventory(home).digest,before);checks++;
  const restoredContext=new Context(),restored=new Persistence(restoredContext,{root:path.join(target,'sessions'),compression:'none'}),reader=await restored.open(id,'read');const read=await reader.read(0,100);assert.equal(read.events.length,0);await reader.close();checks++;
  const writer=await restored.open(id,'write');await writer.append([]);await writer.flush();await writer.close();await restoredContext.fiber.dispose();checks++;
  const reopenedContext=new Context(),reopened=new Persistence(reopenedContext,{root:path.join(target,'sessions'),compression:'none'}),again=await reopened.open(id,'read');assert.equal((await again.read(0,100)).events.length,0);await again.close();await reopenedContext.fiber.dispose();checks++;
  testRuntime('raw');console.log(`PASS real rc2 overlay/store ${checks} assertions; no model request, no device`);
}finally{const target=path.resolve(fixture);if(!target.startsWith(path.resolve(os.tmpdir())+path.sep)||!path.basename(target).startsWith('dsha-portable-rc2-'))throw Error('FIXTURE_CLEANUP_BOUNDARY');fs.rmSync(target,{recursive:true,force:true});}
