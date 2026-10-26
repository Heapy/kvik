"""Cached launcher timing and separate per-child Native RSS measurements.

These are invocation wall times and wait4 high-water marks, not evidence that
polling can exclude short-lived child processes or that cold-build budgets pass.
"""
import argparse
import json
import math
import os
from pathlib import Path
import platform
import signal
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from bootstrap.identity import canonical, digest, file_digest, read_json

MIN_SAMPLES = 20
LAUNCHER_BUDGET_NANOS = 150_000_000
NATIVE_RSS_BUDGET_BYTES = 64 * 1024 * 1024
OPERATIONS = ('help', 'runtime-info')


def sample_count(value):
    try:
        number = int(value)
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError('Sample count must be an integer') from None
    if not MIN_SAMPLES <= number <= 300:
        raise argparse.ArgumentTypeError('Sample count must be between 20 and 300')
    return number


def nearest_rank_p95(samples):
    if len(samples) < MIN_SAMPLES or any(type(value) is not int or value < 0 for value in samples):
        raise ValueError('p95 requires at least 20 nonnegative integer nanosecond samples')
    return sorted(samples)[math.ceil(95 * len(samples) / 100) - 1]


def rss_bytes(value, system):
    if not isinstance(value, (int, float)) or isinstance(value, bool) or value < 0 or not math.isfinite(value):
        raise ValueError('Invalid child ru_maxrss')
    if system not in ('Darwin', 'Linux'):
        raise ValueError('RSS units are only defined here for Darwin and Linux')
    return int(value * (1 if system == 'Darwin' else 1024))


def terminate_group(process):
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    except PermissionError:
        # Some hosts report EPERM for an exited leader's vanished group. Observe
        # without reaping: the RSS path must retain sole ownership of wait4.
        if process.returncode is None and os.waitid(os.P_PID, process.pid,
                os.WEXITED | os.WNOHANG | os.WNOWAIT) is None:
            raise


def stop_and_collect(process, partial_stdout=b'', partial_stderr=b''):
    """Bound pipe draining even if a detached descendant holds the write ends."""
    terminate_group(process)
    try:
        return process.communicate(timeout=0.25)
    except subprocess.TimeoutExpired as failure:
        stdout = failure.output if failure.output is not None else partial_stdout
        stderr = failure.stderr if failure.stderr is not None else partial_stderr
        for stream in (process.stdout, process.stderr):
            if stream is not None:
                stream.close()
        process.wait(timeout=0.25)
        return stdout, stderr


def invoke(command, *, cwd, environment, timeout, rss=False):
    """Timing samples use communicate; independent RSS samples own wait4 reaping."""
    command = list(map(str, command))
    if not rss:
        started = time.monotonic_ns()
        process = subprocess.Popen(command, cwd=cwd, env=environment, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, start_new_session=True)
        timed_out = False
        try:
            stdout, stderr = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired as failure:
            timed_out = True
            stdout, stderr = stop_and_collect(process, failure.output or b'', failure.stderr or b'')
        except BaseException:
            stop_and_collect(process)
            raise
        elapsed = time.monotonic_ns() - started
        return {'command': command, 'exitCode': process.returncode, 'timedOut': timed_out,
                'elapsedNanos': elapsed}, stdout, stderr
    # Files avoid PIPE deadlocks while wait4 owns the child status/resource result.
    # This polling latency is never used as a startup timing sample.
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        process = subprocess.Popen(command, cwd=cwd, env=environment, stdout=stdout, stderr=stderr,
                                   start_new_session=True)
        deadline = time.monotonic() + timeout
        timed_out = False
        try:
            while True:
                pid, status, usage = os.wait4(process.pid, os.WNOHANG)
                if pid:
                    break
                if time.monotonic() >= deadline:
                    timed_out = True
                    terminate_group(process)
                    _, status, usage = os.wait4(process.pid, 0)
                    break
                time.sleep(0.002)
            process.returncode = os.waitstatus_to_exitcode(status)
        finally:
            if process.returncode is None:
                terminate_group(process)
                _, status, _ = os.wait4(process.pid, 0)
                process.returncode = os.waitstatus_to_exitcode(status)
        stdout.seek(0); stderr.seek(0)
        return {'command': command, 'exitCode': process.returncode, 'timedOut': timed_out,
                'measurement': 'wait4.ru_maxrss', 'rawMaxRss': usage.ru_maxrss,
                'rawMaxRssUnit': 'bytes' if platform.system() == 'Darwin' else 'KiB',
                'peakRssBytes': rss_bytes(usage.ru_maxrss, platform.system())}, stdout.read(), stderr.read()


def cache_event(stderr, composition):
    lines = stderr.splitlines()
    events = [json.loads(line) for line in lines]
    if (len(events) != 1 or events[0].get('kind') != 'runtime-cache-hit' or
            events[0].get('composition') != composition):
        raise ValueError('Expected exactly one cache-hit event for the selected composition')


