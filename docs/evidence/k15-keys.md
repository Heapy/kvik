# K15 immediate terminal controls: bounded slice

This slice adds foreground TTY `r/t/q/?` without Enter, an invocation-owned
restoration lease, and compatible same-PID raw-terminal continuation. Explicit
line controls and JSONL version 1 remain available. It does not implement
application launch/restart or claim all K15 lifecycle gates are complete.

## Contract

`dev` defaults TTY input to `keys`; non-TTY input still requires an explicit
`jsonl` or `lines` mode. Ctrl-D means EOF, Ctrl-C retains SIGINT, and CR/LF are
ignored. IXON, suspend keys and extended/literal-next handling are disabled while
keys mode owns the TTY. Unsupported initial terminal modes and nonforeground or
noncontrolling TTYs fail explicitly. See `integration/foreground/README.md` for
the exact ICANON/ISIG/control-character requirements and stream ownership limits.

All cooperating TTY modes flock an independently reopened terminal before mode
inspection. The parent holds that open-file description and a zero-initialized
original termios backup before its direct-child guardian can mutate terminal
state. The guardian acknowledges entry/restoration, restores on owner pipe EOF
or owner loss, and exits. It never starts workers or reads terminal input.
Normal exit waits for restoration; helper death or stop causes owner backup
restoration and session failure. No inherited F_SETFL changes occur.

Raw handoff uses a bounded v2 continuation containing an opaque macOS/ABI-bound
lease; legacy v1 line/JSON continuations are preserved. An owned capability query
must bind the actual replacement composition/runtime and lease ABI. Only then,
after children and complete output frames drain, the locked TTY and two internal
pipes cross exec. Their CLOEXEC flags are restored on adoption or failed exec.
Owner/TTY/pipe identities, direct-child ownership and the guardian cookie challenge
are checked. Pending signals and unread kernel key bytes survive same-PID exec.
The digest-bound local handoff and executable/source ancestors remain trusted;
the cookie/identities are consistency checks, not hostile same-user authentication.

## Reproduction

From `/private/tmp/kvik-k15-raw-controls`:

```sh
python3 tools/test_foreground_keys_boundary.py
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_foreground_keys.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
```

The Native harness builds directly with pinned konanc/cinterop and source-built
C helpers, with Kotlin/Native airplane mode enabled. `--skip-build` reuses retained
binaries; `--skip-unit-build` rebuilds only the integration probe. PTY brokers
remain controlling-session leaders while restoration is measured, then exit.
Every tracked coordinator, guardian, worker and descendant is checked for cleanup.

## Observations

Retained light C matrix:
`.kvik/tests/foreground-keys/boundary-kmyyqv4a/summary.json`.
All 19 cases pass:

- Quit, Ctrl-D, physical Ctrl-C, TERM, owner SIGKILL, helper SIGKILL and helper
  SIGSTOP, plus loss of the owner's separate parent.
- Exact original/restored native termios bytes and inherited stdin/stdout
  F_GETFL/F_GETFD comparisons. Queued input after quit survives restoration.
- Failed exec retains the old usable lease and CLOEXEC state. Successful exec
  retains the same owner/helper PIDs. Pending q, INT and TERM survive exec.
- Modified lease owner, FD kind/identity and original termios are rejected.
- Same-device keys and line competitors fail; two independent PTYs work.
  Simultaneously released canonical contenders produce exactly one winner.
- Unsupported noncanonical initial input fails without mode changes.

Retained actual Kotlin/Native controller matrix:
`.kvik/tests/foreground-keys/native-3slafhv2/summary.json`.
Seven Native protocol/legacy continuation tests and ten actual PTY sessions pass:

- Immediate r/t/? during a blocking owned child; coalescing preserves pending test
  intent. Quit, Ctrl-D, Ctrl-C, TERM and SIGKILL clean worker descendants.
- Two independent PTYs retain independent controls and ownership.
- A compatible replacement executes in the same PID and adopts the raw lease;
  linked session evidence and v2 continuation validate against the session schema.
- An incompatible replacement cannot restart or open the stale-work gate; manual
  retry after repair succeeds. A blocked owned capability query and its descendant
  cancel on q while controls remain usable.

These Native integration fixtures use deliberate worker/bootstrap stand-ins to
isolate controller behavior. They are not evidence of new consumer compilation
or actual source-plugin recompilation. The previous recomposition slice retains
that evidence separately. The public CLI composed-build check for this slice is
pending the shared compiler queue; no passing result is claimed here yet.

`tools/test_foreground_keys_cli.py` prepares that public gate. It is separate from
the frozen production candidate and has only passed Python syntax/import/help
checks so far. It accepts the exact combined `--source-root` and `--runtime`,
copies the source and immutable passed two-module fixture, and refuses an initial
composition mismatch. Its planned run performs one actual source-plugin rebuild,
checks immediate keys and the same owner/restorer across raw handoff, executes the
exact Native consumer test with valid tools, queries current verification, and
compares exact terminal/descriptor restoration after q and physical Ctrl-C.

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_foreground_keys_cli.py \
  --runtime /absolute/path/to/reviewed/runtime/kvik.kexe \
  --source-root /absolute/path/to/that/runtime/source \
  --fixture /Users/yoda/dev/pet/kvik/.kvik/tests/native-scope-review/case-6jrzkpwg \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
```

This command starts real Native compilation and must use an allocated compiler
slot. No successful public-gate result is implied by the prepared harness.

The first integration-probe compile found a test-only POSIX `read` size literal
type error; it was corrected to `1uL` before the passing run. No test was weakened.

## Boundaries

The guardian restores using TCSANOW, never an input flush. On Darwin a nonconsuming
FIONREAD processes PENDIN after canonical restoration before exact comparison.
Darwin's FWASWRITTEN/FWASLOCKED are kernel history bits, not F_SETFL mode changes;
the tests compare inherited flags after ordinary initial output has established
its normal write history. Independent terminal descriptors keep the caller's
descriptor flags unchanged.

The lease excludes cooperating users, not arbitrary noncooperating readers or
writers. Simultaneous owner and helper destruction, a disappeared TTY, external
TTY mutation and uninterruptible kernel/filesystem calls cannot offer a hard
restoration deadline. Helper failure while the owner remains alive is detected
and restored from its backup. Local journal IO remains synchronous. Capability
queries and all expensive hashing/build work stay outside the control loop.
