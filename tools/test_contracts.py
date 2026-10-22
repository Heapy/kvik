#!/usr/bin/env python3
"""Compile and execute platform-independent contracts with retained Native receipts."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT))
from bootstrap.launcher import run_owned


def digest(path):
    data = path.read_bytes()
    return {'hash': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    parent = ROOT / '.kvik/tests/contracts'; parent.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='case-', dir=parent)).resolve()
    sources = sorted((ROOT / 'core/src').rglob('*.kt')) + sorted((ROOT / 'tests/contracts').rglob('*.kt'))
    konan = args.konan_home.resolve(strict=True)
    environment = {'PATH': '/usr/bin:/bin:/usr/sbin:/sbin', 'HOME': str(Path.home()),
        'JAVA_HOME': str(args.java_home.resolve(strict=True)), 'KONAN_DATA_DIR': str(Path.home() / '.konan'),
        'DEVELOPER_DIR': '/Applications/Xcode.app/Contents/Developer', 'TMPDIR': tempfile.gettempdir(),
        'LANG': 'C', 'LC_ALL': 'C', 'TZ': 'UTC', 'KONAN_USE_COMPILER_SERVER': 'false', 'KONAN_USE_INTERNAL_SERVER': '0'}
    executable = output / 'contracts.kexe'
    command = list(map(str, [konan / 'bin/konanc', '-target', 'macos_arm64',
        '-Xoverride-konan-properties=airplaneMode=true', '-generate-test-runner', '-o', executable, *sources]))
    tools = [args.java_home.resolve() / 'bin/java', konan / 'bin/konanc', konan / 'bin/run_konan', konan / 'tools/env_blacklist', *sorted((konan / 'konan/lib').glob('*.jar'))]
    support = [Path(__file__).resolve(), *sorted((ROOT / 'bootstrap').glob('*.py'))]
    receipt = {'passed': False, 'harnessInputs': {str(p): digest(p) for p in support}, 'sources': {str(p): digest(p) for p in sources},
        'compilerInputs': {str(p): digest(p) for p in tools}, 'command': command, 'environment': environment}
    def retain(): (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    retain(); print('Evidence:', output, flush=True)
    started = time.monotonic()
    try:
        with (output / 'compile.log').open('wb') as log:
            run_owned(command, environment, log, timeout=240)
        receipt['compileExitCode'] = 0
        receipt['compileLog'] = digest(output / 'compile.log')
        with (output / 'tests.log').open('wb') as log:
            run_owned([str(executable)], environment, log, timeout=60)
        receipt['testExitCode'] = 0
        receipt['testLog'] = digest(output / 'tests.log')
        assert receipt['sources'] == {str(p): digest(p) for p in sources}, 'Sources changed during verification'
        assert receipt['compilerInputs'] == {str(p): digest(p) for p in tools}, 'Compiler launch inputs changed during verification'
        assert receipt['harnessInputs'] == {str(p): digest(p) for p in support}, 'Harness changed during verification'
        receipt.update(passed=True, binary=digest(executable))
        print((output / 'tests.log').read_text(), end='')
    except Exception as error:
        receipt['failure'] = str(error)
        raise
    finally:
        for name, field in (('compile', 'compileLog'), ('tests', 'testLog')):
            if (output / f'{name}.log').exists(): receipt[field] = digest(output / f'{name}.log')
        receipt['milliseconds'] = int((time.monotonic() - started) * 1000)
        retain()


if __name__ == '__main__': main()
