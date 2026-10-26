#!/usr/bin/env python3
"""Public composed CLI raw-PTY/source-plugin handoff and real Native consumer gate.

This builds one changed source composition and executes real consumer compilation.
Run only in an allocated compiler slot. Supplied source/consumer fixtures are copied.
"""
import argparse
import atexit
import hashlib
import json
import os
from pathlib import Path
import platform
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
from foreground_measurements import terminal_response, terminal_summary

ROOT = Path(__file__).resolve().parents[1]
BROKER = r'''
import fcntl,json,os,signal,subprocess,sys,termios
os.setsid();terminal=os.open(sys.argv[1],os.O_RDWR|os.O_NOCTTY)
fcntl.ioctl(terminal,termios.TIOCSCTTY,0);signal.signal(signal.SIGTTOU,signal.SIG_IGN)
subprocess.run([sys.argv[2],'inspect'],stdin=terminal,check=True)
gate,release=os.pipe()
wrapper='import json,os,sys;fd=int(sys.argv[1]);assert os.read(fd,1)==b"!";os.close(fd);args=json.loads(sys.argv[2]);os.execv(args[0],args)'
child=subprocess.Popen([sys.executable,'-c',wrapper,str(gate),sys.argv[3]],stdin=terminal,pass_fds=(gate,),preexec_fn=lambda:os.setpgid(0,0))
os.close(gate);print('broker',child.pid,flush=True);os.tcsetpgrp(terminal,child.pid);os.write(release,b'!');os.close(release)
for command in sys.stdin:
 if command.strip()=='wait':
  print('exit',child.wait(timeout=15),flush=True)
  subprocess.run([sys.argv[2],'inspect'],stdin=terminal,check=True)
 elif command.strip()=='end':break
if child.poll() is None:child.kill();child.wait(timeout=5)
'''


