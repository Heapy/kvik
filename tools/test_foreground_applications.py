#!/usr/bin/env python3
"""Native foreground application controller with real owned protocol-fixture processes.

This is not the JVM/Native public compiler/application acceptance gate. All worker
readiness verification is synthetic and identified as such in the retained summary.
"""
import argparse
import atexit
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

import jsonschema
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]

def sha(path):
    data = path.read_bytes()
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))

def write(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True)+'\n')

def table():
    return [tuple(map(int, line.split())) for line in subprocess.run(
        ['/bin/ps', '-axo', 'pid=,ppid=,pgid='], check=True, capture_output=True, text=True).stdout.splitlines()]

def descendants(pid):
    rows = table(); found = {pid}
    while True:
        more = found | {child for child, parent, _ in rows if parent in found}
        if more == found:
            return found, {group for child, _, group in rows if child in found and group != os.getpgrp()}
        found = more

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--no-build', action='store_true')
    parser.add_argument('--units-only', action='store_true')
    args = parser.parse_args()
    table()  # Fail before creating owned jobs when the sandbox denies process inventories.
    output = ROOT/'.kvik/tests/foreground-applications'; output.mkdir(parents=True, exist_ok=True)
    temporary = output/'tmp'; temporary.mkdir(exist_ok=True)
    native = ROOT/'execution/platform'
    sources = [path for folder in ('core/src', 'execution/src', 'integration/foreground/src') for path in sorted((ROOT/folder).rglob('*.kt'))]
    sources += [ROOT/'cache/src/kvik/cache/NativeFiles.kt']
    units = [ROOT/'tests/foreground/ProtocolTest.kt', ROOT/'tests/foreground-recomposition/ResumeTest.kt',
             ROOT/'tests/foreground-keys/KeysProtocolTest.kt', *sorted((ROOT/'tests/foreground-applications').glob('*Test.kt'))]
    probe = ROOT/'tests/foreground-applications/SessionProbe.kt'
    support = [args.konan_home/'bin'/name for name in ('konanc', 'cinterop', 'run_konan')]
    support += [args.konan_home/'tools/env_blacklist', *sorted((args.konan_home/'konan/lib').glob('*.jar')),
                args.java_home/'release', args.java_home/'bin/java']
    files = [*sources, *units, probe, native/'guardian.c', native/'process.c', native/'process.h', native/'process.def',
             ROOT/'tests/foreground-applications/worker.py', ROOT/'tools/test_foreground_applications.py', *support]
    env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(Path.home()), JAVA_HOME=str(args.java_home.resolve()),
               KONAN_DATA_DIR=str(Path.home()/'.konan'), TMPDIR=str(temporary)+'/', LANG='C', LC_ALL='C', TZ='UTC')
    stamp = dict(files={str(path): sha(path) for path in files}, environment=env)
    command_log = []
    def run(argv):
        command = list(map(str, argv)); command_log.append(dict(arguments=command, environment=env))
        subprocess.run(command, env=env, check=True)
    if args.no_build:
        old = json.loads((output/'receipt.json').read_text())
        assert old['inputs'] == stamp, 'Native probe source/tool/environment changed'
        for name, identity in old['binaries'].items():
            assert sha(output/name) == identity
    else:
        flags = ['/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang',
                 '-isysroot', '/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk',
                 '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror']
        run([*flags, native/'guardian.c', '-o', output/'guardian'])
        run([*flags, '-c', native/'process.c', '-o', output/'process.o'])
        run(['/usr/bin/ar', 'rcs', output/'libkvik-process.a', output/'process.o'])
        run([args.konan_home/'bin/cinterop', '-def', native/'process.def', '-compiler-option', '-I'+str(native),
             '-libraryPath', output, '-target', 'macos_arm64', '-o', output/'process'])
        base = [args.konan_home/'bin/konanc', '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true',
                '-library', output/'process.klib', *sources]
        run([*base, *units, '-generate-test-runner', '-o', output/'unit.kexe'])
        run([*base, probe, '-entry', 'kvik.foreground.applicationprobe.main', '-o', output/'kvik.kexe'])
        assert stamp['files'] == {str(path): sha(path) for path in files}, 'Inputs changed while compiling'
        write(output/'receipt.json', dict(inputs=stamp, commands=command_log,
              binaries={name: sha(output/name) for name in ('guardian', 'unit.kexe', 'kvik.kexe', 'process.klib')}))
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output))
    result = subprocess.run([str(output/'unit.kexe')], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (case/'unit.log').write_bytes(result.stdout); assert result.returncode == 0, result.stdout.decode()
    if args.units_only:
        write(case/'summary.json', dict(passed=True, units=True, receipt=sha(output/'receipt.json'))); print(case); return
    schema = json.loads((ROOT/'schemas/v1/session.json').read_text())
    registry = Registry()
    for name in ('session', 'contracts', 'bootstrap-launch'):
        resource = json.loads((ROOT/f'schemas/v1/{name}.json').read_text())
        registry = registry.with_resource(resource.get('$id', f'https://kvik.dev/schemas/v1/{name}.json'), Resource.from_contents(resource))
    validate = jsonschema.Draft202012Validator({**schema, '$ref':'#/$defs/event'}, registry=registry).validate
    validate_resume = jsonschema.Draft202012Validator({**schema, '$ref':'#/$defs/resume'}, registry=registry).validate
    sessions = []; results = []
    def cleanup():
        for session in sessions:
            if session.process.poll() is None:
                session.process.kill(); session.process.wait(timeout=10)
    atexit.register(cleanup)

    class Session:
        def __init__(self, name, recompose=False):
            self.root = case/name; self.root.mkdir(); self.events = []; self.messages = queue.Queue(); self.sequence = {}; self.tracked=set(); self.groups=set()
            (self.root/'source').write_text('first'); (self.root/'mode').write_text('normal')
            worker = self.root/'worker.py'; worker.write_text('#!'+sys.executable+'\n'+(ROOT/'tests/foreground-applications/worker.py').read_text()); worker.chmod(0o755)
            self.cache = self.root/'cache'; self.key='a'*64
            for key in ('a'*64, 'b'*64):
                directory = self.cache/'executables'/key; directory.mkdir(parents=True)
                shutil.copy2(output/'kvik.kexe', directory/'kvik.kexe')
            self.directory = self.root/'.kvik/sessions/initial'; self.directory.mkdir(parents=True)
            command = [str(self.cache/'executables'/self.key/'kvik.kexe'), str(output/'guardian'), str(self.directory), str(self.root), 'jsonl']
            if recompose:
                launcher = self.root/'kvik'
                launcher.write_text("import json,pathlib,sys\np=pathlib.Path(__file__).parent\nkey=(p/'desired').read_text()\nif (p/'bootstrap-failure').exists():sys.exit(1)\nv=json.loads((p/'launch.json').read_text());v['composition']={'hash':key,'sizeBytes':42};v['runtime']=str(p/'cache/executables'/key/'kvik.kexe')\nprint(json.dumps({'schemaVersion':1,'composition':v['composition']} if '--print-composition' in sys.argv else v))\n")
                (self.root/'desired').write_text(self.key)
                launch=dict(schemaVersion=1,processOwnershipVersion=1,sourceRoot=str(self.root),python=sys.executable,launcher=str(launcher),
                            bootstrapArguments=['--konan-data',str(self.root/'konan'),'--cache-dir',str(self.cache),'--artifact-cache',str(self.root/'artifacts'),'--offline'],
                            composition=dict(hash=self.key,sizeBytes=42),runtime=command[0])
                write(self.root/'launch.json',launch); command.append(str(self.root/'launch.json'))
            write(self.root/'command.json', command)
            self.log=(self.root/'stream.jsonl').open('w'); self.error=(self.root/'stderr.log').open('w')
            self.process=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.error,text=True,bufsize=1,env=env)
            sessions.append(self)
            def reader():
                for line in self.process.stdout:
                    self.log.write(line);self.log.flush(); self.messages.put(line)
                self.messages.put(None)
            threading.Thread(target=reader,daemon=True).start()
            self.wait('session-started')
        def next(self,timeout=15):
            line=self.messages.get(timeout=timeout); assert line is not None,(self.root,self.process.poll(),self.events[-5:])
            value=json.loads(line);validate(value)
            sid=value['sessionId'];self.sequence[sid]=self.sequence.get(sid,0)+1;assert value['sequence']==self.sequence[sid]
            self.events.append(value);return value
        def wait(self,kind,predicate=lambda e:True,timeout=15):
            deadline=time.monotonic()+timeout
            while True:
                value=self.next(max(.01,deadline-time.monotonic()))
                if value['type']==kind and predicate(value['payload']):return value
        def control(self,command):
            self.process.stdin.write(json.dumps(dict(schemaVersion=1,requestId=str(len(self.events)),command=command))+'\n');self.process.stdin.flush()
        def effects(self):
            return [json.loads(line) for line in (self.root/'effects').read_text().splitlines()] if (self.root/'effects').exists() else []
        def capture(self):
            pids,groups=descendants(self.process.pid);self.tracked |= pids;self.groups |= groups
        def drain_status(self):
            self.control('status');return self.wait('control',lambda p:p['command']=='status')['payload']
        def finish(self,how='q'):
            self.capture();start=time.monotonic()
            if how=='q':self.control('q')
            elif how=='kill':self.process.kill()
            elif how=='int':self.process.send_signal(signal.SIGINT)
            elif how=='term':self.process.terminate()
            elif how=='eof':self.process.stdin.close()
            self.process.wait(timeout=10)
            expected=-9 if how=='kill' else 130 if how in ('int','term') else 0
            assert self.process.returncode==expected,(self.root,self.process.returncode)
            deadline=time.monotonic()+5
            while True:
                rows=table();remaining=[row for row in rows if row[0] in self.tracked or row[2] in self.groups]
                if not remaining:break
                assert time.monotonic()<deadline,remaining;time.sleep(.02)
            self.log.close();self.error.close()
            results.append(dict(name=self.root.name,exitCode=self.process.returncode,tracked=sorted(self.tracked),groups=sorted(self.groups),cleanupSeconds=time.monotonic()-start))

    s=Session('reload-and-tests');s.wait('application-started');first=s.effects()[0];s.capture()
    (s.root/'hold-prepare').touch();s.control('r');s.wait('application-preparing');s.control('t');s.control('r')
    status=s.drain_status();assert status['application']['active'] is not None
    assert set(status['pendingIntents'])=={'test','application'}
    # r supersedes the pending candidate without touching the old process; tests remain queued.
    (s.root/'hold-prepare').unlink();s.wait('operation-finished');s.wait('application-started')
    assert len(s.effects())==2;assert first['pid'] not in {row[0] for row in table()};assert first['child'] not in {row[0] for row in table()}
    (s.root/'mode').write_text('fail');s.control('r');s.wait('application-finished',lambda p:not p['started'])
    assert s.drain_status()['application']['active'] is not None;assert len(s.effects())==2
    (s.root/'mode').write_text('normal');(s.root/'source').write_text('second');s.wait('application-started');assert len(s.effects())==3
    (s.root/'crash').touch();s.wait('application-finished',lambda p:p['exitCode']==7)
    assert s.drain_status()['application']['active'] is None;s.control('r');s.wait('application-started');s.finish()

    s=Session('transient-observation');s.wait('application-started');first=s.effects()[0];s.capture()
    (s.root/'hold-start').touch();s.control('r');s.wait('application-activating')
    (s.root/'snapshot-failure').write_text('fail');s.wait('inputs-observed',lambda p:not p['available'])
    s.wait('application-finished',lambda p:not p['started'] and p['cancelled'])
    status=s.drain_status();assert status['application']['active'] is None and status['application']['candidate'] is None
    assert status['pendingIntents']==['application'] and len(s.effects())==1
    assert first['pid'] not in {row[0] for row in table()} and first['child'] not in {row[0] for row in table()}
    retained=[json.loads(p.read_text()) for p in (s.root/'.kvik/sessions').glob('*/*/observation-result.json')]
    assert any(value['exitCode']==125 and not value['cancelled'] and not value['timedOut'] for value in retained)
    (s.root/'hold-start').unlink();(s.root/'snapshot-failure').unlink()
    s.wait('application-started');assert len(s.effects())==2
    assert s.drain_status()['pendingIntents']==[];s.finish()

    for how in ('q','eof','int','term','kill'):
        s=Session('ownership-'+how);s.wait('application-started');(s.root/'hold-prepare').touch();s.control('r');s.wait('application-preparing');s.finish(how)
    one=Session('owner-one');two=Session('owner-two');one.wait('application-started');two.wait('application-started');one.finish('kill')
    assert two.process.poll() is None and two.drain_status()['application']['active'] is not None;two.finish()

    s=Session('source-handoff',recompose=True);s.wait('application-started');initial_pid=s.process.pid;s.capture()
    (s.root/'bootstrap-failure').touch();(s.root/'desired').write_text('b'*64);s.wait('composition-observed',lambda p:p['state']=='unavailable')
    assert s.drain_status()['application']['active'] is not None
    (s.root/'reject-composition').write_text('b'*64);(s.root/'bootstrap-failure').unlink();s.control('r')
    s.wait('application-finished',lambda p:not p['started'] and p['composition']['hash']=='b'*64)
    assert s.drain_status()['application']['active'] is not None
    (s.root/'reject-composition').unlink();s.control('t');s.control('r')
    s.wait('session-restarting');s.wait('session-started');s.wait('application-started',lambda p:p['composition']['hash']=='b'*64)
    assert s.process.pid==initial_pid;assert len(s.effects())==2
    s.wait('operation-finished',lambda p:p['operation']=='test')
    for path in (s.root/'.kvik/sessions').glob('*/handoff.json'):validate_resume(json.loads(path.read_text()))
    s.finish()
    write(case/'summary.json',dict(passed=True,scope='Native controller with synthetic protocol workers; no public compiler/application claim',
          receipt=sha(output/'receipt.json'),results=results,unitLog=sha(case/'unit.log')))
    print(case)

if __name__=='__main__':main()
