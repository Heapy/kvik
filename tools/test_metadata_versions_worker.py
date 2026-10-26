#!/usr/bin/env python3
"""Exercise the metadata BTA version boundary through source-built JVM workers."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, file_digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--artifacts', type=Path, required=True)
    args = parser.parse_args()
    bundle = args.runtime.resolve().parent / 'jvm'
    manifest = bundle / 'compiler-manifest.json'
    version = json.loads(manifest.read_text())['version']
    assert version in ('2.4.10', '2.4.20'), version
    directory = ROOT / '.kvik/tests/metadata-versions'
    directory.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix=version + '-', dir=directory)).resolve()
    print('Fixture:', case, flush=True)
    shutil.copytree(args.konan_home / 'klib/common/stdlib', case / 'input/native-stdlib/shared')
    (case / 'NativeToken.kt').write_text('package shared\nclass NativeToken(val worker: kotlin.native.concurrent.Worker)\n')
    request = dict(schemaVersion=1, requestId='native', operation='metadata-compile', compiler=file_digest(manifest),
        sources=['NativeToken.kt'], classpath=['input/native-stdlib/shared'], pluginClasspath=[],
        options=['-module-name', 'fixture:shared', '-language-version', '2.4', '-api-version', '2.4', '-Xmulti-platform'],
        outputDirectory='metadata', metadataTargets=['iosArm64', 'iosSimulatorArm64'])
    cp = os.pathsep.join(map(str, [bundle / 'classes', bundle / f'lib/kotlin-build-tools-api-{version}.jar',
                                   bundle / f'lib/kotlin-stdlib-{version}.jar']))
    def invoke(label, value, expected):
        (case / (label + '.request.json')).write_bytes(canonical(value))
        command = [str(args.java_home / 'bin/java'), '-cp', cp, 'kvik.jvm.CompilerWorkerKt', str(case),
                   label + '.request.json', label + '.result.json', str(args.artifacts.resolve()), str(manifest)]
        (case / (label + '.command.json')).write_bytes(canonical(command))
        with (case / (label + '.log')).open('wb') as log:
            process = subprocess.run(command, cwd=case, stdout=log, stderr=subprocess.STDOUT, timeout=120)
        result = json.loads((case / (label + '.result.json')).read_text())
        assert process.returncode == expected == result['exitCode'], result
        return result
    if version == '2.4.10':
        refused = invoke('unsupported-metadata', request, 1)
        assert any(d['code'] == 'KVIK_UNSUPPORTED_COMPILER' for d in refused['diagnostics']), refused
        assert not (case / 'metadata').exists()
    else:
        result = invoke('native', request, 0)
        assert result['status'] == 'success' and list((case / 'metadata/default/linkdata').rglob('*.knm')), result
        refused = invoke('mixed', dict(request, requestId='mixed', outputDirectory='mixed', metadataTargets=['jvm', 'iosArm64']), 1)
        assert 'Platform KLIB is not common metadata' in json.dumps(refused), refused
        assert not (case / 'mixed').exists()
    (case / 'summary.json').write_bytes(canonical(dict(passed=True, compilerVersion=version,
        runtime=file_digest(args.runtime.resolve()), manifest=file_digest(manifest), harness=file_digest(Path(__file__)))))
    print('PASS metadata compiler boundary', version, case, flush=True)


if __name__ == '__main__':
    main()
