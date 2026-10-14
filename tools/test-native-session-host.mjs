import assert from 'node:assert/strict';
import fs from 'node:fs';
import {browserFixture} from './rc1-browser-fixture.mjs';
const runtime=process.env.DSHA_TEST_RUNTIME||JSON.parse(fs.readFileSync('app/build/test-runtimes/current.json','utf8')).raw;
const fixture=await browserFixture(runtime);
try {
  await fixture.page.setViewportSize({width:1200,height:800});
  await fixture.load('dsh-client-ui-workspace',[], 'module.exports.nativeFixture={UiWorkspaceService};');
  const plugin=fs.readFileSync('app/src/main/assets/app-integration/client.js','utf8')
    .replace("return {inject:['conversation'", "return {installNativeRoute,inject:['conversation'");
  assert.ok(plugin.includes('return {installNativeRoute,inject:'));
  await fixture.page.addScriptTag({content:plugin});
  await fixture.page.addScriptTag({content:fs.readFileSync('app/src/main/assets/web-integration/page.js','utf8')});
  const result=await fixture.page.evaluate(async()=>{
    const store=auditModules['@deepseek-ai/dsh-client-store'].createSnapshotStore;
    const list=store({phase:'ready',ids:['old','target','archived'],byId:{old:{id:'old',retainedBy:{mainView:1}},target:{id:'target',retainedBy:{mainView:0}},archived:{id:'archived',retainedBy:{}},fallback:{id:'fallback',retainedBy:{subagent:1}}}});
    const workspaces={list:store({phase:'ready',archivedSessionIds:['archived']})};
    const retained=[], released=[], panels=[], reports=[];
    window.DshaTheme={postMessage:text=>reports.push(JSON.parse(text))};
    const sessions={list,subagentAddress:()=>undefined,retain(id,options){
      if(!list.getSnapshot().byId[id]||options.source!=='mainView')throw Error('invalid retain');
      retained.push(id);const state=list.getSnapshot();
      list.set({...state,byId:{...state.byId,[id]:{...state.byId[id],retainedBy:{mainView:1}}}});
      return {sessionId:id,release(){released.push(id);const current=list.getSnapshot();
        list.set({...current,byId:{...current.byId,[id]:{...current.byId[id],retainedBy:{mainView:0}}}});}};
    }};
    const Service=auditExports['@deepseek-ai/dsh-client-ui-workspace'].nativeFixture.UiWorkspaceService;
    const workspace=Object.create(Service.prototype);
    workspace.sessions=sessions; workspace.lifetime=new AbortController();workspace.selection=store({sessionId:'old'});
    workspace.ctx={layout:{selectPanel:id=>panels.push(id)}};
    workspace.mainReference={sessionId:'old',release(){released.push('old');const state=list.getSnapshot();
      list.set({...state,byId:{...state.byId,old:{...state.byId.old,retainedBy:{mainView:0}}}});}};
    const dispose=auditExports['dsh-app-integration'].installNativeRoute({sessions,workspaces,uiWorkspace:workspace});
    const meta=document.createElement('meta');meta.name='theme-color';meta.content='#123456';document.head.append(meta);
    const css=document.createElement('style');css.textContent='body{--dsw-alias-bg-base:color-mix(in srgb,#123456 100%,white 0%)}body[data-ds-dark-theme]{--dsw-alias-bg-base:#234567}';document.head.append(css);
    const display=window.__dshaNativePresentation, nonce='a'.repeat(32);
    display.install({nonce,generation:17,instance:'real-rc2-workspace'});
    await new Promise(requestAnimationFrame);
    document.body.setAttribute('data-ds-dark-theme','');
    await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    const token='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
    display.route({nonce,generation:17,instance:'real-rc2-workspace',sessionId:'target',requestToken:token});
    await new Promise(resolve=>setTimeout(resolve,0));
    const opened=reports.find(value=>value.type==='session-route'&&value.requestToken===token);
    display.route({nonce,generation:17,instance:'real-rc2-workspace',sessionId:'missing',requestToken:'cccccccc-cccc-cccc-cccc-cccccccccccc'});
    const missing=reports.at(-1);
    display.route({nonce,generation:17,instance:'real-rc2-workspace',sessionId:'fallback',requestToken:'dddddddd-dddd-dddd-dddd-dddddddddddd'});
    const fallback=reports.at(-1);
    display.route({nonce,generation:17,instance:'real-rc2-workspace',sessionId:'archived',requestToken:'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee'});
    const archived=reports.at(-1);dispose();
    return {retained,released,panels,selection:workspace.selection.getSnapshot(),snapshot:list.getSnapshot(),opened,missing,fallback,archived,
      theme:reports.find(value=>value.type==='theme-color'),themes:reports.filter(value=>value.type==='theme-color')};
  });
  assert.deepEqual(result.retained,['target']);assert.deepEqual(result.released,['old']);
  assert.deepEqual(result.panels,[null]);assert.equal(result.selection.sessionId,'target');
  assert.equal(result.snapshot.byId.target.retainedBy.mainView,1);assert.equal(result.snapshot.byId.old.retainedBy.mainView,0);
  assert.equal(result.opened.status,'opened');assert.equal(result.opened.generation,17);
  assert.equal(result.missing.status,'not-found');assert.equal(result.theme.color,'rgba(18,52,86,1)');
  assert.equal(result.fallback.status,'not-found');assert.equal(result.archived.status,'not-found');
  assert.equal(result.themes.at(-1).color,'rgba(35,69,103,1)','body-only dark marker must override an unchanged meta on wide screens');
  assert.deepEqual(fixture.errors,[]);
  fs.mkdirSync('artifacts/pr172/ui',{recursive:true});
  fs.writeFileSync(`artifacts/pr172/ui/rc2-session-host-${process.env.DSHA_BROWSER||'chromium'}.json`,JSON.stringify({
    engine:process.env.DSHA_BROWSER||'chromium',runtime,passed:true,result,
    scope:'Real locked rc2 UiWorkspaceService.openSession/replaceMain and frontend snapshot store; synthetic session retain/reference backend; real browser DOM theme events; no Android WebView/Gecko pixels, model request or device'},null,2));
  console.log('PASS_NATIVE_SESSION_HOST: real rc2 UiWorkspace selected and confirmed target mainView; unknown target preserved; DOM theme parsed');
} finally { await fixture.close(); }
