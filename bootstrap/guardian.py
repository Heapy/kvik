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


def main():
    owner, result = map(int, sys.argv[1:3])
    timeout = float(sys.argv[3])
    command = sys.argv[4:]
    interrupted = False
    process = None
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
        if not command or timeout <= 0:
            raise ValueError("Invalid bootstrap guardian command/timeout")
        if select.select([owner], [], [], 0)[0]:
            outcome.update(exitCode=130, cancelled=True)
        else:
            # close_fds prevents compiler descendants from retaining the ownership pipe.
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, close_fds=True, start_new_session=True)
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
        if process is not None:
            for number in (signal.SIGTERM, signal.SIGKILL):
                try:
                    os.killpg(process.pid, number)
                except ProcessLookupError:
                    pass
                except PermissionError:
                    # Darwin returns EPERM for a group containing only zombies.
                    # Credential-changing/privileged compiler children are unsupported.
                    if os.waitid(os.P_PID, process.pid, flags) is None:
                        raise
                if number == signal.SIGTERM:
                    time.sleep(0.2)
            process.wait()
        try:
            os.write(result, json.dumps(outcome, separators=(",", ":")).encode() + b"\n")
        except BrokenPipeError:
            pass  # Owner loss still completes cleanup.
        os.close(owner)
        os.close(result)


if __name__ == "__main__":
    main()
