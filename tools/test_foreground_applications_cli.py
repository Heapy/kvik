#!/usr/bin/env python3
"""Real public JVM + Native foreground application reload/test/ownership acceptance.

Uses unchanged one-shot program sources plus two explicit test fixtures. Synthetic
activation workers are not used. Source-bootstrap handoff is a separate gate.
"""
import argparse
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

import test_application_cli as base
from test_foreground_applications import table, descendants

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime',type=Path,required=True)
    parser.add_argument('--schema-directory',type=Path,default=ROOT/'schemas/v1')
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--konan-home',type=Path,required=True)
    parser.add_argument('--artifact-cache',type=Path,default=ROOT/'.kvik/tool-artifacts')
    parser.add_argument('--prepare-only',action='store_true')
    args=parser.parse_args()
    runtime=args.runtime.resolve(strict=True)
    if not args.prepare_only:table()
    output=ROOT/'.kvik/tests/foreground-applications-cli';output.mkdir(parents=True,exist_ok=True)
    case=Path(tempfile.mkdtemp(prefix='case-',dir=output)).resolve();project=case/'project'
    fixture=ROOT/'tests/application-cli/fixture';inventory=base.inventory(fixture)
    shutil.copytree(fixture,project)
    test_fixture=ROOT/'tests/foreground-applications/fixture';tests=base.inventory(test_fixture)
    shutil.copytree(test_fixture,project/'app/tests')
    document=base.project_document(project);app=next(m for m in document['modules'] if m['id']=='app')
    for target in app['targets']:
        name=target['name'];test=name+'Tests';target['test']=test
        if name=='jvm':target['testSettings']=dict(classes=['fixture.ApplicationTests'])
        app['sourceSets'].append(dict(name=test,directories=['app/tests/'+('jvm' if name=='jvm' else 'native')],resources=[],dependsOn=[name],
            dependencies=[dict(coordinate='org.junit.jupiter:junit-jupiter-api:5.13.4')] if name=='jvm' else []))
    base.write_json(project/'kvik.json',document);(project/'literal.args').write_text('MUST_NOT_EXPAND\n')
    developer=Path('/Applications/Xcode.app/Contents/Developer');data=Path.home()/'.konan'
    tools=dict(schemaVersion=1,toolLock=json.loads((ROOT/'bootstrap/toolchains.lock.json').read_bytes()),
        roots=dict(jdk=str(args.java_home.resolve()),kotlinNative=str(args.konan_home.resolve()),llvm=str(data/'dependencies/llvm-21-aarch64-macos-essentials-97'),
            libffi=str(data/'dependencies/libffi-3.3-1-macos-arm64'),sdk=str((developer/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk').resolve()),
            xcodeToolchain=str(developer/'Toolchains/XcodeDefault.xctoolchain')),konanData=str(data),compilerArtifacts=str(args.artifact_cache.resolve()))
    base.write_json(project/'tools.json',tools)
    registry=Registry();schemas={}
    for name in ('session','contracts','bootstrap-launch'):
        raw=(args.schema_directory/f'{name}.json').read_bytes();(case/f'{name}.json').write_bytes(raw);schemas[name]=json.loads(raw)
        registry=registry.with_resource(schemas[name].get('$id',f'https://kvik.dev/schemas/v1/{name}.json'),Resource.from_contents(schemas[name]))
    def validate(value,kind):jsonschema.Draft202012Validator({'$defs':schemas['contracts']['$defs'],'$ref':'#/$defs/'+kind}).validate(value)
    validate(document,'project');validate(tools,'toolInstallation')
    session_validator=jsonschema.Draft202012Validator({**schemas['session'],'$ref':'#/$defs/event'},registry=registry)
    base.write_json(case/'invocation.json',dict(runtime=str(runtime),runtimeDigest=base.digest(runtime.read_bytes()),fixture=inventory,tests=tests,
        harness=base.digest(Path(__file__).read_bytes()),schemas={name:base.digest((case/f'{name}.json').read_bytes()) for name in schemas},prepareOnly=args.prepare_only))
    print('Evidence:',case,flush=True)
    if args.prepare_only:return
    environment=dict(os.environ,AMBIENT_APPLICATION_POISON='must-not-propagate',JAVA_TOOL_OPTIONS='-Dapplication.ambient=forbidden')
    environment.pop('KVIK_BOOTSTRAP_LAUNCH',None);environment.pop('KVIK_BOOTSTRAP_OFFLINE',None)
    shared=['--project',str(project/'kvik.json'),'--store',str(case/'store'),*[x for pin in base.PINS for x in ('--pin',pin)]]
    command=[str(runtime),'resolve',*shared];base.write_json(case/'resolve.command.json',command)
    resolved=subprocess.run(command,cwd=case,env=environment,capture_output=True)
    (case/'resolve.stdout.json').write_bytes(resolved.stdout);(case/'resolve.stderr.log').write_bytes(resolved.stderr)
    assert resolved.returncode==0,resolved.stderr.decode();validate(json.loads(resolved.stdout),'dependencyResolution')
    summaries=[];sessions=[]
    def effects(target):
        path=project/f'effects-{target}.log';return path.read_text().splitlines() if path.exists() else []
    def wait_absent(pids,groups,seconds=10):
        deadline=time.monotonic()+seconds
        while True:
            rows=[row for row in table() if row[0] in pids or row[2] in groups]
            if not rows:return
            assert time.monotonic()<deadline,rows;time.sleep(.02)
    class Session:
        def __init__(self,target,label):
            self.target=target;self.path=case/label;self.path.mkdir();self.events=[];self.messages=queue.Queue();self.sequence={};self.pids=set();self.groups=set()
            (project/f'mode-{target}.txt').write_text('wait:'+label)
            command=[str(runtime),'dev','--mode','application','--control','jsonl','--watch-ms','500',*shared,'--tools',str(project/'tools.json'),
                '--module','app','--target',target,'--jobs','1','--offline']
            base.write_json(self.path/'command.json',command)
            self.log=(self.path/'stdout.jsonl').open('w');self.err=(self.path/'stderr.log').open('w')
            self.process=subprocess.Popen(command,cwd=case,env=environment,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.err,text=True,bufsize=1)
            sessions.append(self)
            def reader():
                for line in self.process.stdout:self.log.write(line);self.log.flush();self.messages.put(line)
                self.messages.put(None)
            threading.Thread(target=reader,daemon=True).start();self.wait('session-started')
        def next(self,timeout):
            line=self.messages.get(timeout=timeout);assert line is not None,(self.path,self.process.poll(),self.events[-6:])
            event=json.loads(line);session_validator.validate(event);sid=event['sessionId'];self.sequence[sid]=self.sequence.get(sid,0)+1;assert event['sequence']==self.sequence[sid]
            self.events.append(event);return event
        def wait(self,kind,predicate=lambda p:True,seconds=1200):
            deadline=time.monotonic()+seconds
            while True:
                event=self.next(max(.01,deadline-time.monotonic()))
                if event['type']==kind and predicate(event['payload']):return event['payload']
        def control(self,value):
            self.process.stdin.write(json.dumps(dict(schemaVersion=1,requestId=str(len(self.events)),command=value))+'\n');self.process.stdin.flush()
        def status(self):self.control('status');return self.wait('control',lambda p:p['command']=='status')
        def capture(self):
            pids,groups=descendants(self.process.pid);self.pids |= pids;self.groups |= groups
            # The active tree includes nested stage/application guardians and separate PGIDs.
            assert len(pids)>=6,(pids,groups);assert len(groups)>=2,(pids,groups)
            return pids-{self.process.pid},groups
        def started(self):
            value=self.wait('application-started');ready=next(e['payload']['ready'] for e in self.events if e['type']=='application-ready' and e['payload']['generation']==value['generation'])
            run=Path(ready['preparationDirectory']);record=json.loads((run/'verification.json').read_bytes());validate(record,'verification')
            assert record['status']=='passed' and record['requestedChecks']==[f'app:{self.target}:application']
            assert base.digest((run/'verification.json').read_bytes())==ready['verification']
            assert base.digest((run/'prepared.json').read_bytes())==ready['descriptor']
            started=value['started'];execution=Path(started['executionDirectory']);events=[json.loads(line) for line in (execution/'events.jsonl').read_text().splitlines()]
            for event in events:validate(event,'event')
            assert events[0]['type']=='application-started'
            request=json.loads((execution/'request.json').read_bytes());assert request['timeoutMillis']==0
            assert request['environment']['APP_DECLARED']=='first' and 'AMBIENT_APPLICATION_POISON' not in request['environment']
            assert request['workingDirectory']==str(project)
            stdout=execution/'application.stdout';deadline=time.monotonic()+10
            while 'runtime=runtime-external' not in stdout.read_text():assert time.monotonic()<deadline;time.sleep(.02)
            return dict(ready=ready,record=record,execution=str(execution),generation=value['generation'])
        def finish(self,how='q'):
            self.capture();started=time.monotonic()
            if how=='q':self.control('q')
            elif how=='kill':self.process.kill()
            elif how=='int':self.process.send_signal(signal.SIGINT)
            elif how=='term':self.process.terminate()
            self.process.wait(timeout=15);expected=-9 if how=='kill' else 130 if how in ('int','term') else 0
            assert self.process.returncode==expected
            wait_absent(self.pids,self.groups);self.log.close();self.err.close()
            base.write_json(self.path/'result.json',dict(exitCode=self.process.returncode,cleanupSeconds=time.monotonic()-started,pids=sorted(self.pids),groups=sorted(self.groups)))
    try:
        for target in base.TARGETS:
            s=Session(target,target+'-reload');initial=s.started();initial_effects=len(effects(target));old_pids,old_groups=s.capture()
            s.control('r');warm=s.started()
            # No effects from the replacement may overlap any old guardian, app, or descendant.
            assert not [row for row in table() if row[0] in old_pids or row[2] in old_groups]
            assert len(effects(target))==initial_effects+1
            assert all(result['status']=='local-hit' for result in warm['record']['actions'])
            current=s.status();assert current['application']['active']['generation']==warm['generation']
            source=project/'app'/('jvm' if target=='jvm' else 'native')/'Application.kt';original=source.read_bytes()
            source.write_bytes(original+b'\nTHIS IS NOT VALID KOTLIN\n');s.wait('application-stale')
            s.wait('application-finished',lambda p:not p['started'] and p['exitCode']!=0)
            assert s.status()['application']['active'] is not None and len(effects(target))==initial_effects+1
            source.write_bytes(original);repaired=s.started();assert len(effects(target))==initial_effects+2
            before=len(effects(target));s.control('t');finished=s.wait('operation-finished',lambda p:p['operation']=='test')
            assert finished['exitCode']==0 and not finished['cancelled']
            records=list((Path(finished['evidenceDirectory'])/'runs').glob('*/verification.json'));assert len(records)==1
            tested=json.loads(records[0].read_bytes());validate(tested,'verification')
            assert tested['status']=='passed' and tested['testCounts']['executed']==1 and tested['testCounts']['failed']==0
            assert len(effects(target))==before and s.status()['application']['active'] is not None
            s.finish('int')
            summaries.append(dict(target=target,initial=initial,warm=warm,repaired=repaired,test= str(records[0])))
        # Both targets are simultaneously live; killing one owner must not disturb the other.
        one=Session('jvm','independent-jvm');one.started();two=Session('macosArm64','independent-native');two.started()
        one.finish('kill');assert two.process.poll() is None and two.status()['application']['active'] is not None;two.finish('term')
    finally:
        for session in sessions:
            if session.process.poll() is None:session.process.kill();session.process.wait(timeout=15)
    assert base.inventory(fixture)==inventory and base.inventory(test_fixture)==tests
    assert all(base.inventory(project)[p]==value for p,value in inventory.items())
    base.write_json(case/'summary.json',dict(passed=True,scope='actual JVM and Native public foreground reload/tests/ownership; source-bootstrap handoff separate',results=summaries,
        runtimeDigest=base.digest(runtime.read_bytes()),fixture=inventory,tests=tests))
    print('PASS public foreground applications',case,flush=True)

if __name__=='__main__':main()
