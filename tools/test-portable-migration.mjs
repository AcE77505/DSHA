// Exercise the exact generated offline CLI and dependency bytes, never a global YAML installation.
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
const root=path.resolve(import.meta.dirname,'..');
const assets=path.join(root,'app/src/main/assets');
const declared=JSON.parse(fs.readFileSync(path.join(assets,'portable-tools.manifest.json'),'utf8'));
for(const [name,digest] of Object.entries(declared.files)) {
  const actual=createHash('sha256').update(fs.readFileSync(path.join(assets,name))).digest('hex');
  if(actual!==digest)throw Error('PORTABLE_TEST_ASSET_DRIFT:'+name);
}
const directory=fs.mkdtempSync(path.join(root,'app/build/portable-cli-test-'));
try {
  fs.cpSync(path.join(assets,'pc-migration'),directory,{recursive:true});
  fs.copyFileSync(path.join(root,'tools/pc-migration/hardened-selftest.mjs'),path.join(directory,'hardened-selftest.mjs'));
  const result=spawnSync(process.execPath,[path.join(directory,'hardened-selftest.mjs')],{
    cwd:root,env:{...process.env,PYTHONUTF8:'1',PYTHONIOENCODING:'utf-8'},encoding:'utf8',windowsHide:true,timeout:120000});
  process.stdout.write(result.stdout||'');process.stderr.write(result.stderr||'');
  if(result.error)throw result.error;
  if(result.status!==0)throw Error('PORTABLE_SELFTEST_FAILED:'+result.status);
} finally {
  const resolved=path.resolve(directory),parent=path.resolve(root,'app/build');
  if(!resolved.startsWith(parent+path.sep)||!path.basename(resolved).startsWith('portable-cli-test-'))
    throw Error('PORTABLE_TEST_CLEANUP_BOUNDARY');
  fs.rmSync(resolved,{recursive:true,force:true});
}
