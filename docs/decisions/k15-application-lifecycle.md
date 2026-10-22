# Application artifacts and foreground lifecycle

Application support extends the existing build/test library graph. A target may
optionally declare `application: {entryPoint, arguments?, environment?}`. JVM entry
points are binary class names, including the `Kt` class for a top-level main;
Native entry points are qualified Kotlin function names. Arguments and declared
environment belong to execution settings, never compiler options. Existing
library configurations keep their meaning and ordinary builds remain library
builds. The initial targets are JVM and macosArm64.

## Artifact preparation

`ProjectMode.APPLICATION` adds a `module:target:application` requested check.
Its terminal package action has implementation `kvik-application-bundle-v1` and
LOCAL cache policy. Preparing it cannot execute the application. JVM preparation
includes ordered main/runtime module classes, all runtime resources and locked
java-runtime jars, including runtime-only dependencies. Native preparation links
the main klib and complete kotlin-runtime closure as a PROGRAM using the declared
entry point. Native compilation remains separate from linking; application
arguments/environment must not invalidate main compilation or linking.

Core `ApplicationLaunch(module, target, application, classpath, executable)` is
portable metadata with schema version 1. Paths are relative to its input tree.
JVM requires a nonempty unique ordered classpath and no executable; Native
requires one declared executable file and no classpath. All referenced paths
must exist in declared inputs. Settings are bounded to 64 KiB canonical UTF-8;
launch metadata is bounded to 256 KiB. Unknown fields, malformed Unicode/NULs,
invalid target/entry-point combinations and undeclared paths are errors.
JSON Schema provides structural authoring validation; the Kotlin parser remains
authoritative for UTF-16 field lengths, valid Unicode and total canonical UTF-8
byte bounds. JSON Schema's code-point lengths alone cannot prove those limits.

`applicationBundleAction(launch, inputs, composition, toolchains)` lives in
`kvik.application`. It creates a PACKAGE action with declared output root
`application`. The deterministic result contains `application/launch.json` plus
all inputs under `application/input/`; metadata and input bytes are CAS verified.
`ApplicationBundleExecutor(content)` handles this action without starting a
process. It validates the exact action contract, packages exactly the provided
inputs, and reports corruption as failure. The graph selects the runtime closure
and excludes unrelated source/compiler-plugin outputs. Packaged outputs can be
reused; application execution cannot.

## One-shot application commands

`prepare-application` uses the build options with exactly one explicit module and
target. It materializes the terminal bundle under the retained run's `prepared/`
directory and writes `prepared.json`, binding the run, terminal action, complete
verification record and output tree by digest. An `application-prepared` event
publishes the run directory and descriptor digest. The following `run-finished`
event and committed journal boundary must succeed before that handoff is usable.
If declared inputs change during materialization, the run becomes incomplete.

`run-application` prepares then executes the application once. Both phases share
one interruption-handler lifetime, so cancellation cannot be reset between them.
`run-prepared --run PATH --descriptor DIGEST_JSON` explicitly replays a retained
preparation. Every execution rehashes the complete materialized tree, verifies
the canonical descriptor and reconstructed package key, checks the committed
verification journal, and rechecks current project inputs, runtime composition
and tool installations. A cached or old preparation is not evidence of program
execution, and stale preparations cannot run.

The application uses the project root as its working directory and `/dev/null`
as stdin. Its stdout/stderr go to private files in a fresh application-run
directory; JSONL reports their paths in `application-started` and
`application-finished`. The application phase has its own run identity and
journal. Failures after an event stream starts are reported on stderr without
inserting non-event diagnostics into stdout. The application exit code propagates
to the caller; cancellation returns 130.

The runner inherits no ambient environment. Its baseline is
`PATH=/usr/bin:/bin:/usr/sbin:/sbin`, `LANG=C`, `LC_ALL=C`, and `TZ=UTC`, overridden
only by declared application settings. It additionally provides the reserved
`KVIK_APPLICATION_INPUTS` path to the materialized input tree; Native resources
are available beneath `runtime/<module>/main/`. Declaring that reserved variable
is an error. JVM arguments remain literal, with argument-file expansion disabled
before the main class. JVM bundle directories containing the classpath separator
`:` are rejected. Native applications execute their declared verified executable.

Application processes have no deadline, but retain ownership: interruption,
owner loss, close and ordinary leader exit reap remaining descendants within the
existing process-group boundary. Compilation, testing and packaging keep positive
action deadlines. These commands do not create a background service.

## Continuous-mode integration still required

Foreground integration will use the same preparation and runner validation
contracts. Large hashing/materialization stays in owned children, outside the
terminal control loop. The session must not turn cached preparation into a
claimed run, and candidate validation must finish before replacing the old app.

`dev` application mode will own an additional process independently of its
snapshot/build child. Source observations can mark the running application old;
a failed/cancelled/stale preparation keeps that old instance and reports its
identity accurately. A successful current preparation becomes a candidate;
the old instance is cancelled and reaped before starting the replacement.
Application crashes and launch failures must be visible and recoverable through
reload. Tests do not silently launch applications. Shutdown/recomposition must
cancel and reap application descendants as well as build/observer children.
Runtime replacement and consumer application restart are distinct transitions.

These are required subsequent integration and acceptance steps, not capabilities
claimed by the artifact contract alone. Gates include real JVM and Native
programs, resources/runtime-only dependencies, unchanged compilation after run
argument edits, watch/reload, failed build followed by a fix, crash, long-lived
process ownership, signal/parent-loss cleanup, two independent sessions, and
source-plugin handoff while an application is running. No background service or
unowned process is permitted.
