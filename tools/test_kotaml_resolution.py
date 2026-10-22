#!/usr/bin/env python3
"""Native resolution probe using exact retained public-CLI arguments; no fixture edits or compiler execution."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--resolve-command', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT/'.kvik/tests/kotaml-resolution'
    output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    sdk = subprocess.check_output(['xcrun', '--show-sdk-path'], text=True).strip()
    subprocess.run([args.konan_home/'bin/cinterop', '-def', ROOT/'resolution/native/resolution.def',
        '-compiler-option', '-I'+str(ROOT/'resolution/native'), '-compiler-option', '-I'+sdk+'/usr/include/libxml2',
        '-target', 'macos_arm64', '-o', output/'resolution'], env=env, check=True)
    sources = [path for directory in ('core/src', 'cache/src', 'resolution/src', 'integration/resolution/src')
               for path in sorted((ROOT/directory).rglob('*.kt'))]
    sources.append(ROOT/'tests/resolution/probes/KotamlResolutionProbe.kt')
    subprocess.run([args.konan_home/'bin/konanc', '-target', 'macos_arm64', '-entry', 'kvik.resolution.probe.main', '-library', output/'resolution.klib',
        '-o', output/'probe.kexe', *sources], env=env, check=True)
    print('Native resolver evidence:', work, flush=True)
    with (work/'stdout.log').open('w') as stdout, (work/'stderr.log').open('w') as stderr:
        result = subprocess.run([output/'probe.kexe', args.resolve_command.resolve(), work], stdout=stdout, stderr=stderr, env=env, timeout=1200)
    assert result.returncode == 0, (work, (work/'result.json').read_text())
    lock = json.loads((work/'lock.json').read_text())
    reference = json.loads((ROOT/'compiler/jvm/kotaml-artifacts.lock.json').read_text())
    artifacts = {entry['filename']: entry for entry in reference['artifacts']}
    comparisons = []
    for module, prefix in [('io_heapy_kotaml_kotaml', ''), ('io_heapy_kotaml_kotaml_json', 'json-')]:
        for compilation in ('main', 'test'):
            for usage, kind in [('java-api', 'compileClasspath'), ('java-runtime', 'runtimeClasspath')]:
                domain = dict(module=module, target='jvm', compilation=compilation, usage=usage, purpose='dependencies')
                actual = {artifact['coordinate']: artifact['digest'] for selection in lock['selections'] if selection['domain'] == domain for artifact in selection['artifacts']}
                expected = {artifacts[name]['coordinate']: dict(hash=artifacts[name]['sha256'], sizeBytes=artifacts[name]['size'])
                            for name in reference['classpaths'][prefix+compilation][kind]}
                comparisons.append(dict(domain=domain, matches=actual == expected, missing=sorted(expected.keys()-actual.keys()), extra=sorted(actual.keys()-expected.keys())))
                (work/'comparisons.json').write_text(json.dumps(comparisons, indent=2))
                assert actual == expected, comparisons[-1]
    print('PASS eight exact per-domain kotaml artifact sets/digests and CAS replay:', work, flush=True)


if __name__ == '__main__':
    main()
