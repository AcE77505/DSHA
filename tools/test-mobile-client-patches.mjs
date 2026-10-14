// 源漂移必须拒绝；正式产物只能来自固定预构建client与明确DSHA差异。
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {applyMobileClientPatches,upstreamCommit,upstreamClientHash} from './apply-mobile-client-patches.mjs';
test('移动client固定预构建来源与部署产物闭环，拒绝错误版本/追加字节',()=>{
  const source=readFileSync('tools/mobile-plugin-upstream/lib/client.js');
  assert.equal(createHash('sha256').update(source).digest('hex'),upstreamClientHash);
  assert.equal(upstreamCommit,'ab3a85bead1c53b723e8627f5b44e3c306d85604');
  assert.equal(readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8'),applyMobileClientPatches(source));
  assert.throws(()=>applyMobileClientPatches(Buffer.concat([source,Buffer.from('\n')])),/字节不匹配/);
});
