#!/usr/bin/env node
// Real elapsed-time desktop stress of the locked rc2 renderer and current mobile assets.
// This does not connect to a model, Android device, or the user's runtime data.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {performance} from 'node:perf_hooks';
import {fileURLToPath} from 'node:url';
import {browserFixture} from './rc1-browser-fixture.mjs';
import {testRuntime} from './test-runtime-fixture.mjs';

const options = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index], value = process.argv[index + 1];
  if (!['--hours', '--interval-seconds', '--output', '--browser'].includes(key) || !value || options.has(key))
    throw Error('usage: browser-soak.mjs --hours 8 --interval-seconds 30 --browser both --output <private report>');
  options.set(key, value);
}
const hours = Number(options.get('--hours') || 8);
const interval = Number(options.get('--interval-seconds') || 30);
const engine = options.get('--browser') || 'both';
assert.ok(Number.isFinite(hours) && hours > 0 && hours <= 24, 'invalid real duration');
assert.ok(Number.isFinite(interval) && interval >= 1 && interval <= 60, 'invalid interval');
assert.ok(['both', 'chromium', 'firefox'].includes(engine));
const output = path.resolve(options.get('--output') || 'app/build/browser-soak/report.json');
const root = path.resolve(import.meta.dirname, '..');
assert.ok(output.startsWith(path.join(root, 'app', 'build') + path.sep), 'report must stay in build artifacts');
const runtime = testRuntime('raw');
const assets = path.join(root, 'app/src/main/assets');
const recipe = JSON.parse(fs.readFileSync(path.join(assets, 'sidebar-layout-recovery-patch.json'), 'utf8'));
const frontend = path.join(runtime, 'node_modules/@deepseek-ai/dsh-web-frontend/dist');
const frontendHtml = fs.readFileSync(path.join(frontend, 'index.html'), 'utf8');
const frontendEntry = frontendHtml.match(/src="\.\/(assets\/index-[^"]+\.js)"/)[1];
const inputs = [
  path.join(runtime, 'node_modules/@deepseek-ai/dsh-client-ui-chat/lib/client.js'),
  path.join(runtime, 'node_modules/@deepseek-ai/dsh-client-ui-sidebar-right/lib/client.js'),
  path.join(assets, 'sidebar-layout-recovery-patch.json'),
  path.join(assets, 'builtin-plugins/dsh-web-mobile/lib/client.js'),
  path.join(root, 'tools/rc1-browser-fixture.mjs'),
  path.join(frontend, 'index.html'), path.join(frontend, frontendEntry),
  ...recipe.patches.filter(patch => patch.prependAsset).map(patch => path.join(assets, patch.prependAsset)),
  fileURLToPath(import.meta.url),
];
const fingerprint = () => Object.fromEntries(inputs.map(file => [file, createHash('sha256').update(fs.readFileSync(file)).digest('hex')]));
const sourceHashes = fingerprint();
const report = {schema: 1, status: 'RUNNING', requestedHours: hours,
  requestedDurationMs: hours * 3600000, intervalSeconds: interval,
  scope: 'desktop locked rc2 React chat/sidebar, current mobile phone chrome, shortcut guard and CSS; synthetic conversations only',
  deviceAcceptance: false, liveModelRequests: false, sourceHashes,
  startedAt: new Date().toISOString(), samples: [], engines: {}, limits: [
    'No Android WebView/Gecko or OEM keyboard/PiP execution',
    'No physical-device reproduction of the original eight-hour report',
    'Synthetic deterministic conversation data and sidebar corruption, not a full account/backend session',
    'Mobile navigation, drawer, statistics and Android integrations have separate short tests; they are not covered by this elapsed-time soak',
  ]};
