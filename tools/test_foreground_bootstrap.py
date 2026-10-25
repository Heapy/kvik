#!/usr/bin/env python3
"""Validate the real Python descriptor producer against Native launch/resume consumers."""
import argparse
import ast
import hashlib
import importlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

import jsonschema

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True


def digest(data):
    return hashlib.sha256(data).hexdigest()


def producer_identity(path):
    content = path.read_bytes()
    source = content.decode('utf-8')
    function = next(node for node in ast.parse(source).body
                    if isinstance(node, ast.FunctionDef) and node.name == 'launch_descriptor')
    return {'path': str(path), 'fileSha256': digest(content),
            'functionSha256': digest(ast.get_source_segment(source, function).encode('utf-8'))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--producer-root', type=Path, default=ROOT,
                        help='Source checkout supplying bootstrap.launcher (default: this checkout).')
    args = parser.parse_args()
    producer_root = args.producer_root.resolve(strict=True)
    producer_file = producer_root / 'bootstrap/launcher.py'
    before = producer_identity(producer_file)
    sys.path.insert(0, str(producer_root))
    launcher = importlib.import_module('bootstrap.launcher')
    assert Path(launcher.__file__).resolve() == producer_file.resolve(), 'Unexpected bootstrap producer import'
    output_root = ROOT / '.kvik/tests/foreground-bootstrap'
    output_root.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='case-', dir=output_root))
    key = {'hash': 'a' * 64, 'sizeBytes': 42}
    policy = argparse.Namespace(java_home=Path('/jdk $(literal)/../selected'), konan_home=Path('/native compiler'),
        konan_data=Path('/native data'), cache_dir=Path('/cache with spaces'), artifact_cache=Path('/artifacts'),
        offline=True, no_build=True)
    runtime = policy.cache_dir / 'executables' / key['hash'] / 'kvik.kexe'
    legacy = launcher.launch_descriptor(policy, key, runtime).encode('utf-8')
    policy.source_lock = Path('/source locks $(literal)/../selected.json')
    policy.source_cache = Path('/source cache $(literal)/../selected')
    graph = launcher.launch_descriptor(policy, key, runtime).encode('utf-8')
    policy.runtime_store = Path('/shared store $(literal)/../selected')
    shared = launcher.launch_descriptor(policy, key, runtime).encode('utf-8')
    policy.bootstrap_command_timeout = 1800
    extended = launcher.launch_descriptor(policy, key, runtime).encode('utf-8')
    policy.tool_archives = Path('/tool locks $(literal)/../archives.json')
    policy.tool_cache = Path('/tool cache')
    archives = launcher.launch_descriptor(policy, key, runtime).encode('utf-8')
    after = producer_identity(producer_file)
    assert before == after, 'Producer changed while importing/generating fixtures; rerun'
    graph_doc = json.loads(graph)
    assert graph_doc['bootstrapArguments'][10:14] == [
        '--source-lock', str(policy.source_lock), '--source-cache', str(policy.source_cache)
    ], 'Selected producer must implement source-lock/source-cache descriptors'
    schema_file = ROOT / 'schemas/v1/bootstrap-launch.json'
    schema = json.loads(schema_file.read_text())
    jsonschema.Draft202012Validator.check_schema(schema)
    validator = jsonschema.Draft202012Validator(schema)
    for name, encoded in [('producer.json', legacy), ('source-graph-producer.json', graph), ('shared-store-producer.json', shared), ('timeout-producer.json', extended), ('tool-archives-producer.json', archives)]:
        validator.validate(json.loads(encoded))
        (output / name).write_bytes(encoded)
    lock_only = {**graph_doc, 'bootstrapArguments': graph_doc['bootstrapArguments'][:12] + graph_doc['bootstrapArguments'][14:]}
    validator.validate(lock_only)
    for invalid in [
        {**graph_doc, 'bootstrapArguments': graph_doc['bootstrapArguments'][:10] + graph_doc['bootstrapArguments'][12:]},
        {**graph_doc, 'bootstrapArguments': graph_doc['bootstrapArguments'] + ['--offline'] * 9},
    ]:
        assert not validator.is_valid(invalid), 'Schema accepted cache-only or oversized arguments'
    generated = output / 'Producer.kt'
    generated.write_text('package kvik.foreground\n' + '\n'.join(
        'val ' + name + ' = byteArrayOf(' + ','.join(str(x if x < 128 else x - 256) for x in encoded) + ')'
        for name, encoded in [('producerDescriptor', legacy), ('sourceGraphProducerDescriptor', graph),
                              ('sharedStoreProducerDescriptor', shared), ('timeoutProducerDescriptor', extended),
                              ('toolArchivesProducerDescriptor', archives)]) + '\n')
    sources = sorted((ROOT / 'core/src').rglob('*.kt')) + [
        ROOT / 'integration/foreground/src/kvik/foreground' / name
        for name in ('BootstrapLaunch.kt', 'Protocol.kt', 'SessionResume.kt', 'SessionCapabilities.kt', 'ApplicationActivation.kt')
    ] + [ROOT / 'tests/foreground-bootstrap/BootstrapLaunchTest.kt',
         ROOT / 'tests/foreground-recomposition/ResumeTest.kt', generated]
    source_hashes = {str(path): digest(path.read_bytes()) for path in sources}
    executable = output / 'bootstrap-context.kexe'
    konan = args.konan_home.resolve(strict=True)
    command = list(map(str, [konan / 'bin/konanc', '-target', 'macos_arm64',
        '-Xoverride-konan-properties=airplaneMode=true', '-generate-test-runner', '-o', executable, *sources]))
    environment = {'PATH': '/usr/bin:/bin:/usr/sbin:/sbin', 'HOME': str(Path.home()),
        'JAVA_HOME': str(args.java_home.resolve(strict=True)), 'KONAN_DATA_DIR': str(Path.home() / '.konan'),
        'DEVELOPER_DIR': '/Applications/Xcode.app/Contents/Developer', 'TMPDIR': tempfile.gettempdir(),
        'LANG': 'en_US.UTF-8', 'LC_ALL': 'en_US.UTF-8', 'TZ': 'UTC', 'KONAN_USE_COMPILER_SERVER': 'false'}
    receipt = {'producer': before, 'schemaSha256': digest(schema_file.read_bytes()),
        'sources': source_hashes, 'command': command, 'environment': environment,
        'compilerInputs': {str(path): digest(path.read_bytes()) for path in
            [konan / 'bin/konanc', konan / 'bin/run_konan', konan / 'tools/env_blacklist',
             *sorted((konan / 'konan/lib').glob('*.jar'))]},
        'fixtureSha256': {'legacy': digest(legacy), 'sourceGraph': digest(graph), 'sharedStore': digest(shared)}}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(f'Evidence: {output}', flush=True)
    started = time.monotonic()
    with (output / 'compile.log').open('wb') as log:
        subprocess.run(command, env=environment, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
    with (output / 'tests.log').open('wb') as log:
        subprocess.run([executable], env=environment, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=30)
    assert source_hashes == {str(path): digest(path.read_bytes()) for path in sources}, 'Native sources changed during test'
    final_producer = producer_identity(producer_file)
    assert final_producer['functionSha256'] == before['functionSha256'], 'Producer function changed during test'
    receipt.update(producerAfter=final_producer, elapsedSeconds=time.monotonic() - started,
                   binarySha256=digest(executable.read_bytes()),
                   testLogSha256=digest((output / 'tests.log').read_bytes()),
                   compileLogSha256=digest((output / 'compile.log').read_bytes()), passed=True)
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print((output / 'tests.log').read_text(), end='')


if __name__ == '__main__':
    main()
