#!/usr/bin/env python3
"""Actual Native raw-key session/owned-child lifecycle and same-PID lease continuation.

The worker and bootstrap queries are deliberate bounded fixtures. This proves the
controller/terminal integration, not compiler correctness or actual source compilation.
"""
import argparse
import atexit
import json
import os
from pathlib import Path
import pty
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
BROKER = r'''
import fcntl,json,os,signal,subprocess,sys,termios
os.setsid();terminal=os.open(sys.argv[1],os.O_RDWR|os.O_NOCTTY)
fcntl.ioctl(terminal,termios.TIOCSCTTY,0);signal.signal(signal.SIGTTOU,signal.SIG_IGN)
subprocess.run([sys.argv[2],'inspect'],stdin=terminal,check=True)
gate,release=os.pipe();env=dict(os.environ,KVIK_TEST_GATE=str(gate))
child=subprocess.Popen(json.loads(sys.argv[3]),stdin=terminal,env=env,pass_fds=(gate,),preexec_fn=lambda:os.setpgid(0,0))
os.close(gate);print('broker',child.pid,flush=True)
os.tcsetpgrp(terminal,child.pid);os.write(release,b'!');os.close(release)
for command in sys.stdin:
 if command.strip()=='wait':
  print('exit',child.wait(timeout=15),flush=True)
  subprocess.run([sys.argv[2],'inspect'],stdin=terminal,check=True)
 elif command.strip()=='end':break
if child.poll() is None:child.kill();child.wait(timeout=5)
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--skip-unit-build', action='store_true')
    args = parser.parse_args()
    output = ROOT/'.kvik/tests/foreground-keys'; output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    native = ROOT/'execution/platform'
    def run(command): subprocess.run(list(map(str, command)), env=env, check=True)
    if not args.skip_build:
        flags = ['/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang',
                 '-isysroot', '/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk',
                 '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror']
        run([*flags, native/'guardian.c', '-o', output/'guardian'])
        run([*flags, '-I'+str(native), native/'process.c', ROOT/'tests/foreground-keys/TerminalProbe.c', '-o', output/'probe'])
        run([*flags, '-c', native/'process.c', '-o', output/'process.o'])
        run(['ar', 'rcs', output/'libkvik-process.a', output/'process.o'])
        run([args.konan_home/'bin/cinterop', '-def', native/'process.def', '-compiler-option', '-I'+str(native),
             '-libraryPath', output, '-target', 'macos_arm64', '-o', output/'process'])
        core = sorted((ROOT/'core/src').rglob('*.kt'))
        compiler = [args.konan_home/'bin/konanc', '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true']
        pure = [ROOT/'integration/foreground/src/kvik/foreground'/name for name in
                ('Protocol.kt', 'BootstrapLaunch.kt', 'SessionResume.kt', 'SessionCapabilities.kt')]
        if not args.skip_unit_build:
            run([*compiler, *core, *pure, ROOT/'tests/foreground/ProtocolTest.kt', ROOT/'tests/foreground-recomposition/ResumeTest.kt',
                 ROOT/'tests/foreground-keys/KeysProtocolTest.kt', '-generate-test-runner', '-o', output/'keys-unit.kexe'])
        sources = [path for directory in ('execution/src', 'integration/foreground/src') for path in sorted((ROOT/directory).rglob('*.kt'))]
        run([*compiler, '-library', output/'process.klib', *core, *sources, ROOT/'cache/src/kvik/cache/NativeFiles.kt',
             ROOT/'tests/foreground-keys/SessionProbe.kt', '-entry', 'kvik.foreground.keysprobe.main', '-o', output/'keys-session.kexe'])
    run([output/'keys-unit.kexe'])
    evidence = Path(tempfile.mkdtemp(prefix='native-', dir=output))
    schema = json.loads((ROOT/'schemas/v1/session.json').read_text())
    registry = Registry().with_resource('https://kvik.dev/schemas/v1/bootstrap-launch.json',
                                       Resource.from_contents(json.loads((ROOT/'schemas/v1/bootstrap-launch.json').read_text())))
    validate = jsonschema.Draft202012Validator({**schema, '$ref':'#/$defs/event'}, registry=registry).validate
    validate_resume = jsonschema.Draft202012Validator({**schema, '$ref':'#/$defs/resume'}, registry=registry).validate
    worker = evidence/'worker.py'
    worker.write_text('#!'+sys.executable+'''\nimport hashlib,json,os,pathlib,subprocess,sys,time
p=pathlib.Path.cwd()
if sys.argv[1]=='snapshot-query':
 data=(p/'source').read_bytes();print(json.dumps(dict(schemaVersion=1,fingerprint=dict(hash=hashlib.sha256(data).hexdigest(),sizeBytes=len(data)))))
