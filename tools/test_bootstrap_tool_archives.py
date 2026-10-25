#!/usr/bin/env python3
"""Build the runtime with an archived Native dependency and private compiler state."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, file_digest, inventory, read_json
from bootstrap.pin_tools import tool_roots


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--konan-home', required=True, type=Path)
    parser.add_argument('--konan-data', type=Path, default=Path.home() / '.konan')
    parser.add_argument('--artifact-cache', type=Path, default=ROOT / '.kvik/tool-artifacts')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/bootstrap-tool-archives'
    output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    print('Test directory:', case, flush=True)
    roots = tool_roots(args.java_home, args.konan_home, args.konan_data)
    lock = read_json(ROOT / 'bootstrap/toolchains.lock.json')
    before = digest(canonical(inventory(roots['libffi'], allow_internal_symlinks=True)))
    assert before == lock['components']['libffi']
    archive = case / 'libffi.tar.gz'
    with tarfile.open(archive, 'w:gz') as stream:
        stream.add(roots['libffi'], arcname='libffi')
    archive_digest = file_digest(archive)
    entry = {'url': 'https://fixture.invalid/libffi.tar.gz', 'digest': archive_digest,
             'format': 'tar.gz', 'rootPrefix': 'libffi', 'directory': '.', 'tree': before}
    archive_lock = case / 'tool-archives.json'
    archive_lock.write_bytes(canonical({'schemaVersion': 1, 'host': lock['host'], 'components': {'libffi': entry}}))
    # Seed only the transport blob. The launcher must extract and validate it itself.
    tool_cache = case / 'tools'
    (tool_cache / 'blobs').mkdir(parents=True)
    shutil.copyfile(archive, tool_cache / 'blobs' / f"{archive_digest['hash']}-{archive_digest['sizeBytes']}")
    runtime_cache = case / 'runtime'
    policy = ['--offline', '--tool-archives', str(archive_lock), '--tool-cache', str(tool_cache),
              '--cache-dir', str(runtime_cache), '--artifact-cache', str(args.artifact_cache)]
    environment = dict(os.environ)
    environment.pop('JAVA_HOME', None); environment.pop('KONAN_HOME', None)

    def invoke(name, options, timeout=300):
        result = subprocess.run([sys.executable, ROOT / 'kvik', *options], env=environment,
                                capture_output=True, text=True, timeout=timeout)
        (case / (name + '.log')).write_text(result.stdout + result.stderr)
        assert result.returncode == 0, result.stderr
        return result

    query = json.loads(invoke('query', [*policy, '--print-composition']).stdout)
    assert not (tool_cache / 'trees').exists() and not runtime_cache.exists()
    built = invoke('build', [*policy, '--java-home', str(args.java_home), '--konan-home', str(args.konan_home),
                            '--konan-data', str(args.konan_data), '--print-runtime'], timeout=600)
    executable = Path(built.stdout.strip())
    assert executable.is_file() and 'runtime-built' in built.stderr
    assert not (executable.parent / 'konan-data').exists()
    acquired = next((tool_cache / 'trees').iterdir()) / 'data'
    assert before == digest(canonical(inventory(acquired, allow_internal_symlinks=True)))
    assert before == digest(canonical(inventory(roots['libffi'], allow_internal_symlinks=True)))
    shutil.rmtree(tool_cache)
    warm = invoke('warm', [*policy, '--no-build', '--print-launch'])
    launch = json.loads(warm.stdout)
    assert launch['composition'] == query['composition'] and launch['runtime'] == str(executable)
    assert '--tool-archives' in launch['bootstrapArguments'] and not tool_cache.exists()
    # Smoke-test the compiled CLI through the launcher.
    help_result = invoke('help', [*policy, '--no-build', '--', 'help'])
    assert help_result.stdout.strip()
    print('Runtime build with archived dependency and offline warm launch passed.', flush=True)


if __name__ == '__main__':
    main()
