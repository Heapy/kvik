#!/usr/bin/env python3
"""Focused source-store gates; --public also verifies real HTTPS/Git/KLIB inputs.

No runtime/compiler invocation occurs here. Full composed-runtime acceptance is
separate and must consume these same production provisioning/snapshot APIs.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, inventory, read_json
from bootstrap import source_graph as graph
from bootstrap.source_archives import extract_selected
from bootstrap.source_files import entire_inventory
from bootstrap.source_git import acquire_git
from bootstrap.source_http import download
from bootstrap.source_process import run_owned
from bootstrap.source_store import VerifiedNativeDistribution, provision_graph, snapshot_graph

REVISION = '7fd1a60b01f91b314f59955a4e4d4e80d8edf11d'
ARCHIVE_URL = 'https://codeload.github.com/octocat/Hello-World/tar.gz/' + REVISION
ARCHIVE_DIGEST = {'hash': '39a4b97b9d108782fa7466b07160a2c9227a7da07725ad4110c41be6014e4160', 'sizeBytes': 261}
DEFAULT_NATIVE = Path('/Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20')


def public_gate(case, native_root):
    output = case / 'public-git'; temporary = case / 'git-transport'
    source = {'id': 'public-git', 'contentPaths': ['README'],
              'pin': {'kind': 'git', 'origin': 'https://github.com/octocat/Hello-World.git', 'revision': REVISION}}
    receipt = acquire_git(source, output, temporary, Path('/usr/bin/git'), run_owned)
    if (output / 'README').read_bytes() != b'Hello World!\n':
        raise ValueError('Public pinned Git source differs from expected README bytes')
    git_tree = digest(entire_inventory(output))
    (case / 'public-git-receipt.json').write_bytes(canonical(receipt) + b'\n')
    archive = case / 'public.tar.gz'
    download(ARCHIVE_URL, ARCHIVE_DIGEST, archive)
    archive_source = {'contentPaths': ['README'], 'pin': {'format': 'tar.gz', 'rootPrefix': 'Hello-World-' + REVISION}}
    extract_selected(archive, archive_source, case / 'public-archive')
    if digest(entire_inventory(case / 'public-archive')) != git_tree:
        raise ValueError('Public Git/archive selected inventories disagree')

    # Freshly verify the complete installed Native component, not just stdlib.
    # Other installed tools/host validation belong to the later launcher gate.
    tool_lock = read_json(ROOT / 'bootstrap/toolchains.lock.json')
    native_root = native_root.resolve(strict=True)
    before = time.monotonic()
    native_identity = digest(canonical(inventory(native_root, allow_internal_symlinks=True)))
    if native_identity != tool_lock['components']['kotlinNative']:
        raise ValueError('Installed Native distribution differs from trusted tool lock')
    distribution = VerifiedNativeDistribution(native_root, canonical(native_identity))
    native_seconds = time.monotonic() - before

    # Copy the full current Kvik selection; package Python/scripts are not run.
    document = read_json(ROOT / 'docs/decisions/examples/k14-source-graph.lock.json')
    source = document['sources'][0]
    source['pin'] = {'kind': 'local', 'origin': '.', 'tree': None}
    source['nativeLibraries'] = ['serialization-json']
    document['sources'] = [source]; document['plugins'] = document['plugins'][:1]
    project = case / 'full-kvik'; project.mkdir()
    for relative in source['contentPaths']:
        shutil.copytree(ROOT / relative, project / relative)
    lock_path = project / 'source.lock.json'; lock_path.write_bytes(canonical(document) + b'\n')
    observed = graph.observe_graph(graph.parse_source_lock(lock_path.read_bytes()), project, lock_path=lock_path)
    verified = provision_graph(observed, case / 'cache', False, run_owned, lock_path=lock_path,
                               native_distribution=distribution, git_executable=Path('/usr/bin/git'))
    snapshot = snapshot_graph(verified, case / 'snapshot')
    class NoNetwork:
        def open(self, *_args, **_kwargs): raise AssertionError('Offline replay attempted network')
    offline = provision_graph(observed, case / 'cache', True, run_owned, lock_path=lock_path,
                              native_distribution=distribution, git_executable=Path('/usr/bin/git'), opener=NoNetwork())
    if [(a.logical_path, a.digest) for a in verified.artifacts] != [(a.logical_path, a.digest) for a in offline.artifacts]:
        raise ValueError('Offline artifact identity drift')
    graph.revalidate_observation(observed, lock_path.read_bytes(), project, lock_path=lock_path)
    return {'publicGit': {'origin': 'https://github.com/octocat/Hello-World.git', 'revision': REVISION, 'tree': git_tree},
            'publicArchive': {'url': ARCHIVE_URL, 'digest': ARCHIVE_DIGEST, 'tree': git_tree},
            'nativeDistribution': {'identity': native_identity, 'verificationMillis': round(native_seconds * 1000)},
            'sourceGraph': observed.graph_digest, 'sourceFiles': sum(len(s.inventory['files']) for s in verified.sources),
            'nativeKotlinFiles': len(snapshot.native_kotlin_files), 'jvmWorkerFiles': len(snapshot.jvm_worker_files),
            'nativeSupportFiles': len(snapshot.native_support_files),
            'nativeArtifacts': [{'logicalPath': a.logical_path, 'digest': a.digest} for a in verified.artifacts],
            'metadata': [{'logicalPath': a.logical_path, 'digest': a.digest} for a in verified.metadata],
            'provided': [{'id': p.id, 'inventory': p.digest} for p in verified.provided],
            'offlineReplay': True, 'compiledRuntime': False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--public', action='store_true')
    parser.add_argument('--output', type=Path, default=ROOT / '.kvik/tests/source-store')
    parser.add_argument('--native-root', type=Path, default=DEFAULT_NATIVE)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output)).resolve()
    sources = [*sorted((ROOT / 'bootstrap').glob('source_*.py')), ROOT / 'bootstrap/identity.py', ROOT / 'bootstrap/native_libraries.py',
               *sorted((ROOT / 'tests/bootstrap').glob('test_source_*.py')), Path(__file__).resolve()]
    receipt = {'sourceIdentities': [{'path': str(p.relative_to(ROOT)), 'digest': digest(p.read_bytes())} for p in sources],
               'commands': [], 'python': sys.version, 'public': None}
    started = time.monotonic()
    try:
        for test in ('test_source_graph.py', 'test_source_store.py', 'test_source_git.py', 'test_source_store_process.py'):
            command = [sys.executable, '-B', str(ROOT / 'tests/bootstrap' / test)]
            with (case / (test + '.log')).open('wb') as log:
                completed = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=120)
            receipt['commands'].append({'argv': command, 'exitCode': completed.returncode})
            if completed.returncode: raise RuntimeError(f'Failed {test}; see {case}')
        if args.public:
            receipt['public'] = public_gate(case, args.native_root)
        receipt['passed'] = True
    finally:
        receipt['elapsedMillis'] = round((time.monotonic() - started) * 1000)
        (case / 'summary.json').write_bytes(canonical(receipt) + b'\n')
        print(case, flush=True)


if __name__ == '__main__': main()
