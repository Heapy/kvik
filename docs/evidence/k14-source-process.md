# K14 owned acquisition process boundary

`bootstrap/source_process.py` provides the frozen `OwnedProcessRequest`,
`OwnedProcessResult`, `OwnedProcessCancelled`, and `run_owned(request)` API.
It does not acquire sources itself or change the existing launcher/guardian
interfaces. The source store supplies each exact executable, argument tuple,
working directory, environment, binary stdin and stream quotas.

Requests defensively copy argv and environment. The executable and working
directory must be absolute; no shell or executable PATH lookup is performed.
The child receives exactly the supplied environment. Calls require the main
thread; concurrent invocations use independent owner processes and pipes.
Validation/setup failures return `setup-error`. Ordinary nonzero exits remain
`exited`; signal exits use `128 + signal`. SIGINT/SIGTERM received by the owner
raise `OwnedProcessCancelled(signal_number, result)` after cleanup and restoration
of both prior handlers.

Bounds are 256 KiB each for encoded argv and environment (including separators),
1 MiB stdin, stdout up to 64 MiB + 4096 bytes, stderr up to 1 MiB, and finite
`0 < timeout_seconds <= 120`. Zero output quotas are supported. The cwd string
is additionally bounded to 4096 encoded bytes. Output overrun returns
`output-limit` with the affected stream and bounded binary prefixes; it never
returns a truncated success, even when the command exits before the parent
observes the overrun. No text decoding is applied to command output.

The parent concurrently drains stdout/stderr and writes bounded stdin through
fresh nonblocking pipes. A separate invocation-owned Python guardian receives
bounded request data and returns a separate control stream limited to 4096
bytes. The helper runs under an absolute interpreter with `-I -S` and a fixed
minimal environment. It starts the command in its own process group and uses
non-reaping `waitid` to retain the leader's PID/PGID until group cleanup. Ownership
pipe EOF kills that group even when the owner receives SIGKILL. Normal leader
exit also triggers cleanup, preventing descendants from surviving a successful
command.

The deadline covers startup, stdin and output drain. Cleanup allows up to two
additional seconds, subject to OS scheduling; the guardian sends TERM, waits
200 ms, sends KILL and reaps its direct child. A separate cleanup-start control
frame prevents completed I/O from being relabeled a timeout solely because the
guardian is still cleaning up. Success requires both stream EOFs, a valid terminal
control result and guardian reaping.

This is process-group ownership, not a hostile-code sandbox. Children must not
escape through `setsid`/process-group changes or credential changes. Uninterruptible
processes, a guardian killed independently by SIGKILL, and arbitrary kernel/filesystem
stalls cannot be guaranteed cleaned up within the bound. Orphan descendants are
reaped by the OS after termination; the guardian reaps its own command leader.
The helper is invocation-owned and has no persistent service lifetime.

## Validation

```sh
cd /private/tmp/kvik-k14-source-process
PYTHONDONTWRITEBYTECODE=1 python3 tests/bootstrap/test_source_process.py -v
```

The final retained run passed all **11 groups in 14.291 seconds**. They cover:

- NUL/invalid UTF-8 bytes, stdin EOF, and simultaneous full input/output pipes.
- Exact environment isolation/defensive copy, literal argv and cwd.
- Nonzero and signal exits, ordinary exit 124, exec/cwd failure, bounded Unicode
  setup errors, and a child closing stdin early.
- Both output floods, zero quotas, and output overrun after a fast child exit.
- Deadline while a started child leaves stdin unread, plus handler restoration.
- Deadline versus cleanup separation, using only a test helper with extended
  TERM grace to make the two boundaries deterministic under concurrent host load.
- Invalid bounds/types and rejection outside the main thread.
- Descendant cleanup after leader exit and timeout, including a synchronized
  descendant that ignores TERM.
- SIGINT/SIGTERM exception semantics after cleanup, SIGKILL owner loss, and two
  independent owners without cross-cancellation.

Tests check recorded leader, descendant and guardian PIDs with `ps` until no live
process remains. This requires process-inspection permission in restricted test
environments. No compiler jobs are involved. Exact final source hashes, command,
exit status and test-log digest are retained in
`.kvik/tests/source-process/receipt.json`, with `tests.log` alongside it.

Source-graph provisioning, sanitized Git policy, transport/archive bounds and the
full remote runtime build remain separate integration gates.
