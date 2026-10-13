// 本地策略和真实字节决定恢复；包内声明仅供诊断。无 tar 子进程或安装钩子。
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';

export const HERE = path.dirname(fileURLToPath(import.meta.url));
export const POLICY = JSON.parse(fs.readFileSync(path.join(HERE, 'portable-plan.json'), 'utf8'));
const MAX_BYTES = 16 * 1024 ** 3, MAX_ENTRIES = 100000, MAX_LOG = 256 * 1024 ** 2;
const sha = data => crypto.createHash('sha256').update(data).digest('hex');
const fail = code => { throw new Error(code); };
const stat = p => { try { return fs.lstatSync(p); } catch (e) { if (e.code === 'ENOENT') return null; throw e; } };
export function safeRel(value) {
  if (typeof value !== 'string' || !value || value.length > 2048 || /[\\\0\r\n]/.test(value) || value.startsWith('/') || /^[A-Za-z]:/.test(value)) fail('UNSAFE_PATH');
  const parts = value.split('/');
  if (parts.length > 64 || parts.some(p => !p || p === '.' || p === '..' || p.includes(':') || /[. ]$/.test(p) || /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(p))) fail('UNSAFE_PATH');
  return value;
}
export function profile(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$/.test(value)) fail('UNSAFE_PROFILE');
  return value;
}
export function allowedHome(relative, selectedProfile = null) {
  safeRel(relative);
  if (relative === '.credentials.yaml') return true;
  if (/^profiles\/[A-Za-z0-9][A-Za-z0-9_-]{0,79}\/cordis\.patch\.yml$/.test(relative))
    return selectedProfile === null || relative === `profiles/${profile(selectedProfile)}/cordis.patch.yml`;
  return POLICY.include.some(rule => !rule.path.includes('{profile}') &&
    (relative === rule.path || rule.kind === 'dir' && relative.startsWith(rule.path + '/') &&
      !relative.slice(rule.path.length+1).split('/').some(p=>(rule.skip||[]).includes(p))));
}
export function checkedPath(root, relative, makeParents = false) {
  safeRel(relative);
  let at = root;
  const base = stat(at);
  if (!base?.isDirectory() || base.isSymbolicLink()) fail('UNSAFE_ROOT');
  const parts = relative.split('/');
  for (let i = 0; i < parts.length; i++) {
    at = path.join(at, parts[i]);
    const n = stat(at);
    if (n?.isSymbolicLink()) fail('LINK_PATH');
    if (i < parts.length - 1) {
      if (!n && makeParents) fs.mkdirSync(at, {mode: 0o700});
      else if (!n?.isDirectory()) fail('PARENT_NOT_DIRECTORY');
    }
  }
  return at;
}
export function absoluteTarget(value) {
  if (typeof value !== 'string' || !value.trim() || /[\0\r\n]/.test(value)) fail('INVALID_TARGET');
  if (value === '~' || value.startsWith('~/') || value.startsWith('~\\')) value = path.join(os.homedir(), value.slice(2));
  const target = path.resolve(value);
  if (target === path.parse(target).root || target === os.homedir()) fail('REFUSE_BROAD_TARGET');
  const parsed = path.parse(target);
  let at = parsed.root;
  for (const part of target.slice(parsed.root.length).split(path.sep)) {
    at = path.join(at, part);
    const n = stat(at);
    if (n && (n.isSymbolicLink() || !n.isDirectory())) fail('TARGET_PARENT_UNSAFE');
  }
  return target;
}
export function overlap(a, b) {
  const relative = path.relative(a, b);
  return !relative || !relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative);
}
function same(a, b) { return a.dev === b.dev && a.ino === b.ino && a.size === b.size && a.mtimeMs === b.mtimeMs && a.ctimeMs === b.ctimeMs; }
export function inventory(root) {
  const rows = [];
  let bytes = 0;
  const walk = (file, relative) => {
    const before = fs.lstatSync(file);
    if (before.isSymbolicLink()) fail('TREE_LINK');
    if (rows.length >= MAX_ENTRIES) fail('ENTRY_LIMIT');
    if (before.isDirectory()) {
      rows.push([relative, 'dir']);
      for (const name of fs.readdirSync(file).sort()) walk(path.join(file, name), relative ? `${relative}/${name}` : name);
    } else if (before.isFile()) {
      bytes += before.size; if (bytes > MAX_BYTES) fail('BYTE_LIMIT');
      const fd = fs.openSync(file, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0) | fs.constants.O_NONBLOCK);
      const hash = crypto.createHash('sha256'), buffer = Buffer.alloc(65536);
      try {
        if (!same(before, fs.fstatSync(fd))) fail('SOURCE_CHANGED');
        let n; while ((n = fs.readSync(fd, buffer)) > 0) hash.update(buffer.subarray(0, n));
        if (!same(before, fs.fstatSync(fd)) || !same(before, fs.lstatSync(file))) fail('SOURCE_CHANGED');
      } finally { fs.closeSync(fd); }
      rows.push([relative, 'file', before.size, hash.digest('hex')]);
    } else fail('SPECIAL_FILE');
    if (!same(before, fs.lstatSync(file))) fail('SOURCE_CHANGED');
  };
  if (stat(root)) walk(root, '');
  return {rows, bytes, digest: sha(JSON.stringify(rows))};
}
export function copyTree(source, target) {
  const before = inventory(source);
  if (stat(target)) fail('CANDIDATE_EXISTS');
  const walk = (src, dst) => {
    const n = fs.lstatSync(src);
    if (n.isSymbolicLink()) fail('TREE_LINK');
    if (n.isDirectory()) { fs.mkdirSync(dst, {mode:0o700}); for (const name of fs.readdirSync(src)) walk(path.join(src, name), path.join(dst, name)); }
    else if (n.isFile()) {
      const input = fs.openSync(src, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0) | fs.constants.O_NONBLOCK);
      const output = fs.openSync(dst, 'wx', n.mode & 0o100 ? 0o700 : 0o600), buffer = Buffer.alloc(65536);
      try { if (!same(n, fs.fstatSync(input))) fail('SOURCE_CHANGED'); let count; while ((count = fs.readSync(input, buffer)) > 0) { let offset=0; while(offset<count) offset += fs.writeSync(output,buffer,offset,count-offset); } fs.fsyncSync(output); if (!same(n, fs.fstatSync(input))) fail('SOURCE_CHANGED'); }
      finally { fs.closeSync(input); fs.closeSync(output); }
    } else fail('SPECIAL_FILE');
  };
  walk(source, target);
  if (inventory(source).digest !== before.digest || inventory(target).digest !== before.digest) fail('COPY_CHECKSUM');
}
function octal(buffer) {
  if (buffer[0] & 128) fail('TAR_BASE256_UNSUPPORTED');
  const text = buffer.toString('ascii').replace(/\0.*$/, '').trim();
  if (!/^[0-7]*$/.test(text)) fail('TAR_NUMBER');
  const n = parseInt(text || '0', 8); if (!Number.isSafeInteger(n)) fail('TAR_NUMBER'); return n;
}
function pax(data) {
  const result = {}; let offset = 0;
  while (offset < data.length) {
    const space = data.indexOf(32, offset); if (space < 0) fail('PAX_FORMAT');
    const length = Number(data.subarray(offset, space).toString('ascii'));
    if (!Number.isInteger(length) || length <= space-offset+1 || offset+length > data.length || data[offset+length-1] !== 10) fail('PAX_FORMAT');
    const line = new TextDecoder('utf-8',{fatal:true}).decode(data.subarray(space+1, offset+length-1)), equals = line.indexOf('=');
    if (equals < 1) fail('PAX_FORMAT');
    const key = line.slice(0, equals);
    if (!['path','mtime','atime','ctime','uid','gid','uname','gname'].includes(key) || key in result) fail('PAX_UNSUPPORTED');
    result[key] = line.slice(equals+1); offset += length;
  }
  return result;
}
/** 流式读取实际 tar 内容；只允许目录/普通文件/PAX，不执行外部 tar，也不创建链接。 */
export async function extractPackage(pkg, staging) {
  const source = fs.lstatSync(pkg);
  if (!source.isFile() || source.isSymbolicLink() || source.size > 4*1024**3) fail('PACKAGE_TYPE_OR_LIMIT');
  const originalDigest = inventory(pkg).digest;
  const seen = new Set(); let total=0, buffer=Buffer.alloc(0), state=null, pending={}, zeros=0, ended=false;
  const string = b => new TextDecoder('utf-8',{fatal:true}).decode(b.subarray(0,b.indexOf(0)<0?b.length:b.indexOf(0)));
  const input = fs.createReadStream(pkg, {flags:fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0)});
  const unzip = zlib.createGunzip(); input.on('error', e => unzip.destroy(e)); input.pipe(unzip);
  try {
    for await (const chunk of unzip) {
      total += chunk.length; if (total > MAX_BYTES + MAX_ENTRIES * 1024) fail('EXPANDED_LIMIT');
      buffer = Buffer.concat([buffer,chunk]);
      while (true) {
        if (ended) { if (buffer.some(v=>v!==0)) fail('TAR_TRAILING_DATA'); buffer=Buffer.alloc(0); break; }
        if (!state) {
          if (buffer.length < 512) break;
          const header=buffer.subarray(0,512); buffer=buffer.subarray(512);
          if (header.every(v=>v===0)) { if (++zeros===2) ended=true; continue; }
          if (zeros) fail('TAR_TERMINATOR');
          let sum=0; for(let i=0;i<512;i++) sum += i>=148&&i<156 ? 32 : header[i];
          if(sum!==octal(header.subarray(148,156))) fail('TAR_CHECKSUM');
          const kind=header[156]===0?'0':String.fromCharCode(header[156]);
          const size=octal(header.subarray(124,136)), mode=octal(header.subarray(100,108));
          if(size>MAX_BYTES) fail('MEMBER_LIMIT');
          if(!['0','5','x'].includes(kind)) fail('TAR_LINK_OR_SPECIAL');
          let fd=null, name=null;
          if(kind==='x') { if(size>16384) fail('PAX_LIMIT'); }
          else {
            name=(pending.path||[string(header.subarray(345,500)),string(header.subarray(0,100))].filter(Boolean).join('/')).replace(/\/$/,''); pending={}; safeRel(name);
            const collision = name.normalize('NFC').toLowerCase();
            if(seen.has(collision) || seen.size>=MAX_ENTRIES) fail('TAR_DUPLICATE_OR_LIMIT'); seen.add(collision);
            if(!(name==='home'||name==='workspace'||name==='manifest.json'||name==='README-在电脑上恢复.md'||name.startsWith('home/')||name.startsWith('workspace/'))) fail('TAR_SCOPE');
            if(name.startsWith('home/')) {
              const relative=name.slice(5);
              const ancestor=kind==='5' && (relative==='profiles'||/^profiles\/[A-Za-z0-9][A-Za-z0-9_-]{0,79}$/.test(relative)||POLICY.include.some(r=>!r.path.includes('{profile}')&&r.path.startsWith(relative+'/')));
              if(!ancestor&&!allowedHome(relative))fail('HOME_POLICY');
            }
            if(kind==='5' && size!==0) fail('DIRECTORY_PAYLOAD');
            const target=checkedPath(staging,name,true), existing=stat(target);
            if(kind==='5') { if(existing&&!existing.isDirectory()) fail('TAR_TYPE_CONFLICT'); if(!existing)fs.mkdirSync(target,{mode:0o700}); }
            else { if(existing) fail('TAR_TYPE_CONFLICT'); fd=fs.openSync(target,'wx',0o600); }
          }
          state={kind,name,mode,left:size,padding:(512-size%512)%512,fd,pieces:[]};
        }
        if(state.left) {
          if(!buffer.length) break;
          const count=Math.min(state.left,buffer.length), bytes=buffer.subarray(0,count);
          if(state.fd!==null) { let offset=0; while(offset<count)offset+=fs.writeSync(state.fd,bytes,offset,count-offset); }
          else if(state.kind==='x')state.pieces.push(Buffer.from(bytes));
          state.left-=count; buffer=buffer.subarray(count); if(state.left)break;
        }
        if(buffer.length<state.padding)break;
        if(buffer.subarray(0,state.padding).some(v=>v!==0))fail('TAR_PADDING'); buffer=buffer.subarray(state.padding);
        if(state.fd!==null){fs.fchmodSync(state.fd,state.mode&0o100?0o700:0o600);fs.fsyncSync(state.fd);fs.closeSync(state.fd);state.fd=null;}
        if(state.kind==='x')pending=pax(Buffer.concat(state.pieces)); state=null;
      }
    }
    if(state||buffer.length||!ended)fail('TAR_TRUNCATED');
    if(!same(source,fs.lstatSync(pkg)) || inventory(pkg).digest!==originalDigest)fail('PACKAGE_CHANGED');
  } finally { if(state?.fd!==null&&state?.fd!==undefined)fs.closeSync(state.fd); input.destroy(); unzip.destroy(); }
  const manifestFile=checkedPath(staging,'manifest.json');
  if(!stat(manifestFile)?.isFile()||fs.statSync(manifestFile).size>2*1024**2)fail('MANIFEST_LIMIT');
  let manifest;try{manifest=JSON.parse(fs.readFileSync(manifestFile,'utf8'));}catch{fail('MANIFEST_JSON');}
  if(manifest.kind!=='dsha-portable-package'||![1,2].includes(manifest.schemaVersion)||!manifest.source||!manifest.options||!Array.isArray(manifest.options.workspaces))fail('MANIFEST_SCHEMA');
  profile(manifest.source.profile);
  for(const row of inventory(staging).rows) {
    const name=row[0];
    if(name.startsWith('home/')&&row[1]==='file'&&!allowedHome(name.slice(5),manifest.source.profile))fail('HOME_POLICY');
    if(name.startsWith('home/profiles/')&&!name.startsWith(`home/profiles/${manifest.source.profile}/`)&&name!==`home/profiles/${manifest.source.profile}`)fail('HOME_PROFILE');
  }
  const home=checkedPath(staging,'home'); if(!stat(home)?.isDirectory())fail('HOME_MISSING');
  return {manifest,home,workspace:stat(path.join(staging,'workspace'))?path.join(staging,'workspace'):null,inventory:inventory(staging)};
}
export function yamlDocument(text, sequence=false) {
  if(text.length>1024**2)fail('YAML_LIMIT');
  const document=YAML.parseDocument(text,{uniqueKeys:true});
  if(document.errors.length||document.warnings.length)fail('YAML_INVALID');
  let alias=false; YAML.visit(document,(_key,n)=>{if(YAML.isAlias(n))alias=true;}); if(alias)fail('YAML_ALIAS');
  if(sequence && document.contents===null)document.contents=document.createNode([]);
  if(sequence?!YAML.isSeq(document.contents):!YAML.isMap(document.contents))fail('YAML_SCHEMA');
  return document;
}
export function sanitizedCredentials(text) {
  const doc=yamlDocument(text), keys=doc.contents.items.map(p=>p.key?.value);
  if(keys.some(k=>!['version','records','refs'].includes(k))||doc.get('version')!==1)fail('CREDENTIAL_SCHEMA');
  const records=doc.get('records',true), refs=doc.get('refs',true);
  if(records!==undefined&&!YAML.isMap(records)||refs!==undefined&&!YAML.isMap(refs))fail('CREDENTIAL_SCHEMA');
  for(const pair of [...(records?.items||[])]) {
    const name=pair.key?.value; if(typeof name!=='string')fail('CREDENTIAL_SCHEMA');
    if(name.startsWith('client-connection/'))records.delete(name);
  }
  for(const pair of [...(refs?.items||[])])if(typeof pair.key?.value!=='string')fail('CREDENTIAL_SCHEMA');else if(pair.key.value.startsWith('client-connection/'))refs.delete(pair.key.value);
  return doc.toString();
}
export function projectKey(cwd) {
  if(cwd===undefined)return '_no-cwd';
  if(!cwd)fail('EMPTY_CWD'); let readable='',separators=false;
  for(let i=0;i<cwd.length;i++){const c=cwd[i],code=cwd.charCodeAt(i);if('/\\:'.includes(c)){if(!separators)readable+='-';separators=true;}else if(c!=='~'&&/^[A-Za-z0-9._-]$/.test(c)){readable+=c;separators=false;}else{readable+='~'+code.toString(16).toUpperCase().padStart(4,'0');separators=false;}}
  return `--${(readable.replace(/^-+/,'')||'root').slice(0,251)}--`;
}
export function sessionSegment(id) {
  if(typeof id!=='string'||!id)fail('SESSION_IDENTITY');
  if(id==='.')return '~002E';if(id==='..')return '~002E~002E';let result='';
  for(let i=0;i<id.length;i++){const c=id[i];result+=c!=='~'&&/^[A-Za-z0-9._-]$/.test(c)?c:'~'+id.charCodeAt(i).toString(16).toUpperCase().padStart(4,'0');}return result;
}
export function sessions(home) {
  const out=[], root=path.join(home,'sessions');if(!stat(root))return out;
  for(const project of fs.readdirSync(root,{withFileTypes:true}).sort((a,b)=>a.name<b.name?-1:1)) {
    if(!project.isDirectory())fail('SESSION_LAYOUT');
    for(const entry of fs.readdirSync(path.join(root,project.name),{withFileTypes:true}).sort((a,b)=>a.name<b.name?-1:1)) {
      if(!entry.isDirectory())fail('SESSION_LAYOUT');
      const dir=path.join(root,project.name,entry.name),logs=fs.readdirSync(dir).filter(n=>/^session\.v\d+\.jsonl(?:\.zstd)?$/.test(n));
      if(!logs.length)fail('SESSION_LOG_MISSING');
      const decoded=[];
      for(const log of logs.sort()) {
      const file=checkedPath(home,`sessions/${project.name}/${entry.name}/${log}`);
      if(!stat(file)?.isFile()||fs.statSync(file).size>MAX_LOG)fail('SESSION_LOG_LIMIT');
      const raw=fs.readFileSync(file),bytes=log.endsWith('.zstd')?zlib.zstdDecompressSync(raw,{maxOutputLength:MAX_LOG}):raw;
      const newline=bytes.indexOf(10);let header;
      try{header=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes.subarray(0,newline<0?bytes.length:newline)));}catch{fail('SESSION_HEADER');}
      if(sessionSegment(header.id)!==entry.name||header.cwd!==undefined&&typeof header.cwd!=='string'||projectKey(header.cwd)!==project.name)fail('SESSION_IDENTITY');
      decoded.push({log,header,bytes,tail:newline<0?Buffer.alloc(0):bytes.subarray(newline)});
      }
      if(new Set(decoded.map(row=>row.header.cwd)).size!==1)fail('SESSION_CWD_AMBIGUOUS');
      out.push({dir,header:decoded[0].header,logs:decoded,id:entry.name});
    }
  }
  return out;
}
export function rewrite(home, finalHome, selectedProfile, mapping) {
  const logs=sessions(home), destinations=new Set(), temp=path.join(home,`sessions-converted-${crypto.randomUUID()}`);
  fs.mkdirSync(temp,{mode:0o700});
  for(const item of logs) {
    const cwd=mapping.get(item.header.cwd);if(!cwd)fail('CWD_MAPPING_REQUIRED');
    const key=projectKey(cwd), relative=`${key}/${item.id}`;if(destinations.has(relative))fail('SESSION_COLLISION');destinations.add(relative);
    const dst=checkedPath(temp,relative,true); copyTree(item.dir,dst);
    for(const log of item.logs) {
    const name=log.log.replace(/\.zstd$/,'');
    const bytes=Buffer.concat([Buffer.from(JSON.stringify({...log.header,cwd}),'utf8'),log.tail]);
    fs.writeFileSync(path.join(dst,name+'.part'),bytes,{flag:'wx',mode:0o600});
    if(stat(path.join(dst,name)))fs.unlinkSync(path.join(dst,name));fs.renameSync(path.join(dst,name+'.part'),path.join(dst,name));
    if(log.log!==name)fs.unlinkSync(path.join(dst,log.log));
    }
  }
  const original=path.join(home,'sessions'); if(stat(original))fs.renameSync(original,path.join(path.dirname(home),path.basename(home)+`.original-sessions-${crypto.randomUUID()}`));
  fs.renameSync(temp,original);
  // 原始候选日志不进活动根；原包及候选旁的转换前备份保留到提交回执，永不删除用户源。
  const patch=checkedPath(home,`profiles/${profile(selectedProfile)}/cordis.patch.yml`,true);
  const doc=yamlDocument(stat(patch)?fs.readFileSync(patch,'utf8'):'',true);
  const config={root:path.join(finalHome,'sessions'),compression:'none'};
  if(doc.contents.items.some(row=>!YAML.isMap(row)))fail('OVERLAY_ENTRY');
  const existing=doc.contents.items.find(row=>row.get('id')==='session-persistence-jsonl');
  if(existing){existing.set('name','@deepseek-ai/dsh-session-persistence-jsonl');const original=existing.get('config');existing.set('config',{...(original||{}),...config});}
  else doc.contents.add(doc.createNode({id:'session-persistence-jsonl',name:'@deepseek-ai/dsh-session-persistence-jsonl',config}));
  fs.writeFileSync(patch,doc.toString(),{mode:0o600});yamlDocument(fs.readFileSync(patch,'utf8'),true);
}
export function atomicJson(file,value) {
  const tmp=file+'.tmp-'+crypto.randomUUID();fs.writeFileSync(tmp,JSON.stringify(value,null,2)+'\n',{flag:'wx',mode:0o600});
  const fd=fs.openSync(tmp,'r+');try{fs.fsyncSync(fd);}finally{fs.closeSync(fd);}fs.renameSync(tmp,file);
}
/** 所有候选先完成并读取内容核验；逐根持久化意图，失败恢复已改名原件，冲突原件不删除。 */
export function commit(candidates,journal,fault=()=>{}) {
  const rows=candidates.map(({target,candidate})=>({target,candidate,backup:target+'.before-restore-'+crypto.randomUUID(),before:inventory(target).digest,after:inventory(candidate).digest,stage:'prepared'}));
  const record={kind:'DSHA_PC_RESTORE_V2',rows,state:'prepared'};atomicJson(journal,record);
  try {
    for(const row of rows) {
      absoluteTarget(row.target); if(inventory(row.target).digest!==row.before||inventory(row.candidate).digest!==row.after)fail('COMMIT_CHANGED');
      row.stage='moving-original';atomicJson(journal,record);
      if(stat(row.target))fs.renameSync(row.target,row.backup);
      row.stage='original-retained';atomicJson(journal,record);fault(row);
      fs.renameSync(row.candidate,row.target);row.stage='published';atomicJson(journal,record);
    }
    record.state='committed';atomicJson(journal,record);return record;
  } catch(error) {
    for(const row of [...rows].reverse()) {
      if(row.stage==='published'&&inventory(row.target).digest===row.after&&!stat(row.candidate))fs.renameSync(row.target,row.candidate);
      if(stat(row.backup)&&!stat(row.target)&&inventory(row.backup).digest===row.before)fs.renameSync(row.backup,row.target);
    }
    record.state='failed-retained';atomicJson(journal,record);throw error;
  }
}

