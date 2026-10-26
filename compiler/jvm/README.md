# JVM workers, adapter version 1

These are invocation-owned tool workers for the Kotlin/Native orchestrator. Each process accepts one request, publishes one atomic JSON result, and exits. Neither worker is a JVM orchestrator or daemon. The native owner supplies the explicit environment, working directory, logs, timeout and process-group cleanup through K03.

## Compilation

Launch with a bootstrap classpath containing the compiled worker/core classes and the selected profile's `kotlin-build-tools-api` and `kotlin-stdlib`. The default JVM compiler is 2.4.20; the alternate JVM profiles are 2.4.10 and 2.4.21:

```text
java -cp <worker-bootstrap-classpath> kvik.jvm.CompilerWorkerKt \
  <absolute-workspace> <request-relative> <result-relative> \
  <absolute-compiler-artifact-directory> <absolute-distribution-manifest>
```

The request and result are the existing K02 `WorkerRequest` and `WorkerResult`. Every source, consumer classpath entry, plugin classpath entry and output path in the request is a logical path inside the action workspace. Existing symlink components, symlinks inside classpath directories, non-jar classpath files, duplicate classpath/source entries and pre-existing output directories are rejected. The orchestrator must stage immutable, verified inputs into that workspace. The worker does not resolve dependencies.

`request.compiler` hashes the canonical `compilerDistribution` manifest in [protocol.schema.json](protocol.schema.json), including the ordered compiler implementation artifact paths/digests and the optional serialization plugin digest. This manifest identifies the complete BTA/compiler distribution, not just `kotlin-compiler-embeddable.jar`. The worker verifies every artifact's size and SHA-256 before loading BTA and verifies that the loaded compiler version matches the manifest's supported version, 2.4.10, 2.4.20 or 2.4.21. The launcher separately verifies the worker bootstrap and JDK identities as part of the native action/runtime composition. Common metadata compilation requires 2.4.20; the alternate profiles reject that operation with `KVIK_UNSUPPORTED_COMPILER` before producing outputs. Kotlin/Native remains pinned to 2.4.20 for every JVM profile.

Compilation calls `createInProcessExecutionPolicy()` explicitly and never selects a daemon policy or fallback. No incremental compiler state is enabled. Compiler classes are loaded from the verified implementation classpath; they are not exposed in the consumer classpath. The worker disables implicit stdlib/reflection discovery: dependencies must be supplied explicitly.

The supported options are:

- `-jvm-target` with 11, 17, 21 or 25; `-module-name` with letters, digits, underscores, dots and hyphens.
- `-language-version` and `-api-version` with 2.4 only.
- `-opt-in` with a qualified annotation name; repeatable.
- `-Xexplicit-api=strict`, `-Werror`, `-Xmulti-platform`, `-Xexpect-actual-classes`.
- `-Xcommon-sources` with comma-separated paths already declared in `sources`.
- `-Xfriend-paths` with comma-separated paths already declared in `classpath`.

Other flags, duplicate non-repeatable options, arbitrary `-d`/classpath flags and arbitrary plugin flags are rejected. A compilation can combine the manifest-pinned serialization plugin (`org.jetbrains.kotlinx.serialization`) with up to 16 typed workspace plugin applications in declaration order. Serialization has no configurable options; workspace plugins accept declared literal and path options. Plugin and consumer classpaths remain separate, and duplicate plugin IDs are rejected.

The BTA `CompilerMessageRenderer` supplies structured compiler warnings/errors and source locations. Messages use stable Kvik diagnostic codes, with logical source paths where possible. Tool stdout/stderr are logs, not protocol records. Outputs compile into a new staging directory and are exposed by atomic rename only after successful compilation. Compilation errors and handled infrastructure failures remove staging output. A killed worker can leave an unexposed staging directory for the run owner to clean.

The native caller must require a complete result file, matching request ID, consistent success/exit status, successful owned-process completion and validated output inventory before reporting success or publishing a cache entry. These workers do not themselves publish caches.

## JVM tests

Launch a fresh JVM with the compiled worker/core classes, JUnit Platform 1.13.4, **every declared test runtime classpath entry** in order, then the verified worker stdlib as a fallback:

```text
java -cp <worker-and-declared-test-runtime-classpath> kvik.jvm.TestWorker \
  <absolute-workspace> <request-relative> <result-relative> <verified-fallback-stdlib>
```

Keeping stdlib and reflection in the same JVM classloader is required for Kotest. The worker requires its process classpath to equal the worker/core and pinned JUnit Platform entries, followed by the ordered declared test classpath and the explicitly supplied fallback stdlib. It anchors the first two entries to the loaded worker and launcher code sources; the native composition verifies the fallback artifact. The project's declared stdlib therefore loads before the worker's fallback, including when the compiler profile and project dependency versions differ. The request classpath itself must be unique, and additional or reordered process entries are rejected. Legacy direct clients using three arguments retain the old worker/JUnit/stdlib bootstrap prefix. A fresh process provides request isolation; no classloader or test-engine session is reused between requests.

The adapter-local `testRequest`/`testResult` definitions live in [protocol.schema.json](protocol.schema.json), which references existing core `TestResult`, `TestCounts`, `Diagnostic` and `Digest` definitions. Register the local core schema with a validator; its HTTPS `$id` is an identifier, not a service to fetch.

