#!/usr/bin/env python3
"""Receipt-bound Native -> Python bootstrap -> Native cleanup and poison checks.

Consumes an already-built process-lifetime Native probe; invokes no compiler.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import select
import shlex
import signal
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT))
from bootstrap import launcher, source_process


def digest(path):
    data=path.read_bytes()
    return dict(hash=hashlib.sha256(data).hexdigest(),sizeBytes=len(data))


def write(path,value):
    path.write_text(json.dumps(value,indent=2)+'\n')


def table():
    result=subprocess.run(['/bin/ps','-axo','pid=,ppid=,pgid=,stat='],capture_output=True,text=True,check=True,timeout=5)
    return {int(pid):dict(parent=int(parent),group=int(group),state=state)
            for pid,parent,group,state in (line.split() for line in result.stdout.splitlines())}


def live(rows,pid):
    return pid in rows and not rows[pid]['state'].startswith('Z')


def wait_for(predicate,seconds=10):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if predicate():return
        time.sleep(.01)
    raise AssertionError('Timed out waiting for owned process evidence')


def worker(path, native_case, kind, poison):
    (path/'inner').mkdir()
    child=f'trap "" TERM; echo $$ > {shlex.quote(str(path/"child.pid"))}; while :; do sleep 1; done'
    script=f'trap "" TERM; echo $$ > {shlex.quote(str(path/"leaf.pid"))}; /bin/sh -c {shlex.quote(child)} & wait'
    command=[str(native_case/'probe.kexe'), '/usr/bin/true' if poison else str(native_case/'guardian'),
             str(path/'inner'),'wait','0',script]
    write(path/'python-owner.json',dict(pid=os.getpid(),command=command,kind=kind))
    if kind=='compiler':
        with (path/'inner.log').open('wb') as log:
            launcher.run_owned(command,{},log,timeout=30)
    else:
        value=source_process.run_owned(source_process.OwnedProcessRequest(tuple(command),path,{},timeout_seconds=30))
        (path/'inner.log').write_bytes(value.stdout+value.stderr)
        write(path/'acquisition-result.json',dict(outcome=value.outcome,exitCode=value.exit_code,error=value.error))
        if value.outcome!='exited' or value.exit_code!=0:
            raise RuntimeError(value)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-case',type=Path,required=True)
    args=parser.parse_args()
    native=args.native_case.resolve(strict=True)
    receipt=json.loads((native/'build.json').read_bytes())
    for name in ('probe.kexe','guardian'):
        assert digest(native/name)==receipt['binaries'][name]
    table()  # Refuse before creating workers if process inspection is unavailable.
    output=ROOT/'.kvik/tests/bootstrap-ownership-mixed';output.mkdir(parents=True,exist_ok=True)
    case=Path(tempfile.mkdtemp(prefix='case-',dir=output)).resolve()
    source_paths=[ROOT/'bootstrap'/f'{name}.py' for name in ('launcher','guardian','source_process','source_guardian','process_barrier')]+[Path(__file__).resolve()]
    inputs={str(path.relative_to(ROOT)):digest(path) for path in source_paths}
    summary=dict(passed=False,nativeBuild=dict(path=str(native/'build.json'),digest=digest(native/'build.json')),
                 binaries={name:digest(native/name) for name in ('probe.kexe','guardian')},sources=inputs,scenarios=[])
    write(case/'summary.json',summary)
    print(case,flush=True)
    for kind in ('compiler','acquisition'):
        for poisoned in (False,True):
            path=case/(kind+('-poison' if poisoned else '-paused'));path.mkdir()
            command=[str(native/'probe.kexe'),str(native/'guardian'),str(path),'wait' if poisoned else 'control','0',
                     'exec '+shlex.join([sys.executable,str(Path(__file__).resolve()),'worker',str(path),str(native),kind,str(int(poisoned))])]
            write(path/'command.json',command)
            process=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            captured={process.pid};groups=set();paused=None;before=None;failure=None
            result=dict(kind=kind,poisoned=poisoned,owner=process.pid,command=command)
            try:
                prefix=b''
                for _ in range(2):
                    assert select.select([process.stdout],[],[],10)[0]
                    prefix+=process.stdout.readline()
                assert prefix.decode().splitlines()[1]=='STARTED',prefix
                if poisoned:
                    out,err=process.communicate(timeout=15)
                    assert process.returncode!=0 and b'RESULT ' not in prefix+out
                    assert b'Ownership guardian did not acknowledge complete cleanup' in err
                    # A failure on the inner Native hop must reach the outer one.
                    assert 'Ownership guardian did not acknowledge complete cleanup' in (path/'inner.log').read_text()
                    if kind=='acquisition':
                        evidence=json.loads((path/'acquisition-result.json').read_bytes())
                        assert evidence['outcome']=='setup-error' and evidence['error']
                else:
                    wait_for(lambda:all(p.exists() and p.stat().st_size for p in (path/'leaf.pid',path/'child.pid')))
                    leaf=int((path/'leaf.pid').read_text());child=int((path/'child.pid').read_text())
                    before=table();chain=[leaf]
                    while chain[-1]!=process.pid:
                        chain.append(before[chain[-1]]['parent'])
                        assert len(chain)<12
                    # leaf -> Native guardian -> Native owner -> Python guardian
                    # -> Python owner -> Native guardian -> Native outer owner.
                    assert len(chain)==7,chain
                    python_owner=json.loads((path/'python-owner.json').read_bytes())['pid']
                    assert chain[4]==python_owner
                    paused=chain[3]
                    captured.update(chain);captured.add(child)
                    groups={before[pid]['group'] for pid in captured if pid!=process.pid}
                    assert os.getpgrp() not in groups
                    write(path/'before.json',dict(processes=before,chain=chain,child=child,groups=sorted(groups)))
                    os.kill(paused,signal.SIGSTOP)
                    wait_for(lambda:table().get(paused,{}).get('state','').startswith('T'))
                    process.stdin.write(b'c');process.stdin.flush()
                    time.sleep(.5)
                    delayed=table()
                    assert process.poll() is None and live(delayed,leaf) and live(delayed,child)
                    assert delayed[paused]['state'].startswith('T')
                    write(path/'paused.json',delayed)
                    os.kill(paused,signal.SIGCONT);paused=None
                    out,err=process.communicate(timeout=15)
                    assert process.returncode==0 and b'RESULT 130 false true' in prefix+out,(out,err)
                    after=table()
                    assert not [pid for pid,row in after.items() if (pid in captured or row['group'] in groups) and live(after,pid)]
                    write(path/'at-completion.json',after)
                (path/'stdout').write_bytes(prefix+out);(path/'stderr').write_bytes(err)
                result.update(passed=True,exitCode=process.returncode,stdout=digest(path/'stdout'),stderr=digest(path/'stderr'))
            except BaseException as error:
                failure=error
                result.update(passed=False,error=str(error))
                raise
            finally:
                if paused is not None:
                    try:os.kill(paused,signal.SIGCONT)
                    except ProcessLookupError:pass
                rows=table()
                owned={process.pid}
                while True:
                    expanded=owned|{pid for pid,row in rows.items() if row['parent'] in owned}
                    if expanded==owned:break
                    owned=expanded
                captured.update(owned)
                groups.update(row['group'] for pid,row in rows.items() if pid in owned and pid!=process.pid and row['group']!=os.getpgrp())
                if process.poll() is None:process.kill()
                process.communicate(timeout=15)
                wait_for(lambda:not any((pid in captured or row['group'] in groups) and live(current,pid)
                                        for current in [table()] for pid,row in current.items()))
                result.update(captured=sorted(captured),groups=sorted(groups),cleanupVerified=True)
                summary['scenarios'].append(result);write(case/'summary.json',summary)
    assert inputs=={str(path.relative_to(ROOT)):digest(path) for path in source_paths}
    summary['passed']=True;write(case/'summary.json',summary)
    print('PASS mixed Native/Python recursive cleanup and poison',flush=True)


if __name__=='__main__':
    if len(sys.argv)>1 and sys.argv[1]=='worker':
        worker(Path(sys.argv[2]),Path(sys.argv[3]),sys.argv[4],bool(int(sys.argv[5])))
    else:main()
