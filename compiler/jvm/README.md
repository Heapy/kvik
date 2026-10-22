# JVM workers, adapter version 1

These are invocation-owned tool workers for the Kotlin/Native orchestrator. Each process accepts one request, publishes one atomic JSON result, and exits. Neither worker is a JVM orchestrator or daemon. The native owner supplies the explicit environment, working directory, logs, timeout and process-group cleanup through K03.

## Compilation

Launch with a bootstrap classpath containing the compiled worker/core classes, `kotlin-build-tools-api:2.4.20`, and `kotlin-stdlib:2.4.20`:

```text
java -cp <worker-bootstrap-classpath> kvik.jvm.CompilerWorkerKt \
  <absolute-workspace> <request-relative> <result-relative> \
  <absolute-compiler-artifact-directory> <absolute-distribution-manifest>
```

The request and result are the existing K02 `WorkerRequest` and `WorkerResult`. Every source, consumer classpath entry, plugin classpath entry and output path in the request is a logical path inside the action workspace. Existing symlink components, symlinks inside classpath directories, non-jar classpath files, duplicate classpath/source entries and pre-existing output directories are rejected. The orchestrator must stage immutable, verified inputs into that workspace. The worker does not resolve dependencies.

`request.compiler` hashes the canonical `compilerDistribution` manifest in [protocol.schema.json](protocol.schema.json), including the ordered compiler implementation artifact paths/digests and the optional serialization plugin digest. This manifest identifies the complete BTA/compiler distribution, not just `kotlin-compiler-embeddable.jar`. The worker verifies every artifact's size and SHA-256 before loading BTA and verifies the loaded compiler version is exactly 2.4.20. The launcher separately verifies the worker bootstrap and JDK identities as part of the native action/runtime composition.

Compilation calls `createInProcessExecutionPolicy()` explicitly and never selects a daemon policy or fallback. No incremental compiler state is enabled. Compiler classes are loaded from the verified implementation classpath; they are not exposed in the consumer classpath. The worker disables implicit stdlib/reflection discovery: dependencies must be supplied explicitly.

The supported options are:

- `-jvm-target` with 11, 17, 21 or 25; `-module-name` with letters, digits, underscores, dots and hyphens.
- `-language-version` and `-api-version` with 2.4 only.
- `-opt-in` with a qualified annotation name; repeatable.
- `-Xexplicit-api=strict`, `-Werror`, `-Xmulti-platform`, `-Xexpect-actual-classes`.
- `-Xcommon-sources` with comma-separated paths already declared in `sources`.
- `-Xfriend-paths` with comma-separated paths already declared in `classpath`.

Other flags, duplicate non-repeatable options, arbitrary `-d`/classpath flags and arbitrary plugin flags are rejected. The only supported plugin is the single manifest-pinned serialization plugin, loaded under `org.jetbrains.kotlinx.serialization`; plugin options and other plugins are currently unsupported. Plugin and consumer classpaths remain separate.

The BTA `CompilerMessageRenderer` supplies structured compiler warnings/errors and source locations. Messages use stable Kvik diagnostic codes, with logical source paths where possible. Tool stdout/stderr are logs, not protocol records. Outputs compile into a new staging directory and are exposed by atomic rename only after successful compilation. Compilation errors and handled infrastructure failures remove staging output. A killed worker can leave an unexposed staging directory for the run owner to clean.

The native caller must require a complete result file, matching request ID, consistent success/exit status, successful owned-process completion and validated output inventory before reporting success or publishing a cache entry. These workers do not themselves publish caches.

## JVM tests

Launch a fresh JVM with the compiled worker/core classes, JUnit Platform 1.13.4, Kotlin stdlib and **every declared test runtime classpath entry** on its process classpath:

```text
java -cp <worker-and-declared-test-runtime-classpath> kvik.jvm.TestWorker \
  <absolute-workspace> <request-relative> <result-relative>
```

Keeping stdlib and reflection in the same JVM classloader is required for Kotest. The worker requires its process classpath to equal exactly three bootstrap entries (worker/core classes, the pinned JUnit Platform standalone bundle, Kotlin stdlib), followed by the ordered declared test classpath. It anchors those bootstrap entries to the loaded worker, launcher and stdlib code sources; the native composition must verify their identities. Additional, reordered and duplicated process entries are rejected. The request classpath itself must be unique; a declared stdlib may also occur in the bootstrap prefix. A fresh process provides request isolation; no classloader or test-engine session is reused between requests.

The adapter-local `testRequest`/`testResult` definitions live in [protocol.schema.json](protocol.schema.json), which references existing core `TestResult`, `TestCounts`, `Diagnostic` and `Digest` definitions. Register the local core schema with a validator; its HTTPS `$id` is an identifier, not a service to fetch.

Requests contain `schemaVersion`, `requestId`, `module`, `target` (`jvm`), ordered `classpath`, exact `testClasses`, and `engines` (`junit-jupiter` and/or `kotest`). Unknown fields, unsupported engines and missing engines fail explicitly. An empty selection is incomplete and does not trigger classpath-wide discovery. Tags, method selectors, arbitrary engine configuration and discovery generators are not supported in this version.

Results preserve the framework's unique IDs, scoped by module/target, including dynamically registered tests. The runner records executed passes/failures, skipped/aborted tests and container failures. It never fabricates a leaf test identity for a container failure. An assumption-aborted container marks its known unexecuted leaf descendants skipped. `discoveryComplete` requires completed execution and a terminal result for every discovered leaf. Discovery/infrastructure exceptions retain an unknown discovered count rather than claiming zero. Aborted tests count as skipped and cannot provide passing execution evidence.

Exit codes: 0 for complete passing execution with at least one executed test; 1 for a failed test, container or infrastructure operation; 3 for zero-test, all-skipped or incomplete execution. Tests run on every invocation, even when compilation outputs are reused; the native graph must use `ActionKind.TEST` and `CachePolicy.NEVER`.

## Scope

`tools/test_jvm.py` builds these workers directly with the pinned compiler artifacts. It exercises a two-module common/JVM fixture and can compile/run the unchanged pinned kotaml modules. It does not invoke Gradle, Kotlin Toolchain, KSP or nested builds. The kotaml artifact lock preserves a concrete reference dependency closure and verifies downloaded bytes; it is not a general dependency resolver. Native CLI action construction, cache integration, test-result integration into full verification records and general generator/compiler-plugin support remain separate integration work.
