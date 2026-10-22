#!/usr/bin/env python3
"""Cached source-graph foreground handoff plus a fresh production JVM consumer.

--prepare-only copies and validates compatible published runtimes without compiler
work. The full session requires a coordinated consumer compiler slot; its bootstrap
policy is always --offline --no-build, so it never recompiles the Native runtime.
"""
import argparse
import atexit
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

import jsonschema
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT/'tests/source-bootstrap'))
from fixture import canonical, digest, inventory
sys.path.insert(0, str(ROOT/'tests/source-graph-handoff'))
from session import Session, owned

DEFAULT_GRAPH = Path('/private/tmp/kvik-k14-source-bootstrap/.kvik/tests/source-bootstrap/case-kgcfr28z')
DEFAULT_CONSUMER = Path('/private/tmp/kvik-source-runtime-consumer-evidence/case-kyjizwh6')
EXPECTED_TEST = '[engine:junit-jupiter]/[class:fixture.PassingTests]/[method:dependencyAndResource()]'


def read(path): return json.loads(path.read_bytes())
def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical(value)+b'\n')
def atomic_bytes(path, data):
    temporary = path.with_name(path.name+'.next')
    temporary.write_bytes(data)
    os.replace(temporary,path)


def copy_project(source, destination):
    destination.mkdir()
    for name in ('kvik.json','kvik.lock.json','tools.json'):
        shutil.copyfile(source/name,destination/name)
    config = read(destination/'kvik.json')
    roots = {path for module in config['modules'] for item in module['sourceSets'] for path in item['directories']+item['resources']}
    for relative in sorted(roots):
        shutil.copytree(source/relative,destination/relative,dirs_exist_ok=True,symlinks=True)
    added = destination/'library/common/New.kt'
    assert added.read_bytes() == b'package fixture\nfun added() = 1\n', 'Unexpected historical stale-query fixture mutation'
    added.unlink()
    tests=(destination/'consumer/test/Tests.kt').read_text()
    assert 'fun dependencyAndResource()' in tests and 'assertEquals(resource, message())' in tests
    assert 'Files.writeString(Path.of("effects.txt"), "executed\\n")' in tests
    selected = [destination/name for name in ('kvik.json','kvik.lock.json','tools.json')]
    selected += [file for relative in sorted(roots) for file in (destination/relative).rglob('*') if file.is_file()]
    source_manifest = {path.relative_to(destination).as_posix():digest(path.read_bytes()) for path in selected}
    lock=read(destination/'kvik.lock.json'); blobs={}
    for selection in lock['selections']:
        for entry in selection['artifacts']+selection['metadata']:
            value=entry['digest']; name=value['hash']+'-'+str(value['sizeBytes'])
            original=source/'.kvik/cache/blobs'/name
            assert digest(original.read_bytes())==value
            target=destination/'.kvik/cache/blobs'/name;target.parent.mkdir(parents=True,exist_ok=True)
            if not target.exists():shutil.copyfile(original,target)
            blobs[name]=value
    assert not (destination/'.kvik/cache/actions').exists()
    return {'files':source_manifest,'dependencyBlobs':blobs,'excludedHistoricalFile':'library/common/New.kt'}