def validate_output(operation, stdout, stderr, composition, *, launcher, reference=None):
    if launcher:
        cache_event(stderr, composition)
    elif stderr:
        raise ValueError('Unexpected direct Native stderr')
    if operation == 'runtime-info':
        info = json.loads(stdout)
        if (not isinstance(info, dict) or info.get('schemaVersion') != 1 or
                info.get('backend') != 'kotlin-native' or info.get('composition') != composition):
            raise ValueError('Runtime inspection differs from the selected Native composition')
    elif operation == 'help':
        text = stdout.decode('utf-8', errors='strict')
        if not text.startswith('Kvik — Kotlin/Native') or 'Commands: runtime-info,' not in text:
            raise ValueError('Unexpected Native help output')
    else:
        raise ValueError('Unknown cached startup operation')
    if reference is not None and stdout != reference:
        raise ValueError('Operation output changed between invocations')


def metrics(records, count):
    result = {'launcher': {}, 'directNative': {}}
    checks = []
    for executor in result:
        for operation in OPERATIONS:
            selected = [record for record in records if record.get('executor') == executor and
                        record.get('operation') == operation and record.get('measurement') == 'timing']
            samples = [record['elapsedNanos'] for record in selected]
            if len(samples) != count:
                raise ValueError('Incomplete startup sample set')
            p95 = nearest_rank_p95(samples)
            value = {'sampleCount': len(samples), 'samplesNanos': samples, 'p95Nanos': p95,
                     'p95Method': 'nearest-rank: sorted[ceil(0.95*n)-1]'}
            if executor == 'launcher':
                value.update(budgetExclusiveNanos=LAUNCHER_BUDGET_NANOS, budgetMet=p95 < LAUNCHER_BUDGET_NANOS)
                checks.append(value['budgetMet'])
            else:
                resources = [record['peakRssBytes'] for record in records if record.get('executor') == executor and
                             record.get('operation') == operation and record.get('measurement') == 'wait4.ru_maxrss']
                if len(resources) != count:
                    raise ValueError('Incomplete Native resource sample set')
                value.update(rssSampleCount=len(resources), rssSamplesBytes=resources, peakRssBytes=max(resources),
                             rssBudgetExclusiveBytes=NATIVE_RSS_BUDGET_BYTES,
                             rssBudgetMet=max(resources) < NATIVE_RSS_BUDGET_BYTES)
                checks.append(value['rssBudgetMet'])
            result[executor][operation] = value
    return result, all(checks)


def launcher_files(root):
    paths = [root/'kvik', *sorted((root/'bootstrap').glob('*.py')),
             root/'bootstrap/composition.json', root/'bootstrap/jvm-artifacts.lock.json', root/'bootstrap/toolchains.lock.json']
    return [{'path': path.relative_to(root).as_posix(), 'digest': file_digest(path)} for path in paths]


def write_summary(case, value):
    (case/'summary.json').write_text(json.dumps(value, indent=2) + '\n')


