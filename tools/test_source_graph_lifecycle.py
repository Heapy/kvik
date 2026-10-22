#!/usr/bin/env python3
"""Actual offline source-graph failure/concurrency gate; default prepares only.

--run performs four complete runtime compilations plus one interrupted Native
compile. Every producer/source/cache input is copied from an approved fixture.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'tools'))
from test_source_bootstrap import audit_runtime, digest, inventory, read, trusted_copy, write

REFERENCE = Path('/private/tmp/kvik-k14-source-bootstrap/.kvik/tests/source-bootstrap/case-kgcfr28z')


def events(data):
    return [json.loads(line) for line in data.splitlines() if line.startswith(b'{')]


def assert_runtime(stdout, stderr, key, event):
    info = json.loads(stdout)
    assert info['backend'] == 'kotlin-native' and info['composition'] == key['hash'], info
    plugins = {p['id']: p['schema'] for p in info['plugins']}
    assert set(plugins) == {'kvik.kotlin', 'example.a', 'example.b'}
    assert plugins['example.a']['description'] == plugins['example.b']['description'] == 'source-v2'
    seen = events(stderr)
    assert [e['kind'] for e in seen].count(event) == 1, seen
    opposite = 'runtime-built' if event == 'runtime-cache-hit' else 'runtime-cache-hit'
    assert not any(e['kind'] == opposite for e in seen), seen
    return info


def process_table():
    value = subprocess.run(['/bin/ps', '-axo', 'pid=,ppid=,stat=,command='], check=True,
                           capture_output=True, text=True, timeout=5)
    result = {}
    for line in value.stdout.splitlines():
        fields = line.strip().split(None, 3)
        if len(fields) == 4:
            pid, parent, state, command = fields
            result[int(pid)] = dict(parent=int(parent), state=state, command=command)
    return result


def descendants(table, owners):
    selected = set(owners)
    while True:
        new = {pid for pid, record in table.items() if record['parent'] in selected}
        if new <= selected:
            return {pid: table[pid] for pid in selected if pid in table}
        selected |= new


def live(table, pids):
    return {pid: table[pid] for pid in pids if pid in table and not table[pid]['state'].startswith('Z')}


class Gate:
    def __init__(self, case, args, receipt):
        self.case, self.args, self.receipt = case, args, receipt
        self.children = []
        self.environment = {name: os.environ[name] for name in ('HOME', 'TMPDIR') if name in os.environ}
        self.environment.update(PATH='/usr/bin:/bin:/usr/sbin:/sbin', LANG='C', LC_ALL='C', TZ='UTC')
        receipt['environment'] = self.environment

    def save(self):
        write(self.case/'summary.json', self.receipt)

    def command(self, cache, *, launcher=None, no_build=False, query=None):
        result = [str(Path(sys.executable).resolve()), str((launcher or self.case/'trusted-launcher')/'kvik'),
            '--java-home', str(self.args.java_home), '--konan-home', str(self.args.konan_home),
            '--konan-data', str(self.args.konan_data), '--artifact-cache', str(self.args.artifact_cache),
            '--cache-dir', str(cache), '--source-lock', str(self.case/'source.lock.json'),
            '--source-cache', str(self.case/'source-cache'), '--offline']
        if no_build: result.append('--no-build')
        return result + ([query] if query else ['--', 'runtime-info'])

    def start(self, label, command):
        output = self.case/(label+'.stdout'); error = self.case/(label+'.stderr')
        with output.open('wb') as out, error.open('wb') as err:
            child = subprocess.Popen(command, cwd=self.case, env=self.environment, stdout=out, stderr=err)
        item = dict(label=label, argv=command, pid=child.pid, started=time.monotonic(),
                    stdout=output.name, stderr=error.name)
        self.children.append((child, item)); self.receipt['commands'].append(item); self.save()
        return child, item

    def finish(self, job, expected=0):
        child, item = job
        code = child.wait(timeout=10)
        stdout=(self.case/item['stdout']).read_bytes(); stderr=(self.case/item['stderr']).read_bytes()
        item.update(exitCode=code, seconds=time.monotonic()-item['started'],
                    stdoutDigest=digest(stdout), stderrDigest=digest(stderr))
        self.save()
        assert code == expected, (item['label'], code, stderr[-4000:])
        return stdout, stderr

    def monitor(self, jobs, cache, key, *, interrupt=False, timeout=900):
        started=time.monotonic(); observed={}; staging={}; signalled=False
        phase = dict(processes=observed, staging=staging, interrupted=False)
        self.receipt['monitors'].append(phase)
        while any(child.poll() is None for child, _ in jobs):
            assert time.monotonic()-started < timeout, 'Lifecycle command deadline exceeded'
            table=process_table()
            tree=descendants(table, [child.pid for child, _ in jobs])
            for pid, record in tree.items():
                # Preserve distinct exec transitions under the same PID.
                observations=observed.setdefault(str(pid), [])
                if record not in observations: observations.append(record)
            for directory in (cache/'executables').glob(key['hash']+'-*'):
                if '-corrupt-' in directory.name: continue
                commands=directory/'source-commands.jsonl'
                if commands.exists():
                    data=commands.read_bytes(); complete=data[:data.rfind(b'\n')+1]
                    staging[directory.name]=[json.loads(line) for line in complete.splitlines()]
            native={pid:record for pid,record in tree.items()
                    if 'org.jetbrains.kotlin.cli.utilities.MainKt konanc ' in record['command']
                    and not record['state'].startswith('Z')}
            if interrupt and native and not signalled:
                assert any(any(Path(c['command'][0]).name=='konanc' for c in commands) for commands in staging.values())
                phase['signalProcesses']={str(pid):record for pid,record in tree.items()}
                phase['signal']=signal.SIGTERM; phase['signalElapsedSeconds']=time.monotonic()-started
                jobs[0][0].send_signal(signal.SIGTERM); signalled=True; phase['interrupted']=True
                self.save()
            time.sleep(0.1)
        if interrupt: assert signalled, 'Actual Native compiler was not observed before owner exit'
        # Guardians own cleanup. Never signal an observed descendant by numeric PID.
        deadline=time.monotonic()+10
        while live(process_table(), [int(p) for p in observed]):
            assert time.monotonic()<deadline, 'Observed owned processes remain alive'
            time.sleep(0.05)
        phase['allObservedProcessesStopped']=True
        phase['seconds']=time.monotonic()-started
        self.save()
        return phase

    def invoke(self, label, command, expected=0):
        job=self.start(label, command)
        try: job[0].wait(timeout=180)
        except subprocess.TimeoutExpired:
            job[0].terminate(); raise
        return self.finish(job, expected)

    def build(self, label, cache, key, records):
        job=self.start(label, self.command(cache))
        monitor=self.monitor([job], cache, key)
        stdout,stderr=self.finish(job)
        info=assert_runtime(stdout,stderr,key,'runtime-built')
        audit=audit_runtime(cache/'executables'/key['hash'],read(self.case/'source.lock.json'),key,records)
        assert len(monitor['staging'])==1, monitor['staging'].keys()
        self.receipt['phases'][label]=dict(passed=True,runtimeInfo=info,audit=audit)
        self.save()

    def cleanup(self):
        for child,_ in self.children:
            if child.poll() is None: child.terminate()
        for child,_ in self.children:
            try: child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                child.kill(); child.wait(timeout=10)


def prepare(case, reference):
    prior=read(reference/'attempt-pz7voq2r/summary.json')
    assert prior['passed'] and prior['compiledRuntime']
    producer=reference/'attempt-pz7voq2r/trusted-launcher'
    assert inventory(producer)==prior['producer']['inventory']
    source_lock=reference/'attempt-pz7voq2r/source-v2.lock.json'
    assert read(source_lock)==read(reference/'source.lock.json')
    source_cache_before=inventory(reference/'source-cache')
    trusted=trusted_copy(producer,case/'trusted-launcher')
    shutil.copyfile(source_lock,case/'source.lock.json')
    shutil.copyfile(reference/'archive-identities.json',case/'archive-identities.json')
    shutil.copytree(reference/'source-cache',case/'source-cache')
    assert inventory(case/'source-cache')==source_cache_before
    assert inventory(reference/'source-cache')==source_cache_before
    value=dict(reference=str(reference), referenceSummary=digest((reference/'attempt-pz7voq2r/summary.json').read_bytes()),
        trustedInventory=trusted, sourceCacheInventory=source_cache_before,
        sourceLock=digest(source_lock.read_bytes()), archives=digest((case/'archive-identities.json').read_bytes()))
    write(case/'fixture.json',value)
    return value


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference',type=Path,default=REFERENCE)
    parser.add_argument('--output-dir',type=Path,default=ROOT/'.kvik/tests/source-lifecycle')
    parser.add_argument('--case',type=Path)
    parser.add_argument('--run',action='store_true',help='Run real compilation after coordinated compiler-slot approval.')
    parser.add_argument('--java-home',type=Path,default=Path('/Users/yoda/.sdkman/candidates/java/25.0.4-librca'))
    parser.add_argument('--konan-home',type=Path,default=Path('/Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20'))
    parser.add_argument('--konan-data',type=Path,default=Path.home()/'.konan')
    parser.add_argument('--artifact-cache',type=Path,default=Path('/Users/yoda/dev/pet/kvik/.kvik/tool-artifacts'))
    args=parser.parse_args()
    if args.case:
        case=args.case.resolve(strict=True); fixture=read(case/'fixture.json')
        assert not (case/'summary.json').exists(), 'Use a fresh case; never overwrite prior run evidence'
    else:
        args.output_dir.mkdir(parents=True,exist_ok=True)
        case=Path(tempfile.mkdtemp(prefix='case-',dir=args.output_dir)).resolve()
        fixture=prepare(case,args.reference.resolve(strict=True))
    assert inventory(case/'trusted-launcher')==fixture['trustedInventory']
    assert inventory(case/'source-cache')==fixture['sourceCacheInventory']
    assert digest((case/'source.lock.json').read_bytes())==fixture['sourceLock']
    assert digest((case/'archive-identities.json').read_bytes())==fixture['archives']
    print('Lifecycle fixture:',case,flush=True)
    if not args.run:
        print('Prepared only; no compiler launched.',flush=True); return
    receipt=dict(schemaVersion=1,passed=False,commands=[],monitors=[],phases={},
                 harness=digest(Path(__file__).read_bytes()),
                 auditHelper=digest((ROOT/'tools/test_source_bootstrap.py').read_bytes()),
                 fixtureHelper=digest((ROOT/'tests/source-bootstrap/fixture.py').read_bytes()))
    gate=Gate(case,args,receipt); records=read(case/'archive-identities.json')
    try:
        cache=case/'concurrent-cache'
        stdout,_=gate.invoke('desired',gate.command(cache,query='--print-composition'))
        key=json.loads(stdout)['composition'];receipt['composition']=key
        assert not cache.exists()
        first=gate.start('concurrent-a',gate.command(cache))
        second=gate.start('concurrent-b',gate.command(cache))
        monitor=gate.monitor([first,second],cache,key)
        outcomes=[gate.finish(job) for job in (first,second)]
        kinds=[[e['kind'] for e in events(stderr)] for _,stderr in outcomes]
        assert sum(k.count('runtime-building') for k in kinds)==1, kinds
        assert sum(k.count('runtime-built') for k in kinds)==1, kinds
        assert sum(k.count('runtime-cache-hit') for k in kinds)==1, kinds
        for stdout,stderr in outcomes:
            assert_runtime(stdout,stderr,key,'runtime-built' if any(e['kind']=='runtime-built' for e in events(stderr)) else 'runtime-cache-hit')
        assert len(monitor['staging'])==1, monitor['staging'].keys()
        assert sum(Path(c['command'][0]).name=='konanc' for commands in monitor['staging'].values() for c in commands)==1
        receipt['phases']['concurrent']=dict(passed=True,audit=audit_runtime(cache/'executables'/key['hash'],read(case/'source.lock.json'),key,records))
        gate.save();print('PASS simultaneous cold owners: one actual build',flush=True)

        interrupted=case/'interrupted-cache'
        job=gate.start('interrupted',gate.command(interrupted))
        gate.monitor([job],interrupted,key,interrupt=True)
        stdout,stderr=gate.finish(job,130)
        assert not stdout and not any(e['kind']=='runtime-built' for e in events(stderr))
        assert list((interrupted/'executables').iterdir())==[], 'Interrupted build exposed/retained a runtime staging directory'
        stdout,stderr=gate.invoke('interrupted-no-build',gate.command(interrupted,no_build=True),2)
        assert not stdout and b'No valid executable' in stderr
        gate.build('interrupted-retry',interrupted,key,records)
        receipt['phases']['interruption']=dict(passed=True,noPublication=True)
        print('PASS interrupted actual Native compiler, no publication, retry',flush=True)

        for label,relative in [('executable','kvik.kexe'),('receipt','source-inputs.json')]:
            repaired=case/(label+'-repair-cache');shutil.copytree(cache,repaired)
            destination=repaired/'executables'/key['hash']; path=destination/relative
            original=path.read_bytes(); damaged=bytes([original[0]^1])+original[1:];path.write_bytes(damaged)
            corrupt_digest=digest(damaged)
            stdout,stderr=gate.invoke(label+'-corrupt-no-build',gate.command(repaired,no_build=True),2)
            assert not stdout and b'No valid executable' in stderr
            assert digest(path.read_bytes())==corrupt_digest
            gate.build(label+'-repair',repaired,key,records)
            quarantines=list((repaired/'executables').glob(key['hash']+'-corrupt-*'))
            assert len(quarantines)==1 and digest((quarantines[0]/relative).read_bytes())==corrupt_digest
            receipt['phases'][label+'-repair']['corruption']=dict(path=relative,before=digest(original),damaged=corrupt_digest,
                quarantine=str(quarantines[0]),repaired=digest((destination/relative).read_bytes()))
            gate.save();print('PASS corrupt '+label+': no-build refusal and actual repair',flush=True)

        mismatched=case/'mismatched-launcher';trusted_copy(case/'trusted-launcher',mismatched)
        tool_lock=mismatched/'bootstrap/toolchains.lock.json';tool_doc=read(tool_lock)
        original_tools=read(tool_lock);tool_doc['components']['kotlinNative']['hash']='0'*64;write(tool_lock,tool_doc)
        stdout,_=gate.invoke('incompatible-desired',gate.command(cache,launcher=mismatched,query='--print-composition'))
        other_key=json.loads(stdout)['composition'];assert other_key!=key
        previous=inventory(cache/'executables')
        for label,no_build in [('incompatible-no-build',True),('incompatible-tool-verification',False)]:
            stdout,stderr=gate.invoke(label,gate.command(cache,launcher=mismatched,no_build=no_build),2)
            assert not stdout
            assert (b'No valid executable' if no_build else b'Provisioned tool differs from lock: kotlinNative') in stderr
            assert not any(e['kind'] in ('runtime-built','runtime-cache-hit') for e in events(stderr))
        assert inventory(cache/'executables')==previous
        receipt['phases']['incompatible']=dict(passed=True,composition=other_key,
            originalTools=original_tools,changedTools=tool_doc,noPublication=True)
        assert inventory(case/'trusted-launcher')==fixture['trustedInventory']
        assert inventory(case/'source-cache')==fixture['sourceCacheInventory']
        receipt['passed']=True;receipt['successfulRuntimeCompilations']=4;receipt['interruptedNativeCompilations']=1
        print('PASS incompatible tool identity cannot reuse old runtime',flush=True)
    finally:
        gate.cleanup();gate.save()
    print('PASS all source graph lifecycle phases:',case,flush=True)


if __name__=='__main__':main()
