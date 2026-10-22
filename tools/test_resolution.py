#!/usr/bin/env python3
"""Build native libcurl/libxml2 adapters and execute resolver fixtures and failure cases."""
import argparse
import os
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--online-smoke', action='store_true')
    parser.add_argument('--ktc-cache', type=Path, help='Verify every selected binary against an existing KTC .m2.cache')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/resolution'
    output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()), KVIK_RESOLUTION_ROOT=str(ROOT), KVIK_RESOLUTION_ONLINE=str(args.online_smoke))
    sdk = subprocess.check_output(['xcrun', '--show-sdk-path'], text=True).strip()
    subprocess.run([str(args.konan_home / 'bin/cinterop'), '-def', str(ROOT / 'resolution/native/resolution.def'), '-compiler-option', '-I' + str(ROOT / 'resolution/native'), '-compiler-option', '-I' + sdk + '/usr/include/libxml2', '-o', str(output / 'resolution')], env=env, check=True)
    sources = sorted((ROOT / 'core/src').rglob('*.kt')) + sorted((ROOT / 'resolution/src').rglob('*.kt')) + sorted((ROOT / 'tests/resolution').glob('*.kt'))
    subprocess.run([str(args.konan_home / 'bin/konanc'), '-target', 'macos_arm64', '-generate-test-runner', '-library', str(output / 'resolution.klib'), '-o', str(output / 'tests.kexe'), *map(str, sources)], env=env, check=True)
    subprocess.run([str(output / 'tests.kexe')], env=env, check=True)
    if args.ktc_cache:
        selections = json.loads((output / 'selections.json').read_text())
        artifacts = {a['url']: a for s in selections for a in s['artifacts']}
        for url, artifact in artifacts.items():
            cached = args.ktc_cache / url.split('/maven2/', 1)[1]
            data = cached.read_bytes()
            assert {'hash': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)} == artifact['digest'], cached
        print(f'All {len(artifacts)} selected artifacts match KTC cache bytes')


if __name__ == '__main__':
    main()
