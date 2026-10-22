"""Actual two-Git-worktree/shared-CAS isolation phase; preparation is process-free."""
import copy
import json
from pathlib import Path
import shutil
import signal
import subprocess

FIXTURES = Path(__file__).resolve().parents[1]/'tests/script-cli/concurrency'
COMPILE = 'script:plain:jvm:compile'


def prepare(project):
    shutil.copyfile(FIXTURES/'Main.kt', project/'Main.kt')
    document = json.loads((project/'Main.kvik.json').read_bytes())
    document['script']['timeoutMillis'] = 120000
    (project/'Main.kvik.json').write_text(json.dumps(document, sort_keys=True, separators=(',', ':')))


def git(gate, label, *arguments):
    argv = ['/usr/bin/git', '-c', 'init.templateDir=', '-c', 'core.hooksPath=/dev/null', *map(str, arguments)]
    home = gate.directory/'git-home'; home.mkdir(exist_ok=True)
    environment = dict(PATH='/usr/bin:/bin', HOME=str(home), LANG='C', LC_ALL='C', TZ='UTC',
        GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null', GIT_TERMINAL_PROMPT='0',
        GIT_AUTHOR_NAME='Kvik acceptance', GIT_AUTHOR_EMAIL='fixture@invalid',
        GIT_COMMITTER_NAME='Kvik acceptance', GIT_COMMITTER_EMAIL='fixture@invalid',
        GIT_AUTHOR_DATE='2026-01-01T00:00:00Z', GIT_COMMITTER_DATE='2026-01-01T00:00:00Z')
    write(gate.directory/(label+'.git-command.json'), dict(argv=argv, cwd=str(gate.project), environment=environment, timeoutSeconds=30, executable=digest(Path('/usr/bin/git').read_bytes())))
    with (gate.directory/(label+'.git-stdout')).open('wb') as out, (gate.directory/(label+'.git-stderr')).open('wb') as err:
        process = subprocess.Popen(argv, cwd=gate.project, env=environment, stdin=subprocess.DEVNULL, stdout=out, stderr=err)
        try:
            code = process.wait(timeout=30)
        except BaseException:
            process.kill(); process.wait(timeout=10)
            write(gate.directory/(label+'.git-result.json'), dict(outcome='harness-interrupted', observedExitCode=process.returncode))
            raise
    stdout=(gate.directory/(label+'.git-stdout')).read_bytes()
    write(gate.directory/(label+'.git-result.json'), dict(exitCode=code, stdout=digest(stdout),
        stderr=digest((gate.directory/(label+'.git-stderr')).read_bytes())))
    assert code == 0, label
    return stdout.decode().strip()


def view(gate, name, root):
    child = copy.copy(gate)
    child.directory = gate.directory/name; child.directory.mkdir()
    child.project = root
    child.config, child.lock = root/'Main.kvik.json', root/'Main.kvik.lock.json'
    child.store = gate.directory/'shared-store'; child.runs = child.directory/'runs'
    child.selector = ['--file', str(root/'Main.kt')]
    child.common = child.selector + ['--store', str(child.store)]
    child.commands, child.records = [], []
    invocation = json.loads((gate.directory/'invocation.json').read_bytes())
    invocation['worktree'] = dict(name=name, root=str(root), store=str(child.store), runs=str(child.runs))
    write(child.directory/'invocation.json', invocation)
    write(child.directory/'initial-inputs.json', inventory(root))
    return child


def compiled(result):
    return next(a for a in result['record']['actions'] if a['actionId'] == COMPILE)


def start(gate):
    argv = [str(gate.args.runtime), *gate.run_args(['wait'])]
    write(gate.directory/'held.command.json', dict(argv=argv, cwd=str(gate.project), environment=gate.environment))
    out=(gate.directory/'held.stdout').open('wb'); err=(gate.directory/'held.stderr').open('wb')
    try:
        process=subprocess.Popen(argv, cwd=gate.project, env=gate.environment, stdin=subprocess.DEVNULL, stdout=out, stderr=err)
    except BaseException:
        out.close(); err.close(); raise
    return dict(gate=gate, process=process, out=out, err=err, captured={}, pgid=None)


def capture(owner):
    gate, process=owner['gate'],owner['process']
    paths=[gate.project/(name+'.pid') for name in ('app','child','grandchild')]
    await_true(lambda: all(p.exists() and p.read_text().strip() for p in paths) or process.poll() is not None,900,'concurrent startup')
    assert process.poll() is None
    pids=[int(p.read_text()) for p in paths]
    table=process_table(); captured=descendants(table,process.pid)
    # Keep every successfully discovered owner for failure cleanup, even if a later assertion fails.
    owner['captured']=captured
    assert all(pid in captured and live(captured[pid]) for pid in pids)
    guardians=[pid for pid,row in captured.items() if row['command'].startswith(str(gate.args.runtime.parent/'guardian')+' ')]
    assert len(guardians)==1 and table[pids[0]]['ppid']==guardians[0]
    pgid=table[pids[0]]['pgid']; assert pgid==pids[0] and all(table[pid]['pgid']==pgid for pid in pids)
    owner.update(pgid=pgid,pids=pids,guardians=guardians)
    write(gate.directory/'held.owned-before.json',captured)


def assert_survivor(table, owner):
    assert owner['process'].poll() is None
    for pid,original in owner['captured'].items():
        assert pid in table and live(table[pid]) and table[pid]['pgid']==original['pgid'] and table[pid]['command']==original['command'], (pid,table.get(pid))