Requests contain `schemaVersion`, `requestId`, `module`, `target` (`jvm`), ordered `classpath`, exact `testClasses`, and `engines` (`junit-jupiter` and/or `kotest`). Unknown fields, unsupported engines and missing engines fail explicitly. An empty selection is incomplete and does not trigger classpath-wide discovery. Tags, method selectors, arbitrary engine configuration and discovery generators are not supported in this version.

Results preserve the framework's unique IDs, scoped by module/target, including dynamically registered tests. The runner records executed passes/failures, skipped/aborted tests and container failures. It never fabricates a leaf test identity for a container failure. An assumption-aborted container marks its known unexecuted leaf descendants skipped. `discoveryComplete` requires completed execution and a terminal result for every discovered leaf. Discovery/infrastructure exceptions retain an unknown discovered count rather than claiming zero. Aborted tests count as skipped and cannot provide passing execution evidence.

Exit codes: 0 for complete passing execution with at least one executed test; 1 for a failed test, container or infrastructure operation; 3 for zero-test, all-skipped or incomplete execution. Tests run on every invocation, even when compilation outputs are reused; the native graph must use `ActionKind.TEST` and `CachePolicy.NEVER`.

## Scope

`tools/test_jvm.py` builds these workers directly with the pinned compiler artifacts. It exercises a two-module common/JVM fixture and can compile/run the unchanged pinned kotaml modules. It does not invoke Gradle, Kotlin Toolchain, KSP or nested builds. The kotaml artifact lock preserves a concrete reference dependency closure and verifies downloaded bytes; it is not a general dependency resolver. Native CLI action construction, cache integration, test-result integration into full verification records and general generator/compiler-plugin support remain separate integration work.

## Compiler profile acceptance

The alternate source composition uses [bootstrap/profiles/jvm-2.4.10.lock.json](../../bootstrap/profiles/jvm-2.4.10.lock.json) as `bootstrap/jvm-artifacts.lock.json` in a separate checkout, then builds a complete runtime there. Changing a manifest version on an existing runtime is unsupported. The locks pin the full compatible compiler, BTA, scripting, metadata reader and serialization plugin distributions. The default source lock stays at 2.4.20.

[bootstrap/profiles/jvm-2.4.21.lock.json](../../bootstrap/profiles/jvm-2.4.21.lock.json) provides the same source-composition option for JVM projects requiring Kotlin 2.4.21. In a separate checkout, copy that profile over `bootstrap/jvm-artifacts.lock.json` before building the runtime; do not edit a prepared runtime's manifest. Its compiler, BTA, scripting, metadata reader and serialization JARs are pinned from the published Maven artifacts. The upstream compiler dependencies retain reflect 1.6.10 and coroutines 1.8.0 with excluded transitives; the selected stdlib 2.4.21 also satisfies the daemon client's stdlib 2.2.21 dependency. The existing ABI reader support libraries retain their pins. Unlike the older compiler archives, the 2.4.21 compiler artifact is served directly by Maven and has no authorized release redirect.

The 2.4.21 profile admits JVM compilation, scripts, matching compiler plugins and JVM ABI extraction. Common metadata, Native/JS/Wasm compiler plugins, and non-JVM ABI extraction remain outside that profile's scope. The existing two-profile workflow below exercises 2.4.20 and 2.4.10; it does not supply 2.4.21 acceptance evidence. The version-aware `tools/test_kotlin_script_host.py` and `tools/test_abi_cli.py --jvm-only` can check a prepared 2.4.21 source composition without changing their fixtures.

The [JVM compiler profiles workflow](../../.github/workflows/compiler-versions.yml) runs on Linux x86_64 using the existing digest-pinned Docker image definition and checksum-pinned Kotlin/Native 2.4.20 tool archive. Run the same acceptance locally with Docker:

```sh
docker build --platform linux/amd64 --tag kvik-compiler-profiles tools/linux
docker run --rm --init --platform linux/amd64 \
  --volume "$PWD:/work/kvik" kvik-compiler-profiles \
  python3 tools/linux/compiler_versions.py
```

The runner copies current tracked and unignored source files into private checkouts, excluding reference submodules and caches. It prepares the default Linux runtime and locked fixture, then builds the alternate JVM profile with the same pinned Native tool lock, JDK, Native installation and artifact cache. It does not change the original checkout's locks. The public CLI gate executes A cold/warm, B cold/warm, then restored A: compilation keys must change between profiles, restored A must hit its original cache, and all five test invocations must execute freshly. A test records `KotlinVersion.CURRENT` and `kotlin.Unit`'s code source to prove that both profiles load the project's locked stdlib 2.4.20 from declared run inputs. A separate, explicitly non-executed calculation changes only the compiler identity in an otherwise fixed canonical action.

The same job checks successful 2.4.20 metadata compilation, explicit 2.4.10 metadata refusal, and the alternate profile's JVM ABI snapshots/cache behavior. This does not extend 2.4.10 support to Native, JS, or common metadata compilation. Existing prepared runtimes can be checked directly with:

```sh
python3 tools/check_compiler_versions_cli.py \
  --runtime-a /path/to/2.4.20/kvik.kexe \
  --runtime-b /path/to/2.4.10/kvik.kexe \
  --fixture /path/to/prepared-jvm-fixture
```

CI evidence is exported under `.kvik/ci-artifacts/compiler-versions/` with directories readable by the host uploader, including failure and timeout logs, action records, event journals, metadata results and ABI events. Private checkouts, CAS blobs and tool installations remain under `.kvik/tests/compiler-versions-ci/`.
