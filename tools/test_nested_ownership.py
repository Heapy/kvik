#!/usr/bin/env python3
"""Actual C ownership boundary gates; no JVM/Native consumer. Native lifetime suite is separate."""
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

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bootstrap.launcher import run_owned


# A real controlling TTY plus inherited upstream capability; no production hooks.
TERMINAL_BROKER = r"""
import fcntl,json,os,pty,select,signal,subprocess,sys,termios,time
master,terminal=pty.openpty();os.setsid();fcntl.ioctl(terminal,termios.TIOCSCTTY,0)
signal.signal(signal.SIGTTOU,signal.SIG_IGN)
before=termios.tcgetattr(terminal)
r,w=os.pipe();reader=fcntl.fcntl(r,fcntl.F_DUPFD_CLOEXEC,20);writer=fcntl.fcntl(w,fcntl.F_DUPFD_CLOEXEC,20);os.close(r);os.close(w)
os.dup2(writer,6);os.close(writer);os.set_blocking(6,False);identity=os.fstat(6)
gate,release=os.pipe();env=dict(os.environ,KVIK_TEST_GATE=str(gate),KVIK_PROCESS_BARRIER_V1=f'6:{identity.st_dev}:{identity.st_ino}')
owner=None;anchor=None;helper=None
try:
 owner=subprocess.Popen([sys.argv[1],sys.argv[2]],env=env,stdin=terminal,stdout=subprocess.PIPE,stderr=subprocess.PIPE,pass_fds=(6,gate),preexec_fn=lambda:os.setpgid(0,0))
 os.close(6);os.close(gate);os.tcsetpgrp(terminal,owner.pid);os.write(release,b'!');os.close(release)
 assert select.select([owner.stdout],[],[],10)[0]
 assert owner.stdout.readline()==b'READY\n'
 def table():
  result=subprocess.run(['/bin/ps','-axo','pid=,ppid=,pgid=,stat='],capture_output=True,text=True,check=True)
  return {int(p):dict(parent=int(pp),group=int(g),state=state) for p,pp,g,state in (line.split() for line in result.stdout.splitlines())}
 rows=table();children=[pid for pid,row in rows.items() if row['parent']==owner.pid];assert len(children)==1
 helper=children[0];assert rows[helper]['group']==helper
 anchor=subprocess.Popen(['/bin/sleep','30'],preexec_fn=lambda:os.setpgid(0,helper))
 os.kill(helper,signal.SIGSTOP)
 deadline=time.monotonic()+5
 while not table().get(helper,{}).get('state','').startswith('T'):
  assert time.monotonic()<deadline;time.sleep(.01)
 owner.kill();owner.wait(timeout=5)
 assert not select.select([reader],[],[],.4)[0], 'Terminal cleanup acknowledged while helper paused'
 anchor.kill();anchor.wait(timeout=5)
 try:os.kill(helper,signal.SIGCONT)
 except ProcessLookupError:pass  # Removing anchor may already deliver orphan-group CONT.
 assert select.select([reader],[],[],5)[0]
 assert os.read(reader,1024)==b'', 'Restorer propagated cleanup failure'
 after=termios.tcgetattr(terminal);assert before==after,'Terminal not restored before acknowledgement'
 print(json.dumps(dict(passed=True,owner=owner.pid,guardian=helper,anchor=anchor.pid,terminalRestoredBeforeAcknowledgement=True,after=table().get(helper))))
finally:
 if anchor and anchor.poll() is None:anchor.kill();anchor.wait(timeout=5)
 if owner and owner.poll() is None:owner.kill();owner.wait(timeout=5)
 if helper:
  try:os.kill(helper,signal.SIGCONT)
  except ProcessLookupError:pass
 signal.signal(signal.SIGHUP,signal.SIG_IGN)
 os.close(reader);os.close(master);os.close(terminal)
"""


def digest(path):
    value = path.read_bytes()
    return dict(hash=hashlib.sha256(value).hexdigest(), sizeBytes=len(value))