/** 显式恢复本工具生成的本机回执；后来修改过的活动数据、候选或备份均不回滚。 */
export function recover(journal) {
  journal=path.resolve(journal);absoluteTarget(path.dirname(journal));
  if(path.basename(path.dirname(journal))!=='.dsha-portable-operations'||!/^[-a-f0-9]{36}\.json$/.test(path.basename(journal)))fail('JOURNAL_PATH');
  const file=stat(journal);if(!file?.isFile()||file.isSymbolicLink()||file.size>1024**2)fail('JOURNAL_TYPE');
  let record;try{record=JSON.parse(fs.readFileSync(journal,'utf8'));}catch{fail('JOURNAL_JSON');}
  if(record.kind!=='DSHA_PC_RESTORE_V2'||!Array.isArray(record.rows)||!record.rows.length||record.rows.length>64)fail('JOURNAL_SCHEMA');
  for(const row of record.rows) {
    absoluteTarget(row.target);
    if(path.dirname(row.candidate)!==path.dirname(row.target)||!/^\.dsha-portable-candidate-[-a-f0-9]{36}$/.test(path.basename(row.candidate))||
      !row.backup.startsWith(row.target+'.before-restore-')||!/^[-a-f0-9]{36}$/.test(row.backup.slice((row.target+'.before-restore-').length))||
      !/^[a-f0-9]{64}$/.test(row.before)||!/^[a-f0-9]{64}$/.test(row.after))fail('JOURNAL_SCHEMA');
  }
  if(record.state==='committed'||record.state==='recovered')return record;
  for(const row of [...record.rows].reverse()) {
    const current=inventory(row.target).digest;
    if(current===row.before&&!stat(row.backup))continue;
    if(stat(row.backup)&&inventory(row.backup).digest!==row.before)fail('RECOVERY_ORIGINAL_CHANGED');
    if(stat(row.target)) {
      if(current!==row.after||stat(row.candidate))fail('RECOVERY_TARGET_CHANGED');
      fs.renameSync(row.target,row.candidate);
    }
    if(stat(row.backup))fs.renameSync(row.backup,row.target);
    else if(inventory(row.target).digest!==row.before)fail('RECOVERY_ORIGINAL_MISSING');
    row.stage='recovered';atomicJson(journal,record);
  }
  record.state='recovered';atomicJson(journal,record);return record;
}
