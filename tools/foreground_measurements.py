"""Observational metrics for the existing public foreground acceptance harness."""
import json
import math
import subprocess
import threading
import time


def percentile95(values):
    return sorted(values)[math.ceil(.95 * len(values)) - 1] if values else None


def terminal_response(phase, sent, received, event, events=()):
    """Bind one serial '?' byte to its reader-timestamped key acknowledgement."""
    payload = event['payload']
    if event['type'] != 'control' or payload['command'] != '?' or received < sent:
        raise ValueError('Terminal acknowledgement does not match the sent key')
    active = payload.get('active')
    boundaries = [item for item in events if item['sessionId'] == event['sessionId']
                  and item['sequence'] < event['sequence']
                  and item['type'] in ('rebuild-started', 'rebuild-finished')]
    boundary = max(boundaries, key=lambda item: item['sequence'], default=None)
    rebuilding = (not payload['compositionReady'] and boundary is not None
                  and boundary['type'] == 'rebuild-started')
    expected = {'initial-build': active == 'build', 'source-recomposition': rebuilding,
                'replacement-test': active == 'test', 'before-ctrl-c': active == 'build'}
    if phase not in expected or not expected[phase]:
        raise ValueError('Terminal sample was not acknowledged during the expected work')
    return dict(phase=phase, sentNanos=sent, receivedNanos=received, durationNanos=received-sent,
                sessionId=event['sessionId'], sequence=event['sequence'], active=active,
                compositionReady=payload['compositionReady'],
                rebuildStartSequence=boundary['sequence'] if rebuilding else None)


def terminal_summary(samples):
    phases = {}
    for phase in ('initial-build', 'source-recomposition', 'replacement-test', 'before-ctrl-c'):
        durations = [sample['durationNanos'] for sample in samples if sample['phase'] == phase]
        required = 1 if phase == 'before-ctrl-c' else 20
        complete = len(durations) >= required
        phases[phase] = dict(sampleCount=len(durations), requiredSampleCount=required,
                             p95Nanos=percentile95(durations) if len(durations) >= 20 else None,
                             maximumNanos=max(durations, default=None), budgetNanos=100_000_000,
                             budgetMet=all(n < 100_000_000 for n in durations) if complete else None)
    return dict(scope='Serial PTY master ? byte write start through reader receipt of its control event',
                samples=samples, phases=phases,
                budgetsMet=all(value['budgetMet'] is True for value in phases.values()),
                limitations='Includes PTY, output pipe and scheduling latency; excludes graphical terminal rendering and human key travel.')


def process_rows(text):
    result = {}
    for line in text.splitlines():
        pid, parent, group, rss, birth = line.split(None, 4)
        pid, parent, group, rss = map(int, (pid, parent, group, rss))
        if pid <= 0 or min(parent, group, rss) < 0 or pid in result:
            raise ValueError('Invalid process inventory')
        # ps RSS is KiB on both supported hosts (Darwin and Linux).
        result[pid] = dict(pid=pid, parent=parent, group=group, rssBytes=rss * 1024, birth=birth)
    return result


def owned_processes(rows, root, birth):
    if root not in rows:
        return []
    if rows[root]['birth'] != birth:
        raise ValueError('Foreground PID was reused during measurement')
    owned = {root}
    while True:
        expanded = owned | {pid for pid, row in rows.items() if row['parent'] in owned}
        if expanded == owned:
            return [rows[pid] for pid in sorted(owned)]
        owned = expanded


class ProcessSampler:
    def __init__(self, process, path, interval=.1):
        self.process, self.path, self.interval = process, path, interval
        self.stop = threading.Event()
        self.samples, self.errors = [], []
        self.birth = None
        self.thread = threading.Thread(target=self._sample, daemon=True)
        self.thread.start()

    def _sample(self):
        try:
            with self.path.open('w') as log:
                while not self.stop.is_set() and self.process.poll() is None:
                    started = time.monotonic_ns()
                    result = subprocess.run(['/bin/ps', '-axo', 'pid=,ppid=,pgid=,rss=,lstart='],
                                            check=True, capture_output=True, text=True, timeout=5)
                    rows = process_rows(result.stdout)
                    if self.birth is None and self.process.pid in rows:
                        self.birth = rows[self.process.pid]['birth']
                    owned = owned_processes(rows, self.process.pid, self.birth)
                    sample = dict(startNanos=started, endNanos=time.monotonic_ns(), processes=owned)
                    self.samples.append(sample)
                    log.write(json.dumps(sample, sort_keys=True) + '\n'); log.flush()
                    self.stop.wait(self.interval)
        except BaseException as error:
            self.errors.append(str(error))

    def close(self):
        self.stop.set(); self.thread.join(timeout=6)
        if self.thread.is_alive():
            raise RuntimeError('Process sampler did not stop')
        if self.errors:
            raise RuntimeError('Process sampling failed: ' + '; '.join(self.errors))
        live = [s for s in self.samples if s['processes']]
        if not live:
            raise RuntimeError('No live foreground process sample')
        starts = [s['startNanos'] for s in self.samples]
        return dict(coverage='sampled-live-descendants-including-session-owner', sampleCount=len(live),
                    nominalIntervalNanos=round(self.interval * 1e9),
                    maximumStartGapNanos=max((b-a for a,b in zip(starts, starts[1:])), default=None),
                    maximumObservedProcessCount=max(len(s['processes']) for s in live),
                    maximumObservedSummedRssBytes=max(sum(p['rssBytes'] for p in s['processes']) for s in live),
                    maximumObservedOwnerRssBytes=max(p['rssBytes'] for s in live for p in s['processes'] if p['pid']==self.process.pid),
                    limitations='Sampling can miss short-lived processes and peaks; summed RSS counts shared pages repeatedly. '
                                'Only currently reachable descendants are measured, including fixture applications and witnesses; '
                                'this is not an exact process-creation count or aggregate physical-memory peak.')


class ControlTimings:
    def __init__(self):
        self.pending, self.samples = {}, []

    def sent(self, request, command, when):
        if request in self.pending or any(s['requestId']==request for s in self.samples):
            raise ValueError('Duplicate measured request ID')
        self.pending[request] = (command, when)

    def observed(self, event, when):
        if event['type'] != 'control':
            return
        payload = event['payload']; request = payload.get('requestId')
        if request not in self.pending:
            return  # For example, the separately exercised partial handoff command.
        command, started = self.pending.pop(request)
        if command != payload['command'] or when < started:
            raise ValueError('Control acknowledgement does not match its request')
        candidate = (payload.get('application') or {}).get('candidate')
        self.samples.append(dict(requestId=request, command=command, sentNanos=started, receivedNanos=when,
                                 durationNanos=when-started, sessionId=event['sessionId'], sequence=event['sequence'],
                                 duringBuild=payload.get('active')=='build' or bool(candidate and candidate['phase']=='preparing')))

    def summary(self):
        busy = [s['durationNanos'] for s in self.samples if s['duringBuild']]
        return dict(scope='JSONL command write start through reader receipt of matching acknowledgement; includes pipe and scheduling latency',
                    samples=self.samples, unacknowledgedRequests=sorted(self.pending),
                    duringBuild=dict(sampleCount=len(busy), p95Nanos=percentile95(busy), maximumNanos=max(busy, default=None),
                                     budgetNanos=100_000_000, budgetMet=all(n<100_000_000 for n in busy) if busy else None),
                    limitations='JSONL controls only; physical-key/TTY responsiveness is a separate gate. Sampling adds observation overhead.')
