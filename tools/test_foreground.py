#!/usr/bin/env python3
"""Native session controls/ownership with real child processes; optional composed CLI smoke.

Worker fixtures deliberately block like synchronous verification. They are lifecycle
probes, not evidence of compiler correctness. --runtime additionally exercises the
actual CLI with an isolated copy of --fixture and never changes the supplied fixture.
"""
import argparse
import atexit
import fcntl
import json
import os
from pathlib import Path
import pty
import queue
import signal
import subprocess
import sys
import tempfile
import termios
import threading
import time

import jsonschema

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--fixture', type=Path)
    args = parser.parse_args()
    output = ROOT/'.kvik/tests/foreground'
    output.mkdir(parents=True, exist_ok=True)
    native = ROOT/'execution/platform'
    env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()))
    sdk = Path('/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk')
    clang = Path('/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang')
    def command(argv):
        subprocess.run(list(map(str, argv)), env=env, check=True)
    if not args.skip_build:
        flags = [clang, '-isysroot', sdk, '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror']
        command([*flags, native/'guardian.c', '-o', output/'guardian'])
        command([*flags, '-c', native/'process.c', '-o', output/'process.o'])
        command(['ar', 'rcs', output/'libkvik-process.a', output/'process.o'])
        command([args.konan_home/'bin/cinterop', '-def', native/'process.def', '-compiler-option', f'-I{native}',
                 '-libraryPath', output, '-target', 'macos_arm64', '-o', output/'process'])
        sources = [p for directory in ('core/src', 'execution/src', 'integration/foreground/src')
                   for p in sorted((ROOT/directory).rglob('*.kt'))]
        sources += [ROOT/'cache/src/kvik/cache/NativeFiles.kt']
        base = [args.konan_home/'bin/konanc', '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true',
                '-library', output/'process.klib', *sources]
        command([*base, ROOT/'tests/foreground/ProtocolTest.kt', '-generate-test-runner', '-o', output/'unit.kexe'])
        command([*base, ROOT/'tests/foreground/SessionProbe.kt', '-entry', 'kvik.foreground.probe.main', '-o', output/'probe.kexe'])
    command([output/'unit.kexe'])
    evidence = Path(tempfile.mkdtemp(prefix='lifecycle-', dir=output)).resolve()
    schema = json.loads((ROOT/'schemas/v1/session.json').read_text())
    validator = jsonschema.Draft202012Validator({**schema, '$ref': '#/$defs/event'})
    control_validator=jsonschema.Draft202012Validator({**schema,'$ref':'#/$defs/control'})
    assert not control_validator.is_valid(dict(schemaVersion=1,requestId='x\n',command='q'))
    processes = []
    def cleanup():
        for process in processes:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
    atexit.register(cleanup)
    worker = evidence/'worker.py'
    worker.write_text('#!'+sys.executable+'''\nimport hashlib,json,os,pathlib,subprocess,sys,time
p=pathlib.Path.cwd()
if sys.argv[1]=='snapshot-query':
 if (p/'scan-failure').exists():
  (p/'scan-failure').unlink();sys.exit(1)
 data=(p/'source').read_bytes()
 print(json.dumps(dict(schemaVersion=1,fingerprint=dict(hash=hashlib.sha256(data).hexdigest(),sizeBytes=len(data)))))
else:
 (p/'worker.pid').write_text(str(os.getpid()))
 child=subprocess.Popen(['/bin/sleep','120'])
 (p/'descendant.pid').write_text(str(child.pid))
 if (p/'mode').read_text()=='crash':os.kill(os.getpid(),9)
 if (p/'mode').read_text()=='slow':time.sleep(120)
 else:time.sleep(.25)
 sys.exit(1 if (p/'source').read_text().startswith('broken') else 0)
''')
    worker.chmod(0o755)

    class Session:
        def __init__(self, label, mode='slow', parent=False, tty=False, argv=None, directory=None, retain_streams=False):
            self.path = directory or evidence/label
            self.path.mkdir(parents=True, exist_ok=True)
            if argv is None:
                (self.path/'source').write_text('original')
                (self.path/'mode').write_text(mode)
                argv = [str(output/'probe.kexe'), str(output/'guardian'), str(worker), str(self.path), 'lines' if tty else 'jsonl']
            self.events = []; self.lines = queue.Queue(); self.master = None; self.slave = None
            (self.path/'invoke.json').write_text(json.dumps(argv))
            if parent:
                argv = [sys.executable, '-c', 'import pathlib,subprocess,sys; p=subprocess.Popen(sys.argv[2:]); pathlib.Path(sys.argv[1]).write_text(str(p.pid)); p.wait()', str(self.path/'coordinator.pid'), *argv]
            input_fd = subprocess.PIPE
            if tty:
                self.master, self.slave = pty.openpty(); input_fd = self.slave
                self.before_termios = termios.tcgetattr(self.slave)
                self.before_tty_flags = fcntl.fcntl(self.slave,fcntl.F_GETFL)
            self.retained=[];output_fd=subprocess.PIPE
            if retain_streams:
                input_fd,input_writer=os.pipe();output_reader,output_fd=os.pipe()
                self.retained=[input_fd,output_fd]
                # Darwin marks a pipe as written (0x10000) on the first ordinary write.
                # Prime/drain before recording flags; that kernel history bit is not F_SETFL.
                os.write(output_fd,b'x');os.read(output_reader,1)
                self.before_stream_flags=[fcntl.fcntl(fd,fcntl.F_GETFL) for fd in self.retained]
            self.err = (self.path/'harness.stderr').open('w')
            self.proc = subprocess.Popen(argv, stdin=input_fd, stdout=output_fd, stderr=self.err, text=True, bufsize=1)
            if retain_streams:
                self.proc.stdin=os.fdopen(input_writer,'w');self.proc.stdout=os.fdopen(output_reader,'r')
            processes.append(self.proc)
            self.tracked = {self.proc.pid}
            self.log = (self.path/'harness.events.jsonl').open('w')
            def reader():
                try:
                    for line in self.proc.stdout:
                        self.log.write(line); self.log.flush(); self.lines.put(json.loads(line))
                finally:self.lines.put(None)
            self.thread = threading.Thread(target=reader, daemon=True); self.thread.start()
            self.wait('session-started')
        def wait(self, kind, predicate=lambda event: True, timeout=10):
            end = time.monotonic()+timeout
            while True:
                value = self.lines.get(timeout=max(.01, end-time.monotonic()))
                assert value is not None, (kind, self.events, self.proc.poll())
                validator.validate(value); self.events.append(value)
                if value['type']==kind and predicate(value):return value
                assert time.monotonic()<end, (kind,self.events)
        def send(self, operation):
            if operation=='q':self.tracked.update(descendants(self.proc.pid))
            if self.master is not None:os.write(self.master,(operation+'\n').encode())
            else:
                self.proc.stdin.write(json.dumps(dict(schemaVersion=1,requestId=operation,command=operation))+'\n');self.proc.stdin.flush()
        def finish(self, expected=0):
            self.proc.wait(timeout=10); assert self.proc.returncode==expected,self.proc.returncode
            if self.retained:
                assert [fcntl.fcntl(fd,fcntl.F_GETFL) for fd in self.retained]==self.before_stream_flags, (self.before_stream_flags,[fcntl.fcntl(fd,fcntl.F_GETFL) for fd in self.retained])
                for fd in self.retained:os.close(fd)
            self.thread.join(timeout=5); self.log.close(); self.err.close()
            while not self.lines.empty():
                event=self.lines.get()
                if event is not None:validator.validate(event);self.events.append(event)
            assert [e['sequence'] for e in self.events]==list(range(1,len(self.events)+1))
            if self.slave is not None:
                assert termios.tcgetattr(self.slave)==self.before_termios
                assert fcntl.fcntl(self.slave,fcntl.F_GETFL)==self.before_tty_flags
                os.close(self.slave);os.close(self.master)
            for pid in self.tracked:
                deadline=time.monotonic()+5
                while alive(pid) and time.monotonic()<deadline:time.sleep(.02)
                assert not alive(pid),('surviving owned process',pid)
            for name in ('worker.pid','descendant.pid','coordinator.pid'):
                path=self.path/name
                if path.exists():
                    pid=int(path.read_text());deadline=time.monotonic()+5
                    while alive(pid) and time.monotonic()<deadline:time.sleep(.02)
                    assert not alive(pid),(name,pid)
    def alive(pid):
        result=subprocess.run(['ps','-p',str(pid),'-o','stat='],capture_output=True,text=True)
        return result.returncode==0 and not result.stdout.strip().startswith('Z')
    def descendants(owner):
        result=subprocess.run(['ps','-axo','pid=,ppid='],capture_output=True,text=True,check=True)
        pairs=[tuple(map(int,line.split())) for line in result.stdout.splitlines()]
        found={owner}
        while True:
            expanded=found|{pid for pid,parent in pairs if parent in found}
            if expanded==found:return found
            found=expanded
    def busy(session):
        session.wait('operation-started')
        end=time.monotonic()+5
        while not (session.path/'descendant.pid').exists() and time.monotonic()<end:time.sleep(.01)
        assert (session.path/'descendant.pid').exists()
        session.tracked.update(descendants(session.proc.pid))
    summaries=[]
    init=evidence/'initialization';init.mkdir()
    command([output/'probe.kexe',output/'guardian',worker,init,'init-failure'])
    summaries.append(dict(case='initialization-failure-restores-fds-and-signals'))
    for reason in ('quit','eof','sigint','sigterm','owner-killed','parent-loss'):
        s=Session(reason,parent=reason=='parent-loss');busy(s);started=time.monotonic()
        if reason=='quit':
            s.send('status');s.wait('control',lambda e:e['payload']['command']=='status');s.send('q')
        elif reason=='eof':s.proc.stdin.close()
        elif reason in ('sigint','sigterm'):s.proc.send_signal(signal.SIGINT if reason=='sigint' else signal.SIGTERM)
        else:s.proc.kill()
        s.finish(-9 if reason in ('owner-killed','parent-loss') else 130 if reason in ('sigint','sigterm') else 0)
        elapsed=time.monotonic()-started;assert elapsed<3,(reason,elapsed)
        summaries.append(dict(case=reason,shutdownSeconds=elapsed))
        print('PASS',reason,round(elapsed,3),flush=True)
    print('PASS termination matrix; checking isolation/TTY/watch',flush=True)
    first,second=Session('concurrent-a'),Session('concurrent-b');busy(first);busy(second)
    first.send('q');first.finish();assert alive(int((second.path/'worker.pid').read_text()))
    second.send('status');second.wait('control');second.send('q');second.finish()
    summaries.append(dict(case='independent-sessions'))
    s=Session('pty',tty=True);busy(s);s.send('?');help_event=s.wait('control');assert 'queue build' in help_event['payload']['help'];s.send('q');s.finish()
    summaries.append(dict(case='pty-line-controls-termios-preserved'))
    for label,options in [('pty-owner-killed',dict(tty=True)),('pty-parent-loss',dict(tty=True,parent=True)),('shared-stream-flags',dict(retain_streams=True))]:
        s=Session(label,**options);busy(s);s.send('status');s.wait('control');s.proc.kill();s.finish(expected=-9)
        summaries.append(dict(case=label))
    s=Session('coalescing');busy(s)
    for control in ['r','t','r','t','status']:s.send(control)
    s.wait('control',lambda e:e['payload']['command']=='status' and e['payload']['pending']=='test')
    s.send('q');s.finish();assert len([e for e in s.events if e['type']=='operation-started'])==1
    summaries.append(dict(case='bounded-coalescing-test-intent-retained'))
    s=Session('recovery',mode='fast')
    s.wait('operation-finished',lambda e:e['payload']['exitCode']==0)
    source=s.path/'source';before=source.stat();source.write_text('broken!!');os.utime(source,ns=(before.st_atime_ns,before.st_mtime_ns))
    s.wait('operation-finished',lambda e:e['payload']['exitCode']==1)
    source.write_text('repaired')
    s.wait('operation-finished',lambda e:e['payload']['exitCode']==0)
    s.send('q');s.finish();summaries.append(dict(case='preserved-mtime-watch-failure-recovery'))
    print('PASS isolation, PTY, coalescing and recovery',flush=True)
    s=Session('transient-observer',mode='fast');s.wait('operation-finished',lambda e:e['payload']['exitCode']==0)
    (s.path/'scan-failure').write_text('one unavailable observation')
    s.wait('inputs-observed',lambda e:not e['payload']['available'])
    s.wait('inputs-observed',lambda e:e['payload']['available'])
    time.sleep(1);s.send('status');s.wait('control');s.send('q');s.finish()
    assert len([e for e in s.events if e['type']=='operation-started'])==1
    summaries.append(dict(case='transient-observer-same-input-does-not-rebuild'))
    s=Session('worker-crash',mode='crash');s.wait('operation-finished',lambda e:e['payload']['exitCode']==137)
    s.send('status');s.wait('control');(s.path/'mode').write_text('fast');(s.path/'source').write_text('repaired')
    s.wait('operation-finished',lambda e:e['payload']['exitCode']==0);s.send('q');s.finish()
    summaries.append(dict(case='worker-crash-and-recovery'))
    s=Session('malformed-control');busy(s)
    s.proc.stdin.write('{broken\n');s.proc.stdin.flush()
    s.wait('diagnostic');s.send('status');s.wait('control');s.send('q');s.finish()
    summaries.append(dict(case='malformed-control-recovers'))
    s=Session('input-bound');busy(s);s.proc.stdin.write('x'*4097);s.proc.stdin.flush();s.finish(expected=1)
    assert s.events[-1]['payload']['reason']=='input-limit'
    summaries.append(dict(case='bounded-control-input'))
    blocked=evidence/'backpressure';blocked.mkdir();(blocked/'source').write_text('original');(blocked/'mode').write_text('slow')
    proc=subprocess.Popen([str(output/'probe.kexe'),str(output/'guardian'),str(worker),str(blocked),'jsonl'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    processes.append(proc);assert json.loads(proc.stdout.readline())['type']=='session-started'
    deadline=time.monotonic()+5
    while not (blocked/'descendant.pid').exists() and time.monotonic()<deadline:time.sleep(.01)
    tracked=descendants(proc.pid);started=time.monotonic()
    def flood():
        try:
            for _ in range(20000):proc.stdin.write(b'{"schemaVersion":1,"requestId":"flood","command":"status"}\n')
            proc.stdin.flush()
        except (BrokenPipeError,OSError):pass
    writer=threading.Thread(target=flood,daemon=True);writer.start()
    proc.wait(timeout=15);assert proc.returncode==1
    writer.join(timeout=2)
    try:proc.stdin.close()
    except BrokenPipeError:pass
    (blocked/'stream-prefix.jsonl').write_bytes(proc.stdout.read());(blocked/'stderr.log').write_bytes(proc.stderr.read())
    events=[json.loads(line) for line in (blocked/'session.jsonl').read_text().splitlines()]
    for event in events:validator.validate(event)
    assert events[-1]['type']=='session-finished' and events[-1]['payload']['reason']=='output-backpressure'
    for pid in tracked:assert not alive(pid),pid
    summaries.append(dict(case='bounded-output-backpressure',shutdownSeconds=time.monotonic()-started))
    print('PASS invalid input, input bound and non-reading stdout cleanup',flush=True)
    if args.runtime:
        assert args.fixture,'--runtime requires --fixture'
        import shutil
        project=evidence/'actual-cli';project.mkdir()
        for name in ('kvik.json','kvik.lock.json','tools.json'):
            shutil.copyfile(args.fixture/name,project/name)
        # Only declared consumer inputs: never copy old runs or compiled outputs.
        config=json.loads((project/'kvik.json').read_text())
        for module in config['modules']:
            for source in module['sourceSets']:
                for relative in source['directories']+source['resources']:
                    if (args.fixture/relative).exists():shutil.copytree(args.fixture/relative,project/relative,dirs_exist_ok=True)
        # Preflight must reject output/input overlap before creating session/store directories,
        # including missing leaves and physical aliases. Each case has its own project.
        overlap_cases = [('existing', 'inputs', 'inputs/cache'),
                         ('missing', 'missing/source', 'missing'),
                         ('case-alias', 'Missing/Source', 'missing/source/cache'),
                         ('unicode-alias', 'caf\u00e9/source', 'cafe\u0301/source/cache'),
                         ('generated-output-source', '.kvik', '.kvik/cache')]
        for label, source, store in overlap_cases:
            case=evidence/('overlap-'+label);case.mkdir()
            model=json.loads(json.dumps(config));model['modules'][0]['sourceSets'][0]['directories']=[source]
            (case/'kvik.json').write_text(json.dumps(model))
            if label=='existing':(case/source).mkdir()
            before=sorted(str(p.relative_to(case)) for p in case.rglob('*'))
            result=subprocess.run([str(args.runtime.resolve()),'dev','--control','jsonl','--module','consumer','--target','macosArm64','--tools',str(case/'missing-tools.json'),'--project',str(case/'kvik.json'),
                                   '--store',str(case/store)],input='',capture_output=True,text=True,timeout=10)
            (evidence/('overlap-'+label+'.result.json')).write_text(json.dumps(dict(code=result.returncode,stdout=result.stdout,stderr=result.stderr)))
            assert result.returncode==2 and 'Content store overlaps watched input' in result.stdout+result.stderr,(label,result)
            assert before==sorted(str(p.relative_to(case)) for p in case.rglob('*'))
        case=evidence/'overlap-symlink';case.mkdir();(case/'inputs').mkdir();(case/'alias').symlink_to(case/'inputs',target_is_directory=True)
        model=json.loads(json.dumps(config));model['modules'][0]['sourceSets'][0]['directories']=['inputs']
        (case/'kvik.json').write_text(json.dumps(model));before=sorted(str(p.relative_to(case)) for p in case.rglob('*'))
        result=subprocess.run([str(args.runtime.resolve()),'dev','--control','jsonl','--module','consumer','--target','macosArm64','--tools',str(case/'missing-tools.json'),'--project',str(case/'kvik.json'),
                               '--store',str(case/'alias/cache')],input='',capture_output=True,text=True,timeout=10)
        assert result.returncode==2 and 'Content store overlaps watched input' in result.stdout+result.stderr,result
        assert before==sorted(str(p.relative_to(case)) for p in case.rglob('*'))
        print('PASS six store/input overlap preflight cases without output writes',flush=True)
        # Exercise changing configuration against a permitted arbitrary local store.
        # Invalid tool metadata stops after capture, avoiding another compiler invocation.
        local=evidence/'changing-store-input';local.mkdir()
        for name in ('kvik.json','kvik.lock.json'):shutil.copyfile(project/name,local/name)
        (local/'tools.json').write_text('{}')
        local_argv=[str(args.runtime.resolve()),'dev','--control','jsonl','--watch-ms','100',
                    '--project',str(local/'kvik.json'),'--tools',str(local/'tools.json'),
                    '--module','consumer','--target','macosArm64','--store',str(local/'local-cache')]
        changing=Session('changing-store-input',argv=local_argv,directory=local)
        initial=changing.wait('operation-finished',lambda e:e['payload']['exitCode']==2)
        assert 'KVIK_TOOLCHAIN_CONFIGURATION' in (Path(initial['payload']['evidenceDirectory'])/'stdout.jsonl').read_text()
        def store_inventory():
            import hashlib
            return {str(p.relative_to(local/'local-cache')):hashlib.sha256(p.read_bytes()).hexdigest() if p.is_file() else 'directory'
                    for p in (local/'local-cache').rglob('*')}
        before=store_inventory();original=(local/'kvik.json').read_bytes()
        changed=json.loads(original);changed['modules'][0]['sourceSets'][0]['directories'].append('local-cache')
        (local/'kvik.json').write_text(json.dumps(changed))
        rejected=changing.wait('operation-finished',lambda e:e['payload']['exitCode']==2)
        assert 'Content store overlaps watched input' in (Path(rejected['payload']['evidenceDirectory'])/'stdout.jsonl').read_text()
        assert store_inventory()==before,'overlap failure mutated store'
        (local/'kvik.json').write_bytes(original)
        repaired=changing.wait('operation-finished',lambda e:e['payload']['exitCode']==2)
        assert 'KVIK_TOOLCHAIN_CONFIGURATION' in (Path(repaired['payload']['evidenceDirectory'])/'stdout.jsonl').read_text()
        changing.send('status');changing.wait('control');changing.send('q');changing.finish()
        summaries.append(dict(case='changed-config-overlap-no-cas-write-and-recovery'))
        print('PASS live local-store overlap rejection and repair',flush=True)
        argv=[str(args.runtime.resolve()),'dev','--control','jsonl','--initial','test','--project',str(project/'kvik.json'),
              '--tools',str(project/'tools.json'),'--module','consumer','--target','macosArm64','--store',str(evidence/'shared-cas')]
        # No external dependencies in this fixture. Refresh its lock with this composition.
        resolved=subprocess.run([str(args.runtime.resolve()),'resolve','--project',str(project/'kvik.json'),'--store',str(evidence/'shared-cas')],capture_output=True,text=True,timeout=60)
        assert resolved.returncode==0,resolved.stdout+resolved.stderr
        cancelled=Session('actual-cancel',argv=argv)
        cancelled.wait('operation-started');deadline=time.monotonic()+15
        while not list(project.glob('.kvik/sessions/*/*/runs/*/events.jsonl')) and time.monotonic()<deadline:time.sleep(.02)
        assert list(project.glob('.kvik/sessions/*/*/runs/*/events.jsonl')),'real verification did not start'
        started=time.monotonic();cancelled.send('status');cancelled.wait('control');cancelled.send('q');cancelled.finish()
        assert time.monotonic()-started<3
        summaries.append(dict(case='actual-cancel-during-tool-verification',shutdownSeconds=time.monotonic()-started))
        s=Session('actual-cli',argv=argv,directory=project)
        s.wait('operation-started');started=time.monotonic();s.send('status');s.wait('control');response=time.monotonic()-started
        assert response<1,response
        event=s.wait('operation-finished',lambda e:e['payload']['exitCode']==0,timeout=600)
        child=Path(event['payload']['evidenceDirectory']);record_path=next(child.glob('runs/*/verification.json'))
        contracts=json.loads((ROOT/'schemas/v1/contracts.json').read_text())
        def validate_run(path):
            record=json.loads(path.read_text())
            jsonschema.Draft202012Validator({'$defs':contracts['$defs'],'$ref':'#/$defs/verification'}).validate(record)
            for line in (path.parent/'events.jsonl').read_text().splitlines():
                jsonschema.Draft202012Validator({'$defs':contracts['$defs'],'$ref':'#/$defs/event'}).validate(json.loads(line))
            return record
        record=validate_run(record_path);assert record['status']=='passed' and record['testCounts']['executed']==1
        assert [t['id'] for t in record['tests']]==['fixture.AnswerTest.dependencyAndInternalDeclaration']
        assert len(record['actions'])==4 and all(action['status']=='executed' for action in record['actions'])
        assert record['freshnessAtCompletion']=='current'
        # Generated .kvik writes must not enqueue another run.
        (project/'.kvik/watch-noise').write_text('ignored output')
        time.sleep(3);s.send('status');s.wait('control')
        assert len([e for e in s.events if e['type']=='operation-started'])==1
        # An invalid configuration causes a retained failed invocation without resolving.
        original=(project/'kvik.json').read_bytes();locked=(project/'kvik.lock.json').read_bytes()
        (project/'kvik.json').write_text('{broken')
        s.wait('operation-finished',lambda e:e['payload']['exitCode']==2,timeout=15)
        assert (project/'kvik.lock.json').read_bytes()==locked
        (project/'kvik.json').write_bytes(original)
        repaired=s.wait('operation-finished',lambda e:e['payload']['exitCode']==0,timeout=600)
        repeated=validate_run(next(Path(repaired['payload']['evidenceDirectory']).glob('runs/*/verification.json')))
        assert repeated['testCounts']['executed']==1 and all(a['status']==('executed' if a['actionId'].endswith(':test') else 'local_hit') for a in repeated['actions'])
        # A second project copy shares immutable CAS while owning independent mutable runs/controls.
        other=evidence/'actual-other';other.mkdir()
        for name in ('kvik.json','kvik.lock.json','tools.json'):shutil.copyfile(project/name,other/name)
        for module in config['modules']:
            for source in module['sourceSets']:
                for relative in source['directories']+source['resources']:
                    if (project/relative).exists():shutil.copytree(project/relative,other/relative,dirs_exist_ok=True)
        other_argv=[str(other/'kvik.json') if x==str(project/'kvik.json') else str(other/'tools.json') if x==str(project/'tools.json') else x for x in argv]
        second=Session('actual-other',argv=other_argv,directory=other);second.wait('operation-started')
        s.send('q');s.finish();second.send('status');second.wait('control')
        done=second.wait('operation-finished',lambda e:e['payload']['exitCode']==0,timeout=600)
        other_record=validate_run(next(Path(done['payload']['evidenceDirectory']).glob('runs/*/verification.json')))
        assert other_record['testCounts']['executed']==1 and all(a['status']==('executed' if a['actionId'].endswith(':test') else 'local_hit') for a in other_record['actions'])
        second.send('q');second.finish()
        summaries.append(dict(case='actual-native-cli-test-config-recovery-shared-cas-isolation',record=str(record_path),controlResponseSeconds=response))
    (evidence/'summary.json').write_text(json.dumps(summaries,indent=2))
    print('PASS foreground lifecycle:',evidence,flush=True)


if __name__=='__main__':main()
