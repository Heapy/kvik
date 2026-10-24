#!/usr/bin/env python3
"""Pure Native project graph tests; source fixture is read-only and no consumer process executes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, file_digest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--application-actions', type=Path, default=ROOT / 'integration/application/src/kvik/application/ApplicationActions.kt')
    parser.add_argument('--no-build', action='store_true', help='Require the exact retained compiler/source/environment/binary receipt')
    parser.add_argument('--include-contracts', action='store_true', help='Run the core contracts in the same Native test binary')
    parser.add_argument('--include-evidence', action='store_true', help='Run verification evidence tests in the same Native test binary')
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/project-graph'
    output.mkdir(parents=True, exist_ok=True)
    files = []
    for logical, name in [('fixture/common/Common.kt', 'Common.kt'), ('fixture/jvm/Platform.kt', 'Platform.kt')]:
        data = (ROOT / 'tests/jvm/fixture' / name).read_bytes()
        files.append({'path': logical, 'digest': {'hash': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)}, 'executable': False})
    project = {'schemaVersion': 1, 'name': 'fixture', 'repositories': [{'id': 'central', 'url': 'https://repo.maven.apache.org/maven2'}], 'modules': [
        {'id': 'library', 'sourceSets': [
            {'name': 'commonMain', 'directories': ['fixture/common'], 'resources': [], 'dependsOn': [], 'dependencies': []},
            {'name': 'jvmMain', 'directories': ['fixture/jvm'], 'resources': [], 'dependsOn': ['commonMain'], 'dependencies': []}],
         'targets': [{'name': 'jvm', 'main': 'jvmMain', 'test': None, 'compilerOptions': []}], 'compilerPlugins': []}]}
    fixture = output / 'fixture.json'
    fixture.write_text(json.dumps({'project': project, 'snapshot': {'schemaVersion': 1, 'files': files}}))
    env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', JAVA_HOME=str(args.java_home.resolve()), HOME=str(Path.home()),
        KONAN_DATA_DIR=str(Path.home() / '.konan'), DEVELOPER_DIR='/Applications/Xcode.app/Contents/Developer',
        TMPDIR=tempfile.gettempdir(), LANG='C', LC_ALL='C', TZ='UTC', KONAN_USE_COMPILER_SERVER='false', KVIK_GRAPH_FIXTURE=str(fixture))
    sources = [p for directory in ['core/src', 'integration/resolution/src', 'integration/project-graph/src', 'integration/library/src', 'tests/project-graph', 'tests/library-package']
               for p in sorted((ROOT / directory).rglob('*.kt'))]
    sources += [ROOT / 'compiler/documentation/src/kvik/documentation/DocumentationActions.kt', ROOT / 'compiler/abi/src/kvik/abi/AbiActions.kt', ROOT / 'resolution/src/kvik/resolution/MavenResolver.kt', ROOT / 'resolution/src/kvik/resolution/PomModel.kt', ROOT / 'resolution/src/kvik/resolution/ResolutionGraph.kt', ROOT / 'compiler/jvm/native/src/kvik/compiler/jvm/JvmActions.kt', ROOT / 'compiler/jvm/native/src/kvik/compiler/jvm/JvmScriptActions.kt',
                ROOT / 'compiler/native/src/kvik/compiler/native/NativeActions.kt', ROOT / 'compiler/js/src/kvik/compiler/js/JsActions.kt', ROOT / 'compiler/ksp/src/kvik/compiler/ksp/KspActions.kt']
    sources += [args.application_actions.resolve(), ROOT / 'tests/native/NativeApplicationActionsTest.kt']
    sources += [ROOT / 'tests/locking/CommonPublicationTest.kt']
    if args.include_contracts:
        sources += sorted((ROOT / 'tests/contracts').rglob('*.kt'))
    if args.include_evidence:
        sources += [p for directory in ['integration/run-evidence/src', 'tests/run-evidence']
                    for p in sorted((ROOT / directory).rglob('*.kt'))]
    executable = output / 'tests.kexe'
    command = [str(args.konan_home.resolve() / 'bin/konanc'), '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true', '-generate-test-runner', '-o', str(executable), *map(str, sources)]
    launchers = [args.konan_home.resolve() / 'bin/konanc', args.konan_home.resolve() / 'bin/run_konan',
        args.konan_home.resolve() / 'tools/env_blacklist', args.java_home.resolve() / 'bin/java', *sorted((args.konan_home.resolve() / 'konan/lib').glob('*.jar'))]
    def inputs():
        return {'command': command, 'environment': env, 'sources': {str(p): file_digest(p) for p in sources},
                'launchers': {str(p): file_digest(p) for p in launchers}, 'fixture': file_digest(fixture),
                'harness': {str(p): file_digest(p) for p in [Path(__file__), ROOT / 'bootstrap/identity.py']}}
    stamp = inputs()
    receipt = output / 'build.json'
    if args.no_build:
        old = json.loads(receipt.read_bytes())
        assert old['stamp'] == stamp and old['binary'] == file_digest(executable), 'Stale graph test build receipt'
    else:
        (output / 'compile-command.json').write_bytes(canonical(stamp))
        with (output / 'compile.log').open('wb') as log:
            subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
        assert stamp == inputs(), 'Graph sources/compiler inputs changed during compilation'
        receipt.write_bytes(canonical({'stamp': stamp, 'binary': file_digest(executable), 'compileLog': file_digest(output / 'compile.log')}))
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    for name in ['tests.kexe', 'build.json', 'compile.log', 'fixture.json']:
        shutil.copy2(output / name, case / name)
    with (case / 'tests.log').open('wb') as log:
        subprocess.run([str(case / 'tests.kexe')], env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=60)
    assert stamp == inputs(), 'Graph sources/compiler inputs changed during verification'
    (case / 'summary.json').write_bytes(canonical({'passed': True, 'receipt': file_digest(case / 'build.json'),
        'binary': file_digest(case / 'tests.kexe'), 'testLog': file_digest(case / 'tests.log'), 'harness': file_digest(Path(__file__)),
        'environment': env, 'command': [str(case / 'tests.kexe')]}))
    print(json.dumps({'passed': True, 'case': str(case)}), flush=True)


if __name__ == '__main__':
    main()
