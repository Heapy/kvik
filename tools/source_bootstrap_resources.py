"""Per-invocation OS usage and sampled descendants for the full bootstrap gate.

Only this helper reaps the invocation owner. Process-table sampling never polls
or waits on it; ps subprocesses have separate subprocess.run ownership.
"""
import json
import math
import os
import platform
import signal
import subprocess
import time

from foreground_measurements import process_rows, owned_processes

# Retain ownership if a kernel-stuck child defeats the bounded cleanup deadline.
# Never let Popen.__del__ or a subsequent Popen implicitly reap its wait4 usage.
_UNREAPED = []
WAIT_SCOPE = ('Kernel wait4 usage for the reaped invocation owner. Descendant usage is included only '
              'where propagated by child waiting and the host OS; not exact whole-build CPU or aggregate tree peak RSS.')
SAMPLE_SCOPE = ('Live descendants reachable from the invocation owner at each ps snapshot, including the owner. '
                'Sampling can miss short-lived or reparented/detached processes and peaks. Summed RSS double-counts '
                'shared pages; observed counts are not exact process-creation counts. Instrumentation adds wall time.')


def usage_record(usage, system, exit_code):
    if system not in ('Darwin', 'Linux'):
        raise ValueError('wait4 RSS units are defined only for Darwin and Linux')
    raw = {'userSeconds': usage.ru_utime, 'systemSeconds': usage.ru_stime, 'maxRss': usage.ru_maxrss}
    if any(isinstance(value, bool) or not isinstance(value, (int, float)) or
           not math.isfinite(value) or value < 0 for value in raw.values()):
        raise ValueError('Invalid wait4 resource usage')
    user, kernel = round(raw['userSeconds']*1e9), round(raw['systemSeconds']*1e9)
    return {'available': True, 'source': 'os.wait4', 'scope': WAIT_SCOPE, 'hostSystem': system,
            'exitCode': exit_code, 'raw': raw, 'rawMaxRssUnit': 'bytes' if system == 'Darwin' else 'KiB',
            'userCpuNanos': user, 'systemCpuNanos': kernel, 'cpuNanos': user+kernel,
            'maxRssBytes': int(raw['maxRss']*(1 if system == 'Darwin' else 1024))}


def process_snapshot():
    result = subprocess.run(['/bin/ps', '-axo', 'pid=,ppid=,pgid=,rss=,stat=,lstart='],
                            check=True, capture_output=True, text=True, timeout=1)
    live = []
    for line in result.stdout.splitlines():
        pid, parent, group, rss, state, birth = line.split(None, 5)
        if not state.startswith('Z'):
            live.append(' '.join((pid, parent, group, rss, birth)))
    return process_rows('\n'.join(live))


def sampling_summary(samples, interval, errors):
    live = [sample for sample in samples if sample['processes']]
    starts = [sample['startNanos'] for sample in samples]
    return {'coverage': 'failed' if errors else 'sampled-live-descendants' if live else 'no-live-samples',
            'scope': SAMPLE_SCOPE, 'errors': list(errors), 'snapshotCount': len(samples), 'sampleCount': len(live),
            'nominalIntervalNanos': round(interval*1e9),
            'maximumStartGapNanos': max((b-a for a, b in zip(starts, starts[1:])), default=None),
            'maximumObservedProcessCount': max((len(s['processes']) for s in live), default=None),
            'maximumObservedSummedRssBytes': max((sum(p['rssBytes'] for p in s['processes']) for s in live), default=None)}


def validate_resources(resources, require_samples=False):
    usage = resources.get('wait4', {})
    if not usage.get('available') or usage.get('source') != 'os.wait4':
        raise ValueError('Invocation has no wait4 resource evidence')
    sampling = resources.get('sampling', {})
    if sampling.get('errors') or sampling.get('coverage') == 'failed':
        raise ValueError('Invocation process sampling failed')
    if require_samples and (sampling.get('coverage') != 'sampled-live-descendants' or
                            sampling.get('sampleCount', 0) < 1):
        raise ValueError('Major bootstrap phase has no live process sample')
    if resources.get('cleanup', {}).get('completed') is not True:
        raise ValueError('Invocation cleanup was not completed')


