#!/usr/bin/env python3
"""真实 rc2 seed 与固定 npm 问题包的只读依赖判定；从不执行插件入口。"""
import base64
import hashlib
import importlib.util
import io
import json
import os
import re
from types import SimpleNamespace
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
import urllib.request
from unittest.mock import patch
from test_runtime_fixture import runtime

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module


class ClientSeedsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runtime = runtime('raw')
        cls.global_dirs = [cls.runtime / 'node_modules']
        cls.seed = load('tested_client_seeds', ASSETS / 'plugin-client-seeds.py')
        cls.graph = load('tested_seed_graph', ASSETS / 'backup-plugin-graph.py')
        cls.dependencies = load('tested_current_dependencies', ASSETS / 'plugin-dependencies.py')
        cls.proof = cls.seed.proof(cls.global_dirs)
        cls.packages = {}
        lock = json.loads((ROOT / 'tools/test-client-seed-plugins.lock.json').read_text())
        for row in lock['plugins']:
            cache = ROOT / 'app/build/client-seed-fixtures' / (row['name'] + '-' + row['version'] + '.tgz')
            if not cache.is_file():
                cache.parent.mkdir(parents=True, exist_ok=True)
                with urllib.request.urlopen(row['url'], timeout=40) as response: data = response.read(8 * 1024 * 1024 + 1)
                cache.write_bytes(data)
            data = cache.read_bytes()
            if len(data) > 8 * 1024 * 1024 or 'sha512-' + base64.b64encode(hashlib.sha512(data).digest()).decode() != row['integrity']:
                raise AssertionError('FIXED_PLUGIN_INTEGRITY')
            cls.packages[row['name']] = data

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='client-seed-test-'); self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def unpack(self, name):
        root = self.root / name; root.mkdir()
        with tarfile.open(fileobj=io.BytesIO(self.packages[name]), mode='r:gz') as archive:
            for item in archive:
                parts = Path(item.name).parts
                if not item.isfile() or not parts or parts[0] != 'package' or '..' in parts: continue
                target = root.joinpath(*parts[1:]); target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.extractfile(item).read())
        return root

    def peers(self, root):
        pkg = json.loads((root / 'package.json').read_text(encoding='utf8'))
        return self.seed.client_peers(root, pkg, self.global_dirs, self.graph.resolve_module)

    def synthetic(self, server='export const apply = () => {};', dependency=False, version='^18.2.0'):
        root = self.root / 'synthetic'; root.mkdir()
        pkg = {'name':'synthetic-client', 'version':'1.0.0', 'main':'index.js', 'exports':{'.':'./index.js','./client':'./client.js'},
               'dsh':{'client':{'platform':'web'}}, 'peerDependencies':{'react':version}}
        if dependency: pkg['dependencies'] = {'react':version}
        (root / 'package.json').write_text(json.dumps(pkg), encoding='utf8')
        (root / 'index.js').write_text(server, encoding='utf8')
        (root / 'client.js').write_text('window.__ModuleLoader__.load({id:"synthetic-client",factory:(require)=>require("react")});', encoding='utf8')
        return root

    def test_actual_frontend_proof_is_byte_bound(self):
        self.assertEqual('18.3.1', self.proof[0]['seeds']['react'])
        with self.assertRaisesRegex(ValueError, 'UNAVAILABLE'): self.seed.proof([])

    def test_signed_archive_frontend_matches_seed_proof_when_prepared(self):
        archive = ROOT/'app/src/main/assets/dsh-runtime.bin'
        if not archive.is_file(): self.skipTest('signed runtime archive not prepared')
        record = self.proof[0]
        name = 'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/' + record['frontendPackage'] + '/' + record['bundle']
        with tarfile.open(archive, 'r:gz') as source: data = source.extractfile(name).read()
        self.assertEqual(record['bundleSha256'], hashlib.sha256(data).hexdigest())

    def test_private_old_frontend_does_not_replace_install_anchor(self):
        with self.assertRaisesRegex(ValueError, 'UNAVAILABLE'):
            self.seed.proof([self.global_dirs[0], self.root/'missing-install-anchor'])

    def test_both_reported_fixed_packages_have_client_only_seed_peers(self):
        self.assertEqual({'react'}, set(self.peers(self.unpack('dsh-session-health'))))
        self.assertEqual({'react','react-dom'}, set(self.peers(self.unpack('dsh-codex-subscription'))))

    def test_modified_attested_dynamic_source_cannot_reuse_proof(self):
        root = self.unpack('dsh-codex-subscription')
        with (root / 'lib/index.js').open('a', encoding='utf8') as out: out.write('\nimport("react");\n')
        self.assertEqual({}, self.peers(root))

    def test_extra_js_invalidates_attestation(self):
        root = self.unpack('dsh-codex-subscription'); (root / 'extra.js').write_text('export {};')
        self.assertEqual(set(), self.seed.attested_peers(root, json.loads((root/'package.json').read_text())))
        self.assertEqual({}, self.peers(root))

    def test_static_client_only_peer_is_provided(self):
        self.assertEqual({'react'}, set(self.peers(self.synthetic())))

    def test_real_node_dependency_is_never_provided(self):
        self.assertEqual({}, self.peers(self.synthetic(dependency=True)))

    def test_server_direct_and_indirect_imports_are_not_client_peers(self):
        root = self.synthetic('import React from "react"; export {};'); self.assertEqual({}, self.peers(root))
        (root/'index.js').write_text('import "./server.js";'); (root/'server.js').write_text('import "react";')
        self.assertEqual({}, self.peers(root))

    def test_unknown_dynamic_server_is_not_provided(self):
        self.assertEqual({}, self.peers(self.synthetic('import(moduleName);')))

    def test_require_resolve_and_aliased_require_are_not_client_only(self):
        root = self.synthetic('require.resolve("react");')
        self.assertEqual({}, self.peers(root))
        (root/'index.js').write_text('const lookup=require; lookup(moduleName);')
        self.assertEqual({}, self.peers(root))

    def test_seed_version_mismatch_is_not_provided(self):
        self.assertEqual({}, self.peers(self.synthetic(version='^19.0.0')))

    def test_comments_and_unrelated_strings_do_not_fake_server_imports(self):
        self.assertEqual({'react'}, set(self.peers(self.synthetic('// import React from "react"\nexport const text="react";'))))

    def test_required_missing_preserves_node_graph_and_ignores_only_client_edge(self):
        graph={'root':{'dependencies':{'react':{'target':'react','providedBy':'web-client-seed'},'server':{'target':'server'}}},
               'react':{'dependencies':{'loose-envify':{'missing':True}}}, 'server':{'dependencies':{'real-node-missing':{'missing':True}}}}
        self.assertEqual(['real-node-missing'], self.seed.required_missing(graph,'root'))
        graph['root']['dependencies']['react'].pop('providedBy')
        self.assertEqual(['loose-envify','real-node-missing'], self.seed.required_missing(graph,'root'))

    def current(self, root, with_react=False):
        pool = self.root/'pool'; frontend = pool/self.proof[0]['frontendPackage']; frontend.mkdir(parents=True, exist_ok=True)
        actual = self.runtime/'node_modules'/self.proof[0]['frontendPackage']
        shutil.copyfile(actual/'package.json',frontend/'package.json')
        bundle = frontend/self.proof[0]['bundle']; bundle.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(actual/self.proof[0]['bundle'],bundle)
        if with_react:
            react=pool/'react';react.mkdir(exist_ok=True)
            (react/'package.json').write_text(json.dumps({'name':'react','version':'18.3.1','dependencies':{'loose-envify':'1.0.0'}}))
            (react/'index.js').write_text('export const value=1;')
        home=self.root/'home';home.mkdir(exist_ok=True)
        manager={'DSH_HOME':'/root/.dsh','local':lambda value:str(pool) if value.endswith('node_modules') else str(home),
                 'builtin':SimpleNamespace(valid_name=lambda name:isinstance(name,str) and bool(re.fullmatch(r'(?:@[a-z0-9._-]+/)?[a-z0-9._-]+',name))),
                 'check_cancel':lambda:None}
        return self.dependencies.Dependencies(manager).current(root)

    def test_actual_current_allows_seed_without_executing_server(self):
        root=self.synthetic("throw Error('AUDIT_MUST_NOT_EXECUTE');")
        actual=self.current(root)
        self.assertEqual([],actual['missing']);self.assertTrue(actual['complete'])
        self.assertEqual('web-client-seed',actual['nodes'][str(root.resolve())]['dependencies']['react']['providedBy'])

    def test_actual_current_keeps_installed_node_bytes_and_required_closure(self):
        root=self.synthetic();actual=self.current(root,True)
        self.assertTrue(any(row['name']=='react' for row in actual['nodes'].values()));self.assertEqual([],actual['missing'])
        (self.root/'pool/react/index.js').write_text('modified actual Node bytes')
        self.assertNotEqual(actual['sha256'],self.current(root)['sha256'])
        pkg=json.loads((root/'package.json').read_text());pkg['dependencies']={'react':'^18.2.0'};(root/'package.json').write_text(json.dumps(pkg))
        self.assertEqual(['loose-envify'],self.current(root)['missing'])
        pkg['peerDependenciesMeta']={'react':{'optional':True}};(root/'package.json').write_text(json.dumps(pkg))
        self.assertEqual(['loose-envify'],self.current(root)['missing'], 'optional peer cannot downgrade a real dependency')

    def test_current_does_not_clear_user_or_safe_mode_marker(self):
        root=self.synthetic();home=self.root/'home';home.mkdir()
        for marker in [b'',b'DSHA_SAFE_MODE\n']:
            file=home/'synthetic-client.disabled';file.write_bytes(marker)
            self.current(root);self.assertEqual(marker,file.read_bytes())


if __name__ == '__main__': unittest.main()
