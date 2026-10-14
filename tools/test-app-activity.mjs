import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, lstatSync } from 'node:fs';
import { resolve, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { knownIdle, confirmedIdle } from '../app/src/main/assets/app-integration/activity.js';
import { runtimePlugins } from '../app/src/main/assets/app-integration/runtime-plugins.js';

test('全部签名内置入口及本地 JavaScript 依赖通过真实 Node 语法检查',()=>{
  const assets=resolve(dirname(fileURLToPath(import.meta.url)),'../app/src/main/assets');
  const registry=JSON.parse(readFileSync(join(assets,'builtin-plugins.json'),'utf8'));
  assert.ok(registry.plugins.length>0);
  const checked=new Set();
  function check(folder){
    for(const node of readdirSync(folder,{withFileTypes:true})){
      if(node.name==='node_modules')continue;
      assert.equal(node.isSymbolicLink(),false);
      const file=join(folder,node.name);
      if(node.isDirectory())check(file);
      else if(/\.(?:js|mjs|cjs)$/.test(node.name)){
        const result=spawnSync(process.execPath,['--check',file],{encoding:'utf8',timeout:10000,windowsHide:true});
        assert.equal(result.error,undefined);assert.equal(result.status,0,file+'\n'+result.stderr);checked.add(file);
      }
    }
  }
  for(const plugin of registry.plugins){
    const folder=join(assets,plugin.name==='dsh-app-integration'?'app-integration':'builtin-plugins/'+plugin.name);
    const entry=join(folder,plugin.entrypoint);
    assert.equal(lstatSync(entry).isFile(),true);check(folder);assert.ok(checked.has(entry));
  }
});

test('真实插件入口可导入并安装受鉴权保护的来源恢复页面',async()=>{
  const plugin=await import('../app/src/main/assets/app-integration/index.js');
  assert.deepEqual(plugin.inject,['agents','sessions','sessionProjections']);
  const previous=process.env.DSHA_WEB_GENERATION;
  delete process.env.DSHA_WEB_GENERATION;
  let route,dispose,authorizationCalls=0,removed=0;
  const scoped={
    connection:{authorizeIndex(req,res){
      authorizationCalls++;
      if(req.authorized)return true;
      res.writeHead(401,{});res.end();return false;
    }},
    webServer:{register(value){route=value;return()=>{removed++}}},
    effect(install,label){assert.equal(label,'dsha-browser-origin-restore');dispose=install();},
  };
  try{
    plugin.apply({inject(names,activate){assert.deepEqual(names,['webServer','connection']);activate(scoped);}});
    assert.equal(route.kind,'exact');assert.equal(route.path,'/__dsha/browser-origin-restore');
    assert.equal(typeof dispose,'function');
    async function request(method,authorized){
      const response={writeHead(status,headers){this.status=status;this.headers=headers;},end(body){this.body=body;this.ended=true;}};
      await route.handler({method,authorized},response);assert.equal(response.ended,true);return response;
    }
    const denied=await request('GET',false);assert.equal(denied.status,401);assert.equal(denied.body,undefined);
    const allowed=await request('GET',true);assert.equal(allowed.status,200);
    assert.equal(allowed.headers['cache-control'],'no-store');assert.equal(allowed.headers['x-content-type-options'],'nosniff');
    assert.match(allowed.headers['content-type'],/^text\/html; charset=utf-8$/);
    assert.match(allowed.headers['content-security-policy'],/frame-ancestors 'none'/);
    assert.match(allowed.body,/DshaOriginState/);assert.doesNotMatch(allowed.body,/\/\*DSHA_ORIGIN_STATE_SCRIPT\*\//);
    const head=await request('HEAD',true);assert.equal(head.status,200);assert.equal(head.body,undefined);
    const beforeDeniedMethod=authorizationCalls;
    const rejected=await request('POST',true);assert.equal(rejected.status,405);
    assert.equal(authorizationCalls,beforeDeniedMethod);
    dispose();assert.equal(removed,1);
  }finally{
    if(previous===undefined)delete process.env.DSHA_WEB_GENERATION;else process.env.DSHA_WEB_GENERATION=previous;
  }
});

function context({status='idle', queued=[], jobs=[], active=[], missing=false}={}) {
  const agent={status,inbox:{nextTurn:queued,nextStep:[]},whenIdle:async()=>{}};
  return {agents:{list:()=>[agent]},sessions:{list:()=>[{}]},get:()=>({list:()=>jobs}),
    sessionProjections:{stateOf:()=>missing?undefined:{active}}};
}
test('生成、排队、后台作业和定时任务均不可当作空闲',()=>{
  assert.equal(knownIdle(context()),true);
  for (const state of [{status:'running'},{status:'unknown'},{queued:[{}]},
    {jobs:[{status:'running'}]},{jobs:[{status:'stopping'}]},{active:[{}]},{missing:true}])
    assert.equal(knownIdle(context(state)),false);
});
test('公开 idle 仍在维护时不会撤销保活',async()=>{
  const ctx=context();
  assert.equal(await confirmedIdle(ctx),true);
  ctx.agents.list()[0].whenIdle=()=>new Promise(()=>{});
  assert.equal(await confirmedIdle(ctx,10),false);
  ctx.agents.list()[0].whenIdle=async()=>{ctx.agents.list()[0].status='running'};
  assert.equal(await confirmedIdle(ctx),false);
});
test('动态插件摘要只含公开身份，不保存源码或授权细节',()=>{
  const rows=runtimePlugins([{id:'test-session'}],{listPlugins:()=>[{
    pluginId:'plugin-id',name:'测试插件',currentPackageId:'v1',activeRun:{pluginRunId:'run'},
    hostCode:'sensitive-code',clientCode:'private-client',latestRun:{status:'running',secret:'not-exported'},
  }]});
  assert.equal(rows.length,1);assert.equal(rows[0].active,true);
  assert.equal(rows[0].sessionId,'test-session');
  assert.equal(JSON.stringify(rows).includes('sensitive-code'),false);
  assert.equal(JSON.stringify(rows).includes('not-exported'),false);
  assert.deepEqual(runtimePlugins([],undefined),[]);
});
test('任务结束恢复空闲，接口失效保持保活',()=>{
  assert.equal(knownIdle(context({jobs:[{status:'completed'},{status:'failed'},{status:'killed'}]})),true);
  assert.equal(knownIdle({}),false);
  assert.equal(knownIdle(context({jobs:[{status:'new-upstream-state'}]})),false);
});
