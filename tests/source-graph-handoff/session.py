"""JSONL foreground fixture ownership and exact stream/journal observation."""
import json
from pathlib import Path
import queue
import signal
import subprocess
import threading
import time


def owned(pid):
    rows = [line.split(None, 2) for line in subprocess.run(
        ['/bin/ps', '-axo', 'pid=,ppid=,command='], capture_output=True, text=True, check=True).stdout.splitlines()]
    found = {pid}
    while True:
        more = found | {int(row[0]) for row in rows if int(row[1]) in found}
        if more == found:
            return {int(row[0]): row[2] for row in rows if int(row[0]) in found}
        found = more


def alive(pid):
    result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'stat='], capture_output=True, text=True, check=False)
    return result.returncode == 0 and not result.stdout.strip().startswith('Z')


class Session:
    def __init__(self, directory, argv, environment, validate):
        directory.mkdir()
        self.directory = directory
        self.validate = validate
        self.events = []
        self.lines = []
        self.sequences = {}
        self.messages = queue.Queue()
        self.tracked = set()
        self.error = (directory/'stderr.log').open('wb')
        self.log = (directory/'events.jsonl').open('wb')
        (directory/'command.json').write_text(json.dumps({'argv':argv,'environment':environment}, indent=2)+'\n')
        self.process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.error, env=environment)
        def reader():
            try:
                for line in self.process.stdout:
                    self.log.write(line); self.log.flush()
                    self.messages.put(line)
            except Exception as error:
                self.messages.put(error)
            finally:
                self.messages.put(None)
        self.thread = threading.Thread(target=reader, daemon=True)
        self.thread.start()
    def raw(self, data):
        self.process.stdin.write(data); self.process.stdin.flush()
    def send(self, command, request=None):
        self.raw(json.dumps({'schemaVersion':1,'requestId':request or command,'command':command}).encode()+b'\n')
    def next(self, timeout=120):
        line = self.messages.get(timeout=timeout)
        if line is None: return None
        if isinstance(line, Exception): raise line
        event = json.loads(line)
        self.validate(event, 'event', True)
        sid = event['sessionId']; self.sequences[sid] = self.sequences.get(sid, 0)+1
        assert event['sequence'] == self.sequences[sid]
        self.events.append(event); self.lines.append(line)
        return event
    def wait(self, kind, predicate=lambda event:True, timeout=120):
        deadline = time.monotonic()+timeout
        while True:
            event = self.next(max(.01, deadline-time.monotonic()))
            assert event is not None, (kind, self.process.poll(), self.events[-5:])
            if event['type'] == kind and predicate(event): return event
    def finish(self, reason='q'):
        tree = owned(self.process.pid); self.tracked.update(tree)
        started = time.monotonic()
        if reason == 'q': self.send('q')
        elif reason == 'signal': self.process.send_signal(signal.SIGTERM)
        else: raise ValueError(reason)
        self.process.wait(timeout=10)
        assert self.process.returncode == (130 if reason == 'signal' else 0)
        self.thread.join(timeout=5); assert not self.thread.is_alive()
        while self.next(timeout=1) is not None: pass
        self.log.close(); self.error.close()
        self.process.stdin.close(); self.process.stdout.close()
        elapsed = time.monotonic()-started
        assert elapsed < 5, elapsed
        for pid in self.tracked:
            deadline = time.monotonic()+5
            while alive(pid) and time.monotonic()<deadline: time.sleep(.02)
            assert not alive(pid), ('owned process survived',pid,tree.get(pid))
        assert self.events[-1]['type'] == 'session-finished'
        assert self.events[-1]['payload']['reason'] == ('signal' if reason=='signal' else 'quit')
        for start in [e for e in self.events if e['type']=='session-started']:
            journal = Path(start['payload']['directory'])/'session.jsonl'
            assert journal.read_bytes() == b''.join(line for line in self.lines if json.loads(line)['sessionId']==start['sessionId'])
        return {'exitCode':self.process.returncode, 'seconds':elapsed, 'trackedProcesses':tree}
    def abort(self):
        if self.process.poll() is None:
            self.process.terminate()
            try: self.process.wait(timeout=10)
            except subprocess.TimeoutExpired: self.process.kill(); self.process.wait(timeout=10)
        self.thread.join(timeout=5)
        for stream in (self.process.stdin,self.process.stdout,self.log,self.error):
            if not stream.closed: stream.close()