def write(path, value): path.write_text(json.dumps(value, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--no-build', action='store_true')
    args = parser.parse_args()
    output = ROOT/'.kvik/tests/nested-ownership'; output.mkdir(parents=True, exist_ok=True)
    platform = ROOT/'execution/platform'; fixtures = ROOT/'tests/nested-ownership'
    developer = Path('/Applications/Xcode.app/Contents/Developer')
    clang = developer/'Toolchains/XcodeDefault.xctoolchain/usr/bin/clang'
    sdk = developer/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk'
    env = dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin', HOME=str(Path.home()), TMPDIR=tempfile.gettempdir(),
               DEVELOPER_DIR=str(developer), SDKROOT=str(sdk), LANG='C', LC_ALL='C', TZ='UTC')
    base = [str(clang), '-std=c11', '-D_DARWIN_C_SOURCE', '-Wall', '-Wextra', '-Werror', '-I'+str(platform)]
    commands = [base+[str(platform/'guardian.c'), '-o', str(output/'guardian')],
                base+['-Dsysctl=kvik_test_sysctl', '-c', str(platform/'guardian.c'), '-o', str(output/'poison.o')],
                base+[str(output/'poison.o'), str(fixtures/'query_failure.c'), '-o', str(output/'poison-guardian')],
                base+[str(fixtures/'bad_guardian.c'), '-o', str(output/'bad-guardian')],
                base+[str(fixtures/'capability_launcher.c'), '-o', str(output/'capability-launcher')],
                base+[str(platform/'process.c'), str(fixtures/'probe.c'), '-o', str(output/'probe')],
                base+[str(platform/'process.c'), str(fixtures/'terminal_probe.c'), '-o', str(output/'terminal-probe')]]
    source_paths = [platform/'process.c', platform/'process.h', platform/'guardian.c', *sorted(fixtures.glob('*.c'))]
    support = [Path(__file__).resolve(), *sorted((ROOT/'bootstrap').glob('*.py'))]
    def stamp():
        return dict(commands=commands, environment=env, sources={str(p.relative_to(ROOT)): digest(p) for p in source_paths},
                    support={str(p.relative_to(ROOT)): digest(p) for p in support}, tools={str(clang): digest(clang)})
    inputs = stamp(); binaries = ['guardian', 'poison-guardian', 'bad-guardian', 'capability-launcher', 'probe', 'terminal-probe']
    receipt = output/'build.json'; log_path = output/'compile.log'
    if args.no_build:
        old = json.loads(receipt.read_bytes())
        assert old['inputs'] == inputs and old['binaries'] == {n: digest(output/n) for n in binaries}
        assert old['compileLog'] == digest(log_path)
    else:
        with log_path.open('wb') as log:
            for command in commands: run_owned(command, env, log, timeout=60)
        assert stamp() == inputs
        write(receipt, dict(inputs=inputs, binaries={n: digest(output/n) for n in binaries}, compileLog=digest(log_path)))
    case = Path(tempfile.mkdtemp(prefix='case-', dir=output)).resolve()
    for name in [*binaries, 'build.json', 'compile.log']: shutil.copy2(output/name, case/name)
    print('Evidence:', case, flush=True)
    checks = []; active = []; anchors = []; observed = {}
    def table():
        result = subprocess.run(['/bin/ps', '-axo', 'pid=,ppid=,pgid=,stat=,command='], capture_output=True, text=True, check=True, timeout=5)
        return {int(p): dict(pid=int(p), parent=int(pp), group=int(g), state=s, command=c)
                for p,pp,g,s,c in (line.split(None,4) for line in result.stdout.splitlines())}
    def alive(row): return row is not None and not row['state'].startswith('Z')
    def wait(condition, message, seconds=10):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            if condition(): return
            time.sleep(.01)
        raise AssertionError(message)
    def capture(job):
        rows=table(); ids={job['process'].pid}
        while True:
            larger=ids|{pid for pid,r in rows.items() if r['parent'] in ids}
            if larger==ids: break
            ids=larger
        result={pid:rows[pid] for pid in ids if pid in rows}
        observed.update(result)
        job['captured']=result
        job['groups']={r['group'] for pid,r in result.items() if pid!=job['process'].pid}
        write(job['root']/'before.json', result)
        return result
    def no_owned(job):
        rows=table(); remaining={pid:r for pid,r in rows.items() if pid in job['captured'] or r['group'] in job['groups']}
        return not any(alive(r) for r in remaining.values()), remaining
    def start(name, depth=2, mode='wait', timeout=0, helper=None, extra_env=None, capability=None):
        root=case/name;root.mkdir()
        command=list(map(str,[case/'probe',case/'guardian',root,depth,mode,timeout,helper or case/'bad-guardian']))
        if capability: command=[str(case/'capability-launcher'),capability,*command]
        environment=dict(env, **(extra_env or {})); write(root/'command.json',command);write(root/'environment.json',environment)
        out=(root/'stdout').open('wb');err=(root/'stderr').open('wb')
        process=subprocess.Popen(command,env=environment,stdin=subprocess.DEVNULL,stdout=out,stderr=err)
        job=dict(root=root, process=process, out=out, err=err, depth=depth);active.append(job)
        return job
    def leaf(job):
        wait(lambda:(job['root']/'leaf-ready-0').exists() and (job['root']/'leaf-child-0').exists(),'leaf absent')
        assert (job['root']/'only-barrier-inherited-0').read_text().strip()=='1'
        rows=capture(job)
        guardians=[r for r in rows.values() if r['command'].startswith(str(case/'guardian')+' ') or r['command'].startswith(str(case/'poison-guardian')+' ')]
        assert len(guardians)==job['depth'],rows
        assert all(r['pid']==r['group'] for r in guardians),'Guardian not isolated'
        return rows, guardians
    def finish(job, expected=0, label=None):
        assert job['process'].wait(timeout=10)==expected,(label,expected,(job['root']/'stderr').read_text())
        job['out'].close();job['err'].close()
        # No eventual-wait allowance: terminal already acknowledges all groups stopped.
        if 'captured' in job:
            stopped, rows=no_owned(job);write(job['root']/'at-terminal.json',rows);assert stopped,rows
        checks.append(dict(name=label or job['root'].name, exitCode=expected, stdout=digest(job['root']/'stdout'),stderr=digest(job['root']/'stderr')))
        print('PASS',checks[-1]['name'],flush=True)
    def cancel(job): (job['root']/('cancel-'+str(job['depth']))).touch()
    def delayed(name, depth, trigger, mode='wait'):
        job=start(name,depth,mode);rows,guardians=leaf(job)
        # Pause deepest guardian after leaf exists, deterministically beyond outer grace.
        inner_owner=int((job['root']/'owner-1').read_text())
        inner=next(r['pid'] for r in guardians if r['parent']==inner_owner)
        # Darwin resumes stopped orphaned groups. An inert harness-owned member
        # with this live outside parent makes STOP deterministic until release.
        anchor=subprocess.Popen(['/bin/sleep','30'],preexec_fn=lambda:os.setpgid(0,inner))
        anchors.append(anchor)
        wait(lambda:table().get(anchor.pid,{}).get('group')==inner,'anchor not joined')
        anchor_row=table()[anchor.pid];observed[anchor.pid]=anchor_row;job['captured'][anchor.pid]=anchor_row
        write(job['root']/'before.json',job['captured'])
        os.kill(inner,signal.SIGSTOP)
        wait(lambda: table().get(inner,{}).get('state','').startswith('T'),'guardian not stopped')
        if trigger=='cancel': cancel(job)
        elif trigger=='exit': (job['root']/('exit-'+str(depth-1))).touch()
        elif trigger=='owner-loss': os.kill(int((job['root']/('owner-'+str(depth-1))).read_text()),signal.SIGKILL)
        time.sleep(.55)
        assert job['process'].poll() is None,'Outer reported terminal before nested cleanup'
        assert not (job['root']/('done-'+str(depth))).exists()
        assert alive(table().get(inner))
        write(job['root']/'while-paused.json',table())
        anchor.kill();anchor.wait(timeout=5)
        os.kill(inner,signal.SIGCONT)
        finish(job)
    try:
        table()
        for depth in (2,3): delayed('cancel-depth-'+str(depth),depth,'cancel')
        delayed('ordinary-leader-exit',2,'exit')
        delayed('intermediate-owner-loss',3,'owner-loss')
        for mode in ('exec','failed-exec'): delayed('same-pid-'+mode,2,'cancel',mode)
        bounded=start('positive-deadline',2,timeout=400);leaf(bounded);finish(bounded)
        assert int((bounded['root']/'done-2').read_text())==124
        survivor=start('isolated-survivor');leaf(survivor)
        victim=start('root-owner-loss',3);leaf(victim);victim['process'].kill();victim['process'].wait(timeout=5)
        wait(lambda:no_owned(victim)[0],'root owner loss leaked')
        finish(victim,-signal.SIGKILL)
        assert survivor['process'].poll() is None and all(alive(table().get(pid)) for pid in survivor['captured'])
        cancel(survivor);finish(survivor)
        for mode,depth,helper in [('poison',2,case/'poison-guardian'),('bad-result',1,case/'bad-guardian'),('nested-bad-result',3,case/'bad-guardian')]:
            job=start(mode,depth,mode,helper=helper)
            if mode=='poison': leaf(job);cancel(job)
            finish(job,42)
            assert int((job['root']/('done-'+str(depth))).read_text())==-1
            assert int((job['root']/('close-'+str(depth))).read_text())==-1
        job=start('reserved-environment',mode='override');finish(job)
        assert (job['root']/'refused-2').exists() and not (job['root']/'owner-1').exists()
        for value in ('bogus','6:0:0','06:0:0','6:0:0\n','x'*1024):
            job=start('malformed-'+str(len(checks)),mode='invalid',extra_env={'KVIK_PROCESS_BARRIER_V1':value});finish(job)
            assert (job['root']/'refused-2').exists() and not (job['root']/'owner-1').exists()
        for kind in ('regular','read-only','identity','blocking'):
            job=start('capability-'+kind,mode='invalid',capability=kind);finish(job)
            assert (job['root']/'refused-2').exists() and not (job['root']/'owner-1').exists()
        job=start('fd-collision-and-leaks',capability='collision');leaf(job);cancel(job);finish(job)
        terminal=subprocess.run([sys.executable,'-B','-c',TERMINAL_BROKER,str(case/'terminal-probe'),str(case/'guardian')],env=env,capture_output=True,timeout=30)
        (case/'terminal.stdout').write_bytes(terminal.stdout);(case/'terminal.stderr').write_bytes(terminal.stderr)
        assert terminal.returncode==0,(terminal.returncode,terminal.stdout,terminal.stderr)
        terminal_result=json.loads(terminal.stdout);assert terminal_result['passed']
        checks.append(dict(name='terminal-restoration-barrier',result=terminal_result,stdout=digest(case/'terminal.stdout'),stderr=digest(case/'terminal.stderr')))
        assert stamp()==inputs
        write(case/'summary.json',dict(passed=True,checks=checks,buildReceipt=digest(case/'build.json'),allObservedOwnedProcessesStopped=True))
        print('PASS',len(checks),'nested ownership cases',case,flush=True)
    finally:
        # Only this gate's observed processes/groups; preserve unrelated invocations.
        for anchor in anchors:
            if anchor.poll() is None: anchor.kill()
            anchor.wait(timeout=5)
        for job in active:
            if job['process'].poll() is None: job['process'].kill()
        for pid,row in observed.items():
            if alive(table().get(pid)):
                try: os.kill(pid,signal.SIGCONT);os.kill(pid,signal.SIGKILL)
                except ProcessLookupError: pass
        for job in active:
            job['process'].wait(timeout=10);job['out'].close();job['err'].close()


if __name__=='__main__': main()
