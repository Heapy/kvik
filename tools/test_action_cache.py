#!/usr/bin/env python3
"""Build and run the action-cache integration probes as Kotlin/Native with a real filesystem CAS."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    output = ROOT / '.kvik/tests/action-cache'; output.mkdir(parents=True, exist_ok=True)
    sources = [p for base in ('core/src', 'cache/src', 'integration/action-cache/src', 'tests/action-cache')
               for p in sorted((ROOT/base).rglob('*.kt'))]
    sources += [ROOT/'execution/src/kvik/execution/Scheduler.kt']
    executable = output/'action-cache.kexe'
    subprocess.run([str(args.konan_home.resolve()/'bin/konanc'), '-target', 'macos_arm64',
        '-Xoverride-konan-properties=airplaneMode=true', '-entry', 'kvik.actioncache.tests.main', '-o', str(executable), *map(str, sources)],
        env=dict(os.environ, JAVA_HOME=str(args.java_home.resolve()), KONAN_USE_INTERNAL_SERVER='0'), check=True)
    with tempfile.TemporaryDirectory(prefix='kvik-action-cache-') as root:
        subprocess.run([str(executable), str(Path(root).resolve())], check=True)


if __name__ == '__main__': main()
