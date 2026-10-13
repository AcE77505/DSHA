#!/usr/bin/env python3
"""只读证明 web 客户端 peer 的来源；不执行插件，不豁免真实 Node 依赖。"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

SEED_ROOTS = {'react', 'react-dom'}
MAX_SOURCE_BYTES = 16 * 1024 * 1024
WORD = re.compile(r'[A-Za-z_$][A-Za-z0-9_$]*')


def tokens(text):
    """有界、注释/字符串感知的词法证据；未知动态调用不颁发客户端专用证明。"""
    result = []
    i = 0
    while i < len(text):
        if text[i].isspace():
            i += 1; continue
        if text.startswith('//', i):
            end = text.find('\n', i + 2); i = len(text) if end < 0 else end; continue
        if text.startswith('/*', i):
            end = text.find('*/', i + 2)
            if end < 0: raise ValueError('CLIENT_SEED_SOURCE_PARSE')
            i = end + 2; continue
        if text[i] in ('"', "'", '`'):
            quote = text[i]; i += 1; value = ''; dynamic = False
            while i < len(text) and text[i] != quote:
                if text[i] == '\\':
                    dynamic = True; i += 2; continue
                if quote == '`' and text.startswith('${', i): dynamic = True
                value += text[i]; i += 1
            if i >= len(text): raise ValueError('CLIENT_SEED_SOURCE_PARSE')
            result.append(('unknown' if dynamic else 'string', value)); i += 1; continue
        match = WORD.match(text, i)
        if match:
            result.append(('word', match.group(0))); i += len(match.group(0)); continue
        result.append(('symbol', text[i])); i += 1
    return result


def imports(text):
    words = tokens(text); found = set(); uncertain = False
    for i, (kind, value) in enumerate(words):
        if kind != 'word': continue
        following = words[i + 1:i + 5]
        if value in ('eval', 'Function', 'createRequire', '__require') and following and following[0][1] == '(':
            uncertain = True
        if value in ('require', 'requireModule'):
            if len(following) >= 3 and following[0][1] == '(' and following[1][0] == 'string' and following[2][1] == ')':
                found.add(following[1][1])
            elif len(following) >= 4 and following[0][1] == '.' and following[1][1] == 'resolve' and following[2][1] == '(' and following[3][0] == 'string':
                found.add(following[3][1])
            else: uncertain = True
        if value == 'import' and following:
            if following[0][1] == '.': continue
            if following[0][1] == '(':
                if len(following) >= 3 and following[1][0] == 'string' and following[2][1] == ')': found.add(following[1][1])
                else: uncertain = True
            elif following[0][0] == 'string': found.add(following[0][1])
        if value == 'from' and following and following[0][0] == 'string': found.add(following[0][1])
    return found, uncertain


def entry_paths(value):
    if isinstance(value, str): return [value]
    if isinstance(value, dict):
        output = []
        for key, child in value.items():
            if key != 'types': output += entry_paths(child)
        return output
    if value is None: return []
    raise ValueError('CLIENT_SEED_ENTRY_UNKNOWN')


def inside(root, path):
    resolved = path.resolve(strict=True)
    if os.path.commonpath([str(root), str(resolved)]) != str(root): raise ValueError('CLIENT_SEED_ENTRY_ESCAPE')
    return resolved


def local_entry(root, base, spec):
    raw = base / spec
    for path in (raw, Path(str(raw) + '.js'), Path(str(raw) + '.mjs'), Path(str(raw) + '.cjs'), raw / 'index.js'):
        if path.is_file(): return inside(root, path)
    raise ValueError('CLIENT_SEED_ENTRY_MISSING')


def closure(root, starts, global_dirs, resolve_module, follow_packages):
    queue = [(root, local_entry(root, root, value)) for value in starts]
    seen = set(); used = set(); uncertain = False; total = 0
    while queue:
        package_root, file = queue.pop()
        if file in seen: continue
        seen.add(file)
        if len(seen) > 2000: raise ValueError('CLIENT_SEED_SOURCE_LIMIT')
        if file.suffix == '.json': continue
        if file.suffix not in ('.js', '.cjs', '.mjs'): raise ValueError('CLIENT_SEED_SOURCE_UNKNOWN')
        data = file.read_bytes(); total += len(data)
        if total > MAX_SOURCE_BYTES: raise ValueError('CLIENT_SEED_SOURCE_LIMIT')
        names, unknown = imports(data.decode('utf8')); uncertain |= unknown
        for name in names:
            if name.startswith('.'):
                queue.append((package_root, local_entry(package_root, file.parent, name)))
            elif name.startswith('node:'): continue
            else:
                bare = '/'.join(name.split('/')[:2]) if name.startswith('@') else name.split('/')[0]
                used.add(bare)
                if not follow_packages or bare in SEED_ROOTS: continue
                dependency = resolve_module(file.parent, bare, global_dirs)
                if dependency is None: continue # 普通缺包由实际 Node 图报告。
                dependency = dependency.resolve(strict=True)
                pkg = json.loads((dependency / 'package.json').read_text(encoding='utf8'))
                paths = entry_paths(pkg.get('exports', {}).get('.') if isinstance(pkg.get('exports'), dict) else pkg.get('exports')) or entry_paths(pkg.get('main')) or ['index.js']
                for value in paths: queue.append((dependency, local_entry(dependency, dependency, value)))
    return used, uncertain, seen


def proof(global_dirs):
    record_file = Path(__file__).with_name('client-seed-proof.json')
    record = json.loads(record_file.read_text(encoding='utf8'))
    if record.get('schema') != 1 or record.get('dshVersion') != '0.2.0-rc.2' or set(record.get('seeds', {})) != {'react', 'react/jsx-runtime', 'react-dom', 'react-dom/client'}:
        raise ValueError('CLIENT_SEED_PROOF_INVALID')
    # current() 的第二项是实际 dsh 安装锚点，不能借用户 HOME 中的同名旧前端证明。
    for base in (global_dirs[1:2] if len(global_dirs) > 1 else global_dirs):
        frontend = base / record['frontendPackage']
        manifest = frontend / 'package.json'
        if not manifest.is_file(): continue
        if json.loads(manifest.read_text(encoding='utf8')).get('version') != record['dshVersion']: continue
        bundle = inside(frontend.resolve(strict=True), frontend / record['bundle'])
        if bundle.stat().st_size > MAX_SOURCE_BYTES: raise ValueError('CLIENT_SEED_PROOF_LIMIT')
        data = bundle.read_bytes()
        if hashlib.sha256(data).hexdigest() == record['bundleSha256']:
            return record, hashlib.sha256(record_file.read_bytes()).hexdigest()
    raise ValueError('CLIENT_SEED_PROOF_UNAVAILABLE')


def client_peers(root, pkg, global_dirs, resolve_module):
    """仅 root 自己声明的 peer 可获客户端证明；任何未知服务端闭包保持普通审计。"""
    candidate = set(pkg.get('peerDependencies') or {}) & SEED_ROOTS
    if not candidate or (pkg.get('dsh') or {}).get('client', {}).get('platform') != 'web': return {}
    try:
        root = Path(root).resolve(strict=True)
        exports = pkg.get('exports') or {}
        if not isinstance(exports, dict): return {}
        clients = entry_paths(exports.get('./client'))
        if not clients: return {}
        server = entry_paths(pkg.get('main')) + entry_paths((pkg.get('cordis') or {}).get('entry'))
        for key, value in exports.items():
            if key != './client' and not key.endswith(('.json', '.yml', '.yaml')): server += entry_paths(value)
        if not server: server = ['index.js']
        attested = attested_peers(root, pkg)
        client_used, client_unknown, client_files = closure(root, clients, global_dirs, resolve_module, False)
        try:
            server_used, server_unknown, server_files = closure(root, server, global_dirs, resolve_module, True)
        except (OSError, ValueError, TypeError):
            if not attested: return {}
            server_used, server_unknown, server_files = set(), True, set()
        if client_files & server_files: return {}
        record, record_sha = proof(global_dirs)
        result = {}
        for name in sorted(candidate & client_used - server_used):
            if server_unknown and name not in attested: continue
            if name in (pkg.get('dependencies') or {}) or name in (pkg.get('optionalDependencies') or {}): continue
            request = {'version': record['seeds'][name], 'range': pkg['peerDependencies'][name]}
            completed = subprocess.run(['node', str(Path(__file__).with_name('plugin-semver.cjs'))], input=json.dumps(request),
                                       encoding='utf8', capture_output=True, timeout=10)
            if completed.returncode == 0 and completed.stdout.strip() == 'true':
                result[name] = {'providedBy': 'web-client-seed', 'seedVersion': record['seeds'][name],
                                'proofSha256': record_sha, 'clientEntries': sorted(clients),
                                'sourceProofSha256': hashlib.sha256(Path(__file__).with_name('client-peer-attestations.json').read_bytes()).hexdigest() if name in attested else '',
                                'serverUsage': 'exact-source-attestation' if server_unknown else 'checked-static-no-reference'}
        return result
    except (OSError, ValueError, TypeError, AttributeError, subprocess.SubprocessError):
        return {}


def attested_peers(root, pkg):
    """只匹配已核查版本的完整代码集合；版本、任意代码字节或额外文件变化均失效。"""
    path = Path(__file__).with_name('client-peer-attestations.json')
    value = json.loads(path.read_text(encoding='utf8'))
    if value.get('schema') != 1: return set()
    for row in value.get('plugins', []):
        if (row.get('name'), row.get('version')) != (pkg.get('name'), pkg.get('version')): continue
        actual = {}; total = 0
        for base, directories, files in os.walk(root, followlinks=False):
            directories[:] = [name for name in directories if name != 'node_modules']
            if any((Path(base) / name).is_symlink() for name in directories): return set()
            for name in files:
                file = Path(base) / name
                if name != 'package.json' and file.suffix not in ('.js', '.mjs', '.cjs', '.yml', '.yaml'): continue
                if file.is_symlink(): return set()
                total += file.stat().st_size
                if total > MAX_SOURCE_BYTES or len(actual) > 2000: return set()
                actual[str(file.relative_to(root)).replace(os.sep, '/')] = hashlib.sha256(file.read_bytes()).hexdigest()
        return set(row.get('clientPeers', [])) if actual == row.get('files') else set()
    return set()


def required_missing(graph, root):
    """保留实际所有节点摘要；只客户端 peer 的闲置 Node 副本不决定服务端闭包完整性。"""
    pending = [str(root)]; seen = set(); missing = []
    while pending:
        at = pending.pop()
        if at in seen: continue
        seen.add(at)
        for name, edge in graph[at]['dependencies'].items():
            if edge.get('providedBy') == 'web-client-seed' or edge.get('optional') or edge.get('hostService'): continue
            if edge.get('missing'): missing.append(name)
            elif edge.get('target') in graph: pending.append(edge['target'])
    return sorted(set(missing))
