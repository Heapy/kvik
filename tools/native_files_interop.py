"""Explicit header-only filesystem interop recipe shared by Native test harnesses."""
from pathlib import Path


def files_interop_inputs(root):
    root = Path(root)
    return [root / 'cache/platform/files.h', root / 'cache/platform/files.def',
            root / 'tools/native_files_interop.py']


def files_interop_command(root, konan_home, output, target='macos_arm64'):
    root, konan_home, output = map(Path, (root, konan_home, output))
    return [str(konan_home / 'bin/cinterop'), '-def', str(root / 'cache/platform/files.def'),
            '-compiler-option', '-I' + str(root / 'cache/platform'), '-target', target,
            '-o', str(output / 'files')]


def files_library_args(output):
    return ['-library', str(Path(output) / 'files.klib')]