else:
 (p/'worker.pid').write_text(str(os.getpid()));child=subprocess.Popen(['/bin/sleep','120']);(p/'descendant.pid').write_text(str(child.pid))
 if (p/'mode').read_text()=='slow':time.sleep(120)
 else:time.sleep(.15);child.terminate();child.wait()
'''); worker.chmod(0o755)
    def alive(pid):
        result = subprocess.run(['ps','-p',str(pid),'-o','stat='],capture_output=True,text=True)
        return result.returncode == 0 and not result.stdout.strip().startswith('Z')
    def descendants(pid):
        rows = [tuple(map(int,line.split())) for line in subprocess.run(['ps','-axo','pid=,ppid='],capture_output=True,text=True,check=True).stdout.splitlines()]
        found = {pid}
        while True:
            expanded = found | {child for child,parent in rows if parent in found}
            if expanded == found:return found
            found = expanded
    sessions=[]; results=[]
    class Session:
        def __init__(self,label,mode='slow',descriptor=False):
            self.path=evidence/label; self.path.mkdir(); (self.path/'source').write_text('original'); (self.path/'mode').write_text(mode)
            self.master,self.slave=pty.openpty(); self.messages=queue.Queue(); self.events=[]; self.sequences={}; self.tracked=set()
            argv=[str(output/'keys-session.kexe'),str(output/'guardian'),str(worker),str(self.path)]
            if descriptor:
                self.cache=self.path/'cache'; self.cache.mkdir(); self.launcher=self.path/'kvik'
                self.launcher.write_text('''import json,pathlib,sys,time
p=pathlib.Path(__file__).parent;key=(p/'desired').read_text();digest=dict(hash=key,sizeBytes=42)
if '--print-composition' in sys.argv:print(json.dumps(dict(schemaVersion=1,composition=digest)))
else:
 value=json.loads((p/'launch.json').read_text());value['composition']=digest;value['runtime']=str(p/'cache/executables'/key/'kvik.kexe');print(json.dumps(value))
