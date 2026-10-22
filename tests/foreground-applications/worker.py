#!/usr/bin/env python3
"""Controlled activation-protocol fixture; deliberately not a compiler or real CLI verifier."""
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

root = Path.cwd()
runtime = Path(sys.argv[1])
args = sys.argv[2:]

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()

def digest(data):
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))

def publish(path, value):
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_bytes(canonical(value))
    os.replace(temporary, path)

if args[0] == 'snapshot-query':
    failure=root/'snapshot-failure'
    if failure.exists() and failure.read_text()=='fail':
        failure.write_text('hold');sys.exit(125)
    while failure.exists():time.sleep(.005)
    source = (root/'source').read_bytes()
    print(canonical(dict(schemaVersion=1, fingerprint=digest(source))).decode())
    sys.exit(0)
if args[0] == 'test':
    with (root/'tests').open('a') as stream:
        stream.write(str(os.getpid()) + '\n')
    while (root/'hold-test').exists():
        time.sleep(.01)
    sys.exit(0)
assert args[0] in ('stage-application', 'stage-prepared')
gate = Path(args[2]); token = json.loads(args[4])
mode = (root/'mode').read_text().strip()
while (root/'hold-prepare').exists():
    time.sleep(.01)
if mode == 'fail' or (root/'reject-composition').exists() and (root/'reject-composition').read_text() == runtime.parent.name:
    sys.exit(1)
source = digest((root/'source').read_bytes())
if args[0] == 'stage-application':
    runs = Path(args[args.index('--runs')+1]); preparation = runs/'prepared'; preparation.mkdir(parents=True)
    descriptor = digest(canonical(dict(source=source, composition=runtime.parent.name)))
    (preparation/'source.json').write_bytes(canonical(source))
else:
    preparation = Path(args[args.index('--run')+1]); descriptor = json.loads(args[args.index('--descriptor')+1])
    if json.loads((preparation/'source.json').read_bytes()) != source:
        sys.exit(3)
ready = dict(schemaVersion=1, token=token, preparationDirectory=str(preparation), descriptor=descriptor,
             verification=source, composition=dict(hash=runtime.parent.name, sizeBytes=42), module='app', target='jvm')
publish(gate/'ready.json', ready)
if mode == 'die-ready':
    sys.exit(2)
while not (gate/'activate.json').exists():
    time.sleep(.005)
authorization = json.loads((gate/'activate.json').read_bytes())
assert authorization == dict(schemaVersion=1, token=token, ready=digest(canonical(ready)))
if digest((root/'source').read_bytes()) != source:
    sys.exit(3)
while (root/'hold-start').exists():
    time.sleep(.005)
execution = preparation/'application-runs'/'execution'; execution.mkdir(parents=True)
# Descendants deliberately ignore TERM: guardian cleanup must finish before replacement activates.
child = subprocess.Popen([sys.executable, '-c', 'import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(120)'])
(root/f'child-{os.getpid()}.pid').write_text(str(child.pid))
with (root/'effects').open('a') as stream:
    stream.write(json.dumps(dict(pid=os.getpid(), child=child.pid, composition=runtime.parent.name, token=token, source=source)) + '\n')
publish(gate/'started.json', dict(schemaVersion=1, token=token, ready=digest(canonical(ready)), executionDirectory=str(execution)))
while True:
    if (root/'crash').exists():
        (root/'crash').unlink(); sys.exit(7)
    time.sleep(.01)
