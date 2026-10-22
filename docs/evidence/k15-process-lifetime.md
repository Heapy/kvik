# Explicit unbounded owned-process lifetime

`ProcessRequest.timeoutMillis = 0` now means no deadline. Positive values keep
their existing bounded-millisecond behavior; negative values fail in the Kotlin
contract and at the C entry point before creating ownership pipes or spawning.
`ActionSpec.timeoutMillis` remains strictly positive. This is an additive process
capability for a future foreground application runner, not application execution
or restart integration.

The Native host passes the literal zero to its invocation-owned guardian. The
guardian validates the full numeric timeout and skips only the deadline check
for zero. Cancellation, host close, owner-pipe EOF, signal handling, ordinary
leader-exit cleanup, and the existing 200 ms TERM-to-KILL grace remain active.
No substitute large timeout or daemon is introduced.

## Reproduction and retained result

From the isolated worktree `/private/tmp/kvik-k15-unbounded-process`:

```sh
python3 -B tools/test_process_lifetime.py
# Reuse only when all stamped sources, environment, tools and binaries still match:
python3 -B tools/test_process_lifetime.py --no-build
```

The harness needs process-table permission. It builds one Native executable and
the C boundary/guardian, using the pinned Kotlin/Native 2.4.20 and JDK 25.0.4
installations, offline Native dependency policy, and an explicit environment.
Its six exact build commands, 20 source identities, eight tool/support identities,
18 harness/bootstrap identities, complete environment and resulting binaries are
bound by `build.json`. This bounded receipt does not inventory the complete SDK,
JDK or Native installation: those installed roots must stay stable during use.

Build/test handle `19026` exited zero. The retained case is
`.kvik/tests/process-lifetime/case-_u5c7iou`, with **10 passing checks**:

- Kotlin accepts zero/positive process deadlines, rejects negative values, and
  still rejects a zero action deadline. The C entry point rejects negative values
  without dereferencing otherwise-invalid fixture arguments.
- A 150 ms process deadline returns `124 / timedOut=true / cancelled=false`.
- Two zero-deadline trees remain alive for more than 0.9 seconds, then stop through
  explicit cancellation and host close. Cancellation reports `130 / false / true`.
- Separate zero-deadline owners receive SIGINT, SIGTERM and SIGKILL. In each case
  the guardian stops TERM-resistant descendants. Observed cleanup took 0.29–0.32
  seconds after the signal.
- An independent zero-deadline owner remains alive across those three shutdowns,
  then is explicitly cancelled.
- Ordinary leader exit preserves exit code 7 and stops remaining descendants.

The eight Native owners terminated. PID and process-group observations found no
running guardian or worker remaining; the predicate treats zombies as stopped,
so this is not a claim that this owner can `waitpid` unrelated reparented children.
The summary retains exact worker commands, PIDs/groups, timings and output hashes.

| Retained file | SHA-256 |
| --- | --- |
| `summary.json` | `9ff5e58898ff44c921f25e4113f440442fd2f9f3e9172bfb7c530ba8736c08ae` |
| `build.json` | `e0f182dfd37e62a39f94be8d0f58cac7d9b968893b330bf9d42c0b16bb66bdf0` |
| `probe.kexe` | `d51ba96966c98931b9ccca54180b232bc923247ad8a8fdae06ea1f6a51ba7639` |
| `guardian` | `cc6e6b741c6136c8e9f48bed63db79ce4646a31b2dc69f41c3b25bf79527fdbb` |
| `boundary` | `98747aab07ab59301078f124222e483a1056e78e44660fbe76ae3d401bf84ea3` |

An initial C compile failed because the harness's constructed environment omitted
`SDKROOT`; no Native compilation or lifecycle worker started in that attempt.
Its inputs/log remain in `build-failed-sdk`. The passing harness obtains SDKROOT
through `/usr/bin/xcrun` under the explicit environment and stamps it. Subsequent
build failures retain their own command, inputs and log in `failed-build-*`.

## Scope

The actual lifecycle checks cover macOS ARM64 with cooperating process groups.
The existing restrictions remain: descendants that deliberately escape their
process group/session and uninterruptible kernel tasks are not contained by this
guardian. Zero removes the deadline only; it does not broaden those guarantees.
No composed CLI, application runner, foreground terminal or compiler consumer was
changed or tested by this slice.
