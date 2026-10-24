#!/usr/bin/env python3
"""Normalize the explicitly supported container /usr before strict tool pinning."""
import argparse
import os
from pathlib import Path
import platform
import shutil
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from bootstrap.identity import canonical, digest, inventory


EXTERNAL_LINKS = {
    'lib/python3.12/sitecustomize.py': 'python3.12/sitecustomize.py',
    'lib/ssl/cert.pem': 'ssl/certs/ca-certificates.crt',
    'lib/ssl/certs': 'ssl/certs',
    'lib/ssl/openssl.cnf': 'ssl/openssl.cnf',
    'lib/ssl/private': 'ssl/private',
    'share/locale/locale.alias': 'locale.alias',
}


def normalize(root, etc):
    """Internal path parameterization permits small filesystem tests; CLI is /usr-only."""
    root, etc = Path(root).resolve(strict=True), Path(etc).resolve(strict=True)
    copies, dangling = [], []
    # Preflight every expected alias and every dangling link before removing anything.
    for relative, target in EXTERNAL_LINKS.items():
        path = root / relative
        if not path.exists() and not path.is_symlink():
            raise ValueError(f'Missing expected system path: {relative}')
        if path.is_symlink():
            expected = etc / target
            if path.resolve(strict=True) != expected.resolve(strict=True):
                raise ValueError(f'Unexpected system link target: {relative}')
            if relative == 'lib/ssl/private' and (not expected.is_dir() or any(expected.iterdir())):
                raise ValueError('Refusing to copy a nonempty SSL private directory')
            copies.append((path, expected))
        elif relative == 'lib/ssl/private' and (not path.is_dir() or any(path.iterdir())):
            raise ValueError('SSL private directory must remain empty')
    for path in sorted(root.rglob('*')):
        if not path.is_symlink():
            continue
        try:
            resolved = path.resolve(strict=True)
        except (OSError, RuntimeError):
            relative = path.relative_to(root)
            if len(relative.parts) < 3 or relative.parts[:2] not in (('share', 'doc'), ('share', 'man'), ('share', 'locale')):
                raise ValueError(f'Unexpected dangling system link: {relative}') from None
            dangling.append(path)
        else:
            if not resolved.is_relative_to(root) and path.relative_to(root).as_posix() not in EXTERNAL_LINKS:
                raise ValueError(f'Unexpected external system link: {path.relative_to(root)}')
    for path, target in copies:
        with tempfile.TemporaryDirectory(prefix='.kvik-system-', dir=path.parent) as staging:
            staged = Path(staging) / 'content'
            if target.is_dir():
                shutil.copytree(target, staged, symlinks=False)
            elif target.is_file():
                shutil.copy2(target, staged)
            else:
                raise ValueError(f'Unsupported external system object: {path}')
            path.unlink()
            os.rename(staged, path)
    for path in dangling:
        path.unlink()
    # No exclusions or relaxed symlink policy: this is the same inventory used by pin_tools.
    identity = digest(canonical(inventory(root, allow_internal_symlinks=True)))
    return len(copies), len(dangling), identity


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--system-root', type=Path, required=True)
    args = parser.parse_args()
    if (platform.system() != 'Linux' or not Path('/.dockerenv').is_file() or
            args.system_root != Path('/usr') or args.system_root.is_symlink()):
        parser.error('Provisioning supports only --system-root /usr inside a Linux build container')
    copied, removed, identity = normalize(args.system_root, Path('/etc'))
    print(f'Normalized {copied} external links; removed {removed} dangling documentation/locale links')
    print(canonical({'system': identity}).decode())


if __name__ == '__main__':
    main()
