# Foreground application lifecycle

This slice adds `dev --mode application --module NAME --target jvm|macosArm64`.
The default remains library mode. An application target must declare application
settings. `--initial test` explicitly queues a fresh test operation followed by
application preparation in application mode; library tests never start programs.

Application mode watches application inputs. `r` requests a reload; `t` queues
fresh tests independently, without changing the watched operation. Duplicate
intents coalesce, but tests and reloads do not erase each other. Status exposes
active identity/staleness, candidate phase, and both queued intents.

The session owns two independent application supervisors at most: the active
program and a candidate. Readiness is a canonical, bounded activation record,
validated against the owned candidate token, current input revision, selected
composition, module and target. The candidate owns expensive preparation and
full verification. The session loop reads only readiness/start records; it does
not hash source trees, tools, artifacts, or full journals. Readiness is neither
an application health assertion nor a guarantee that later validation succeeds.

A failed or cancelled candidate keeps the old program. A current ready candidate
may cancel the old supervisor, but activation waits until its ownership tree has
terminated and its handle has closed. Input changes invalidate readiness and mark
the active identity stale. Program exit is reported and waits for reload or an
observed input change. Quit, EOF, signals and owner death retain owned cleanup.

During source recomposition the old application stays alive while bootstrap and
the new runtime prepare a replacement. Every control mode requires explicit v3
continuation capability. Only a validated replacement permits stopping the old
application and waiting candidate; both must be reaped before exec. Resume v3
carries scope, independent pending intents and the digest-bound prepared pointer,
never process handles. The new runtime issues a new activation token and performs
full `stage-prepared` validation. A stale prepared pointer or invalidated handoff
queues one fresh preparation, not an unbounded retry of ordinary failed builds.
If validation fails after the old program was stopped, there is an observable
availability gap; the protocol does not promise zero downtime. Library v1/v2
continuations retain their existing serialized representation and behavior.

## Validation status

Source/test preparation is not a completed acceptance gate. Native unit tests and
actual owned-process protocol fixtures are prepared in
`tools/test_foreground_applications.py`. The fixture worker explicitly simulates
readiness; it is not a compiler or a replacement for public JVM and Native gates.
The harness binds source files, compiler launcher/JAR inputs, a controlled
compiler environment, commands and produced binaries in its receipt. Its SDK and
JDK tracking is a focused test-build receipt, not a complete installed-toolchain
inventory or a portable binary reproducibility claim.

Focused Python schema checks pass. Native controller compilation, real protocol
lifecycle results, and full public JVM/Native foreground acceptance remain
pending. No application foreground completion claim is made at this point.
