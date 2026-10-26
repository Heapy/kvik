#!/usr/bin/env python3
"""Generate/check the trusted module-cut inventory packaged with a thin launcher.

Run after core source/root changes and before distributing kvik + bootstrap only.
This reference enables an optimization; stale bytes never authorize core reuse.
"""
import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, read_json
from bootstrap.native_core_cache import CURATED_INVENTORY, curated_inventory, valid_curated_inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--check', action='store_true', help='Fail if missing/stale; never rewrite source inputs')
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    recipe = read_json(root / 'bootstrap/composition.json')
    value = curated_inventory(root, recipe)
    if not valid_curated_inventory(value, recipe):
        raise ValueError('Invalid curated module cut')
    expected = canonical(value) + b'\n'
    destination = root / 'bootstrap' / CURATED_INVENTORY
    if args.check:
        if not destination.is_file() or destination.is_symlink() or destination.read_bytes() != expected:
            parser.exit(1, 'Native core inventory is stale; run tools/pin_native_core.py after reviewing the module cut\n')
    else:
        destination.write_bytes(expected)


if __name__ == '__main__':
    main()