''')
                for key in ('a'*64,'b'*64):
                    target=self.cache/'executables'/key;target.mkdir(parents=True);shutil.copyfile(output/'keys-session.kexe',target/'kvik.kexe');(target/'kvik.kexe').chmod(0o755)
                self.desired=self.path/'desired';self.desired.write_text('a'*64)
                launch=dict(schemaVersion=1,sourceRoot=str(self.path),python=sys.executable,launcher=str(self.launcher),
                            bootstrapArguments=['--konan-data',str(self.path/'konan'),'--cache-dir',str(self.cache),'--artifact-cache',str(self.path/'artifacts'),'--offline'],
                            composition=dict(hash='a'*64,sizeBytes=42),runtime=str(self.cache/'executables'/('a'*64)/'kvik.kexe'))
                (self.path/'launch.json').write_text(json.dumps(launch));argv.append(str(self.path/'launch.json'))
            (self.path/'command.json').write_text(json.dumps(argv))
            self.log=(self.path/'stream.log').open('w');self.error=(self.path/'stderr.log').open('w')
            self.proc=subprocess.Popen([sys.executable,'-c',BROKER,os.ttyname(self.slave),str(output/'probe'),json.dumps(argv)],
                                       stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.error,text=True,bufsize=1)
            sessions.append(self)
            def reader():
                for line in self.proc.stdout:self.log.write(line);self.log.flush();self.messages.put(line.strip())
                self.messages.put(None)
            self.thread=threading.Thread(target=reader,daemon=True);self.thread.start()
            self.before=self.line('state');self.pid=int(self.line('broker').split()[1]);self.start=self.wait('session-started')
            assert self.start['payload']['control']=='keys'
        def next(self,timeout=15):
            value=self.messages.get(timeout=timeout);assert value is not None,(self.path,self.events[-5:])
            if value.startswith('{'):
                event=json.loads(value);validate(event);sid=event['sessionId'];self.sequences[sid]=self.sequences.get(sid,0)+1
                assert event['sequence']==self.sequences[sid];self.events.append(event);return event
            return value
        def line(self,prefix):
            while True:
                value=self.next()
                if isinstance(value,str) and value.startswith(prefix):return value
        def wait(self,kind,predicate=lambda e:True):
            deadline=time.monotonic()+20
            while True:
                value=self.next(max(.01,deadline-time.monotonic()))
                if isinstance(value,dict) and value['type']==kind and predicate(value):return value
        def key(self,value):os.write(self.master,value)
        def busy(self):
            self.wait('operation-started');deadline=time.monotonic()+5
            while not (self.path/'descendant.pid').exists() and time.monotonic()<deadline:time.sleep(.01)
            assert (self.path/'descendant.pid').exists();self.tracked.update(descendants(self.pid))
        def finish(self,reason='q',expected=0):
            self.tracked.update(descendants(self.pid));start=time.monotonic()
            if reason=='q':self.key(b'q')
            elif reason=='ctrl-d':self.key(b'\x04')
            elif reason=='ctrl-c':self.key(b'\x03');expected=130
            elif reason=='term':os.kill(self.pid,signal.SIGTERM);expected=130
            elif reason=='kill':os.kill(self.pid,signal.SIGKILL);expected=-9
            self.proc.stdin.write('wait\n');self.proc.stdin.flush();assert self.line('exit')=='exit '+str(expected)
            after=self.line('state');assert after==self.before,(self.before,after)
            elapsed=time.monotonic()-start;assert elapsed<4,elapsed
            self.proc.stdin.write('end\n');self.proc.stdin.flush();os.close(self.master);os.close(self.slave)
            self.proc.wait(timeout=5);self.thread.join(timeout=5);self.log.close();self.error.close()
            for pid in self.tracked:
                deadline=time.monotonic()+5
                while alive(pid) and time.monotonic()<deadline:time.sleep(.01)
                assert not alive(pid),('surviving owned process',pid)
            results.append(dict(case=self.path.name,seconds=elapsed,pid=self.pid,original=self.before,restored=after))
            print('PASS',self.path.name,round(elapsed,3),flush=True)
    def cleanup():
        for session in sessions:
            if session.proc.poll() is None:
                try:session.proc.stdin.write('end\n');session.proc.stdin.flush()
                except (BrokenPipeError,ValueError):pass
                for fd in (session.master,session.slave):
                    try:os.close(fd)
                    except OSError:pass
                try:session.proc.wait(timeout=5)
                except subprocess.TimeoutExpired:session.proc.kill();session.proc.wait(timeout=5)
    atexit.register(cleanup)
    for reason in ('q','ctrl-d','ctrl-c','term','kill'):
        session=Session(reason);session.busy();session.key(b'rt?')
        status=session.wait('control',lambda e:e['payload']['command']=='?');assert status['payload']['pending']=='test'
        assert 'immediate' in status['payload']['help'];session.finish(reason)
    first,second=Session('independent-a'),Session('independent-b');first.busy();second.busy();first.finish()
    second.key(b'?');second.wait('control');second.finish()
    session=Session('handoff',mode='fast',descriptor=True);session.wait('operation-finished')
    session.desired.write_text('b'*64);session.wait('replacement-checked',lambda e:e['payload']['compatible'])
    session.key(b't');session.wait('session-restarting');resumed=session.wait('session-started')
    assert resumed['payload']['previousSessionDirectory']==session.start['payload']['directory']
    assert resumed['payload']['composition']['hash']=='b'*64
    state=json.loads((Path(resumed['payload']['directory'])/'handoff.json').read_text());validate_resume(state)
    assert state['schemaVersion']==2 and state['terminalLease']['state']
    session.key(b'?');session.wait('control',lambda e:e['payload']['command']=='?');session.finish()
    session=Session('incompatible-replacement',mode='fast',descriptor=True);session.wait('operation-finished')
    replacement=session.cache/'executables'/('b'*64)/'kvik.kexe'
    replacement.write_text('#!'+sys.executable+'\nprint("{}")\n');replacement.chmod(0o755)
    session.desired.write_text('b'*64)
    session.wait('replacement-checked',lambda e:not e['payload']['compatible'])
    session.key(b'?');assert not session.wait('control')['payload']['compositionReady']
    assert not any(e['type']=='session-restarting' for e in session.events)
    shutil.copyfile(output/'keys-session.kexe',replacement);replacement.chmod(0o755);session.key(b'r')
    session.wait('replacement-checked',lambda e:e['payload']['compatible']);session.wait('session-started');session.finish()
    session=Session('cancel-capability-query',mode='fast',descriptor=True);session.wait('operation-finished')
    replacement=session.cache/'executables'/('b'*64)/'kvik.kexe'
    replacement.write_text('#!'+sys.executable+'\nimport pathlib,subprocess,time\npathlib.Path(__file__).with_suffix(".started").write_text("started")\nsubprocess.Popen(["/bin/sleep","120"])\ntime.sleep(120)\n');replacement.chmod(0o755)
    session.desired.write_text('b'*64);session.wait('rebuild-finished')
    deadline=time.monotonic()+10
    while not replacement.with_suffix('.started').exists() and time.monotonic()<deadline:time.sleep(.01)
    assert replacement.with_suffix('.started').exists();session.key(b'?');session.wait('control');session.finish()
    (evidence/'summary.json').write_text(json.dumps(results,indent=2));print('PASS actual Native keys session',evidence)


if __name__=='__main__':main()
