#!/usr/bin/env python3
"""Run focused compact build output contract tests in Kotlin/Native."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, file_digest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--no-build', action='store_true', help='Require a matching source/compiler/environment/binary receipt')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/build-output'
    output.mkdir(parents=True, exist_ok=True)
    sources = [p for directory in ('core/src', 'tests/build-output')
               for p in sorted((ROOT / directory).rglob('*.kt'))]
    sources.append(ROOT / 'cli/src/kvik/cli/BuildOutput.kt')
    environment = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', JAVA_HOME=str(args.java_home.resolve()), HOME=str(Path.home()),
        KONAN_DATA_DIR=str(Path.home() / '.konan'), DEVELOPER_DIR='/Applications/Xcode.app/Contents/Developer',
        TMPDIR=tempfile.gettempdir(), LANG='C', LC_ALL='C', TZ='UTC', KONAN_USE_COMPILER_SERVER='false')
    executable = output / 'unit.kexe'
    command = [str(args.konan_home.resolve() / 'bin/konanc'), '-target', 'macos_arm64' if sys.platform == 'darwin' else 'linux_x64', '-generate-test-runner',
               '-Xoverride-konan-properties=airplaneMode=true', '-o', str(executable), *map(str, sources)]
    launchers = [args.konan_home.resolve() / 'bin/konanc', args.konan_home.resolve() / 'bin/run_konan',
        args.konan_home.resolve() / 'tools/env_blacklist', args.java_home.resolve() / 'bin/java',
        *sorted((args.konan_home.resolve() / 'konan/lib').glob('*.jar'))]
    def inputs():
        return {'command': command, 'environment': environment, 'sources': {str(p): file_digest(p) for p in sources},
                'launchers': {str(p): file_digest(p) for p in launchers}, 'harness': file_digest(Path(__file__))}
    stamp = inputs()
    receipt = output / 'build.json'
    if args.no_build:
        old = json.loads(receipt.read_bytes())
        assert old['stamp'] == stamp and old['binary'] == file_digest(executable), 'Stale evidence test build receipt'
        assert old['compileLog'] == file_digest(output / 'compile.log'), 'Changed compiler log'
    else:
        (output / 'compile-command.json').write_bytes(canonical(stamp))
        with (output / 'compile.log').open('wb') as log:
            subprocess.run(command, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
        assert stamp == inputs(), 'Evidence sources/compiler inputs changed during compilation'
        receipt.write_bytes(canonical({'stamp': stamp, 'binary': file_digest(executable), 'compileLog': file_digest(output / 'compile.log')}))
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    for name in ('unit.kexe', 'build.json', 'compile.log'):
        shutil.copy2(output / name, case / name)
    with (case / 'tests.log').open('wb') as log:
        subprocess.run([str(case / 'unit.kexe')], env=environment, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=60)
    assert stamp == inputs(), 'Evidence sources/compiler inputs changed during verification'
    (case / 'summary.json').write_bytes(canonical({'passed': True, 'receipt': file_digest(case / 'build.json'),
        'binary': file_digest(case / 'unit.kexe'), 'testLog': file_digest(case / 'tests.log'),
        'environment': environment, 'command': [str(case / 'unit.kexe')]}))
    print(json.dumps({'passed': True, 'case': str(case)}), flush=True)


if __name__ == '__main__':
    main()