def validate_consumer_actions(record, run):
    """Accept only the known combined-link or separate-link Native fixture graph."""
    results = {action['actionId']: action for action in record['actions']}
    assert len(results) == len(record['actions'])
    expected = {'library:macosArm64:main', 'consumer:macosArm64:main',
                'consumer:macosArm64:test-compile', 'consumer:macosArm64:test'}
    split = 'consumer:macosArm64:test-link' in results
    if split:
        expected.add('consumer:macosArm64:test-link')
    assert set(results) == expected, set(results)
    documents = [json.loads(path.read_text()) for path in (run/'actions').glob('*.json')]
    actions = {document['actionId']: document for document in documents}
    assert len(documents) == len(actions) and set(actions) == expected
    for identity, result in results.items():
        document = actions[identity]
        assert document['key'] == result['key']
        assert result['status'] in ('executed', 'local_hit')
        terminal = identity.endswith(':test')
        assert document['action']['implementation'] == ('kvik-native-tests-v1' if terminal else 'kvik-native-compiler-v1')
        if terminal:
            assert result['status'] == 'executed' and document['action']['cachePolicy'] == 'never'
    for module in ('library', 'consumer'):
        action = actions[f'{module}:macosArm64:main']['action']
        assert action['kind'] == 'compile' and action['parameters']['produce'] == 'LIBRARY'
        assert action['outputs'] == ['module.klib']
    compilation = actions['consumer:macosArm64:test-compile']['action']
    assert compilation['kind'] == ('compile' if split else 'link')
    assert compilation['parameters']['produce'] == ('LIBRARY' if split else 'TEST')
    assert compilation['parameters']['output'] == ('tests.klib' if split else 'tests.kexe')
    linked = 'consumer:macosArm64:test-link' if split else 'consumer:macosArm64:test-compile'
    assert linked in actions['consumer:macosArm64:test']['dependencies']
    if split:
        link = actions[linked]
        assert link['action']['kind'] == 'link'
        assert link['action']['parameters']['sources'] == []
        assert link['action']['parameters']['includedLibrary'] == 'modules/consumer/macosArm64/test.klib'
        assert link['action']['parameters']['output'] == 'tests.kexe'
        assert 'consumer:macosArm64:test-compile' in link['dependencies']
        assert 'library:macosArm64:main' in link['dependencies']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--source-root', type=Path, required=True, help='Exact source of the supplied composed runtime')
    parser.add_argument('--fixture', type=Path, required=True, help='Passed two-module Native fixture; preserved unchanged')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--konan-home', type=Path, required=True)
    parser.add_argument('--artifact-cache', type=Path, default=Path('/Users/yoda/dev/pet/kvik/.kvik/tool-artifacts'))
    parser.add_argument('--host-name')
    parser.add_argument('--strict-budgets', action='store_true')
    args = parser.parse_args()
    runtime=args.runtime.resolve(); original=args.source_root.resolve(); fixture=args.fixture.resolve()
    output=ROOT/'.kvik/tests/foreground-keys';output.mkdir(parents=True,exist_ok=True)
    evidence=Path(tempfile.mkdtemp(prefix='cli-',dir=output));source=evidence/'source $(literal)';project=evidence/'project'
    print('Terminal fixture:',evidence,flush=True)
    summary=dict(schemaVersion=1,passed=False,budgetsMet=None,
                 host=dict(name=args.host_name or platform.node(),os=platform.system(),osRelease=platform.release(),
                           architecture=platform.machine(),logicalCpuCount=os.cpu_count()),
                 inputs={str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in
                         (runtime,Path(__file__),ROOT/'tools/foreground_measurements.py',original/'tests/foreground-keys/TerminalProbe.c')})
    def save_summary():
        (evidence/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    save_summary()
    source.mkdir();project.mkdir()
    for name in ('bootstrap','core','cache','execution','resolution','integration','compiler','cli','plugins'):
        shutil.copytree(original/name,source/name,ignore=shutil.ignore_patterns('__pycache__'))
    shutil.copyfile(original/'kvik',source/'kvik')
    configuration=json.loads((fixture/'kvik.json').read_text())
    preserved={}
    for name in ('kvik.json','kvik.lock.json','tools.json'):shutil.copyfile(fixture/name,project/name)
    inputs={relative for module in configuration['modules'] for item in module['sourceSets'] for relative in item['directories']+item['resources']}
    for relative in sorted(inputs):
        if (fixture/relative).exists():shutil.copytree(fixture/relative,project/relative,dirs_exist_ok=True)
    for path in [fixture/name for name in ('kvik.json','kvik.lock.json','tools.json')]+[file for relative in inputs if (fixture/relative).exists() for file in (fixture/relative).rglob('*') if file.is_file()]:
        preserved[str(path)]=hashlib.sha256(path.read_bytes()).hexdigest()
    # Reject a historical fixture whose failure/zero-test scenario changed the final source.
    expected_id='fixture.AnswerTest.dependencyAndInternalDeclaration'
    sources='\n'.join(path.read_text() for path in (project/'consumer').rglob('*.kt'))
    assert 'fun dependencyAndInternalDeclaration()' in sources and 'assertEquals(42, observedAnswer())' in sources, 'Use an immutable passed consumer fixture'
    commands=[]
    def command(argv, timeout=120):
        commands.append(list(map(str,argv)));(evidence/'commands.json').write_text(json.dumps(commands,indent=2))
        return subprocess.run(list(map(str,argv)),capture_output=True,text=True,timeout=timeout)
    bootstrap=[sys.executable,str(source/'kvik'),'--java-home',str(args.java_home.resolve()),'--konan-home',str(args.konan_home.resolve()),
               '--konan-data',str(Path.home()/'.konan'),'--cache-dir',str(runtime.parents[2]),'--artifact-cache',str(args.artifact_cache.resolve()),'--offline']
    initial=command([*bootstrap,'--print-composition']);(evidence/'initial-composition.json').write_text(initial.stdout)
    assert initial.returncode==0,initial.stderr
    composition=json.loads(initial.stdout)['composition'];assert composition['hash']==runtime.parent.name, 'Copied source differs from supplied runtime; do not build an unrelated initial runtime'
    # This C inspector is only a measurement helper; the session uses its bundled guardian.
    flags=['/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang','-isysroot',
           '/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk','-std=c11','-D_DARWIN_C_SOURCE','-Wall','-Wextra','-Werror']
    built=command([*flags,'-I'+str(source/'execution/platform'),source/'execution/platform/process.c',original/'tests/foreground-keys/TerminalProbe.c','-o',evidence/'terminal-inspector'])
    assert built.returncode==0,built.stderr
    assert all('coordinate' not in dependency for module in configuration['modules'] for item in module['sourceSets'] for dependency in item['dependencies'])
    assert all(not module['compilerPlugins'] for module in configuration['modules']), 'Setup resolve must be dependency-free'
    resolved=command([runtime,'resolve','--project',project/'kvik.json']);(evidence/'resolve.json').write_text(resolved.stdout)
    assert resolved.returncode==0,resolved.stdout+resolved.stderr
    schemas={name:json.loads((original/'schemas/v1'/name).read_text()) for name in ('session.json','contracts.json','bootstrap-launch.json')}
    registry=Registry().with_resource('https://kvik.dev/schemas/v1/bootstrap-launch.json',Resource.from_contents(schemas['bootstrap-launch.json']))
    def validate(value,kind,session=False):
        schema=schemas['session.json' if session else 'contracts.json']
        jsonschema.Draft202012Validator({**schema,'$ref':'#/$defs/'+kind},registry=registry).validate(value)
    def processes():
        result=subprocess.run(['ps','-axo','pid=,ppid=,command='],capture_output=True,text=True,check=True)
        return [line.split(None,2) for line in result.stdout.splitlines()]
    def owned(pid):
        rows=processes();found={pid}
        while True:
            more=found|{int(row[0]) for row in rows if int(row[1]) in found}
            if more==found:return {int(row[0]):row[2] for row in rows if int(row[0]) in found}
            found=more
    def alive(pid):
        result=subprocess.run(['ps','-p',str(pid),'-o','stat='],capture_output=True,text=True)
        return result.returncode==0 and not result.stdout.strip().startswith('Z')
    sessions=[]
    samples=[]
    class Session:
        def __init__(self,label,prefix):
            self.path=evidence/label;self.path.mkdir();self.master,self.slave=pty.openpty();self.messages=queue.Queue();self.events=[];self.sequences={};self.tracked=set();self.lines=[]
            argv=[*prefix,'dev','--watch-ms','1000','--project',str(project/'kvik.json'),'--tools',str(project/'tools.json'),'--module','consumer','--target','macosArm64']
            (self.path/'command.json').write_text(json.dumps(argv));self.log=(self.path/'stream.log').open('w');self.err=(self.path/'stderr.log').open('w')
            self.proc=subprocess.Popen([sys.executable,'-c',BROKER,os.ttyname(self.slave),str(evidence/'terminal-inspector'),json.dumps(argv)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.err,text=True,bufsize=1)
            sessions.append(self)
            def read():
                for line in self.proc.stdout:
                    received=time.monotonic_ns()
                    self.messages.put((line.strip(),received))
                    self.log.write(line);self.log.flush()
                self.messages.put((None,time.monotonic_ns()))
            self.thread=threading.Thread(target=read,daemon=True);self.thread.start()
            self.before=self.line('state');self.pid=int(self.line('broker').split()[1]);self.start=self.wait('session-started')
            assert self.start['payload']['control']=='keys'
            self.helper=next(pid for pid,cmd in owned(self.pid).items() if ' --terminal ' in cmd)
        def next(self,timeout=120):
            value,self.received=self.messages.get(timeout=timeout);assert value is not None,(self.path,self.proc.poll(),self.events[-5:])
            if value.startswith('{'):
                event=json.loads(value);validate(event,'event',True);sid=event['sessionId'];self.sequences[sid]=self.sequences.get(sid,0)+1
                assert event['sequence']==self.sequences[sid];self.events.append(event);self.lines.append(value+'\n');return event
            return value
        def line(self,prefix):
            while True:
                value=self.next()
                if isinstance(value,str) and value.startswith(prefix):return value
        def wait(self,kind,predicate=lambda e:True,timeout=900):
            deadline=time.monotonic()+timeout
            while True:
                value=self.next(max(.01,deadline-time.monotonic()))
                if isinstance(value,dict) and value['type']==kind and predicate(value):return value
        def key(self,value):os.write(self.master,value)
        def status(self,phase):
            # Exactly one outstanding '?' and a receipt after its write boundary
            # prevent an earlier queued event from satisfying a new sample.
            start=time.monotonic_ns();self.key(b'?');event=self.wait('control',lambda e:e['payload']['command']=='?',timeout=2)
            sample=terminal_response(phase,start,self.received,event,self.events);samples.append(sample)
            summary['controls']=terminal_summary(samples);save_summary()
            return event,sample['durationNanos']/1e9
        def status_series(self,phase):
            for _ in range(20):self.status(phase)
            print('Measured terminal phase:',phase,flush=True)
        def finish(self,reason='q'):
            self.tracked.update(owned(self.pid));start=time.monotonic();self.key(b'q' if reason=='q' else b'\x03')
            self.proc.stdin.write('wait\n');self.proc.stdin.flush();assert self.line('exit')=='exit '+str(0 if reason=='q' else 130)
            restored=self.line('state');assert restored==self.before,(self.before,restored)
            restored_seconds=time.monotonic()-start;assert restored_seconds<4,restored_seconds
            self.proc.stdin.write('end\n');self.proc.stdin.flush();os.close(self.master);os.close(self.slave);self.proc.wait(timeout=5)
            self.thread.join(timeout=5);assert not self.thread.is_alive(),'Terminal output reader did not stop'
            self.log.close();self.err.close()
            for pid in self.tracked:
                deadline=time.monotonic()+5
                while alive(pid) and time.monotonic()<deadline:time.sleep(.02)
                assert not alive(pid),('owned survivor',pid)
            elapsed=time.monotonic()-start
            # Exact stream framing agrees with each retained session journal across exec.
            for event in [e for e in self.events if e['type']=='session-started']:
                directory=Path(event['payload']['directory']);sid=event['sessionId']
                lines=''.join(line for line in self.lines if json.loads(line)['sessionId']==sid)
                assert (directory/'session.jsonl').read_text()==lines
            return dict(reason=reason,seconds=elapsed,terminalRestoredSeconds=restored_seconds,
                        scope='Key write through owner exit, terminal restoration, reader join and captured owned-process absence',
                        budgetSeconds=2,budgetMet=elapsed<2,original=self.before,restored=restored)
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
    session=Session('reload',bootstrap+['--']);session.wait('operation-started');session.status_series('initial-build')
    first_done=session.wait('operation-finished',lambda e:not e['payload']['cancelled']);assert first_done['payload']['exitCode']==0,first_done
    plugin=source/'plugins/kotlin/src/kvik/plugins/kotlin/KotlinBuildPlugin.kt';before=plugin.read_text()
    needle='Kotlin consumes the core project or standalone script authoring document.';assert needle in before
    plugin.write_text(before.replace(needle,'raw-terminal-recomposition-proof'))
    session.wait('rebuild-started');session.key(b't');control=session.wait('control',lambda e:e['payload']['command']=='t');assert control['payload']['pending']=='test'
    session.status_series('source-recomposition')
    session.wait('replacement-checked',lambda e:e['payload']['compatible']);boundary=session.wait('session-restarting');resumed=session.wait('session-started')
    assert resumed['payload']['previousSessionDirectory']==session.start['payload']['directory'];assert resumed['payload']['composition']!=composition
    tree=owned(session.pid)
    assert session.pid in tree and session.helper in tree, 'Owner and restoration guardian must survive same-PID exec'
    assert tree[session.pid].startswith(boundary['payload']['runtime']), tree
    information=command([boundary['payload']['runtime'],'runtime-info']);assert information.returncode==0,information.stderr
    (evidence/'replacement-runtime-info.json').write_text(information.stdout)
    assert any(item['schema'].get('description')=='raw-terminal-recomposition-proof' for item in json.loads(information.stdout)['plugins'])
    state=json.loads((Path(resumed['payload']['directory'])/'handoff.json').read_text());validate(state,'resume',True);assert state['schemaVersion']==2 and state['pending']=='test'
    started=session.wait('operation-started');assert started['payload']['operation']=='test';session.status_series('replacement-test')
    done=session.wait('operation-finished',lambda e:not e['payload']['cancelled']);assert done['payload']['exitCode']==0,done
    child=Path(done['payload']['evidenceDirectory']);run_events=[json.loads(line) for line in (child/'stdout.jsonl').read_text().splitlines()]
    for event in run_events:validate(event,'event')
    record=run_events[-1]['payload'];validate(record,'verification')
    assert record['status']=='passed' and record['freshnessAtCompletion']=='current'
    assert record['composition']==resumed['payload']['composition']
    assert record['testCounts']==dict(discovered=1,executed=1,failed=0,skipped=0,discoveryComplete=True)
    assert [test['id'] for test in record['tests']]==[expected_id]
    run=child/'runs'/record['runId'];assert json.loads((run/'verification.json').read_text())==record
    validate_consumer_actions(record, run)
    assert (run/'events.jsonl').read_bytes()==(child/'stdout.jsonl').read_bytes()
    observations=[json.loads(path.read_text()) for path in (run/'observations').glob('*.json')]
    tests=next(value['observation'] for value in observations if value['actionId']=='consumer:macosArm64:test')
    assert any('KVIK_NATIVE_TEST_EFFECT' in Path(log).read_text() for log in tests['logs'])
    query=command([boundary['payload']['runtime'],'verification','--run',run],timeout=300);(evidence/'verification-query.json').write_text(query.stdout)
    assert query.returncode==0,query.stdout+query.stderr;value=json.loads(query.stdout);validate(value,'verificationQuery');assert value['freshness']=='current'
    quit_result=session.finish()
    summary['restoration']=[quit_result];save_summary()
    # The cached replacement launches directly; Ctrl-C interrupts actual owned tool verification.
    interrupted=Session('physical-ctrl-c',[boundary['payload']['runtime']]);active=interrupted.wait('operation-started')
    child=Path(active['payload']['evidenceDirectory']);deadline=time.monotonic()+10
    while time.monotonic()<deadline:
        path=child/'stdout.jsonl'
        if path.exists() and 'run-started' in path.read_text():break
        time.sleep(.01)
    else:raise AssertionError('Actual child verification did not begin')
    interrupted.status('before-ctrl-c');signal_result=interrupted.finish('ctrl-c')
    summary.update(runtime=str(runtime),pid=session.pid,terminalGuardian=session.helper,initialComposition=composition,replacement=resumed['payload']['composition'],
                   run=str(run),testIds=[expected_id],actions=record['actions'],controls=terminal_summary(samples),
                   restoration=[quit_result,signal_result])
    assert all(hashlib.sha256(Path(path).read_bytes()).hexdigest()==digest for path,digest in preserved.items()), 'Supplied fixture changed'
    summary['passed']=True
    summary['budgetsMet']=summary['controls']['budgetsMet'] and all(value['budgetMet'] for value in summary['restoration'])
    save_summary();print('PASS public raw CLI, actual source handoff, fresh exact Native test, current query and restoration',evidence,flush=True)
    if args.strict_budgets and not summary['budgetsMet']:raise SystemExit(3)


if __name__=='__main__':main()
