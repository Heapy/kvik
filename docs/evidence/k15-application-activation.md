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
Both retain the validated launch plan privately; readiness never supplies
trusted executable arguments.

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
logs and effects, and uses a real Kotlin JVM application. Planned checks include
no effects before activation, wrong token/digest/shape, cancellation/owner loss,
post-ready source or bundle corruption, old process and descendants reaped
before replacement activation, 60-second deadline, and public one-shot/replay.
Schema tests (2 groups), help, syntax and diff checks pass. Native protocol and
composed-runtime/consumer evidence are pending their matching builds.