def prepare(case, graph, consumer):
    approved=graph/'attempt-pz7voq2r'
    gate=read(approved/'summary.json');assert gate['passed'] and gate['compiledRuntime'] and gate['listenerClosed']
    bootstrap=case/'trusted launcher';shutil.copytree(approved/'trusted-launcher',bootstrap)
    assert inventory(bootstrap)==gate['producer']['inventory']
    keys={name:gate['phases'][phase]['audit']['manifest']['composition'] for name,phase in [('v1','cold'),('v2','helper-mutation')]}
    runtime_inventories={}
    for name,key in keys.items():
        original=graph/'runtime-cache/executables'/key['hash']
        copied=case/'runtime cache/executables'/key['hash']
        shutil.copytree(original,copied)
        runtime_inventories[name]=inventory(original)
        assert runtime_inventories[name]==inventory(copied)
        manifest=read(copied/'manifest.json')
        assert manifest['composition']==key and manifest['executable']==digest((copied/'kvik.kexe').read_bytes())
        shutil.copyfile(approved/f'source-{name}.lock.json',case/f'source-{name}.lock.json')
    shutil.copytree(graph/'source-cache',case/'source cache')
    assert inventory(graph/'source-cache')==inventory(case/'source cache')
    shutil.copyfile(case/'source-v1.lock.json',case/'active source.lock.json')
    project=case/'project';project_proof=copy_project(consumer,project)
    real_tools=(project/'tools.json').read_bytes();(case/'valid-tools.json').write_bytes(real_tools)
    (project/'tools.json').write_text('{}')
    receipt={'keys':keys,'runtimeInventories':runtime_inventories,'producerInventory':inventory(bootstrap),'sourceCacheInventory':inventory(case/'source cache'),
        'project':project_proof,'originalGateSummary':digest((approved/'summary.json').read_bytes()),
        'originalConsumer':str(consumer),'originalGraph':str(graph)}
    write(case/'preparation.json',receipt)
    return receipt


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--graph-fixture',type=Path,default=DEFAULT_GRAPH)
    parser.add_argument('--consumer-fixture',type=Path,default=DEFAULT_CONSUMER)
    parser.add_argument('--artifact-cache',type=Path,default=Path('/Users/yoda/dev/pet/kvik/.kvik/tool-artifacts'))
    parser.add_argument('--prepare-only',action='store_true')
    parser.add_argument('--case',type=Path)
    args=parser.parse_args()
    if args.case:
        case=args.case.resolve(strict=True);prepared=read(case/'preparation.json')
        assert not (case/'project/.kvik/sessions').exists(), 'Use a fresh preparation; do not overwrite a previous live session gate'
    else:
        output=ROOT/'.kvik/tests/source-graph-handoff';output.mkdir(parents=True,exist_ok=True)
        case=Path(tempfile.mkdtemp(prefix='case-',dir=output)).resolve()
        prepared=prepare(case,args.graph_fixture.resolve(strict=True),args.consumer_fixture.resolve(strict=True))
    attempt=Path(tempfile.mkdtemp(prefix='attempt-',dir=case));print(attempt,flush=True)
    schemas={name:read(ROOT/'schemas/v1'/name) for name in ('session.json','contracts.json','bootstrap-launch.json')}
    registry=Registry().with_resource('https://kvik.dev/schemas/v1/bootstrap-launch.json',Resource.from_contents(schemas['bootstrap-launch.json']))
    def validate(value,kind,session=False):
        schema=schemas['session.json' if session else 'contracts.json']
        jsonschema.Draft202012Validator({**schema,'$ref':'#/$defs/'+kind},registry=registry).validate(value)
    project=case/'project';active=case/'active source.lock.json';bootstrap=case/'trusted launcher'
    cache=case/'runtime cache';source_cache=case/'source cache'
    python=str(Path(sys.executable).resolve())
    environment={key:os.environ[key] for key in ('HOME','TMPDIR') if key in os.environ}
    environment.update(PATH='/usr/bin:/bin:/usr/sbin:/sbin',LANG='C',LC_ALL='C',TZ='UTC')
    prefix=[python,str(bootstrap/'kvik'),'--java-home','/absent/jdk','--konan-home','/absent/native',
        '--konan-data',str(Path.home()/'.konan'),'--cache-dir',str(cache),'--artifact-cache',str(args.artifact_cache.resolve()),
        '--source-lock',str(active),'--source-cache',str(source_cache),'--offline','--no-build']
    expected_arguments=prefix[2:]
    summary={'passed':False,'nativeRuntimeRecompiled':False,'commands':[], 'preparation':digest((case/'preparation.json').read_bytes()),
        'harness':digest(Path(__file__).read_bytes()),'helper':digest((ROOT/'tests/source-graph-handoff/session.py').read_bytes())}
    def invoke(label,command,timeout=90):
        start=time.monotonic();result=subprocess.run(command,env=environment,capture_output=True,timeout=timeout)
        (attempt/(label+'.stdout')).write_bytes(result.stdout);(attempt/(label+'.stderr')).write_bytes(result.stderr)
        summary['commands'].append({'label':label,'argv':command,'environment':environment,'exitCode':result.returncode,
            'seconds':time.monotonic()-start,'stdout':digest(result.stdout),'stderr':digest(result.stderr)})
        write(attempt/'summary.json',summary)
        assert result.returncode==0,(label,result.returncode,result.stdout[-2000:],result.stderr[-2000:])
        return json.loads(result.stdout)
    sessions=[]
    def cleanup():
        for session in sessions:session.abort()
    atexit.register(cleanup)
    try:
        descriptors={}
        for version,value in [('v1','source-v1'),('v2','source-v2')]:
            atomic_bytes(active,(case/f'source-{version}.lock.json').read_bytes())
            descriptor=invoke('launch-'+version,[*prefix,'--print-launch'])
            bootstrap_events=[json.loads(line) for line in (attempt/('launch-'+version+'.stderr')).read_bytes().splitlines()]
            assert [event['kind'] for event in bootstrap_events]==['runtime-cache-hit']
            jsonschema.Draft202012Validator(schemas['bootstrap-launch.json']).validate(descriptor)
            assert descriptor['bootstrapArguments']==expected_arguments
            assert descriptor['composition']==prepared['keys'][version]
            assert descriptor['sourceRoot']==str(bootstrap) and descriptor['launcher']==str(bootstrap/'kvik')
            assert descriptor['runtime']==str(cache/'executables'/prepared['keys'][version]['hash']/'kvik.kexe')
            info=invoke('runtime-'+version,[descriptor['runtime'],'runtime-info'])
            assert info['composition']==prepared['keys'][version]['hash']
            assert {p['id']:p['schema'].get('description') for p in info['plugins'] if p['id'].startswith('example.')}=={'example.a':value,'example.b':value}
            descriptors[version]=descriptor
        atomic_bytes(active,(case/'source-v1.lock.json').read_bytes())
        summary['preflight']={'passed':True,'descriptors':descriptors,'noBuild':True,'missingCompilerPaths':True}
        if args.prepare_only:
            summary['passed']=True
            return
        def start(label):
            argv=[*prefix,'--','dev','--control','jsonl','--watch-ms','200','--project',str(project/'kvik.json'),
                  '--tools',str(project/'tools.json'),'--module','consumer','--target','jvm']
            session=Session(attempt/label,argv,environment,validate);sessions.append(session)
            initial=session.wait('session-started');assert initial['payload']['composition']==prepared['keys']['v1']
            finished=session.wait('operation-finished');assert finished['payload']['exitCode']!=0 and not finished['payload']['cancelled']
            return session,initial
        session,initial=start('handoff')
        pid=session.process.pid
        atomic_bytes(active,(case/'source-v2.lock.json').read_bytes())
        stale=session.wait('composition-observed',lambda e:e['payload']['state']=='stale')
        assert stale['payload']['composition']==prepared['keys']['v2']
        partial=b'{"schemaVersion":1,"requestId":"carried","command":"sta'
        batch=canonical({'schemaVersion':1,'requestId':'pending-test','command':'t'})+b'\n'+partial
        session.raw(batch)
        queued=session.wait('control',lambda e:e['payload']['requestId']=='pending-test')
        assert queued['payload']['pending']=='test' and not queued['payload']['compositionReady']
        atomic_bytes(project/'tools.json',(case/'valid-tools.json').read_bytes())
        boundary=session.wait('session-restarting');resumed=session.wait('session-started')
        assert session.process.pid==pid and resumed['sessionId']!=initial['sessionId']
        assert resumed['payload']['previousSessionDirectory']==initial['payload']['directory']
        assert resumed['payload']['composition']==prepared['keys']['v2']
        assert boundary['payload']['runtime']==descriptors['v2']['runtime']
        process_tree=owned(pid);assert process_tree[pid].startswith(descriptors['v2']['runtime']+' dev --resume '),process_tree
        handoff_path=Path(resumed['payload']['directory'])/'handoff.json';handoff=read(handoff_path)
        validate(handoff,'resume',True)
        assert handoff['schemaVersion']==1 and handoff['pending']=='test' and bytes(handoff['partialInput'])==partial
        assert handoff['launch']==descriptors['v2']
        assert handoff['arguments'][handoff['arguments'].index('--project')+1]==str(project/'kvik.json')
        assert handoff['arguments'][handoff['arguments'].index('--tools')+1]==str(project/'tools.json')
        assert '--resume-hash '+digest(handoff_path.read_bytes())['hash'] in process_tree[pid]
        session.raw(b'tus"}\n')
        carried=session.wait('control',lambda e:e['payload']['requestId']=='carried');assert carried['payload']['command']=='status'
        # The operation may start before the carried status response is consumed.
        started=next((e for e in session.events if e['sessionId']==resumed['sessionId'] and e['type']=='operation-started'),None)
        if started is None:started=session.wait('operation-started')
        assert started['payload']['operation']=='test'
        done=session.wait('operation-finished',lambda e:not e['payload']['cancelled'],timeout=300)
        assert done['payload']['exitCode']==0,done
        child=Path(done['payload']['evidenceDirectory']);run_bytes=(child/'stdout.jsonl').read_bytes()
        run_events=[json.loads(line) for line in run_bytes.splitlines()]
        for event in run_events:validate(event,'event')
        assert [event['sequence'] for event in run_events]==list(range(1,len(run_events)+1))
        record=run_events[-1]['payload'];validate(record,'verification')
        assert run_events[-1]['type']=='run-finished' and record['status']=='passed' and record['freshnessAtCompletion']=='current'
        assert record['composition']==prepared['keys']['v2']
        assert record['testCounts']=={'discovered':1,'executed':1,'failed':0,'skipped':0,'discoveryComplete':True}
        assert [test['id'] for test in record['tests']]==[EXPECTED_TEST]
        expected={'library:jvm:main','consumer:jvm:main','consumer:jvm:test-compile','consumer:jvm:test'}
        assert {action['actionId'] for action in record['actions']}==expected
        assert all(action['status']=='executed' for action in record['actions'])
        run=child/'runs'/record['runId'];assert read(run/'verification.json')==record and (run/'events.jsonl').read_bytes()==run_bytes
        completion=read(run/'completion.json')
        assert completion['journal']==digest(run_bytes) and completion['verification']==digest((run/'verification.json').read_bytes())
        assert completion['event']==digest(run_bytes.splitlines()[-1])
        effects=list(run.glob('kvik-run-*/effects.txt'));assert len(effects)==1 and effects[0].read_bytes()==b'executed\n'
        for action in record['actions']:
            for output in action['outputs']['files']:
                value=output['digest'];assert digest((project/'.kvik/cache/blobs'/(value['hash']+'-'+str(value['sizeBytes']))).read_bytes())==value
        action_docs=[read(path) for path in (run/'actions').glob('*.json')]
        assert {a['actionId'] for a in action_docs}==expected
        for doc in action_docs:assert digest(canonical(doc['action']))==doc['key']
        assert next(a for a in action_docs if a['actionId']=='consumer:jvm:test')['action']['cachePolicy']=='never'
        query=invoke('current-verification',[descriptors['v2']['runtime'],'verification','--run',str(run)],timeout=120)
        validate(query,'verificationQuery');assert query['freshness']=='current'
        summary['handoff']={'pid':pid,'initial':initial,'boundary':boundary,'resumed':resumed,'handoff':digest(handoff_path.read_bytes()),
            'partialInput':list(partial),'consumerRun':str(run),'record':record,'query':query,'effects':str(effects[0]),'cleanup':session.finish()}
        # An actual owned --print-launch child blocks on this private runtime cache mutex.
        atomic_bytes(active,(case/'source-v1.lock.json').read_bytes());atomic_bytes(project/'tools.json',b'{}')
        cancel,first=start('cancel')
        lock_file=cache/'locks'/(prepared['keys']['v2']['hash']+'.lock');lock_file.parent.mkdir(exist_ok=True)
        with lock_file.open('a+b') as mutex:
            fcntl.flock(mutex,fcntl.LOCK_EX)
            atomic_bytes(active,(case/'source-v2.lock.json').read_bytes())
            cancel.wait('composition-observed',lambda e:e['payload']['state']=='stale')
            rebuilding=cancel.wait('rebuild-started')
            command=read(Path(rebuilding['payload']['evidenceDirectory'])/'command.json')
            assert command==[*prefix,'--print-launch']
            deadline=time.monotonic()+5
            while True:
                tree=owned(cancel.process.pid)
                blocked={pid:cmd for pid,cmd in tree.items() if '--print-launch' in cmd and str(active) in cmd and str(bootstrap/'kvik') in cmd}
                if blocked:break
                assert time.monotonic()<deadline,tree
                time.sleep(.02)
            cancel.tracked.update(tree)
            cancel.send('status','blocked-status');state=cancel.wait('control',lambda e:e['payload']['requestId']=='blocked-status')
            assert not state['payload']['compositionReady']
            cancel_cleanup=cancel.finish('signal')
            assert not any(e['type']=='session-restarting' for e in cancel.events)
            summary['cancellation']={'blockedProcesses':blocked,'cleanup':cancel_cleanup,'bootstrapCommand':command}
        # Every retained bootstrap observation/rebuild keeps the exact source path and offline policy.
        checked=[]
        for session_value in sessions:
            for event in session_value.events:
                if event['type'] not in ('composition-observed','rebuild-started'):continue
                path=Path(event['payload']['evidenceDirectory'])/'command.json'
                assert path.is_file(),path
                command=read(path)
                assert command in ([*prefix,'--print-composition'],[*prefix,'--print-launch'])
                checked.append({'path':str(path),'command':command})
        assert checked
        summary['bootstrapCommands']=checked
        assert inventory(bootstrap)==prepared['producerInventory']
        assert inventory(source_cache)==prepared['sourceCacheInventory']
        for version,key in prepared['keys'].items():
            assert inventory(cache/'executables'/key['hash'])==prepared['runtimeInventories'][version]
        summary['passed']=True
    finally:
        cleanup()
        write(attempt/'summary.json',summary)


if __name__=='__main__':main()
