"""One compiler's bootstrap guardian; exits when its foreground owner disappears.

This source-only helper works before the native runtime/helper has been compiled.
It never polls/reaps the compiler until group cleanup, reserving its PID/PGID.
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

# -I -S intentionally excludes the checkout from sys.path.
sys.dont_write_bytecode = True
if __package__:
    from bootstrap import process_barrier as barrier
else:
    _spec = importlib.util.spec_from_file_location("kvik_process_barrier", Path(__file__).with_name("process_barrier.py"))
    barrier = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(barrier)


def main():
    owner, result = map(int, sys.argv[1:3])
    timeout = float(sys.argv[3])
    command = sys.argv[4:]
    interrupted = False
    process = None
    completion = None
    outcome = {"exitCode": 125, "timedOut": False, "cancelled": False}

    def stop(_number, _frame):
        nonlocal interrupted
        interrupted = True

    signal.signal(signal.SIGCHLD, signal.SIG_DFL)
    for number in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(number, stop)
    try:
        # Fail before starting anything on a Python/platform without non-reaping wait.
        flags = os.WEXITED | os.WNOHANG | os.WNOWAIT
        barrier.check()
        if not command or timeout <= 0:
            raise ValueError("Invalid bootstrap guardian command/timeout")
        if select.select([owner], [], [], 0)[0]:
            outcome.update(exitCode=130, cancelled=True)
        else:
            # close_fds prevents compiler descendants from retaining the ownership pipe.
            completion = barrier.Completion()
            process = completion.spawn(command, environment=os.environ, stdin=subprocess.DEVNULL, start_new_session=True)
            deadline = time.monotonic() + timeout
            while True:
                info = os.waitid(os.P_PID, process.pid, flags)
                if info is not None:
                    outcome["exitCode"] = info.si_status if info.si_code == os.CLD_EXITED else 128 + info.si_status
                    break
                if interrupted or select.select([owner], [], [], 0.01)[0]:
                    outcome.update(exitCode=130, cancelled=True)
                    break
                if time.monotonic() >= deadline:
                    outcome.update(exitCode=124, timedOut=True)
                    break
    except Exception as failure:
        print(f"Bootstrap compiler setup failed: {failure}", file=sys.stderr, flush=True)
        # The owner waits before reading: keep the result below PIPE_BUF even for
        # huge filenames/messages, with full details retained in the compiler log.
        outcome.update(exitCode=125, error=str(failure)[:128])
    finally:
        try:
            if process is not None:
                barrier.clean_group(process, completion)
            elif completion is not None:
                completion.close()
        except BaseException:
            barrier.poison()
            os.close(owner)
            os.close(result)
            raise
        try:
            os.write(result, json.dumps(outcome, separators=(",", ":")).encode() + b"\n")
        except BrokenPipeError:
            pass  # Owner loss still completes cleanup.
        os.close(owner)
        os.close(result)


if __name__ == "__main__":
    main()
