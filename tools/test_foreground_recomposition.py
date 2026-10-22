#!/usr/bin/env python3
"""Real source-plugin recompilation, same-PID handoff and owned bootstrap cleanup.
Requires pinned tools and a passed Native two-module fixture; preserves that fixture.
"""
import argparse
import atexit
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

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--konan-home',type=Path,required=True)
    parser.add_argument('--fixture',type=Path,required=True)
    parser.add_argument('--runtime',type=Path)
    parser.add_argument('--skip-unit-build',action='store_true')
    args=parser.parse_args()
    output=ROOT/'.kvik/tests/foreground-recomposition';output.mkdir(parents=True,exist_ok=True)
    env=dict(os.environ,JAVA_HOME=str(args.java_home.resolve()))
    if not args.skip_unit_build:
        files=[*sorted((ROOT/'core/src').rglob('*.kt')),ROOT/'integration/foreground/src/kvik/foreground/BootstrapLaunch.kt',
               ROOT/'integration/foreground/src/kvik/foreground/SessionResume.kt',ROOT/'integration/foreground/src/kvik/foreground/Protocol.kt',
               ROOT/'tests/foreground-recomposition/ResumeTest.kt']
        subprocess.run([str(args.konan_home/'bin/konanc'),'-target','macos_arm64','-Xoverride-konan-properties=airplaneMode=true',
                        '-generate-test-runner','-o',str(output/'resume.kexe'),*map(str,files)],env=env,check=True)
        subprocess.run(['/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang',
                        '-isysroot','/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk','-std=c11','-D_DARWIN_C_SOURCE','-Wall','-Wextra','-Werror','-I'+str(ROOT/'execution/platform'),
                        str(ROOT/'execution/platform/process.c'),str(ROOT/'tests/foreground-recomposition/ExecProbe.c'),'-o',str(output/'exec-probe')],check=True)
    for command in ([output/'resume.kexe'],[output/'exec-probe','failure'],[output/'exec-probe','pending']):subprocess.run(command,check=True)
    bootstrap=[str(ROOT/'kvik'),'--java-home',str(args.java_home.resolve()),'--konan-home',str(args.konan_home.resolve()),
               '--artifact-cache','/Users/yoda/dev/pet/kvik/.kvik/tool-artifacts','--offline']
    runtime=args.runtime or Path(subprocess.run([*bootstrap,'--print-runtime'],capture_output=True,text=True,check=True).stdout.strip())
    cache=runtime.resolve().parents[2]
    evidence=Path(tempfile.mkdtemp(prefix='case-',dir=output)).resolve()
    source=evidence/'source $(literal)';source.mkdir()
    for name in ('bootstrap','core','cache','execution','resolution','integration','compiler','cli','plugins'):
        shutil.copytree(ROOT/name,source/name,ignore=shutil.ignore_patterns('__pycache__'))
    shutil.copyfile(ROOT/'kvik',source/'kvik')
    plugin=source/'plugins/kotlin/src/kvik/plugins/kotlin/KotlinBuildPlugin.kt';original_plugin=plugin.read_text()
    def plugin_version(value):
        return original_plugin.replace('obj("type" to "object".json(),','obj("description" to "'+value+'".json(), "type" to "object".json(),')
    def project(label):
        path=evidence/label;path.mkdir()
        for name in ('kvik.json','kvik.lock.json'):shutil.copyfile(args.fixture/name,path/name)
        (path/'tools.json').write_text('{}')
        return path
    base=[sys.executable,str(source/'kvik'),'--java-home',str(args.java_home.resolve()),'--konan-home',str(args.konan_home.resolve()),
          '--konan-data',str(Path.home()/'.konan'),'--cache-dir',str(cache),'--artifact-cache','/Users/yoda/dev/pet/kvik/.kvik/tool-artifacts','--offline']
    schema=json.loads((ROOT/'schemas/v1/session.json').read_text());jsonschema.Draft202012Validator.check_schema(schema)
    registry=Registry().with_resource('https://kvik.dev/schemas/v1/bootstrap-launch.json',Resource.from_contents(json.loads((ROOT/'schemas/v1/bootstrap-launch.json').read_text())))
    validate=jsonschema.Draft202012Validator({**schema,'$ref':'#/$defs/event'},registry=registry).validate
    validate_resume=jsonschema.Draft202012Validator({**schema,'$ref':'#/$defs/resume'},registry=registry).validate
    processes=[]
    def cleanup():
        for process in processes:
            if process.poll() is None:process.kill();process.wait(timeout=10)
    atexit.register(cleanup)
    def alive(pid):
        result=subprocess.run(['ps','-p',str(pid),'-o','stat='],capture_output=True,text=True)
        return result.returncode==0 and not result.stdout.strip().startswith('Z')
    def descendants(pid):
        result=subprocess.run(['ps','-axo','pid=,ppid=,command='],capture_output=True,text=True,check=True)
        rows=[line.split(None,2) for line in result.stdout.splitlines()];found={pid}
        while True:
            more=found|{int(row[0]) for row in rows if int(row[1]) in found}
            if more==found:return {int(row[0]):row[2] for row in rows if int(row[0]) in found}
            found=more
    class Session:
        def __init__(self,label,no_build=False,direct=False):
            self.path=project(label);self.events=[];self.queue=queue.Queue();self.sequences={};self.tracked=set()
            command=([str(runtime)] if direct else [*base,*(['--no-build'] if no_build else []),'--'])+[
                'dev','--control','jsonl','--watch-ms','200','--project',str(self.path/'kvik.json'),
                '--tools',str(self.path/'tools.json'),'--module','consumer','--target','macosArm64']
            (self.path/'command.json').write_text(json.dumps(command))
            self.log=(self.path/'events.jsonl').open('w');self.err=(self.path/'stderr.log').open('w')
            self.proc=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.err,text=True,bufsize=1)
            self.pid=self.proc.pid;processes.append(self.proc)
            def read():
                try:
                    for line in self.proc.stdout:
                        self.log.write(line);self.log.flush();self.queue.put(json.loads(line))
                finally:self.queue.put(None)
            self.thread=threading.Thread(target=read,daemon=True);self.thread.start()
            self.start=self.wait('session-started')
        def raw(self,value):self.proc.stdin.write(value);self.proc.stdin.flush()
        def send(self,command):self.raw(json.dumps(dict(schemaVersion=1,requestId=command,command=command))+'\n')
        def wait(self,kind,predicate=lambda e:True,timeout=240):
            deadline=time.monotonic()+timeout
            while True:
                event=self.queue.get(timeout=max(.01,deadline-time.monotonic()))
                assert event is not None,(kind,self.proc.poll(),self.events[-5:])
                validate(event);sid=event['sessionId'];self.sequences[sid]=self.sequences.get(sid,0)+1
                assert event['sequence']==self.sequences[sid];self.events.append(event)
                if event['type']==kind and predicate(event):return event
        def finish(self,reason='q',expected=0):
            self.tracked.update(descendants(self.pid))
            if reason=='q':self.send('q')
            elif reason=='eof':self.proc.stdin.close()
            elif reason=='kill':self.proc.kill()
            started=time.monotonic();self.proc.wait(timeout=10);assert self.proc.returncode==expected
            self.thread.join(timeout=5);self.log.close();self.err.close()
            for pid in self.tracked:
                deadline=time.monotonic()+5
                while alive(pid) and time.monotonic()<deadline:time.sleep(.02)
                assert not alive(pid),('surviving process',pid)
            return time.monotonic()-started
    direct=Session('direct',direct=True)
    assert 'source-recomposition-unsupported-without-bootstrap-context' in direct.start['payload']['limitations']
    direct.finish()
    s=Session('restart');s.wait('operation-finished')
    assert 'source-recomposition' in s.start['payload']['capabilities']
    initial=s.start['payload']['composition'];project_bytes=(s.path/'kvik.json').read_bytes()
    plugin.write_text(plugin_version('recomposition-v1'))
    s.wait('rebuild-started');(s.path/'kvik.json').write_text('{broken')
    s.send('t');s.send('r')
    s.raw('{"schemaVersion":1,"requestId":"carried","command":"sta')
    boundary=s.wait('session-restarting',timeout=600)
    resumed=s.wait('session-started',timeout=30)
    assert s.proc.pid==s.pid and resumed['payload']['previousSessionDirectory']==s.start['payload']['directory']
    assert resumed['payload']['composition']!=initial
    handoff=json.loads((Path(resumed['payload']['directory'])/'handoff.json').read_text())
    validate_resume(handoff)
    assert handoff['pending']=='test' and bytes(handoff['partialInput']).endswith(b'"sta')
    s.raw('tus"}\n');s.wait('control',lambda e:e['payload']['requestId']=='carried')
    operation=s.wait('operation-started');assert operation['payload']['operation']=='test'
    s.wait('operation-finished',lambda e:e['payload']['exitCode']==2)
    info=json.loads(subprocess.run([boundary['payload']['runtime'],'runtime-info'],capture_output=True,text=True,check=True).stdout)
    assert info['plugins'][0]['schema']['description']=='recomposition-v1'
    (s.path/'kvik.json').write_bytes(project_bytes);s.wait('operation-finished')
    plugin.write_text(plugin_version('recomposition-v1')+'\nTHIS IS NOT KOTLIN\n')
    failure=s.wait('rebuild-finished',lambda e:not e['payload']['accepted'] and not e['payload']['cancelled'] and not e['payload']['timedOut'],timeout=600)
    assert 'Native compiler failed' in (Path(failure['payload']['evidenceDirectory'])/'stderr.log').read_text()
    s.send('status');status=s.wait('control');assert not status['payload']['compositionReady']
    plugin.write_text(plugin_version('recomposition-v2'));s.send('t')
    s.wait('session-restarting',timeout=600);third=s.wait('session-started')
    assert third['payload']['previousSessionDirectory']==resumed['payload']['directory']
    s.wait('operation-finished');s.finish()
    print('PASS actual source plugin edit, invalid project handoff, compiler failure and repair',evidence,flush=True)
    partial=Session('partial-quit');partial.wait('operation-finished')
    partial.raw('{"schemaVersion":1,"requestId":"partial-q","command":"q')
    plugin.write_text(plugin_version('recomposition-v1'))
    partial.wait('session-restarting');partial.wait('session-started')
    partial.raw('"}\n');partial.finish(reason=None)
    plugin.write_text(plugin_version('recomposition-v2'))
    restricted=Session('no-build',no_build=True);restricted.wait('operation-finished')
    plugin.write_text(plugin_version('recomposition-v3'))
    denied=restricted.wait('rebuild-finished',lambda e:not e['payload']['accepted'])
    log=(Path(denied['payload']['evidenceDirectory'])/'stderr.log').read_text()
    assert '--no-build' in log and 'runtime-building' not in log
    restricted.send('status');assert not restricted.wait('control')['payload']['compositionReady'];restricted.finish()
    plugin.write_text(plugin_version('recomposition-v2'))
    first,second=Session('concurrent-a'),Session('concurrent-b')
    first.wait('operation-finished');second.wait('operation-finished')
    plugin.write_text(plugin_version('recomposition-v3'))
    first.wait('rebuild-started');second.wait('rebuild-started')
    second.finish(reason='eof')
    first.send('status');first.wait('control')
    deadline=time.monotonic()+180
    while time.monotonic()<deadline:
        tree=descendants(first.pid)
        if any('K2Native' in cmd or '/bin/konanc' in cmd for cmd in tree.values()):break
        time.sleep(.1)
    else:raise AssertionError('No actual owned Native compiler observed')
    first.tracked.update(tree)
    cancelled=first.finish();assert cancelled<3,cancelled
    summary=dict(initialComposition=initial,firstReplacement=resumed['payload']['composition'],secondReplacement=third['payload']['composition'],
                 failedRebuild=failure['payload'],noBuild=denied['payload'],cancelDuringCompilerSeconds=cancelled,pid=s.pid)
    (evidence/'summary.json').write_text(json.dumps(summary,indent=2))
    print('PASS no-build policy, concurrent sessions and nested compiler cancellation',evidence,flush=True)

if __name__=='__main__':main()
