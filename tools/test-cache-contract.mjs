import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { testRuntime } from './test-runtime-fixture.mjs';

const runtime = testRuntime('raw');
const require = createRequire(pathToFileURL(runtime + '/package.json'));
let source = fs.readFileSync(new URL('../app/src/main/assets/builtin-plugins/dsh-device-shell-guide/lib/index.js', import.meta.url), 'utf8');
const anchor = "from '@deepseek-ai/dsh-llm'";
assert.equal(source.split(anchor).length - 1, 1);
source = source.replace(anchor, 'from ' + JSON.stringify(pathToFileURL(require.resolve('@deepseek-ai/dsh-llm')).href));
const guide = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const directory = { isDirectory: () => true, isSymbolicLink: () => false };
const created = [];
assert(guide.prepareCacheDirectory({ lstatSync: () => directory, mkdirSync: () => { throw Error('must not replace'); } }).ready);
assert(!guide.prepareCacheDirectory({ lstatSync: () => { throw Object.assign(Error(), { code: 'EACCES' }); } }).ready);
assert(!guide.prepareCacheDirectory({ lstatSync: () => ({ ...directory, isSymbolicLink: () => true }) }).ready);
assert(guide.prepareCacheDirectory({ lstatSync: path => { if (!created.includes(path)) throw Object.assign(Error(), {code:'ENOENT'}); return directory; },
  mkdirSync: path => created.push(path) }).ready);
assert.deepEqual(created, ['/root', '/root/.cache', '/root/.cache/dsha']);
const saved = fs.lstatSync;
try {
  for (const success of [true, false]) {
    fs.lstatSync = () => { if (!success) throw Object.assign(Error(), {code:'EACCES'}); return directory; };
    let prompt;
    guide.apply({ inject: (_, callback) => callback({ systemPrompt: { section: value => { prompt = value.text; } }, effect: callback => callback() }),
      on() {}, logger: { warn() {} } });
    assert(prompt.includes('/root/.cache/dsha'));
    assert.equal(prompt.includes('已创建并核验'), success);
    if (!success) assert(prompt.includes('当前不可用：EACCES'));
    assert(!prompt.includes('日志、草稿'), 'persistent drafts cannot be described as scratch');
  }
} finally { fs.lstatSync = saved; }
console.log('PASS cache fixed-path / safe parents / readiness prompt contract; no real directory created');
