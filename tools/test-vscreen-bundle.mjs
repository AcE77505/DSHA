// 真实锁定 rc2 组合、Cordis 工具与 MCP stdio 生命周期；不调用模型或设备。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {verifyRuntimeContent} from './test-runtime-fixture.mjs';

const root=path.resolve(import.meta.dirname,'..');
const pointer=JSON.parse(fs.readFileSync(path.join(root,'app/build/test-runtimes/current.json'),'utf8'));
const runtime=process.env.DSHA_TEST_RUNTIME||pointer.raw;
const proof=JSON.parse(fs.readFileSync(path.join(runtime,'dsha-test-runtime.json'),'utf8'));
const expected=JSON.parse(fs.readFileSync(path.join(root,'tools/dsh-runtime/package.json'),'utf8')).dependencies['@deepseek-ai/dsh'];
assert.equal(JSON.parse(fs.readFileSync(path.join(runtime,'node_modules/@deepseek-ai/dsh/package.json'),'utf8')).version,expected);
assert.equal(proof.lockSha256,createHash('sha256').update(fs.readFileSync(path.join(root,'tools/dsh-runtime/package-lock.json'))).digest('hex'));
assert.equal(proof.platform.os,process.platform);assert.equal(proof.platform.cpu,process.arch);
verifyRuntimeContent(runtime,proof);
const load=async name=>import(pathToFileURL(path.join(runtime,'node_modules',name,'lib/index.js')));
const {Context}=await load('@deepseek-ai/cordis');
const {default:ToolRuntime}=await load('@deepseek-ai/dsh-tools');
const {default:SystemPrompt}=await load('@deepseek-ai/dsh-system-prompt');
const {loadProfileDirectory,composeEntries}=await load('@deepseek-ai/dsh-app-boot');
const directory=fs.mkdtempSync(path.join(root,'app/build/vscreen-bundle-'));
const profile=path.join(directory,'root/.dsh/profiles/web');
const pluginDir=path.join(directory,'root/dsha-tool-vscreen');
const write=(file,value)=>{fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,typeof value==='string'?value:JSON.stringify(value));};
let ctx;
try{
  fs.cpSync(path.join(root,'app/src/main/assets/builtin-plugins/dsh-tool-vscreen'),pluginDir,{recursive:true});
  fs.symlinkSync(path.join(runtime,'node_modules'),path.join(directory,'node_modules'),process.platform==='win32'?'junction':'dir');
  const anchor=path.join(directory,'installation/package.json');
  write(anchor,{name:'@deepseek-ai/dsh',version:expected});
  write(path.join(profile,'package.json'),{name:'vscreen-fixture',version:'1.0.0',dsh:{profile:{bundles:['dsh-tool-vscreen']}},dependencies:{'dsh-tool-vscreen':'link:'+pluginDir}});
  const link=path.join(profile,'node_modules/dsh-tool-vscreen');fs.mkdirSync(path.dirname(link),{recursive:true});
  fs.symlinkSync(pluginDir,link,process.platform==='win32'?'junction':'dir');
  write(path.join(profile,'cordis.patch.yml'),'[]\n');
  const composed=()=>{const loaded=loadProfileDirectory('dsh',profile,anchor);return composeEntries([...loaded.layers.map(layer=>layer.patches),loaded.patches]);};
  assert.deepEqual(composed().map(row=>[row.id,row.name]),[['dsh-tool-vscreen','dsh-tool-vscreen']]);
  const python=(mode)=>{
    const source=`import importlib.util,os,json\nfrom pathlib import Path\np=Path(os.environ['DSHA_REGISTER'])\nspec=importlib.util.spec_from_file_location('register_vscreen',p)\nr=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)\nr.builtin_names=lambda:['dsh-tool-vscreen']\nr.OFFICIAL_BUNDLES=()\nr.ensure_runtime_modules=lambda:0\nmode=os.environ['DSHA_MODE']\nif mode=='text':\n for text in [\"- insert:\\n    - id: dsh-tool-vscreen\\n      name: dsh-tool-vscreen\\n      disabled: true\\n\",\"- insert:\\n    - id: dsh-tool-vscreen\\n      name: dsh-tool-vscreen\\n      config: !!js ({x:1})\\n\",\"- insert:\\n    - id: dsh-tool-vscreen\\n      name: dsh-tool-vscreen\\n      config:\\n        insert: [{id: 'other', name: 'other'}]\\n\"]:\n  assert r.vscreen_workaround_text(text)==(text,0)\n mixed=\"# retain\\n- insert:\\n    - id: dsh-tool-vscreen\\n      name: 'dsh-tool-vscreen'\\n    - id: custom\\n      name: custom\\n      config: !!js ({x:1})\\n\"\n changed,n=r.vscreen_workaround_text(mixed)\n assert n==1 and \"# retain\\n\" in changed and \"    - id: custom\\n      name: custom\\n      config: !!js ({x:1})\\n\" in changed\n print('TEXT_PASS')\nelif mode=='migrate': print('MIGRATED',r.migrate_vscreen_workaround())\nelif mode=='disable': assert r.disable_plugin('dsh-tool-vscreen')==0\nelif mode=='register': assert r.register()==0\nelif mode=='enable': assert r.enable_plugin('dsh-tool-vscreen')==0\n`;
    const result=spawnSync(process.env.DSHA_PYTHON||'python',['-B','-c',source],{env:{...process.env,DSHA_TEST_ROOT:directory,DSH_HOME:'/root/.dsh',DSHA_MODE:mode,DSHA_REGISTER:path.join(root,'app/src/main/assets/register-builtin-plugins.py')},encoding:'utf8',windowsHide:true,timeout:30000});
    assert.equal(result.status,0,result.stderr||result.stdout||result.error?.message);return result.stdout;
  };
  assert.match(python('text'),/TEXT_PASS/);
  const workaround="# old workaround\n- insert:\n    - id: dsh-tool-vscreen\n      name: 'dsh-tool-vscreen'\n";
  write(path.join(profile,'cordis.patch.yml'),workaround);
  assert.equal(composed().filter(row=>row.name==='dsh-tool-vscreen').length,2);
  assert.match(python('migrate'),/MIGRATED 1/);
  assert.equal(composed().filter(row=>row.name==='dsh-tool-vscreen').length,1);
  const originals=fs.readdirSync(profile).filter(name=>name.startsWith('cordis.patch.yml.dsha-vscreen-original-'));
  assert.equal(originals.length,1);assert.equal(fs.readFileSync(path.join(profile,originals[0]),'utf8'),workaround);
  assert.match(python('migrate'),/MIGRATED 0/);
  const plugin=await import(pathToFileURL(path.join(pluginDir,'lib/index.js')));
  const wanted=(await import(pathToFileURL(path.join(pluginDir,'lib/server.cjs')))).tools.map(row=>'mcp__dsha-vscreen__'+row.name).sort();
  for(let turn=0;turn<2;turn++){
    ctx=new Context();await ctx.plugin(SystemPrompt,{});await ctx.plugin(ToolRuntime,{mode:'native'});
    const mounted=ctx.plugin(plugin);await mounted;
    assert.deepEqual(ctx.tools.schemas().map(row=>row.name).sort(),wanted);
    assert.equal(wanted.length,13);
    await mounted.dispose();assert.equal(ctx.tools.schemas().length,0);
    await ctx.fiber.dispose();ctx=null;
  }
  python('disable');assert.equal(composed().filter(row=>row.name==='dsh-tool-vscreen').length,0);
  const disabled=path.join(profile,'node_modules/dsh-tool-vscreen.disabled');assert.equal(fs.readFileSync(disabled,'utf8'),'');
  write(path.join(profile,'cordis.patch.yml'),workaround);
  python('register');python('register');
  assert.equal(composed().filter(row=>row.name==='dsh-tool-vscreen').length,0);
  assert.ok(fs.existsSync(disabled));
  if(!fs.existsSync(link))fs.symlinkSync(pluginDir,link,process.platform==='win32'?'junction':'dir');
  python('enable');assert.equal(composed().filter(row=>row.name==='dsh-tool-vscreen').length,1);
  assert.ok(!fs.existsSync(disabled));
  console.log('PASS real rc2 composition, exact workaround migration with preserved originals and configured/expressed rows, disabled restart intent, reenable, and two MCP lifecycles with exactly 13 tools');
}finally{
  if(ctx)await ctx.fiber.dispose();
  const relative=path.relative(path.resolve(root,'app/build'),fs.realpathSync(directory));
  assert.ok(relative && relative!=='..' && !relative.startsWith('..'+path.sep) && !path.isAbsolute(relative));
  fs.rmSync(directory,{recursive:true,force:true});
}
