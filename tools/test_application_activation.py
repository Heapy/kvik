#!/usr/bin/env python3
"""Actual gated JVM supervisor checks. Requires this source-composed runtime; never builds the CLI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import jsonschema

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.launcher import composition


def canonical(value): return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
def digest(value): return dict(hash=hashlib.sha256(value).hexdigest(), sizeBytes=len(value))
def write_json(path, value): path.write_bytes(canonical(value))
def file_digest(path): return digest(path.read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True, help='Pinned installation document, copied without changing roots')
    parser.add_argument('--output-dir', type=Path, default=ROOT/'.kvik/tests/application-activation')
    parser.add_argument('--skip-timeout', action='store_true', help='Explicitly omit the real 60-second wait deadline scenario')
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    _, selected, expected_lock, expected_composition = composition()
    manifest = json.loads((runtime.parent/'manifest.json').read_bytes())
    assert manifest['composition'] == expected_composition and manifest['executable'] == file_digest(runtime)
    assert json.loads((runtime.parent/'runtime.lock.json').read_bytes()) == expected_lock
    subprocess.run(['/bin/ps', '-axo', 'pid=,stat='], check=True, stdout=subprocess.DEVNULL)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='case-', dir=args.output_dir.resolve()))
    print('Evidence:', case, flush=True)
    project = case/'project'; (project/'main').mkdir(parents=True)
    source = ROOT/'tests/application-activation/fixture/Main.kt'
    shutil.copy2(source, project/'main/Main.kt'); original_source=(project/'main/Main.kt').read_bytes()
    shutil.copy2(args.tools, project/'tools.json')
    document = dict(schemaVersion=1,name='activationFixture',repositories=[dict(id='central',url='https://repo.maven.apache.org/maven2')],modules=[
        dict(id='app',sourceSets=[dict(name='main',directories=['main'],resources=[],dependsOn=[],dependencies=[dict(coordinate='org.jetbrains.kotlin:kotlin-stdlib:2.4.20')])],
             targets=[dict(name='jvm',main='main',test=None,compilerOptions=['-jvm-target=11','-Xjdk-release=11'],
                           application=dict(entryPoint='fixture.MainKt',arguments=['@literal','space value',''],environment={}))],compilerPlugins=[])])
    write_json(project/'kvik.json',document)
    schema=json.loads((ROOT/'schemas/v1/contracts.json').read_bytes())
    def validate(value, kind): jsonschema.Draft202012Validator({**schema,'$ref':'#/$defs/'+kind}).validate(value)
    validate(document,'project');validate(json.loads((project/'tools.json').read_bytes()),'toolInstallation')
    write_json(case/'invocation.json',dict(runtime=str(runtime),runtimeDigest=file_digest(runtime),composition=expected_composition,
        sources={logical:identity for _,logical,identity in selected},fixture=file_digest(source),harness=file_digest(Path(__file__)),
        tools=file_digest(project/'tools.json'),schema=file_digest(ROOT/'schemas/v1/contracts.json'),skipTimeout=args.skip_timeout))
    for name in ('manifest.json','runtime.lock.json'): shutil.copy2(runtime.parent/name,case/name)
    environment=dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin',HOME=str(Path.home()),TMPDIR=tempfile.gettempdir(),LANG='C',LC_ALL='C',TZ='UTC',JAVA_TOOL_OPTIONS='-Dactivation.ambient=forbidden')
    shared=['--project',str(project/'kvik.json'),'--lock',str(project/'dependencies.lock.json'),'--store',str(case/'store'),
        '--pin','org.jetbrains.kotlin:kotlin-stdlib:2.4.20','--pin','org.jetbrains:annotations:13.0']
    build=shared+['--tools',str(project/'tools.json'),'--module','app','--target','jvm','--runs',str(case/'runs'),'--offline']
    active=[]; results=[]; sequence=0
    def effects():
        path=project/'effects.log'
        return path.read_text().splitlines() if path.exists() else []
    def run_command(name, argv, expected=0):
        write_json(case/(name+'.command.json'),argv)
        result=subprocess.run(argv,cwd=project,env=environment,capture_output=True,timeout=180)
        (case/(name+'.stdout.jsonl')).write_bytes(result.stdout);(case/(name+'.stderr.log')).write_bytes(result.stderr)
        assert result.returncode==expected,(name,result.returncode,result.stderr[-1000:])
        results.append(dict(name=name,exitCode=result.returncode,stdout=digest(result.stdout),stderr=digest(result.stderr)))
        return result
    def start(name, prepared=None):
        nonlocal sequence
        sequence+=1
        path=case/name;path.mkdir();gate=path/'gate';gate.mkdir()
        token=digest(canonical(dict(case=str(case),sequence=sequence)))
        argv=[str(runtime),'stage-prepared' if prepared else 'stage-application','--activation',str(gate),'--token',canonical(token).decode()]
        argv+=['--run',prepared['preparationDirectory'],'--descriptor',canonical(prepared['descriptor']).decode()] if prepared else build
        write_json(path/'command.json',argv);write_json(path/'environment.json',environment)
        out=(path/'stdout.jsonl').open('wb');err=(path/'stderr.log').open('wb')
        process=subprocess.Popen(argv,cwd=project,env=environment,stdin=subprocess.DEVNULL,stdout=out,stderr=err)
        active.append(process)
        return dict(name=name,path=path,gate=gate,token=token,process=process,out=out,err=err,before=list(effects()),started=time.monotonic())
    def finish(item, expected, timeout=180):
        process=item['process'];code=process.wait(timeout=timeout);item['out'].close();item['err'].close()
        value=dict(name=item['name'],exitCode=code,seconds=time.monotonic()-item['started'],
            stdout=file_digest(item['path']/'stdout.jsonl'),stderr=file_digest(item['path']/'stderr.log'))
        write_json(item['path']/'result.json',value);results.append(value)
        assert code==expected,(item['name'],code,expected,(item['path']/'stderr.log').read_text()[-1000:])
        return value
    def ready(item):
        path=item['gate']/'ready.json';deadline=time.monotonic()+180
        while not path.exists():
            assert item['process'].poll() is None,(item['name'],'exited before readiness',(item['path']/'stderr.log').read_text())
            assert time.monotonic()<deadline,(item['name'],'readiness timeout')
            time.sleep(.02)
        data=path.read_bytes();value=json.loads(data)
        assert data==canonical(value) and len(data)<=8192
        validate(value,'applicationReady');assert value['token']==item['token'] and value['module']=='app' and value['target']=='jvm'
        assert value['composition']==expected_composition
        run=Path(value['preparationDirectory']);descriptor=(run/'prepared.json').read_bytes()
        assert digest(descriptor)==value['descriptor']
        record=json.loads((run/'verification.json').read_bytes())
        assert digest(canonical(record))==value['verification'] and record['status']=='passed'
        completion=json.loads((run/'completion.json').read_bytes())
        assert completion['verification']==value['verification'] and completion['journal']==file_digest(run/'events.jsonl')
        assert not (item['gate']/'started.json').exists() and effects()==item['before']
        time.sleep(.15)
        assert effects()==item['before'],'Application effects before activation'
        item['ready']=value;item['readyBytes']=data
        return value
    def activate(item, patch=None, raw=None):
        value=dict(schemaVersion=1,token=item['token'],ready=digest(item['readyBytes']))
        if patch:value.update(patch)
        temporary=item['gate']/'activation.tmp';temporary.write_bytes(raw if raw is not None else canonical(value))
        os.link(temporary,item['gate']/'activate.json');temporary.unlink()
    def started(item):
        deadline=time.monotonic()+60;path=item['gate']/'started.json'
        while not path.exists():
            assert item['process'].poll() is None,(item['name'],'exited before started',(item['path']/'stderr.log').read_text())
            assert time.monotonic()<deadline
            time.sleep(.02)
        data=path.read_bytes();value=json.loads(data);assert data==canonical(value)
        validate(value,'applicationStarted')
        assert value['token']==item['token'] and value['ready']==digest(item['readyBytes'])
        assert Path(value['executionDirectory']).parent==Path(item['ready']['preparationDirectory'])/'application-runs'
        return value
    def no_start(item):
        assert not (item['gate']/'started.json').exists() and effects()==item['before'],item['name']
    def wait_pids():
        deadline=time.monotonic()+30
        while not all((project/name).exists() for name in ('app.pid','child.pid','grandchild.pid')):
            assert time.monotonic()<deadline;time.sleep(.02)
        return [int((project/name).read_text()) for name in ('app.pid','child.pid','grandchild.pid')]
    def assert_stopped(pids):
        deadline=time.monotonic()+10
        while True:
            live=[]
            for pid in pids:
                result=subprocess.run(['/bin/ps','-p',str(pid),'-o','stat='],capture_output=True,text=True)
                if result.returncode==0 and result.stdout.strip() and not result.stdout.strip().startswith('Z'):live.append(pid)
            if not live:return
            assert time.monotonic()<deadline,('remaining processes',live)
            time.sleep(.05)
    try:
        run_command('resolve',[str(runtime),'resolve',*shared])
        # Gate syntax/ownership errors must precede the build stream.
        bad=case/'not-empty';bad.mkdir();(bad/'ready.json').write_bytes(b'{}')
        result=run_command('preexisting-ready',[str(runtime),'stage-application','--activation',str(bad),'--token',canonical(digest(b'token')).decode(),*build],2)
        assert len(result.stdout.splitlines())==1 and 'diagnostic' in json.loads(result.stdout)
        early=case/'early-activation';early.mkdir();(early/'activate.json').write_bytes(b'{}')
        run_command('preexisting-activation',[str(runtime),'stage-application','--activation',str(early),'--token',canonical(digest(b'token')).decode(),*build],2)
        empty=case/'bad-token';empty.mkdir()
        run_command('noncanonical-token',[str(runtime),'stage-application','--activation',str(empty),'--token',json.dumps(digest(b'token')),*build],2)
        cold=start('cold');saved=ready(cold);activate(cold);started(cold);finish(cold,0)
        assert len(effects())==len(cold['before'])+1
        for label,patch,raw in [('wrong-token',dict(token=digest(b'wrong')),None),('wrong-ready',dict(ready=digest(b'wrong')),None),
            ('unknown-field',dict(extra=True),None),('noncanonical',None,b'{}\n')]:
            item=start(label,prepared=saved);ready(item);activate(item,patch=patch,raw=raw);finish(item,1);no_start(item)
        cancel=start('cancel-ready',prepared=saved);ready(cancel);cancel['process'].send_signal(signal.SIGTERM);finish(cancel,130);no_start(cancel)
        owner=start('owner-loss-before-activation',prepared=saved);ready(owner);owner['process'].kill();finish(owner,-signal.SIGKILL);no_start(owner)
        stale=start('stale-after-ready',prepared=saved);ready(stale)
        (project/'main/Main.kt').write_bytes(original_source+b'\n// changed after readiness\n');activate(stale);finish(stale,3);no_start(stale)
        (project/'main/Main.kt').write_bytes(original_source)
        corrupted=start('corrupt-after-ready');value=ready(corrupted)
        cls=next((Path(value['preparationDirectory'])/'prepared/application/input').rglob('MainKt.class'))
        content=cls.read_bytes();cls.chmod(0o644);cls.write_bytes(bytes([content[0]^1])+content[1:]);activate(corrupted);finish(corrupted,1);no_start(corrupted)
        tamper=start('tampered-ready',prepared=saved);ready(tamper);(tamper['gate']/'ready.json').chmod(0o644);(tamper['gate']/'ready.json').write_bytes(b'{}');activate(tamper);finish(tamper,1);no_start(tamper)
        old=start('old-running',prepared=saved);ready(old);(project/'wait.txt').write_text('wait');activate(old);started(old);old_pids=wait_pids()
        replacement=start('replacement',prepared=saved);ready(replacement)
        assert len(effects())==len(old['before'])+1
        old['process'].send_signal(signal.SIGTERM);finish(old,130);assert_stopped(old_pids)
        (project/'wait.txt').unlink()
        activate(replacement);started(replacement);finish(replacement,0)
        assert len(effects())==len(old['before'])+2
        for name in ('app.pid','child.pid','grandchild.pid'):(project/name).unlink()
        killed=start('owner-loss-running',prepared=saved);ready(killed);(project/'wait.txt').write_text('wait');activate(killed);started(killed);pids=wait_pids()
        killed['process'].kill();finish(killed,-signal.SIGKILL);assert_stopped(pids);(project/'wait.txt').unlink()
        if not args.skip_timeout:
            timeout=start('activation-deadline',prepared=saved);ready(timeout);value=finish(timeout,1,timeout=90);no_start(timeout)
            assert value['seconds']>=60
        # Existing public paths still run directly; both must retain actual execution effects.
        before=len(effects());run_command('one-shot',[str(runtime),'run-application',*build]);assert len(effects())==before+1
        before=len(effects());run_command('public-replay',[str(runtime),'run-prepared','--run',saved['preparationDirectory'],'--descriptor',canonical(saved['descriptor']).decode()]);assert len(effects())==before+1
        assert (project/'main/Main.kt').read_bytes()==original_source
        assert composition()[3]==expected_composition and file_digest(runtime)==manifest['executable']
        write_json(case/'summary.json',dict(passed=True,composition=expected_composition,runtime=file_digest(runtime),results=results,effects=effects(),timeoutCovered=not args.skip_timeout))
        print('PASS',len(results),'actual supervisor scenarios',case,flush=True)
    finally:
        for process in active:
            if process.poll() is None:process.terminate()
        for process in active:
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)

if __name__=='__main__':main()