def _exited(pid):
    return os.waitid(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is not None


def _signal_group(process, number):
    try:
        os.killpg(process.pid, number)
    except ProcessLookupError:
        pass
    except PermissionError:
        # Darwin can report EPERM for a vanished group whose owner is a zombie.
        # WNOWAIT preserves both PID identity and wait4's resource ownership.
        if not _exited(process.pid):
            raise


def _reap(process, resources):
    pid, status, usage = os.wait4(process.pid, os.WNOHANG)
    if not pid:
        return False
    process.returncode = os.waitstatus_to_exitcode(status)
    resources['wait4'] = usage_record(usage, platform.system(), process.returncode)
    return True


def _cleanup(process, resources, grace, deadline_seconds):
    resources['cleanup'] = {'required': True, 'completed': False, 'scope': 'Owned process group; detached groups are not enumerated or claimed reaped'}
    limit = time.monotonic()+deadline_seconds
    _signal_group(process, signal.SIGTERM)
    # Keep the owner unreaped until the final group signal, even if it exits
    # first. This prevents the PGID being recycled before we finish cleanup.
    until = min(time.monotonic()+grace, limit)
    while time.monotonic() < until:
        time.sleep(max(0, min(.01, until-time.monotonic())))
    _signal_group(process, signal.SIGKILL)
    while not _reap(process, resources):
        if time.monotonic() >= limit:
            raise RuntimeError('Owned invocation did not exit within cleanup deadline')
        time.sleep(.005)
    resources['cleanup']['completed'] = True


def measured_invocation(command, *, cwd, environment, stdout, stderr, timeout, samples_path, resources,
                        interval=.1, cleanup_grace=1, cleanup_timeout=5):
    """Return CompletedProcess or raise after retaining partial resource telemetry."""
    resources.update(schemaVersion=1, timedOut=False, wait4={'available': False, 'source': 'os.wait4', 'scope': WAIT_SCOPE},
                     cleanup={'required': False, 'completed': False, 'scope': 'Invocation owner reaped; no complete descendant-exit claim'})
    samples, errors = [], []
    process = None
    birth = None
    handlers = {}
    def interrupted(number, _frame):
        raise InterruptedError('Invocation interrupted by signal '+str(number))
    try:
        for number in (signal.SIGINT, signal.SIGTERM):
            handlers[number] = signal.signal(number, interrupted)
        if _UNREAPED:
            raise RuntimeError('A previous invocation has uncollected wait4 ownership')
        with samples_path.open('w') as log:
            process = subprocess.Popen(command, cwd=cwd, env=environment, stdin=subprocess.DEVNULL,
                                       stdout=stdout, stderr=stderr, start_new_session=True)
            limit = time.monotonic()+timeout
            next_sample = time.monotonic()
            while True:
                if _exited(process.pid):
                    if not _reap(process, resources):
                        raise RuntimeError('Invocation exit observation lost before wait4')
                    resources['cleanup']['completed'] = True
                    return subprocess.CompletedProcess(command, process.returncode)
                now = time.monotonic()
                if now >= limit:
                    resources['timedOut'] = True
                    raise subprocess.TimeoutExpired(command, timeout)
                if now >= next_sample:
                    started = time.monotonic_ns()
                    try:
                        rows = process_snapshot()
                        if birth is None and process.pid in rows:
                            birth = rows[process.pid]['birth']
                        owned = owned_processes(rows, process.pid, birth)
                        sample = {'startNanos': started, 'endNanos': time.monotonic_ns(), 'processes': owned}
                        samples.append(sample)
                        log.write(json.dumps(sample, sort_keys=True)+'\n'); log.flush()
                    except Exception as error:
                        errors.append(type(error).__name__+': '+str(error))
                        raise RuntimeError('Invocation process sampling failed') from error
                    next_sample = time.monotonic()+interval
                time.sleep(min(.005, max(0, limit-time.monotonic())))
    finally:
        for number in handlers:
            signal.signal(number, signal.SIG_IGN)
        try:
            if process is not None and process.returncode is None:
                try:
                    _cleanup(process, resources, cleanup_grace, cleanup_timeout)
                except BaseException as error:
                    resources['cleanup']['error'] = type(error).__name__+': '+str(error)
                    if process.returncode is None:
                        _UNREAPED.append(process)
                    raise
        finally:
            resources['sampling'] = sampling_summary(samples, interval, errors)
            for number, handler in handlers.items():
                signal.signal(number, handler)
