#!/usr/bin/env python3
"""Check worker argument and published-plugin validation with the actual worker sources."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap import jvm_worker


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--artifact-cache', type=Path, required=True)
    args = parser.parse_args()
    case = Path(tempfile.mkdtemp(prefix='kvik-plugin-worker-arguments-')).resolve()
    print('Fixture:', case, flush=True)
    java = args.java_home.resolve() / 'bin/java'
    cache = args.artifact_cache.resolve()
    lock = jvm_worker.read_lock(ROOT)
    artifacts = jvm_worker.acquire(lock, cache, True)

    def compile_worker(command, environment, log):
        with (case / 'compile.log').open('wb') as output:
            subprocess.run(command, env=environment, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=180)

    jvm_worker.build_bundle(case, ROOT, 'compiler/jvm/src', lock, artifacts, java, dict(os.environ), None, compile_worker,
        source_files=[*sorted((ROOT / 'core/src').rglob('*.kt')), *sorted((ROOT / 'compiler/jvm/src').rglob('*.kt')),
                      ROOT / 'tests/js-workspace-plugin/WorkerArgumentsTest.kt'])
    classpath = os.pathsep.join(map(str, [case / 'jvm/classes', cache / 'kotlin-stdlib-2.4.20.jar']))
    subprocess.run([str(java), '-cp', classpath, 'kvik.jvm.WorkerArgumentsTest'], check=True, timeout=60)


if __name__ == '__main__':
    main()
