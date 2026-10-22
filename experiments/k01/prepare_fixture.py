#!/usr/bin/env python3
"""Export a pinned fixture without touching its original checkout or production sources."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("name", choices=["kotaml", "grogu-time"])
    parser.add_argument("--repository", type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads((ROOT / "experiments/k01/fixtures.lock.json").read_text())
    fixture = next(f for f in lock["fixtures"] if f["name"] == args.name)
    destination = ROOT / ".kvik/fixtures" / args.name
    if destination.exists():
        raise RuntimeError(f"Fixture destination already exists: {destination}")
    archive = subprocess.check_output(["git", "-C", str(args.repository), "archive", "--format=tar", fixture["commit"]])
    destination.mkdir(parents=True)
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        source.extractall(destination, filter="data")
    evidence = dict(fixture, archiveSha256=hashlib.sha256(archive).hexdigest(), destination=str(destination))
    (destination.parent / f"{args.name}-provenance.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(destination)


if __name__ == "__main__":
    main()