def run_quick(args, *, runner=invoke):
    count = sample_count(args.samples)
    if platform.system() not in ('Darwin', 'Linux') or not hasattr(os, 'wait4'):
        raise ValueError('Cached resource measurement requires Darwin/Linux wait4')
    output = args.output_dir.absolute(); output.mkdir(parents=True, exist_ok=True)
    case = Path(tempfile.mkdtemp(prefix='cached-startup-', dir=output)).resolve()
    print(case, flush=True)
    root = args.launcher_root.resolve(strict=True)
    cache = args.cache_dir.absolute()
    missing = {name: case/'absent'/name for name in ('jdk', 'native', 'artifacts', 'native-data')}
    environment = {name: os.environ[name] for name in ('HOME', 'TMPDIR') if name in os.environ}
    environment.update(PATH='/usr/bin:/bin:/usr/sbin:/sbin', LANG='C', LC_ALL='C', TZ='UTC')
    command = [str(Path(sys.executable).resolve()), root/'kvik', '--cache-dir', cache, '--offline', '--no-build',
               '--java-home', missing['jdk'], '--konan-home', missing['native'],
               '--konan-data', missing['native-data'], '--artifact-cache', missing['artifacts']]
    if args.source_lock is not None: command.extend(['--source-lock', args.source_lock.absolute()])
    if args.source_cache is not None: command.extend(['--source-cache', args.source_cache.absolute()])
    summary = {'schemaVersion': 1, 'passed': False, 'budgetsMet': None, 'sampleCountPerOperation': count,
        'case': str(case), 'records': [], 'environment': environment, 'workingDirectory': str(case),
        'host': {'name': args.host_name or platform.node(), 'os': platform.system(), 'osRelease': platform.release(),
                 'architecture': platform.machine(), 'logicalCpuCount': os.cpu_count()},
        'python': {'version': platform.python_version(), 'executable': str(Path(sys.executable).resolve()),
                   'digest': file_digest(Path(sys.executable).resolve())},
        'harness': [{'path': str(path), 'digest': file_digest(path)} for path in
                    (Path(__file__), Path(__file__).with_name('test_launcher.py'), ROOT/'bootstrap/identity.py')],
        'scope': {'startup': 'Wall time through process spawn and stdout/stderr collection; all samples retained, no warmup discarded',
                  'resources': 'Separate direct Native child wait4 high-water measurements, including child startup; no cumulative getrusage',
                  'cacheState': 'Provisioned executable; OS page/file caches uncontrolled',
                  'operations': 'help and runtime-info inspection only',
                  'notEvaluated': ['cold provisioning/construction', 'plugin recomposition', 'foreground reload',
                                   'absence of short-lived JVM or other child processes']},
        'missingCompilerPaths': {name: str(path) for name, path in missing.items()}}
    write_summary(case, summary)

    def run(label, argv, *, rss=False, executor=None, operation=None):
        index = len(summary['records'])
        entry, stdout, stderr = runner(argv, cwd=case, environment=environment, timeout=args.timeout_seconds, rss=rss)
        entry.update(label=label, executor=executor, operation=operation)
        if not rss: entry['measurement'] = 'timing' if executor else 'preflight'
        for name, data in (('stdout', stdout), ('stderr', stderr)):
            path = case/f'{index:03d}-{label}.{name}'; path.write_bytes(data)
            entry[name] = path.name; entry[name+'Digest'] = digest(data)
        summary['records'].append(entry)
        write_summary(case, summary)
        if entry['timedOut'] or entry['exitCode'] != 0:
            raise ValueError(f"{label} failed: exit={entry['exitCode']}, timedOut={entry['timedOut']}")
        return stdout, stderr

    try:
        if any(path.exists() or path.is_symlink() for path in missing.values()):
            raise ValueError('Expected absent compiler/artifact paths')
        summary['launcherFiles'] = launcher_files(root)
        selected, error = run('composition', [*command, '--print-composition'])
        if error: raise ValueError('Unexpected desired-composition stderr')
        composition = json.loads(selected)['composition']
        summary['composition'] = composition
        value, error = run('runtime-path', [*command, '--print-runtime'])
        cache_event(error, composition['hash'])
        executable = Path(value.decode().strip()).resolve(strict=True)
        if executable != (cache/'executables'/composition['hash']/'kvik.kexe').resolve(strict=True):
            raise ValueError('Launcher returned an unexpected executable')
        manifest_path = executable.parent/'manifest.json'
        manifest = read_json(manifest_path)
        runtime_lock = read_json(executable.parent/'runtime.lock.json')
        if manifest['composition'] != composition or digest(canonical(runtime_lock)) != composition:
            raise ValueError('Runtime manifest/lock differs from desired composition')
        expected_binary = file_digest(executable)
        if expected_binary != manifest['executable']: raise ValueError('Runtime executable differs from manifest')
        summary['runtime'] = {'path': str(executable), 'executable': expected_binary,
                              'manifest': file_digest(manifest_path), 'lock': file_digest(executable.parent/'runtime.lock.json'),
                              'toolchains': runtime_lock['toolchains']}
        if args.source_lock is not None:
            summary['sourceLock'] = {'path': str(args.source_lock.absolute()), 'digest': file_digest(args.source_lock)}
        references = {}
        for operation in OPERATIONS:
            stdout, stderr = run('reference-'+operation, [executable, operation])
            validate_output(operation, stdout, stderr, composition['hash'], launcher=False)
            references[operation] = stdout
        # Interleave timing operations, keeping resource instrumentation in a
        # separate later pass so its polling cannot inflate launcher p95.
        for sample in range(count):
            for executor, prefix in (('launcher', [*command, '--']), ('directNative', [executable])):
                for operation in OPERATIONS:
                    stdout, stderr = run(f'{executor}-{operation}-{sample}', [*prefix, operation],
                                         executor=executor, operation=operation)
                    validate_output(operation, stdout, stderr, composition['hash'],
                                    launcher=executor == 'launcher', reference=references[operation])
        for sample in range(count):
            for operation in OPERATIONS:
                stdout, stderr = run(f'rss-{operation}-{sample}', [executable, operation], rss=True,
                                     executor='directNative', operation=operation)
                validate_output(operation, stdout, stderr, composition['hash'], launcher=False, reference=references[operation])
        if (file_digest(executable) != expected_binary or file_digest(manifest_path) != summary['runtime']['manifest'] or
                file_digest(executable.parent/'runtime.lock.json') != summary['runtime']['lock'] or
                launcher_files(root) != summary['launcherFiles']):
            raise ValueError('Measured runtime or launcher changed during the run')
        if args.source_lock is not None and file_digest(args.source_lock) != summary['sourceLock']['digest']:
            raise ValueError('Source lock changed during measurements')
        if any(path.exists() or path.is_symlink() for path in missing.values()):
            raise ValueError('Compiler/artifact paths were created during cached operations')
        summary['metrics'], summary['budgetsMet'] = metrics(summary['records'], count)
        summary['passed'] = True
    except BaseException as error:
        summary['failure'] = {'type': type(error).__name__, 'message': str(error)}
        raise
    finally:
        write_summary(case, summary)
    print(json.dumps({'passed': summary['passed'], 'budgetsMet': summary['budgetsMet'], 'summary': str(case/'summary.json')}), flush=True)
    return summary
