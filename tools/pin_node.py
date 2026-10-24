#!/usr/bin/env python3
"""Print the node object for a Kvik tools.json using an explicitly selected installation."""
import argparse
import json
from pathlib import Path
import stat
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bootstrap.identity import canonical, digest, inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    mode = (root / 'bin/node').lstat().st_mode
    if not stat.S_ISREG(mode) or not mode & 0o111:
        parser.error('The installation must contain a regular executable bin/node')
    print(json.dumps({'root': str(root), 'identity': digest(canonical(inventory(root, True)))}))


if __name__ == '__main__':
    main()
