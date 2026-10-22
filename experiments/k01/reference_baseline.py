#!/usr/bin/env python3
"""Run pinned KTC comparison builds in the exported fixture, preserving logs and exit status."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path, required=True)
    args = parser.parse_args()
    fixture = ROOT / ".kvik/fixtures/kotaml"
    evidence_dir = ROOT / ".kvik/k01/reference"
    evidence_dir.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.java_home), KOTLIN_CLI_JAVA_HOME=str(args.java_home), KOTLIN_CLI_NO_WELCOME_BANNER="1")
    records = []
    for target in ["jvm", "macosArm64"]:
        for mode in ["cold", "warm"]:
            command = ["./kotlin", "test", "--platform", target, "-m", "kotaml", "-m", "kotaml-json"]
            log = evidence_dir / f"{target}-{mode}.log"
            start = time.monotonic()
            with log.open("w") as output:
                result = subprocess.run(command, cwd=fixture, env=env, stdout=output, stderr=subprocess.STDOUT)
            records.append({"target": target, "mode": mode, "command": command, "exitCode": result.returncode,
                            "seconds": round(time.monotonic() - start, 3), "log": str(log)})
            (evidence_dir / "results.json").write_text(json.dumps(records, indent=2) + "\n")
            print(json.dumps(records[-1]), flush=True)
            if result.returncode:
                print("\n".join(log.read_text().splitlines()[-40:]), flush=True)
                raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
