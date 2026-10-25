#!/usr/bin/env python3
"""Create the CI transport configuration without persisting the cache credential."""
import json
import os
from pathlib import Path
import sys


def main():
    name = 'BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN'
    if not os.environ.get(name):
        raise SystemExit(f'Missing GitHub Actions secret {name}')
    # A fresh namespace requires cold compilation even when this commit is rerun.
    run = os.environ['GITHUB_RUN_ID']
    attempt = os.environ['GITHUB_RUN_ATTEMPT']
    if not run.isdigit() or not attempt.isdigit():
        raise SystemExit('Invalid CI run identity')
    path = Path(sys.argv[1])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(dict(schemaVersion=1, provider='gradle-http',
        url='https://cache.eu-central-a.buildfetch.com/b1CXAN/gradle/',
        username='token-auth', tokenEnvironment=name, trustedWriters=True,
        instance=f'kvik-ci-{run}-{attempt}', mode='read'), indent=2) + '\n')


if __name__ == '__main__':
    main()
