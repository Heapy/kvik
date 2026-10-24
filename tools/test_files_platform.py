#!/usr/bin/env python3
"""Host C checks for the filesystem interop; no Kotlin compiler or tool installation needed."""
import argparse
import os
import platform
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cc', default=os.environ.get('CC', 'cc'))
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='kvik-files-') as raw:
        directory = Path(raw)
        executable = directory / 'files-test'
        subprocess.run([args.cc, '-std=c11', '-D_GNU_SOURCE', '-D_DARWIN_C_SOURCE',
                        '-Wall', '-Wextra', '-Werror', '-I' + str(ROOT / 'cache/platform'),
                        str(ROOT / 'tests/cache-files/FilesTest.c'), '-o', str(executable),
                        *(['-framework', 'CoreFoundation'] if platform.system() == 'Darwin' else [])], check=True)
        subprocess.run([str(executable), str(directory)], check=True)


if __name__ == '__main__':
    main()
