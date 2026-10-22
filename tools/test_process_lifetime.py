#!/usr/bin/env python3
"""One Native probe: zero deadline remains owned; positive deadlines and cleanup remain enforced.

--no-build verifies the source/launcher/environment/binary receipt. Installation
and SDK support outside this bounded receipt must remain stable. No consumers run.
"""
import argparse
import atexit
import hashlib
import json
import os
from pathlib import Path
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.launcher import run_owned


def digest(path):
    data = path.read_bytes()
    return dict(hash=hashlib.sha256(data).hexdigest(), sizeBytes=len(data))


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, default=Path.home()/'.sdkman/candidates/java/25.0.4-librca')
    parser.add_argument('--konan-home', type=Path, default=Path.home()/'.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20')
    parser.add_argument('--no-build', action='store_true')
    args = parser.parse_args()
    java = args.java_home.resolve(strict=True); native = args.konan_home.resolve(strict=True)
    output = ROOT/'.kvik/tests/process-lifetime'; output.mkdir(parents=True, exist_ok=True)
    platform = ROOT/'execution/platform'; developer = Path('/Applications/Xcode.app/Contents/Developer')
    clang = developer/'Toolchains/XcodeDefault.xctoolchain/usr/bin/clang'; ar = clang.with_name('ar')
    environment = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', JAVA_HOME=str(java), HOME=str(Path.home()),
        KONAN_DATA_DIR=str(Path.home()/'.konan'), DEVELOPER_DIR=str(developer), TMPDIR=str(Path(tempfile.gettempdir()).resolve()),
        LANG='C', LC_ALL='C', TZ='UTC', KONAN_USE_COMPILER_SERVER='false', KONAN_USE_INTERNAL_SERVER='0')
    environment['SDKROOT'] = subprocess.check_output(['/usr/bin/xcrun', '--show-sdk-path'], env=environment, text=True).strip()
    sources = sorted((ROOT/'core/src').rglob('*.kt')) + sorted((ROOT/'execution/src').rglob('*.kt')) + [ROOT/'tests/execution/ProcessLifetimeProbe.kt']
    commands = [
        [clang, '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', platform/'guardian.c', '-o', output/'guardian'],
        [clang, '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-c', platform/'process.c', '-o', output/'process.o'],
        [ar, 'rcs', output/'libkvik-process.a', output/'process.o'],
        [clang, '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-I'+str(platform), ROOT/'tests/execution/process_lifetime_boundary.c', output/'libkvik-process.a', '-o', output/'boundary'],
        [native/'bin/cinterop', '-def', platform/'process.def', '-compiler-option', '-I'+str(platform), '-libraryPath', output, '-target', 'macos_arm64', '-Xoverride-konan-properties', 'airplaneMode=true', '-o', output/'process'],
        [native/'bin/konanc', '-target', 'macos_arm64', '-Xoverride-konan-properties=airplaneMode=true', '-library', output/'process.klib', *sources, '-entry', 'kvik.execution.lifetime.main', '-o', output/'probe.kexe'],
    ]
    commands = [list(map(str, command)) for command in commands]
    source_paths = sources + [platform/name for name in ('process.c', 'process.h', 'process.def', 'guardian.c')] + [ROOT/'tests/execution/process_lifetime_boundary.c']
    tool_paths = [java/'bin/java', native/'bin/konanc', native/'bin/cinterop', native/'bin/run_konan', native/'tools/env_blacklist', clang, ar, *sorted((native/'konan/lib').glob('*.jar'))]
    support = [Path(__file__).resolve(), *sorted((ROOT/'bootstrap').glob('*.py'))]
    def stamp():
        return dict(commands=commands, environment=environment, sources={str(p.relative_to(ROOT)): digest(p) for p in source_paths},
            tools={str(p): digest(p) for p in tool_paths}, harness={str(p.relative_to(ROOT)): digest(p) for p in support})
    inputs = stamp(); receipt = output/'build.json'; compile_log = output/'compile.log'
    binaries = ['guardian', 'boundary', 'probe.kexe']
    if args.no_build:
        old = json.loads(receipt.read_bytes())
        assert old['stamp'] == inputs and old['binaries'] == {name: digest(output/name) for name in binaries}, 'Stale process lifetime build'
        assert old['compileLog'] == digest(compile_log)
    else:
        write(output/'build-inputs.json', inputs)
        try:
            with compile_log.open('wb') as log:
                for command in commands:
                    run_owned(command, environment, log, timeout=300)
        except Exception as failure:
            failed = Path(tempfile.mkdtemp(prefix='failed-build-', dir=output))
            shutil.copy2(compile_log, failed/'compile.log')
            write(failed/'receipt.json', dict(passed=False, stamp=inputs, failedCommand=command, failure=str(failure), compileLog=digest(compile_log)))
            raise
        assert stamp() == inputs, 'Inputs changed during compilation'
        write(receipt, dict(stamp=inputs, binaries={name: digest(output/name) for name in binaries}, compileLog=digest(compile_log)))
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    print(case, flush=True)
    for name in binaries + ['build.json', 'compile.log']: shutil.copy2(output/name, case/name)
    summary = dict(passed=False, buildReceipt=digest(case/'build.json'), jobs=[], checks=[])
    owners = []
    def retain(): write(case/'summary.json', summary)
    def table():
        result = subprocess.run(['/bin/ps', '-axo', 'pid=,ppid=,pgid=,stat='], capture_output=True, text=True, check=True, timeout=5)
        return {int(p): dict(parent=int(parent), group=int(group), state=state) for p,parent,group,state in (line.split() for line in result.stdout.splitlines())}
    def alive(rows, pid): return pid in rows and not rows[pid]['state'].startswith('Z')
    def wait_for(condition, label, timeout=5):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if condition(): return
            time.sleep(.02)
        raise AssertionError(label)
    def cleanup():
        for process, _ in owners:
            if process.poll() is None:
                process.kill(); process.communicate(timeout=5)
    atexit.register(cleanup)
    def start(label, mode, script, timeout):
        argv = list(map(str, [case/'probe.kexe', case/'guardian', case, mode, timeout, script]))
        process = subprocess.Popen(argv, env=environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
        job = dict(label=label, argv=argv, environment=environment, owner=process.pid, started=time.monotonic())
        owners.append((process, job)); summary['jobs'].append(job)
        prefix = b''
        for _ in range(2):
            assert select.select([process.stdout], [], [], 10)[0], 'Probe startup did not complete'
            prefix += process.stdout.readline()
        lines = prefix.decode().splitlines(); assert len(lines)==2 and lines[1]=='STARTED', prefix
        directory = Path(lines[0]); assert directory.parent == case
        job.update(directory=str(directory), prefix=prefix.decode()); retain()
        return process, job, directory
    def finish(process, job, expected=0):
        out, err = process.communicate(timeout=5)
        out = job['prefix'].encode() + out
        (case/(job['label']+'.stdout')).write_bytes(out); (case/(job['label']+'.stderr')).write_bytes(err)
        job.update(exitCode=process.returncode, seconds=time.monotonic()-job['started'], stdout=digest(case/(job['label']+'.stdout')), stderr=digest(case/(job['label']+'.stderr'))); retain()
        assert process.returncode==expected, (job,err)
        return out.decode()
    def owned(process, job, directory):
        wait_for(lambda: all((directory/name).exists() and (directory/name).stat().st_size for name in ('leader.pid','descendant.pid')), 'Owned child tree did not start')
        pids = [int((directory/name).read_text()) for name in ('leader.pid','descendant.pid')]
        rows=table();guardians=[pid for pid,r in rows.items() if r['parent']==process.pid];assert len(guardians)==1
        assert all(alive(rows,pid) for pid in pids+guardians)
        job.update(pids=pids, guardians=guardians, group=pids[0]); retain()
        return pids,guardians
    def stopped(job):
        rows=table()
        return all(not alive(rows,pid) for pid in job['pids']+job['guardians']) and not any(r['group']==job['group'] and not r['state'].startswith('Z') for r in rows.values())
    def check(label, **evidence):
        summary['checks'].append(dict(label=label, **evidence)); retain(); print('PASS',label,flush=True)
    resistant = 'trap "" TERM; echo $$ > leader.pid; /bin/sh -c \'trap "" TERM; echo $$ > descendant.pid; while :; do sleep 1; done\' & wait'
    try:
        # Check observer permissions before launching any long-lived worker.
        table()
        for name, command, expected in [('contracts', [str(case/'probe.kexe'),'contracts'], b'CONTRACTS PASS'), ('boundary', [str(case/'boundary')], b'NEGATIVE BOUNDARY PASS')]:
            value=subprocess.run(command,env=environment,capture_output=True,timeout=5)
            (case/(name+'.stdout')).write_bytes(value.stdout);(case/(name+'.stderr')).write_bytes(value.stderr)
            assert value.returncode==0 and expected in value.stdout
            check(name,argv=command,exitCode=value.returncode,stdout=digest(case/(name+'.stdout')),stderr=digest(case/(name+'.stderr')))
        process,job,directory=start('bounded','wait',resistant,150);owned(process,job,directory)
        out=finish(process,job);assert 'RESULT 124 true false' in out;wait_for(lambda:stopped(job),'Bounded process leaked')
        check('positive deadline',milliseconds=150)
        for operation in ('c','x'):
            process,job,directory=start('unbounded-'+operation,'control',resistant,0);owned(process,job,directory)
            time.sleep(.75);rows=table();assert process.poll() is None and all(alive(rows,pid) for pid in job['pids']+job['guardians'])
            job['aliveAfterSeconds']=time.monotonic()-job['started'];started=time.monotonic()
            process.stdin.write(operation.encode());process.stdin.flush()
            out=finish(process,job);assert ('RESULT 130 false true' if operation=='c' else 'CLOSED') in out
            wait_for(lambda:stopped(job),'Unbounded cancel/close leaked');elapsed=time.monotonic()-started;assert elapsed<3
            check('zero deadline '+('cancel' if operation=='c' else 'host close'),aliveSeconds=job['aliveAfterSeconds'],cleanupSeconds=elapsed)
        survivor,survivor_job,survivor_dir=start('survivor','control',resistant,0);owned(survivor,survivor_job,survivor_dir)
        for sig in (signal.SIGINT,signal.SIGTERM,signal.SIGKILL):
            process,job,directory=start('owner-'+str(int(sig)),'control',resistant,0);owned(process,job,directory)
            time.sleep(.25);assert process.poll() is None
            started=time.monotonic();process.send_signal(sig);finish(process,job,-int(sig));wait_for(lambda:stopped(job),'Owner death leaked process group')
            elapsed=time.monotonic()-started;assert elapsed<3
            rows=table();assert survivor.poll() is None and all(alive(rows,pid) for pid in survivor_job['pids']+survivor_job['guardians'])
            check('zero deadline owner signal',signal=int(sig),cleanupSeconds=elapsed)
        survivor.stdin.write(b'c');survivor.stdin.flush();assert 'RESULT 130 false true' in finish(survivor,survivor_job)
        wait_for(lambda:stopped(survivor_job),'Independent survivor leaked')
        check('independent unbounded owner survived other shutdowns')
        script='(/bin/sh -c \'trap "" TERM; echo $$ > descendant.pid; while :; do sleep 1; done\') & echo $$ > leader.pid; sleep .15; exit 7'
        process,job,directory=start('normal-exit','wait',script,0);owned(process,job,directory)
        assert 'RESULT 7 false false' in finish(process,job);wait_for(lambda:stopped(job),'Unbounded normal exit leaked descendant')
        check('zero deadline ordinary leader exit reaps descendants')
        assert all(process.poll() is not None and stopped(job) for process,job in owners)
        assert stamp()==inputs,'Source/compiler identities changed during tests'
        summary.update(passed=True,allObservedOwnedProcessesStopped=True)
    finally:
        cleanup();retain();atexit.unregister(cleanup)
    print(json.dumps(dict(passed=True,case=str(case))),flush=True)


if __name__=='__main__':main()
