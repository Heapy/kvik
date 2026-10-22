#!/usr/bin/env python3
"""Native lock/CAS integration tests; repository metadata and KTC binaries are read-only fixtures."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--ktc-cache', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/locking'
    output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()), KVIK_LOCKING_ROOT=str(ROOT),
               KVIK_LOCKING_WORK=str(work), KVIK_KTC_CACHE=str(args.ktc_cache.resolve()))
    sdk = subprocess.check_output(['xcrun', '--show-sdk-path'], text=True).strip()
    subprocess.run([str(args.konan_home / 'bin/cinterop'), '-def', str(ROOT / 'resolution/native/resolution.def'),
                    '-compiler-option', '-I' + str(ROOT / 'resolution/native'), '-compiler-option', '-I' + sdk + '/usr/include/libxml2',
                    '-target', 'macos_arm64', '-o', str(output / 'resolution')], env=env, check=True)
    sources = [str(p) for directory in ['core/src', 'cache/src', 'resolution/src', 'integration/resolution/src', 'tests/locking']
               for p in sorted((ROOT / directory).rglob('*.kt'))]
    subprocess.run([str(args.konan_home / 'bin/konanc'), '-target', 'macos_arm64', '-generate-test-runner',
                    '-library', str(output / 'resolution.klib'), '-o', str(output / 'tests.kexe'), *sources], env=env, check=True)
    subprocess.run([str(output / 'tests.kexe')], env=env, check=True)
    schema = json.loads((ROOT / 'schemas/v1/contracts.json').read_text())
    schema['$ref'] = '#/$defs/dependencyLock'
    jsonschema.Draft202012Validator(schema).validate(json.loads((work / 'production-lock.json').read_text()))
    jsonschema.Draft202012Validator(schema).validate(json.loads((work / 'scoped-lock.json').read_text()))
    print(f'PASS persisted production and scoped locks match dependencyLock v2 schema: {work}')


if __name__ == '__main__':
    main()