def finish_owner(owner, expected, status):
    gate,process=owner['gate'],owner['process']
    code=process.wait(timeout=30)
    terminal_cleanup(gate.directory,'held',owner['captured'],owner['pgid'],False)
    owner['out'].close(); owner['err'].close()
    stdout=(gate.directory/'held.stdout').read_bytes(); stderr=(gate.directory/'held.stderr').read_bytes()
    write(gate.directory/'held.result.json',dict(exitCode=code,expected=expected,stdout=digest(stdout),stderr=digest(stderr)))
    assert code==expected
    result=gate.audit_run('held',[json.loads(line) for line in stdout.splitlines()],status)
    assert compiled(result)['status']=='local_hit'
    gate.fresh_output(result,['wait'])
    assert gate.observation(result)['processResult']==dict(exitCode=expected,timedOut=False,cancelled=status=='cancelled',setupFailure=None)
    return result


def run(gate):
    global write,digest,inventory,await_true,process_table,descendants,live,terminal_cleanup,cleanup_failed_owner
    from test_scripts_cli import write,digest,inventory,await_true,process_table,descendants,live,terminal_cleanup,cleanup_failed_owner
    git(gate,'init','init')
    git(gate,'add','add','Main.kt','Main.kvik.json','literal.args')
    git(gate,'commit','commit','-m','Bounded script concurrency fixture')
    revision=git(gate,'revision','rev-parse','HEAD')
    roots=[gate.directory/'worktree-a',gate.directory/'worktree-b']
    for index,root in enumerate(roots): git(gate,'worktree-'+str(index),'worktree','add','--detach',root,revision)
    listing=git(gate,'worktrees','worktree','list','--porcelain')
    assert all('worktree '+str(root)+'\n' in listing+'\n' for root in roots)
    a,b=[view(gate,name,root) for name,root in zip(('a','b'),roots)]
    assert a.project.resolve()!=b.project.resolve() and a.store==b.store and a.runs!=b.runs
    assert {p.name:p.read_bytes() for p in a.project.iterdir() if p.name!='.git'}=={p.name:p.read_bytes() for p in b.project.iterdir() if p.name!='.git'}
    a.resolve('resolve'); b.resolve('resolve')
    assert a.lock.read_bytes()==b.lock.read_bytes()
    cold=a.run('cold'); a.fresh_output(cold,['ok'])
    warm=b.run('relocated-warm'); b.fresh_output(warm,['ok'])
    key=compiled(cold)['key']; assert compiled(cold)['status']=='executed'
    assert compiled(warm)['key']==key and compiled(warm)['status']=='local_hit'
    before_cache=inventory(a.store/'actions')
    owners=[]
    try:
        owners.append(start(a)); owners.append(start(b))
        for owner in owners: capture(owner)
        first,second=owners
        assert set(first['captured']).isdisjoint(second['captured']) and first['pgid']!=second['pgid']
        both=process_table(); assert_survivor(both,first); assert_survivor(both,second)
        write(gate.directory/'both-live.json',both)
        first['process'].send_signal(signal.SIGINT)
        cancelled=finish_owner(first,130,'cancelled')
        after=process_table(); assert_survivor(after,second)
        write(gate.directory/'survivor-after-cancel.json',after)
        assert not (b.project/'release').exists()
        assert (a.project/'effects.log').read_text().splitlines()[1].startswith('wait\t')
        assert (b.project/'effects.log').read_text().splitlines()[1].startswith('wait\t')
        (b.project/'release').write_text('complete normally\n')
        passed=finish_owner(second,0,'passed')
        assert compiled(cancelled)['key']==compiled(passed)['key']==key
        assert gate.observation(cancelled)['directory']!=gate.observation(passed)['directory']
        assert before_cache==inventory(a.store/'actions'), 'Fresh runs must not publish execution cache records'
        for child in (a,b):
            result=child.run('fresh-cached'); child.fresh_output(result,['ok'])
            assert compiled(result)['key']==key and compiled(result)['status']=='local_hit'
            effects=(child.project/'effects.log').read_text().splitlines()
            assert len(effects)==3 and len({line.split('\t')[1] for line in effects})==3
        evidence=dict(revision=revision,worktrees=list(map(str,roots)),sharedStore=str(a.store),compileKey=key,
            owners=[dict(owner=o['process'].pid,capturedPids=sorted(o['captured']),pgid=o['pgid']) for o in owners],
            cancelledTreeDrainedBeforeSurvivorRelease=True)
        # Finalize only after finally has retained its cleanup observations.
    finally:
        failures=[]
        for owner in owners:
            try: cleanup_failed_owner(owner['process'],owner['gate'].directory,'held-final',owner['captured'])
            except BaseException as error: failures.append(error)
            finally:
                owner['out'].close(); owner['err'].close()
                directory=owner['gate'].directory
                if not (directory/'held.result.json').exists():
                    write(directory/'held.incomplete.json',dict(outcome='harness-failure',ownerExitCode=owner['process'].returncode,
                        stdout=digest((directory/'held.stdout').read_bytes()),stderr=digest((directory/'held.stderr').read_bytes())))
        if failures: raise failures[0]
    # Children summaries are made after cleanup so their own inventories are complete.
    evidence['children']=[child.finish() for child in (a,b)]
    return gate.finish(dict(concurrency=evidence))
