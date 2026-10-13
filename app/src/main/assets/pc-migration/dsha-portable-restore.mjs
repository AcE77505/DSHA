#!/usr/bin/env node
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {extractPackage,absoluteTarget,overlap,copyTree,inventory,profile,sessions,rewrite,sanitizedCredentials,yamlDocument,checkedPath,commit,recover} from './portable-core.mjs';
export function parseArgs(argv) {
  const args={_:[],maps:[]};
  for(let i=0;i<argv.length;i++) {
    const token=argv[i];if(!token.startsWith('--')){args._.push(token);continue;}const name=token.slice(2);
    if(['rewrite-cwd','with-credentials','help'].includes(name)){if(args[name])throw Error('DUPLICATE_ARGUMENT');args[name]=true;}
    else if(['package','home','workspace-dir','workspace-map','journal'].includes(name)){
      const value=argv[++i];if(!value||value.startsWith('--'))throw Error('MISSING_ARGUMENT');
      if(name==='workspace-map')args.maps.push(value);else {if(args[name])throw Error('DUPLICATE_ARGUMENT');args[name]=value;}
    }else throw Error('UNKNOWN_ARGUMENT:'+name);
  }
  if(args._.length>1)throw Error('COMMAND_ARGUMENTS');
  if(args['rewrite-cwd']&&!args['workspace-dir']&&!args.maps.length)throw Error('CWD_MAPPING_REQUIRED');return args;
}
function mappingFor(args,ctx,strict) {
  const logs=sessions(ctx.home),source=[...new Set([...ctx.manifest.options.workspaces,...logs.map(s=>s.header.cwd).filter(s=>s!==undefined)])],mapping=new Map();
  if(source.some(s=>typeof s!=='string'||!s||/[\0\r\n]/.test(s)))throw Error('SOURCE_CWD');
  for(const row of args.maps){const i=row.indexOf('=');if(i<1)throw Error('WORKSPACE_MAP_FORMAT');const from=row.slice(0,i);if(!source.includes(from)||mapping.has(from))throw Error('WORKSPACE_MAP_SOURCE');mapping.set(from,absoluteTarget(row.slice(i+1)));}
  if(args['workspace-dir']){
    const base=absoluteTarget(args['workspace-dir']),anchors=ctx.manifest.options.workspaces.length?ctx.manifest.options.workspaces:source;
    for(let i=0;i<anchors.length;i++)if(!mapping.has(anchors[i]))mapping.set(anchors[i],anchors.length===1?base:path.join(base,`ws${i+1}`));
    const normalized=s=>s.replaceAll('\\','/').replace(/\/+$/,'');
    for(const old of source)if(!mapping.has(old)){
      const ancestor=[...mapping.keys()].filter(k=>typeof k==='string'&&normalized(old).startsWith(normalized(k)+'/')).sort((a,b)=>b.length-a.length)[0];
      if(ancestor){const relative=normalized(old).slice(normalized(ancestor).length+1);if(relative.split('/').some(p=>!p||p==='.'||p==='..'))throw Error('CWD_RELATIVE_ESCAPE');mapping.set(old,path.join(mapping.get(ancestor),...relative.split('/')));}
      else mapping.set(old,path.join(base,`extra-${mapping.size+1}`));
    }
  }
  if(args['rewrite-cwd']&&source.some(s=>!mapping.has(s)))throw Error('CWD_MAPPING_REQUIRED');
  if(args['workspace-dir']&&logs.some(s=>s.header.cwd===undefined))mapping.set(undefined,absoluteTarget(args['workspace-dir']));
  if(strict&&ctx.workspace&&ctx.manifest.options.workspaces.some(s=>!mapping.has(s)))throw Error('WORKSPACE_DESTINATION_REQUIRED');return mapping;
}
function uniqueTargets(targets,pkg) {
  for(const target of targets){absoluteTarget(target);if(overlap(target,pkg))throw Error('PACKAGE_TARGET_OVERLAP');}
  for(let i=0;i<targets.length;i++)for(let j=i+1;j<targets.length;j++)if(overlap(targets[i],targets[j])||overlap(targets[j],targets[i]))throw Error('TARGET_OVERLAP');
}
export async function run(args,fault) {
  if(args._[0]==='recover'){if(!args.journal)throw Error('JOURNAL_REQUIRED');return recover(args.journal);}
  const command=args._[0]||'plan';if(!['plan','apply'].includes(command))throw Error('UNKNOWN_COMMAND');if(!args.package)throw Error('PACKAGE_REQUIRED');
  if(args['rewrite-cwd']&&!args['workspace-dir']&&!args.maps.length)throw Error('CWD_MAPPING_REQUIRED');
  const home=absoluteTarget(args.home||path.join(os.homedir(),'.dsh')),pkg=path.resolve(args.package);if(args['workspace-dir'])absoluteTarget(args['workspace-dir']);
  const staging=fs.mkdtempSync(path.join(os.tmpdir(),'dsha-portable-inspect-'));let keep=false;
  try {
    const ctx=await extractPackage(pkg,staging),mapping=mappingFor(args,ctx,command==='apply'),selected=profile(ctx.manifest.source.profile),workspaceRows=[];
    if(ctx.workspace){const roots=ctx.manifest.options.workspaces;if(!roots.length)throw Error('WORKSPACE_MANIFEST');for(let i=0;i<roots.length;i++)workspaceRows.push({source:roots.length===1?ctx.workspace:checkedPath(ctx.workspace,`ws${i+1}`),target:mapping.get(roots[i])});}
    if(ctx.workspace&&ctx.manifest.options.workspaces.length>1){const expected=ctx.manifest.options.workspaces.map((_,i)=>`ws${i+1}`).sort();if(JSON.stringify(fs.readdirSync(ctx.workspace).sort())!==JSON.stringify(expected))throw Error('WORKSPACE_SCOPE');}
    uniqueTargets([home,...workspaceRows.map(r=>r.target).filter(Boolean)],pkg);
    for(const target of mapping.values())if(overlap(home,target)||overlap(target,home))throw Error('CWD_HOME_OVERLAP');
    const report={kind:'DSHA_PC_PLAN_V2',sourceVersion:ctx.manifest.source.dshVersion,home,workspaces:workspaceRows.map(r=>({target:r.target})),cwdMapping:Object.fromEntries(mapping),packageDigest:inventory(staging).digest,legacy:ctx.manifest.schemaVersion===1,scope:'portable subset; source was read and verified, not frozen; no model request'};
    if(command==='plan'){report.applyArguments=['apply','--package',pkg,'--home',home];if(ctx.workspace)report.applyArguments.push('--workspace-dir',args['workspace-dir']||'<destination>');return report;}
    const candidates=[];const make=(target,source)=>{fs.mkdirSync(path.dirname(target),{recursive:true,mode:0o700});absoluteTarget(target);const candidate=path.join(path.dirname(target),`.dsha-portable-candidate-${crypto.randomUUID()}`);copyTree(source,candidate);candidates.push({target,candidate});return candidate;};
    const candidate=make(home,ctx.home),credentials=path.join(candidate,'.credentials.yaml');
    if(fs.existsSync(credentials)){if(!args['with-credentials'])fs.unlinkSync(credentials);else {try{fs.writeFileSync(credentials,sanitizedCredentials(fs.readFileSync(credentials,'utf8')),{mode:0o600});fs.chmodSync(credentials,0o600);}catch{fs.unlinkSync(credentials);report.credentials='REJECTED_SCHEMA';}}}
    const patch=path.join(candidate,'profiles',selected,'cordis.patch.yml');if(fs.existsSync(patch)){const doc=yamlDocument(fs.readFileSync(patch,'utf8'),true);if(doc.toJSON().some(row=>!row||typeof row!=='object'||Array.isArray(row)))throw Error('OVERLAY_ENTRY');fs.writeFileSync(patch,doc.toString(),{mode:0o600});report.profile='REVIEW_BEFORE_DSH_START';}
    if(args['rewrite-cwd'])rewrite(candidate,home,selected,mapping);
    const registry=path.join(candidate,'storages','workspace.json');if(fs.existsSync(registry)){const doc=JSON.parse(fs.readFileSync(registry,'utf8'));for(const r of Object.values(doc?.tables?.workspaces||{}))if(r&&typeof r.path==='string'&&mapping.has(r.path))r.path=mapping.get(r.path);fs.writeFileSync(registry,JSON.stringify(doc,null,2)+'\n',{mode:0o600});}
    for(const row of workspaceRows)make(row.target,row.source);
    if(args['rewrite-cwd'])for(const target of mapping.values())if(!workspaceRows.some(row=>overlap(row.target,target))&&!fs.existsSync(target))fs.mkdirSync(target,{recursive:true,mode:0o700});
    const operations=path.join(path.dirname(home),'.dsha-portable-operations');if(fs.existsSync(operations)&&!fs.lstatSync(operations).isDirectory())throw Error('JOURNAL_ROOT');fs.mkdirSync(operations,{recursive:true,mode:0o700});absoluteTarget(operations);
    const journal=path.join(operations,crypto.randomUUID()+'.json');keep=true;return {...report,journal,result:commit(candidates,journal,fault)};
  }catch(error){if(keep)error.message+='; 候选、原件与事务回执已保留';throw error;}
  finally{if(!keep)fs.rmSync(staging,{recursive:true,force:true});}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href){try{const args=parseArgs(process.argv.slice(2));if(args.help)console.log('Node 24+: plan|apply --package <包> [--home <目录>] [--workspace-dir <目录> | --workspace-map <旧cwd=新目录>] [--rewrite-cwd] [--with-credentials]');else console.log(JSON.stringify(await run(args),null,2));}catch(error){console.error('恢复失败：'+error.message);process.exitCode=2;}}
