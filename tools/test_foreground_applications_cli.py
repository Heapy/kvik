#!/usr/bin/env python3
"""Real public JVM + Native foreground application reload/test/ownership acceptance.

Uses unchanged one-shot program sources plus two explicit test fixtures. Synthetic
activation workers are not used. Source-bootstrap handoff is a separate gate.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import queue
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]


def wait_application_effect(path,pid,arguments,environment_value,seconds=10):
    expected=f'{pid}:'+('|'.join(value.encode('utf-8').hex() for value in arguments))+':'+environment_value
    deadline=time.monotonic()+seconds
    while True:
        raw=path.read_text() if path.exists() else ''
        # stdout is emitted before this append in both fixture applications.
        # Require its complete newline-terminated record for this execution PID.
        lines=raw.splitlines() if raw.endswith('\n') else raw[:raw.rfind('\n')+1].splitlines()
        matches=[(index,line) for index,line in enumerate(lines) if line.startswith(f'{pid}:')]
        if matches:
            assert [line for _,line in matches]==[expected],f'Application effect mismatch: expected one {expected!r}, observed {matches!r}'
            return dict(pid=pid,line=expected,lineNumber=matches[0][0]+1)
        if time.monotonic()>=deadline:
            raise TimeoutError(f'No complete effect for application PID {pid} in {path}; expected={expected!r}, tail={raw[-1000:]!r}')
        time.sleep(.01)


def require_cached_preparation(initial,recovered):
    expected=initial['actions'];actual=recovered['actions']
    assert expected and actual,'Application preparation actions must not be empty'
    def keys(actions):
        result={action['actionId']:action['key'] for action in actions}
        assert len(result)==len(actions),'Duplicate application preparation action'
        return result
    assert keys(actual)==keys(expected),'Recovered preparation changed action IDs or keys'
    assert all(action['status']=='local_hit' for action in actual),'Recovery executed a preparation action'


def process_identities():
    rows={}
    for line in subprocess.run(['/bin/ps','-axo','pid=,ppid=,pgid=,stat=,lstart='],
                               check=True,capture_output=True,text=True,timeout=2).stdout.splitlines():
        pid,parent,group,state,birth=line.split(None,4)
        pid,parent,group=map(int,(pid,parent,group))
        assert pid>0 and parent>=0 and group>=0 and pid not in rows,'Invalid process identity table'
        rows[pid]=dict(pid=pid,parent=parent,group=group,state=state,birth=birth)
    return rows


def application_identity(ownership,rows,expected=None):
    chain=[ownership[role] for role in ('application','inner','stage','outer','session')]
    assert len(set(chain))==5 and all(pid>1 for pid in chain),'Invalid application ownership chain'
    identity=[]
    for index,pid in enumerate(chain):
        row=rows[pid]
        assert row['birth'] and not row['state'].startswith('Z'),'Application ownership is not live'
        if index<len(chain)-1:
            assert row['parent']==chain[index+1],'Application parent chain changed'
            assert row['group']==ownership['rows'][str(pid)][1],'Application ownership group changed'
        identity.append({key:row[key] for key in ('pid','parent','group','birth')})
    if expected is not None:assert identity==expected,'Application process birth/ownership changed'
    return identity


def signal_application(ownership,expected):
    # No status wait or file writes between the final live-chain/birth check and
    # signalling. Darwin exposes no pidfd: this is a checked live fixture PID,
    # not a claim of an atomic OS identity-and-signal operation.
    identity=application_identity(ownership,process_identities(),expected)
    os.kill(ownership['application'],signal.SIGKILL)
    return identity


def main():
    # The pure lifecycle guards also run without the acceptance environment.
    # Schema validation and real process fixtures are needed only by this CLI.
    import jsonschema
    from referencing import Registry, Resource
    import test_application_cli as base
    from test_foreground_applications import table, descendants
    from foreground_measurements import ProcessSampler, ControlTimings

    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime',type=Path,required=True)
    parser.add_argument('--schema-directory',type=Path,default=ROOT/'schemas/v1')
    parser.add_argument('--java-home',type=Path,required=True)
    parser.add_argument('--konan-home',type=Path,required=True)
    parser.add_argument('--artifact-cache',type=Path,default=ROOT/'.kvik/tool-artifacts')
    parser.add_argument('--prepare-only',action='store_true')
    parser.add_argument('--host-name',default=platform.node())
    parser.add_argument('--source-handoff',action='store_true',help='Also compile changed copied runtime sources and prove JVM application handoff/stale-pointer fallback')
    args=parser.parse_args()
    runtime=args.runtime.resolve(strict=True)
    if not args.prepare_only:table()
    output=ROOT/'.kvik/tests/foreground-applications-cli';output.mkdir(parents=True,exist_ok=True)
    case=Path(tempfile.mkdtemp(prefix='case-',dir=output)).resolve();project=case/'project'
    fixture=ROOT/'tests/application-cli/fixture';inventory=base.inventory(fixture)
    shutil.copytree(fixture,project)
    test_fixture=ROOT/'tests/foreground-applications/fixture';tests=base.inventory(test_fixture)
    witness_fixture=ROOT/'tests/foreground-applications/witness';witnesses=base.inventory(witness_fixture)
    shutil.copytree(test_fixture,project/'app/tests')
    for language in ('jvm','native'):shutil.copyfile(witness_fixture/language/'ApplicationWitness.kt',project/'app'/language/'ApplicationWitness.kt')
    document=base.project_document(project);app=next(m for m in document['modules'] if m['id']=='app')
    for target in app['targets']:
        name=target['name'];test=name+'Tests';target['test']=test
        target['application']['entryPoint']='fixture.witness.ApplicationWitnessKt' if name=='jvm' else 'fixture.witness.launchWitness'
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
    base.write_json(case/'invocation.json',dict(runtime=str(runtime),runtimeDigest=base.digest(runtime.read_bytes()),fixture=inventory,tests=tests,witnesses=witnesses,
        harness=base.digest(Path(__file__).read_bytes()),measurementHelper=base.digest((ROOT/'tools/foreground_measurements.py').read_bytes()),
        schemas={name:base.digest((case/f'{name}.json').read_bytes()) for name in schemas},prepareOnly=args.prepare_only))
    print('Evidence:',case,flush=True)
    if args.prepare_only:return
    environment=dict(os.environ,AMBIENT_APPLICATION_POISON='must-not-propagate',JAVA_TOOL_OPTIONS='-Dapplication.ambient=forbidden')
    environment.pop('KVIK_BOOTSTRAP_LAUNCH',None);environment.pop('KVIK_BOOTSTRAP_OFFLINE',None)
    shared=['--project',str(project/'kvik.json'),'--store',str(case/'store'),*[x for pin in base.PINS for x in ('--pin',pin)]]
    command=[str(runtime),'resolve',*shared];base.write_json(case/'resolve.command.json',command)
    resolved=subprocess.run(command,cwd=case,env=environment,capture_output=True)
    (case/'resolve.stdout.json').write_bytes(resolved.stdout);(case/'resolve.stderr.log').write_bytes(resolved.stderr)
    assert resolved.returncode==0,resolved.stderr.decode();validate(json.loads(resolved.stdout),'dependencyResolution')
    summaries=[];sessions=[];restore_sources={}
    primary_failure=None
    def effects(target):
        path=project/f'effects-{target}.log';return path.read_text().splitlines() if path.exists() else []
    def wait_absent(pids,groups,seconds=10):
        deadline=time.monotonic()+seconds
        while True:
            rows=[row for row in table() if row[0] in pids or row[2] in groups]
            if not rows:return
            assert time.monotonic()<deadline,rows;time.sleep(.02)
    class Session:
        def __init__(self,target,label,bootstrap=None):
            self.target=target;self.path=case/label;self.path.mkdir();self.events=[];self.messages=queue.Queue();self.sequence={};self.pids=set();self.groups=set()
            self.timings=ControlTimings();self.control_sequence=0;self.reload_measurements=[];self.resource_summary=None
            (project/f'mode-{target}.txt').write_text('wait:'+label)
            command=([*bootstrap,'--'] if bootstrap else [str(runtime)])+['dev','--mode','application','--control','jsonl','--watch-ms','500',*shared,'--tools',str(project/'tools.json'),
                '--module','app','--target',target,'--jobs','1','--offline']
            base.write_json(self.path/'command.json',command)
            self.log=(self.path/'stdout.jsonl').open('w');self.err=(self.path/'stderr.log').open('w')
            self.process=subprocess.Popen(command,cwd=case,env=environment,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.err,text=True,bufsize=1)
            sessions.append(self)
            self.sampler=ProcessSampler(self.process,self.path/'process-samples.jsonl')
            def reader():
                for line in self.process.stdout:
                    received=time.monotonic_ns();self.log.write(line);self.log.flush();self.messages.put((line,received))
                self.messages.put(None)
            self.reader=threading.Thread(target=reader,daemon=True);self.reader.start();self.wait('session-started')
        def next(self,timeout):
            message=self.messages.get(timeout=timeout);assert message is not None,(self.path,self.process.poll(),self.events[-6:])
            return self.accept(message)
        def accept(self,message):
            line,self.received_nanos=message
            event=json.loads(line);session_validator.validate(event);sid=event['sessionId'];self.sequence[sid]=self.sequence.get(sid,0)+1;assert event['sequence']==self.sequence[sid]
            self.timings.observed(event,self.received_nanos)
            self.events.append(event);return event
        def drain(self):
            while not self.messages.empty():
                message=self.messages.get_nowait()
                if message is None:break
                self.accept(message)
        def wait(self,kind,predicate=lambda p:True,seconds=1200):
            deadline=time.monotonic()+seconds
            while True:
                remaining=deadline-time.monotonic()
                if remaining<=0:raise TimeoutError(f'{self.path}: no {kind} within {seconds}s')
                event=self.next(remaining)
                if event['type']==kind and predicate(event['payload']):return event['payload']
        def control(self,value):
            self.control_sequence+=1;request='measured-'+str(self.control_sequence);started=time.monotonic_ns()
            self.timings.sent(request,value,started)
            self.process.stdin.write(json.dumps(dict(schemaVersion=1,requestId=request,command=value))+'\n');self.process.stdin.flush()
            return request,started
        def status(self,seconds=1200):
            request,_=self.control('status');return self.wait('control',lambda p:p['requestId']==request,seconds=seconds)
        def sample_busy_controls(self):
            self.wait('application-preparing')
            for _ in range(20):self.status()
            assert self.timings.summary()['duringBuild']['sampleCount']>=20,'Controls did not observe an active build'
        def capture(self):
            deadline=time.monotonic()+10
            files=[project/f'{self.path.name}-{role}.pid' for role in ('app','child','grandchild')]
            while True:
                rows={}
                for line in subprocess.run(['/bin/ps','-axo','pid=,ppid=,pgid=,command='],check=True,capture_output=True,text=True).stdout.splitlines():
                    pid,parent,group,command=line.strip().split(None,3);rows[int(pid)]=(int(parent),int(group),command)
                if all(path.exists() and path.read_text().strip().isdigit() for path in files):
                    application,child,grandchild=(int(path.read_text()) for path in files)
                    if {application,child,grandchild} <= rows.keys():break
                assert time.monotonic()<deadline, self.path
                time.sleep(.02)
            inner=rows[application][0];stage=rows[inner][0];outer=rows[stage][0]
            assert rows[outer][0]==self.process.pid
            assert '/guardian' in rows[outer][2] and '/guardian' in rows[inner][2]
            assert 'stage-application' in rows[stage][2] or 'stage-prepared' in rows[stage][2]
            assert rows[child][0]==application and rows[grandchild][0]==child
            owned={outer}
            while True:
                more=owned|{pid for pid,(parent,_,_) in rows.items() if parent in owned}
                if more==owned:break
                owned=more
            assert {outer,stage,inner,application,child,grandchild} <= owned
            owned_groups={rows[pid][1] for pid in owned if rows[pid][1] in owned}
            assert rows[application][1] in owned_groups and rows[stage][1] in owned_groups
            self.pids |= owned;self.groups |= owned_groups
            self.ownership=dict(session=self.process.pid,outer=outer,stage=stage,inner=inner,
                application=application,child=child,grandchild=grandchild,rows={str(pid):rows[pid] for pid in owned},groups=sorted(owned_groups))
            base.write_json(self.path/f'ownership-{len(self.events)}.json',self.ownership)
            return owned,owned_groups
        def witness(self):
            pids,groups=self.capture()
            text=''.join(f'pid {pid}\n' for pid in sorted(pids))+''.join(f'group {group}\n' for group in sorted(groups))
            (project/f'previous-ownership-{self.target}.txt').write_text(text)
            return pids,groups
        def started(self):
            value=self.wait('application-started')
            self.application_started_nanos=self.received_nanos
            matches=[e['payload'] for e in self.events if e['type']=='application-ready' and e['payload']['ready']['token']==value['started']['token']]
            assert len(matches)==1,matches
            ready_event=matches[0];ready=ready_event['ready']
            for field in ('generation','revision','composition','module','target','evidenceDirectory'):assert ready_event[field]==value[field]
            assert base.digest(base.canonical(ready))==value['started']['ready']
            run=Path(ready['preparationDirectory']);record=json.loads((run/'verification.json').read_bytes());validate(record,'verification')
            assert record['status']=='passed' and record['requestedChecks']==[f'app:{self.target}:application']
            assert base.digest((run/'verification.json').read_bytes())==ready['verification']
            assert base.digest((run/'prepared.json').read_bytes())==ready['descriptor']
            started=value['started'];execution=Path(started['executionDirectory']);deadline=time.monotonic()+10
            while True:
                raw=(execution/'events.jsonl').read_bytes()
                if raw.endswith(b'\n') and raw:break
                assert time.monotonic()<deadline;time.sleep(.01)
            events=[json.loads(line) for line in raw.splitlines()]
            for event in events:validate(event,'event')
            assert events[0]['type']=='application-started'
            request=json.loads((execution/'request.json').read_bytes());assert request['timeoutMillis']==0
            assert request['environment']['APP_DECLARED']=='first' and 'AMBIENT_APPLICATION_POISON' not in request['environment']
            assert request['workingDirectory']==str(project)
            expected=(project/f'previous-ownership-{self.target}.txt').read_text() if (project/f'previous-ownership-{self.target}.txt').exists() else ''
            witness_log=project/f'witness-{self.target}.log';deadline=time.monotonic()+10
            while not witness_log.exists() or not witness_log.read_text().endswith(expected.replace('\n',';')+'\n'):
                assert time.monotonic()<deadline;time.sleep(.01)
            stdout=execution/'application.stdout';deadline=time.monotonic()+10
            while 'runtime=runtime-external' not in stdout.read_text():assert time.monotonic()<deadline;time.sleep(.02)
            self.capture()
            inner_command=self.ownership['rows'][str(self.ownership['inner'])][2]
            assert str(stdout) in inner_command and str(execution/'application.stderr') in inner_command, \
                f'Captured application PID {self.ownership["application"]} belongs to another execution: expected {execution}, guardian={inner_command!r}'
            effect=wait_application_effect(project/f'effects-{self.target}.log',self.ownership['application'],base.LITERALS,'first')
            return dict(ready=ready,record=record,execution=str(execution),generation=value['generation'],effect=effect)
        def crash_application(self,active):
            pids,groups=self.witness();application=self.ownership['application']
            identity=application_identity(self.ownership,process_identities())
            status=self.status(seconds=5)
            assert self.process.poll() is None and application!=self.process.pid
            assert status['application']['active']['generation']==active['generation']
            injection=dict(kind='application-process-SIGKILL',signal=signal.SIGKILL,application=application,
                owner=self.process.pid,ownerAliveBefore=True,generation=active['generation'],ownership=self.ownership,statusBefore=status,
                capturedIdentity=identity,signalSent=False,cleanupDeadlineSeconds=15,
                scope='Signal actual running application, not compiler failure or session-owner loss')
            # Retain the precise fault target before mutation, including on failure.
            base.write_json(self.path/'crash.json',injection)
            started=time.monotonic();checked_identity=signal_application(self.ownership,identity)
            injection.update(signalSent=True,preSignalIdentity=checked_identity)
            base.write_json(self.path/'crash.json',injection)
            finished=self.wait('application-finished',lambda p:p['generation']==active['generation'],seconds=15)
            for field in ('operationId','generation','revision','composition','module','target','evidenceDirectory'):
                assert finished[field]==status['application']['active'][field]
            assert finished['started'] and finished['exitCode']==128+signal.SIGKILL
            assert not finished['cancelled'] and not finished['timedOut'] and not finished['stale']
            wait_absent(pids,groups,seconds=max(0,15-(time.monotonic()-started)))
            # Keep the audit and replacement witness, but never later signal
            # historical PIDs/groups once their absence has been established.
            self.pids.difference_update(pids);self.groups.difference_update(groups)
            cleanup_seconds=time.monotonic()-started
            assert cleanup_seconds<15 and self.process.poll() is None
            status=self.status(seconds=5)
            assert status['application']['active'] is None and status['application']['candidate'] is None
            execution=Path(active['execution']);raw=(execution/'events.jsonl').read_bytes()
            assert raw.endswith(b'\n')
            retained=[json.loads(line) for line in raw.splitlines()]
            for event in retained:validate(event,'event')
            assert [event['type'] for event in retained]==['application-started','application-finished']
            assert [event['sequence'] for event in retained]==[1,2]
            assert retained[0]['runId']==retained[1]['runId']
            terminal=retained[-1]['payload']
            assert terminal['exitCode']==128+signal.SIGKILL and not terminal['cancelled'] and not terminal['timedOut']
            assert terminal['preparationRun']==active['record']['runId']
            assert Path(terminal['stdout'])==execution/'application.stdout' and Path(terminal['stderr'])==execution/'application.stderr'
            injection.update(ownerAliveAfter=True,finished=finished,statusAfter=status,cleanupSeconds=cleanup_seconds,
                capturedOwnershipAbsent=True,executionEvents=base.digest(raw))
            base.write_json(self.path/'crash.json',injection)
            return injection
        def finish(self,how='q'):
            assert how in ('q','kill','int','term','eof'),how
            self.capture();started=time.monotonic()
            if how=='q':self.control('q')
            elif how=='kill':self.process.kill()
            elif how=='int':self.process.send_signal(signal.SIGINT)
            elif how=='term':self.process.terminate()
            elif how=='eof':self.process.stdin.close()
            self.process.wait(timeout=15);expected=-9 if how=='kill' else 130 if how in ('int','term') else 0
            assert self.process.returncode==expected
            wait_absent(self.pids,self.groups);self.reader.join(timeout=5);assert not self.reader.is_alive();self.log.close();self.err.close()
            cleanup_seconds=time.monotonic()-started
            self.drain()
            result=dict(method=how,exitCode=self.process.returncode,cleanupSeconds=cleanup_seconds,pids=sorted(self.pids),groups=sorted(self.groups),
                cleanupBudgetSeconds=5 if how=='kill' else 2,cleanupBudgetMet=cleanup_seconds<(5 if how=='kill' else 2),
                cleanupScope='command/signal/EOF through owner exit, captured descendant absence and output-reader drain')
            if how=='eof':
                session=next(event for event in reversed(self.events) if event['type']=='session-started')
                events=[event for event in self.events if event['sessionId']==session['sessionId']]
                assert [event['payload']['reason'] for event in events if event['type']=='closing']==['end-of-input']
                assert events[-1]['type']=='session-finished' and events[-1]['payload']['reason']=='end-of-input'
                active=next(event['payload'] for event in reversed(events) if event['type']=='application-started')
                terminal=[event['payload'] for event in events if event['type']=='application-finished' and event['payload']['generation']==active['generation']]
                assert len(terminal)==1 and terminal[0]['started'] and terminal[0]['cancelled'] and not terminal[0]['timedOut']
                journal=Path(session['payload']['directory'])/'session.jsonl';raw=journal.read_bytes()
                assert raw.endswith(b'\n') and [json.loads(line) for line in raw.splitlines()]==events
                result.update(reason='end-of-input',sessionJournal=base.digest(raw),applicationFinished=terminal[0])
            base.write_json(self.path/'result.json',result)
            self.write_measurements()
            return result
        def write_measurements(self):
            if self.resource_summary is None:self.resource_summary=self.sampler.close()
            value=dict(resources=self.resource_summary,controls=self.timings.summary(),reloads=self.reload_measurements)
            base.write_json(self.path/'measurements.json',value)
            return value
    try:
        for target in base.TARGETS:
            s=Session(target,target+'-reload');s.sample_busy_controls();initial=s.started();initial_effects=len(effects(target));old_pids,old_groups=s.witness()
            reload_request,reload_started=s.control('r');warm=s.started()
            s.reload_measurements.append(dict(requestId=reload_request,sentNanos=reload_started,receivedNanos=s.application_started_nanos,
                durationNanos=s.application_started_nanos-reload_started,generation=warm['generation'],
                scope='warm explicit reload through application-started event; includes old-application cleanup and new launch',
                actions=[dict(actionId=a['actionId'],status=a['status']) for a in warm['record']['actions']]))
            # The replacement witness checked exact old ownership before its first effect; also observe cleanup here.
            assert not [row for row in table() if row[0] in old_pids or row[2] in old_groups]
            assert len(effects(target))==initial_effects+1
            assert all(result['status']=='local_hit' for result in warm['record']['actions'])
            current=s.status();assert current['application']['active']['generation']==warm['generation']
            s.witness()
            source=project/'app'/('jvm' if target=='jvm' else 'native')/'Application.kt';original=source.read_bytes();restore_sources[source]=original
            source.write_bytes(original+b'\nTHIS IS NOT VALID KOTLIN\n');s.wait('application-stale')
            s.wait('application-finished',lambda p:not p['started'] and p['exitCode']!=0 and not p['cancelled'] and not p['timedOut'])
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
            # Kill the real application's PID while its foreground owner stays
            # responsive. The retained child/grandchild ownership must drain
            # before an explicit reload's first effect, then stdin EOF closes it.
            s=Session(target,target+'-application-crash-eof');active=s.started();before=len(effects(target))
            crash=s.crash_application(active)
            assert len(effects(target))==before
            s.control('r');recovered=s.started()
            assert recovered['generation']>active['generation']
            assert recovered['ready']['composition']==active['ready']['composition']
            assert len(effects(target))==before+1
            require_cached_preparation(active['record'],recovered['record'])
            assert s.process.pid==crash['owner'] and s.process.poll() is None
            assert s.status(seconds=5)['application']['active']['generation']==recovered['generation']
            eof=s.finish('eof')
            summaries.append(dict(target=target,applicationCrash=crash,recovered=recovered,eof=eof))
        # Both targets are simultaneously live; killing one owner must not disturb the other.
        one=Session('jvm','independent-jvm');one.started();two=Session('macosArm64','independent-native');two.started()
        one.finish('kill');assert two.process.poll() is None and two.status()['application']['active'] is not None;two.finish('term')
        if args.source_handoff:
            source_root=case/'source-bootstrap';source_root.mkdir()
            for name in ('bootstrap','core','cache','execution','resolution','integration','compiler','cli','plugins'):
                shutil.copytree(ROOT/name,source_root/name,ignore=shutil.ignore_patterns('__pycache__'))
            shutil.copyfile(ROOT/'kvik',source_root/'kvik')
            source_inventory=base.inventory(source_root);base.write_json(case/'source-bootstrap-inputs.json',source_inventory)
            bootstrap=[sys.executable,str(source_root/'kvik'),'--java-home',str(args.java_home.resolve()),'--konan-home',str(args.konan_home.resolve()),
                '--konan-data',str(data),'--cache-dir',str(runtime.parents[2]),'--artifact-cache',str(args.artifact_cache.resolve()),'--offline']
            plugin=source_root/'plugins/kotlin/src/kvik/plugins/kotlin/KotlinBuildPlugin.kt';plugin_original=plugin.read_bytes()
            s=Session('jvm','source-handoff',bootstrap=bootstrap);initial=s.started();original_pid=s.process.pid
            old_pids,old_groups=s.witness()
            plugin.write_bytes(plugin_original+b'\nTHIS IS INVALID RUNTIME KOTLIN\n')
            failure=s.wait('rebuild-finished',lambda p:not p['accepted'] and not p['cancelled'] and not p['timedOut'])
            assert s.status()['application']['active'] is not None
            assert 'Native compiler failed' in (Path(failure['evidenceDirectory'])/'stderr.log').read_text()
            assert old_pids <= {row[0] for row in table()}
            changed_plugin=plugin_original.decode().replace('Kotlin consumes the core project or standalone script authoring document.', 'foreground-application-handoff')
            assert changed_plugin!=plugin_original.decode();plugin.write_text(changed_plugin)
            source=project/'app/jvm/Application.kt';original=source.read_bytes();restore_sources[source]=original
            source.write_bytes(original+b'\nINVALID APPLICATION CANDIDATE\n')
            s.control('r');s.wait('rebuild-finished',lambda p:p['accepted'])
            s.wait('application-finished',lambda p:not p['started'] and p['exitCode']!=0 and not p['cancelled'] and not p['timedOut'])
            assert s.status()['application']['active'] is not None and old_pids <= {row[0] for row in table()}
            source.write_bytes(original);s.control('t');s.control('r')
            s.process.stdin.write('{"schemaVersion":1,"requestId":"handoff-partial","command":"sta');s.process.stdin.flush()
            restarting=s.wait('session-restarting')
            # Make the already validated pointer stale while exec/drain proceeds. Fresh fallback is required.
            source.write_bytes(original+b'\n// changed after prepared handoff\n')
            resumed=s.wait('session-started');assert s.process.pid==original_pid
            s.process.stdin.write('tus"}\n');s.process.stdin.flush()
            s.wait('control',lambda p:p['requestId']=='handoff-partial')
            s.wait('diagnostic',lambda p:'queued one fresh preparation' in p['message'])
            fresh=s.started();assert fresh['ready']['composition']!=initial['ready']['composition']
            assert not [row for row in table() if row[0] in old_pids or row[2] in old_groups]
            test_result=s.wait('operation-finished',lambda p:p['operation']=='test') if not any(e['type']=='operation-finished' and e['payload']['operation']=='test' for e in s.events) else next(e['payload'] for e in s.events if e['type']=='operation-finished' and e['payload']['operation']=='test')
            assert test_result['exitCode']==0
            handoff=json.loads((Path(resumed['directory'])/'handoff.json').read_bytes())
            jsonschema.Draft202012Validator({**schemas['session'],'$ref':'#/$defs/resume'},registry=registry).validate(handoff)
            assert handoff['schemaVersion']==3 and handoff['applicationModule']=='app' and handoff['applicationTarget']=='jvm'
            assert handoff['preparedApplication']['composition']==fresh['ready']['composition']
            assert 'test' in handoff['pendingIntents'] and bytes(handoff['partialInput']).endswith(b'"sta')
            info=subprocess.run([restarting['runtime'],'runtime-info'],capture_output=True,text=True,check=True)
            base.write_json(case/'source-handoff-runtime-info.json',json.loads(info.stdout))
            assert json.loads(info.stdout)['plugins'][0]['schema']['description']=='foreground-application-handoff'
            s.finish();source.write_bytes(original)
            summaries.append(dict(sourceHandoff=dict(initial=initial,replacement=fresh,pid=original_pid,continuation=handoff,
                scope='actual JVM application across compiled runtime handoff; Native application handoff not exercised')))
    except BaseException as failure:
        primary_failure=failure
        base.write_json(case/'failure.json',dict(type=type(failure).__name__,message=str(failure)))
        raise
    finally:
        cleanup=[]
        for session in sessions:
            try:
                if session.process.poll() is None:
                    try:
                        pids,groups=descendants(session.process.pid);session.pids |= pids;session.groups |= groups
                    finally:
                        session.process.kill();session.process.wait(timeout=15)
                wait_absent(session.pids,session.groups)
                session.reader.join(timeout=5);assert not session.reader.is_alive()
                if not session.log.closed:session.log.close()
                if not session.err.closed:session.err.close()
                if primary_failure is None:session.drain()
                session.write_measurements()
                cleanup.append(dict(session=session.process.pid,pids=sorted(session.pids),groups=sorted(session.groups),drained=True))
            except BaseException as failure:
                cleanup.append(dict(session=session.process.pid,pids=sorted(session.pids),groups=sorted(session.groups),drained=False,error=str(failure)))
                # Only the exact captured private groups are emergency-cleaned; retain the failed barrier.
                for group in session.groups:
                    try:os.killpg(group,signal.SIGKILL)
                    except ProcessLookupError:pass
                if primary_failure is not None:primary_failure.add_note('Cleanup failure: '+str(failure))
        for source,original in restore_sources.items():source.write_bytes(original)
        base.write_json(case/'cleanup.json',cleanup)
        if primary_failure is None:assert all(item['drained'] for item in cleanup),cleanup
    assert base.inventory(fixture)==inventory and base.inventory(test_fixture)==tests and base.inventory(witness_fixture)==witnesses
    assert all(base.inventory(project)[p]==value for p,value in inventory.items())
    base.write_json(case/'summary.json',dict(passed=True,scope='actual JVM and Native public foreground reload/tests/ownership, application SIGKILL with live owner and explicit recovery, EOF cleanup; optional JVM compiled-source handoff',results=summaries,
        runtimeDigest=base.digest(runtime.read_bytes()),fixture=inventory,tests=tests,
        host=dict(name=args.host_name,os=platform.system(),release=platform.release(),architecture=platform.machine(),logicalCpuCount=os.cpu_count()),
        measurements={s.path.name:s.write_measurements() for s in sessions}))
    print('PASS public foreground applications',case,flush=True)

if __name__=='__main__':main()
