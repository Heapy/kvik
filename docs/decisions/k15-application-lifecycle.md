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

`applicationBundleAction(launch, inputs, composition, toolchains)` lives in
`kvik.application`. It creates a PACKAGE action with declared output root
`application`. The deterministic result contains `application/launch.json` plus
all inputs under `application/input/`; metadata and input bytes are CAS verified.
`ApplicationBundleExecutor(content)` handles this action without starting a
process. It validates the exact action contract, never copies unrelated source
or compiler-plugin artifacts into the runtime closure, and reports corruption
as failure. Its outputs can be reused; application execution cannot.

## Invocation and continuous-mode integration to implement

A public preparation operation will use the existing snapshot/lock/tool
verification and run-evidence path, materialize the verified terminal bundle in
an invocation-owned directory, and publish a bounded digest-bound handoff only
after successful, current evidence. A separate owned application runner will
validate that handoff, bundle and consumed runtime tools before launching.
Large hashing/materialization stays in owned children, outside the terminal
control loop. The session must not turn cached preparation into a claimed run.

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