fs.mkdirSync(path.dirname(output), {recursive: true});
const save = () => {
  const temporary = output + '.partial';
  fs.writeFileSync(temporary, JSON.stringify(report, null, 2) + '\n');
  fs.renameSync(temporary, output);
};
const fixtures = [];
let started = performance.now();
save();
try {
  for (const name of engine === 'both' ? ['chromium', 'firefox'] : [engine]) {
    process.env.DSHA_BROWSER = name;
    const fixture = await browserFixture(runtime);
    fixtures.push({name, fixture});
    await fixture.load('dsh-client-ui-chat', [], 'module.exports.audit = {ChatNodeList};');
    await fixture.load('dsh-client-ui-sidebar-right', [recipe],
      'module.exports.audit = {createSidebarRightStore,RightbarSeat,dshaValidSurface};');
    const mobile = fs.readFileSync(path.join(assets, 'builtin-plugins/dsh-web-mobile/lib/client.js'), 'utf8');
    assert.ok(mobile.includes('var __cache = {};'), 'mobile module boundary must remain exact');
    await fixture.page.addScriptTag({content: mobile.replace('var __cache = {};', 'window.mobileRequire=__localRequire; var __cache = {};')});
    await fixture.page.evaluate(() => {
      const React = auditModules.react.default || auditModules.react;
      const ReactDOM = auditModules['react-dom'].default || auditModules['react-dom'];
      const primitives = auditModules['@deepseek-ai/dsh-client-ui-primitives'];
      const chat = auditExports['@deepseek-ai/dsh-client-ui-chat'].audit;
      const sidebar = auditExports['@deepseek-ai/dsh-client-ui-sidebar-right'].audit;
      const disposers = [];
      const context = {effect(run) { const dispose = run(); if (dispose) disposers.push(dispose); }};
      mobileRequire('./effects/phone-chrome.js').installPhoneChrome(context);
      mobileRequire('./effects/shortcut-modal-keyboard-guard.js').installShortcutModalKeyboardGuard(context);
      for (const name of ['base', 'layout', 'compat', 'misc']) {
        const style = document.createElement('style');
        style.textContent = Object.values(mobileRequire(`./styles/${name}.css.js`)).filter(value => typeof value === 'string').join('\n');
        document.head.append(style);
      }
      const host = document.getElementById('root');
      const chatHost = document.createElement('div'), sidebarHost = document.createElement('div');
      chatHost.style.cssText = 'height:500px;overflow:auto';
      host.append(chatHost, sidebarHost);
      const nodes = {}, entries = [];
      for (let index = 0; index < 100; index++) {
        const key = `synthetic-${index}`, kind = index % 5 === 0 ? 'user' : 'assistant';
        entries.push({kind: 'node', key});
        nodes[key] = {key, kind, anchorSeq: index + 1, data: {text: `${kind} ${index}\n\n${'bounded streamed content '.repeat(8)}`},
          location: {kind: 'turn', turn: {turn: index, status: 'closed', data: {}}}};
      }
      const nodeListeners = new Map();
      const chatProps = {entries, pendingInputs: [], nodeStore: {get: key => nodes[key]},
        useChatGroup: () => undefined,
        useChatNode: key => React.useSyncExternalStore(listener => {
          let listeners = nodeListeners.get(key);
          if (!listeners) nodeListeners.set(key, listeners = new Set());
          listeners.add(listener); return () => listeners.delete(listener);
        }, () => nodes[key]), useChatNodeProcess: () => undefined,
        usePresentation: pick => pick({foldCompletedTurns: false}), useStore: pick => pick({}),
        actions: {setTurnProcessOpen() {}}, cwd: '', openFile() {}, openSkill() {}, inspectCall() {},
        forkAt() {}, loadImage() {}, renderMessageImages: () => null, fileMentions() {},
        renderSlot: (_name, owner) => React.createElement(primitives.MarkdownText,
          {text: owner.node.data.text, streaming: true, labels: {code: {copyLabel: 'Copy synthetic code',
            copiedLabel: 'Copied', toolbarLabels: {codeLabel: 'Code', wrapLabel: 'Wrap', unwrapLabel: 'Unwrap'}}}}), t: key => key};
      const handle = sidebar.createSidebarRightStore(() => ({kind: 'guide', contentId: 'guide', title: 'Synthetic guide'}));
      const instance = handle.create('synthetic');
      instance.actions.open('synthetic'); instance.actions.open('other');
      const other = JSON.stringify(instance.getSnapshot().bySession.other);
      localStorage.setItem('dsh.conversation.synthetic', JSON.stringify({draft: 'PRESERVE SYNTHETIC DRAFT', view: null, viewRequest: null}));
      const noop = () => {};
      const sidebarProps = {sessionId: 'synthetic', width: 350, viewportWidth: 360, canShow: true,
        active: true, actions: instance.actions, t: key => key,
        useStore: pick => pick(React.useSyncExternalStore(instance.subscribe, instance.getSnapshot)),
        useShortcuts: pick => pick([]), useTabTypes: pick => pick([]), renderSlot: () => null,
        useTabNavigation: () => ({}), occurrence: tab => ({id: tab.id, signal: new AbortController().signal, tabActions: {}}),
        retainTab: noop, syncPresentation: noop, measureRoom: noop, reportAutoFullscreen: noop,
        openTab: noop, closeTab: noop, splitPane: noop, toggleFullscreen: noop};
      const chatRoot = ReactDOM.createRoot(chatHost), sidebarRoot = ReactDOM.createRoot(sidebarHost);
      let renderRevision = 0;
      const render = () => { chatRoot.render(React.createElement(chat.ChatNodeList, {...chatProps, renderRevision: ++renderRevision})); sidebarRoot.render(React.createElement(sidebar.RightbarSeat, sidebarProps)); };
      render();
      window.soak = {async tick(sequence) {
        const streamText = `bounded stream revision ${sequence}`;
        nodes['synthetic-99'] = {...nodes['synthetic-99'], data: {text: `${streamText}\n\n\`\`\`text\nretained code block\n\`\`\``}};
        for (const listener of nodeListeners.get('synthetic-99') || []) listener();
        render();
        await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        const renderDeadline = performance.now() + 5000;
        while (!chatHost.textContent.includes(streamText) && performance.now() < renderDeadline)
          await new Promise(resolve => setTimeout(resolve, 20));
        return {sequence, streamedRevisionVisible: chatHost.textContent.includes(streamText), rows: chatHost.querySelectorAll('[data-chat-flow-key]').length,
          valid: sidebar.dshaValidSurface(instance.getSnapshot().bySession.synthetic),
          sidebarRendered: sidebarHost.querySelectorAll('[data-sidebar-right-session="synthetic"]').length === 1,
          otherPreserved: JSON.stringify(instance.getSnapshot().bySession.other) === other,
          draft: JSON.parse(localStorage.getItem('dsh.conversation.synthetic')).draft,
          domNodes: document.querySelectorAll('*').length, heapBytes: performance.memory?.usedJSHeapSize || null};
      }, corrupt() {
        const value = JSON.parse(JSON.stringify(instance.getSnapshot()));
        value.bySession.synthetic.layout.rootId = 'missing-synthetic-pane'; instance.store.set(value);
      }, close() { chatRoot.unmount(); sidebarRoot.unmount(); for (const dispose of disposers.reverse()) dispose(); }};
    });
    await fixture.page.locator('[data-sidebar-right-session="synthetic"]').waitFor();
    report.engines[name] = {version: await fixture.browser.version(), cycles: 0, recoveries: 0};
  }
  report.setupDurationMs = Math.round(performance.now() - started);
  started = performance.now();
  report.startedAt = new Date().toISOString();
  let sequence = 0;
  do {
    sequence++;
    for (const {name, fixture} of fixtures) {
      const sample = await fixture.page.evaluate(index => window.soak.tick(index), sequence);
      assert.equal(sample.rows, 100, `${name}: rows lost`);
      assert.equal(sample.streamedRevisionVisible, true, `${name}: renderer did not apply the actual stream update`);
      assert.equal(sample.valid, true, `${name}: sidebar invalid`);
      assert.equal(sample.sidebarRendered, true, `${name}: actual sidebar DOM disappeared`);
      assert.equal(sample.otherPreserved, true, `${name}: another session changed`);
      assert.equal(sample.draft, 'PRESERVE SYNTHETIC DRAFT', `${name}: draft changed`);
      assert.ok(sample.domNodes < 15000, `${name}: unbounded DOM growth`);
      if (sample.heapBytes !== null) assert.ok(sample.heapBytes < 512 * 1024 * 1024, `${name}: heap exceeded the desktop fixture budget`);
      if (sequence % 60 === 0) {
        await fixture.page.evaluate(() => window.soak.corrupt());
        await fixture.page.locator('[data-dsha-layout-recovery] button').click();
        await fixture.page.locator('[data-sidebar-right-session="synthetic"]').waitFor();
        report.engines[name].recoveries++;
      }
      assert.equal(fixture.errors.length, 0,
        `${name}: ${fixture.errors.join('\n')}`);
      report.engines[name].cycles++;
      report.samples.push({engine: name, elapsedMs: Math.round(performance.now() - started), ...sample});
    }
    report.elapsedMs = Math.round(performance.now() - started); save();
    if (sequence % 10 === 0) process.stdout.write(JSON.stringify({status: 'RUNNING', sequence, elapsedMs: report.elapsedMs}) + '\n');
    const remaining = report.requestedDurationMs - (performance.now() - started);
    if (remaining > 0) await new Promise(resolve => setTimeout(resolve, Math.min(interval * 1000, remaining)));
  } while (performance.now() - started < report.requestedDurationMs);
  assert.deepEqual(fingerprint(), sourceHashes, 'loaded source changed during soak; rerun on final inputs');
  report.status = 'PASS';
  report.completedAt = new Date().toISOString();
  report.elapsedMs = Math.round(performance.now() - started);
  assert.ok(report.elapsedMs >= report.requestedDurationMs);
  const minimumCycles = Math.max(1, Math.floor(report.requestedDurationMs / (interval * 1000 + 5000)));
  for (const value of Object.values(report.engines)) assert.ok(value.cycles >= minimumCycles, 'insufficient actual stress cycles');
  save();
  process.stdout.write(JSON.stringify({status: report.status, elapsedMs: report.elapsedMs, engines: report.engines, output}) + '\n');
} catch (error) {
  report.status = 'FAIL'; report.error = String(error?.stack || error); report.elapsedMs = Math.round(performance.now() - started);
  report.browserErrors = Object.fromEntries(fixtures.map(({name, fixture}) => [name, fixture.errors.slice(-20)]));
  report.completedAt = new Date().toISOString(); save(); throw error;
} finally {
  for (const {fixture} of fixtures) {
    await fixture.page.evaluate(() => window.soak?.close()).catch(() => {});
    await fixture.close();
  }
}
