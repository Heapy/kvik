# K15 application activation boundary

This slice adds an internal owned-supervisor gate. It does not implement the
foreground Session/Dev application state machine or source-runtime handoff.
Public one-shot run-application and run-prepared retain immediate validated
execution. All paths keep the same interrupt latch through their owned phases.

Internal commands:

```
stage-application --activation DIR --token DIGEST_JSON [normal build options]
stage-prepared --activation DIR --token DIGEST_JSON --run PATH --descriptor DIGEST_JSON
```

DIR is a dedicated existing empty absolute normalized directory, owned by the
caller, outside mutable project inputs. The token is canonical Digest JSON,
bounded to 256 UTF-8 bytes. Preflight errors precede the build event stream.
Stage-application completes executeBuild(APPLICATION), including committed run
completion, then validates the prepared bundle and full current source/tool and
journal evidence. Stage-prepared performs that validation without building.
Readiness also verifies the runtime guardian bytes required for ownership; the
process host verifies them again after activation. The owned validator also
checks effective executable access for both the guardian and the verified Java
or materialized Native entry executable before readiness and after activation.
Both retain the validated launch plan privately; readiness never supplies trusted
executable arguments.

`kvik.foreground` provides immutable ApplicationReady(token,
preparationDirectory, descriptor, verification, composition, module, target),
ApplicationActivation(token, ready), and ApplicationStarted(token, ready,
executionDirectory). Each exposes toJson and strict parse(ByteArray). Every
record is exact canonical UTF-8 JSON, at most 8192 bytes, rejects unknown fields,
invalid versions/Unicode/paths/digests, and has schema version 1. The shared JSON
Schema describes structural fields; Native parsing additionally enforces
canonical bytes, valid Unicode, UTF-16 path lengths, total UTF-8 bounds and
cross-record digest equality.

The supervisor atomically publishes ready.json after validation and waits at
most 60 seconds for activate.json. Activation must bind both its token and the
SHA-256/size of the exact ready bytes. No application is launched while waiting.
Cancellation before activation returns 130. Timeout or malformed activation
fails without executing application code. Readiness is not an application-start
or current-forever claim.

After activation it revalidates the complete materialized bundle, committed
journal, source snapshot, composition and installed tools, then compares the
new private launch plan to the readiness plan. A change during an old
application's drain can therefore leave a gap; it fails explicitly rather than
starting stale code. The caller must keep roots/files stable through use under
the existing filesystem trust boundary.

Only after ProcessHost.start returns does the supervisor atomically publish
started.json with token, exact readiness digest and actual execution directory.
This means process-host acceptance, not health or successful exit; existing
application-started/finished events and final process result remain authoritative.
The actual application's timeout stays zero, preserving ownership without a
fixed lifetime. Signal, cancellation, owner loss and ordinary leader exit retain
existing guardian cleanup. A started.json publication failure occurs inside
process.use and host.use and therefore closes the owned process. This cleanup
path is source-reviewed; the focused actual gate does not use an unsafe timed
race to claim injection precisely between host.start and publication.

The protocol is separate from old-application management: its caller must verify
candidate generation/revision/composition, cancel and reap the old supervisor,
then publish activation. No PID/guardian survives source-runtime exec in this
slice. Full foreground restart/resume acceptance remains subsequent work.

Focused reproduction:

```
python3 -B tools/test_application_activation_protocol.py
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tests/application-activation/test_schema.py
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_application_activation.py --runtime PATH --tools PINNED_TOOLS_JSON
```

The protocol harness binds sources/compiler support/command/environment and
rejects stale --no-build use. Unhashed installation/SDK support remains stable
through build/reuse. The actual harness verifies the runtime manifest and lock
against the current source composition, records exact commands, protocol bytes,
logs and effects, and uses a real Kotlin JVM application. Checks include
no effects before activation, wrong token/digest/shape, cancellation/owner loss,
post-ready source or bundle corruption, old process and descendants reaped
before replacement activation, 60-second deadline, and public one-shot/replay.
Schema tests (2 groups), help, syntax and diff checks pass. Frozen `84cbe3b`
production plus harness cleanup correction `f444975` built composition
`b08d0edb18ec07738c70f9dcb571f55665581e35cffdbae8b3b8eea1c20e56e3`.
Five Native protocol groups passed in retained
`.kvik/tests/application-activation-protocol/case-m0yl4dni`. The actual JVM gate
passed 20 scenarios in `.kvik/tests/application-activation/case-01l_iqwf`,
including a 60.558-second activation deadline and retained full owned-tree/PGID
cleanup. Its summary SHA-256 is
`e00e3d996926734517018a328f27258a66a0f99e6fe7ad62f8872e42acc48ded`.

That gate predates the pre-readiness guardian verification correction: previously
stage-prepared first checked the guardian only after activation. The correction
checks it before readiness and retains the later host check. Three additional
actual cases copy the runtime bundle, remove/corrupt/deny execute access only on
the copied guardian, and require stage-prepared refusal with no ready/started file or application
effects, with specific failure diagnostics. A fourth case copies the full pinned
JDK, retains its exact canonical inventory identity with owner-readable but
owner-nonexecutable Java mode, and requires the specific executable-access
refusal before readiness. No user tool installation is modified.

Final frozen source/harness `85f2bfe` built composition
`cd80c6f3f9b67b6808d86612e9d449c46a5cbea1003fd65140fbd647d5e11291`
in 93.475 seconds of compiler time (handle 5975, terminal 0). Its executable
SHA-256 is `a90f30e2affbfe8956b1e33e85982a6f3fee0c07bf21583408a0e33b2bf263b8`
(size 4970792). The full actual gate (handle 95905, terminal 0) passed all 24
scenarios in `.kvik/tests/application-activation/case-k7_uvlpc`, including all
four new access/prerequisite refusals and the 60.294-second readiness deadline.
The summary SHA-256 is
`5db5fb4171f490a5258e6ebc32fd8b55fe907f46e6bce73bd4cf253b1851e2e2`.
Protocol source and its five-group compiled receipt are unchanged.

The final case retains exact command arguments, logs, readiness/activation/start
records, committed preparation journals, six application effect records, and
full owned-tree/process-group observations. The old-running and owner-loss-running
ownership receipts have SHA-256
`0ae912554b929b9e49a49f64ed565edf33afd9fa07533e0fcbc3b8b0a01d086c` and
`59824645f798275f955a0eaf01dbb77cfa3c945de01c3e719a8c221c2cc3c4f7`.
The private JDK proof records the unchanged pinned inventory and denied actual
execute access; the project tools document is restored in a finally block.

Exact final invocation from this worktree:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_application_activation.py \
  --runtime /private/tmp/kvik-k15-application-activation/.kvik/runtime/executables/cd80c6f3f9b67b6808d86612e9d449c46a5cbea1003fd65140fbd647d5e11291/kvik.kexe \
  --tools /private/tmp/kvik-k15-application-acceptance/.kvik/tests/application-cli/case-dao5fqft/project/tools.json
```

This proves the standalone staged supervisor boundary with a real JVM consumer.
It does not establish nested foreground-supervisor cleanup, the Session/Dev
state machine, source-runtime resume, or a real Native application's activation.
Readiness establishes checked artifacts, tools and current executable access;
future OS spawn success and application health still require the actual start
and terminal process evidence.
