"""Private, invocation-owned process-group supervisor for source acquisition.

The owner supplies fresh pipes only. The command leader is observed without
reaping until the entire group has received TERM/KILL, preventing PGID reuse.
No package scripts are loaded by this helper.
"""
import json
import os
import select
import signal
import subprocess
import sys
import time
import importlib.util
from pathlib import Path

sys.dont_write_bytecode = True
if __package__:
    from bootstrap import process_barrier as barrier
else:
    _spec = importlib.util.spec_from_file_location("kvik_process_barrier", Path(__file__).with_name("process_barrier.py"))
    barrier = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(barrier)

REQUEST_LIMIT = 4 * 1024 * 1024
TERM_GRACE_SECONDS = 0.2
REAP_GRACE_SECONDS = 0.5


def main():
    owner, request_fd, result_fd, child_in, child_out, child_err = map(int, sys.argv[1:7])
    deadline = float(sys.argv[7])
    process = None
    completion = None
    interrupted = False
    outcome = dict(outcome="setup-error", exit_code=None, limit_stream=None, error=None)
    flags = os.WEXITED | os.WNOHANG | os.WNOWAIT

    def stop(_number, _frame):
        nonlocal interrupted
        interrupted = True

    signal.signal(signal.SIGCHLD, signal.SIG_DFL)
    for number in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(number, stop)

    def stopping():
        if interrupted:
            return dict(outcome="setup-error", exit_code=None, limit_stream=None, error="Acquisition guardian interrupted")
        if select.select([owner], [], [], 0)[0]:
            reason = os.read(owner, 1)
            if reason == b"t":
                return dict(outcome="timed-out", exit_code=124, limit_stream=None, error=None)
            if reason in (b"o", b"e"):
                return dict(outcome="output-limit", exit_code=None, limit_stream="stdout" if reason == b"o" else "stderr", error=None)
            if reason in (b"i", b"s"):
                return dict(outcome="exited", exit_code=130 if reason == b"i" else 143, limit_stream=None, error=None)
            return dict(outcome="setup-error", exit_code=None, limit_stream=None, error="Acquisition owner disappeared")
        if time.monotonic() >= deadline:
            return dict(outcome="timed-out", exit_code=124, limit_stream=None, error=None)
        return None

    try:
        barrier.check()
        data = bytearray()
        while True:
            reason = stopping()
            if reason is not None:
                outcome = reason
                return
            ready, _, _ = select.select([request_fd, owner], [], [], min(0.02, max(0, deadline - time.monotonic())))
            if request_fd not in ready:
                continue
            chunk = os.read(request_fd, 65536)
            if not chunk:
                break
            data.extend(chunk)
            if len(data) > REQUEST_LIMIT:
                raise ValueError("Acquisition request exceeds control bound")
        configuration = json.loads(data)
        os.close(request_fd)
        request_fd = None
        reason = stopping()
        if reason is not None:
            outcome = reason
            return
        # Only the completion capability reaches the child; control pipes do not.
        completion = barrier.Completion()
        process = completion.spawn(configuration["argv"], cwd=configuration["cwd"], environment=configuration["environment"],
                                   stdin=child_in, stdout=child_out, stderr=child_err,
                                   start_new_session=True)
        for fd in (child_in, child_out, child_err):
            os.close(fd)
        child_in = child_out = child_err = None
        while True:
            reason = stopping()
            if reason is not None:
                outcome = reason
                break
            info = os.waitid(os.P_PID, process.pid, flags)
            if info is not None:
                code = info.si_status if info.si_code == os.CLD_EXITED else 128 + info.si_status
                outcome = dict(outcome="exited", exit_code=code, limit_stream=None, error=None)
                break
            select.select([owner], [], [], min(0.02, max(0, deadline - time.monotonic())))
    except Exception as failure:
        outcome = dict(outcome="setup-error", exit_code=None, limit_stream=None,
                       error=f"{type(failure).__name__}: {failure}"[:256])
    finally:
        try:
            os.write(result_fd, b'{"cleanupStarted":true}\n')
        except BrokenPipeError:
            pass
        if process is not None:
            try:
                barrier.clean_group(process, completion, grace=TERM_GRACE_SECONDS)
            except Exception as failure:
                outcome.update(outcome="setup-error", error=f"Group cleanup failed: {failure}"[:256])
                barrier.poison()
        elif completion is not None:
            completion.close()
        for fd in (request_fd, child_in, child_out, child_err):
            if fd is not None:
                os.close(fd)
        try:
            # Bounded control frame, drained concurrently and separate from command output.
            os.write(result_fd, json.dumps(outcome, separators=(",", ":")).encode() + b"\n")
        except BrokenPipeError:
            pass
        os.close(result_fd)
        os.close(owner)


if __name__ == "__main__":
    main()
