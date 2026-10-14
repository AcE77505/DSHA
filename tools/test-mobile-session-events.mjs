// 当前 rc2 的真实两种会话行 + 实際移动插件捕获事件，不以纯函数代替传播路径。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { browserFixture } from './rc1-browser-fixture.mjs';
const runtime = process.env.DSHA_TEST_RUNTIME || JSON.parse(fs.readFileSync('app/build/test-runtimes/current.json', 'utf8')).raw;
const fixture = await browserFixture(runtime);
try {
  const page = fixture.page;
  const recipe = JSON.parse(fs.readFileSync('app/src/main/assets/session-interaction-patch.json', 'utf8'));
  await fixture.load('dsh-client-ui-workspace', [recipe],
    'module.exports.audit={SearchResultItem,SessionNodeItem,dshaSessionSelection};');
  const bundle = fs.readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js', 'utf8');
  await page.evaluate(() => { window.__ModuleLoader__ = { load: ({factory}) => factory(() => ({})) }; });
  await page.addScriptTag({content:bundle.replace('var __cache = {};', 'window.mobileRequire=__localRequire; var __cache = {};')});
  await page.addStyleTag({content:'[role="treeitem"] {display:block;min-height:46px;width:270px;padding:8px;box-sizing:border-box} aside{width:280px;min-height:300px}'});
  await page.evaluate(() => {
    const React = auditModules.react.default || auditModules.react;
    const ReactDOM = auditModules['react-dom'].default || auditModules['react-dom'];
    const rows = auditExports['@deepseek-ai/dsh-client-ui-workspace'].audit;
    const frame = document.getElementById('root');
    frame.dataset.mobileNav = 'frame';
    frame.innerHTML = '<aside><div id="rows"></div></aside><main><textarea data-composer-input>draft survives navigation</textarea></main><div data-shell-overlay></div>';
    window.sessionTest = {kind:'tree',current:'old',opened:[],closes:0,events:[],disposers:[],rows,frame};
    const root = ReactDOM.createRoot(document.getElementById('rows'));
    const render = () => {
      const node = {id:'target',title:'Target conversation',runningSubagentCount:0,blank:true,archived:false,updatedAt:1};
      const onOpen = id => { sessionTest.opened.push(id);sessionTest.current=id;render(); };
      const common = {currentId:sessionTest.current,onOpen,t:key=>key};
      root.render(React.createElement(sessionTest.kind==='tree'?rows.SessionNodeItem:rows.SearchResultItem,
        sessionTest.kind==='tree'?{...common,node,now:100,renderSlot:()=>null,onRenameRequest:()=>{throw Error('unexpected rename')}}
          :{...common,result:node,onUnarchive:()=>{throw Error('unexpected unarchive')}}));
    };
    window.resetSessionTest = kind => {
      sessionTest.kind=kind;sessionTest.current='old';sessionTest.opened=[];sessionTest.closes=0;sessionTest.events=[];
      rows.dshaSessionSelection.select('old','old');frame.removeAttribute('data-sidebar-collapsed');render();
    };
    document.addEventListener('dsha-session-open',event=>sessionTest.events.push(event.detail.id));
    mobileRequire('./effects/drawer-close.js').installOverlayInteractions({
      effect:run=>sessionTest.disposers.push(run()),
      layout:{toggleSidebar(){sessionTest.closes++;frame.toggleAttribute('data-sidebar-collapsed');}},
      sessions:{list:{getSnapshot:()=>({byId:{[sessionTest.current]:{id:sessionTest.current,retainedBy:{mainView:1}}}})}}
    });
    resetSessionTest('tree');
  });
  const row = page.locator('[data-dsha-session-select="target"]');
  const reset = async kind => { await page.evaluate(kind=>resetSessionTest(kind),kind); await row.waitFor(); await page.waitForTimeout(30); };
  const snapshot = () => page.evaluate(()=>({opened:sessionTest.opened,events:sessionTest.events,closes:sessionTest.closes,
    collapsed:sessionTest.frame.hasAttribute('data-sidebar-collapsed'),draft:document.querySelector('textarea').value}));
  const assertOpen = async () => {
    await page.waitForTimeout(350);
    assert.deepEqual(await snapshot(),{opened:['target'],events:['target'],closes:1,collapsed:true,draft:'draft survives navigation'});
  };
  const results=[];
  for (const kind of ['tree','search']) {
    await reset(kind);
    await row.tap();
    assert.equal((await snapshot()).opened.length,0,'touch first tap selects only');
    assert.equal((await snapshot()).collapsed,false,'first selected row does not close drawer');
    await row.tap(); await assertOpen();
    await reset(kind);
    const box = await row.boundingBox();
    await page.mouse.click(box.x+30,box.y+20); await assertOpen();
    // Pen click may be exposed to React as a MouseEvent: the real row pointerdown owns its type.
    await reset(kind);
    const pen = () => row.evaluate(el=>{
      const r=el.getBoundingClientRect(), init={bubbles:true,cancelable:true,pointerId:9,pointerType:'pen',clientX:r.x+20,clientY:r.y+20};
      el.dispatchEvent(new PointerEvent('pointerdown',init));el.dispatchEvent(new PointerEvent('pointerup',init));
      el.dispatchEvent(new MouseEvent('click',{...init,detail:1}));
    });
    await pen(); assert.equal((await snapshot()).opened.length,0,'pen first tap selects only');
    await pen(); await assertOpen();
    await reset(kind);
    await row.focus(); await page.keyboard.press('Space');
    assert.equal((await snapshot()).opened.length,0,'Space selects only');
    await page.keyboard.press('Enter'); await assertOpen();
    await reset(kind);
    await row.evaluate(el=>el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,detail:0})));
    await assertOpen();
    results.push({kind,touchDouble:true,mouseSingleOnCoarseSurface:true,penMouseEventDouble:true,spaceSelect:true,enterOpen:true,assistiveOpen:true,oneOpenEvent:true,draftRetained:true});
  }
  await page.evaluate(()=>sessionTest.disposers.reverse().forEach(fn=>fn?.()));
  assert.deepEqual(fixture.errors,[]);
  console.log(JSON.stringify({engine:process.env.DSHA_BROWSER||'chromium',host:'actual rc2 SearchResultItem + SessionNodeItem',results,errors:[]},null,2));
} finally { await fixture.close(); }
