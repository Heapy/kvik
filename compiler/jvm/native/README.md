# Native JVM action bridge

`kvik.compiler.jvm.JvmActionExecutor` implements the core `ActionExecutor` using K03 `ProcessHost` and K04 `ContentStore`/`NativeOutputTrees`. One-shot invocations use fresh compiler/test JVMs. An explicit foreground coordinator may supply `JvmCompilerSessionPool` for plugin-free JVM compilation; tests, scripts, metadata and compiler plugins continue to use fresh JVMs.

Construct actions with `jvmCompileAction(JvmCompilation, ...)` or `jvmTestAction(JvmTestSelection, ...)`. The executor reconstructs and compares the complete action before resolving tools or starting a process. Ordered classpaths are retained; test runtime classpaths must be nonempty, matching the worker schema. Compilation has typed JVM target/module/common-source/friend-path/opt-in/API/warning settings and at most one manifest-pinned serialization plugin; arbitrary flags, process arguments, environment variables and test engine settings are unavailable. Inputs must declare every source/plugin and every classpath file/directory inventory. Compilation uses `LOCAL`; test actions always use `NEVER` and remain separate requests even when their keys match.

Each action reserves an isolated directory, materializes verified inputs under `input/`, and creates separate `output/`, `home/` and `tmp/` directories. Protocol files and logs stay at the action directory root. The actual JVM invocation uses explicit arguments, environment and working directory, private HOME/user.home and temporary directory, and the action timeout. Tests receive exactly the three bootstrap entries `workerClasses`, `junitJar`, `stdlibJar`, followed by the ordered declared input classpath. Compile workers receive `workerClasses`, `btaApiJar`, `stdlibJar`. Request IDs come from the unique reserved directory and do not affect action keys.

The bridge accepts result files after owned-process completion or a validated compiler-session acknowledgement, with matching request ID and exit code. It validates concrete test IDs/scopes, duplicate IDs, aggregate counts, completion and status/exit consistency. Malformed/missing results and crashes cannot pass. Failed/all-skipped/zero/incomplete test semantics remain distinct in `JvmTestReport`, even though core action status only has `FAILED`. Compiler diagnostics get action/module/target scope and logical paths with the staging `input/` prefix removed. Successful compile outputs must be nonempty, exclusively below the declared output directory, and pass K04 inventory checks. Partial or cancelled compiler outputs are never exposed through successful results.

Cancellation is latched on the action, including cancellation after the worker exits but before polling consumes completion. Cancellation takes precedence over result-file success and timeout. Real test cancellation can therefore report unknown discovery/executed counts; it does not claim execution evidence from an unpublished worker result. Logs are retained as paths without loading unbounded process output into memory. Protocol files are limited to 4 MiB. Validated test reports are retained as output artifacts for negative outcomes as well; these remain non-cacheable.

`observations` retains `JvmActionObservation` values with action results, structured diagnostics, optional tests and absolute log paths. The scheduler emits action events; the run coordinator owns authoritative run boundaries and combining observations into a complete verification record.

## Foreground compiler ownership

The coordinator owns at most two reusable compiler JVMs through its process host. They share no socket, daemon or cross-session registry. Each worker retains the verified BTA implementation but opens a fresh BTA build session per action. Ordered, bounded mailbox requests bind the action request digest; acknowledgements bind the request envelope and completed result. Logs close and JVM properties are restored before acknowledgement. Each action retains `compiler-session-request.json` and `compiler-session-result.json`; the latter includes sequence and PID. Whole-process usage is not attributed to individual pooled actions.

Scheduler admission leases reserve an idle worker for one action through asynchronous cache lookup and tool verification. Unreserved idle workers consume 2048 MiB reservations and are drained when other actions need that memory. An incompatible verified tool layout or environment replaces the reserved worker. A full busy pool falls back to a fresh JVM within the action's existing reservation. These are admission estimates, not hard RSS limits.

Compilation errors may reuse the worker. Infrastructure errors, invalid acknowledgements, cancellation and deadlines retire it without replaying a submitted action. Cleanup failures poison the pool and retire the coordinator. Session exit, cancellation and runtime replacement drain owned JVMs. Tool verification still runs for every action, and the worker rechecks its compiler distribution for every request.

## Verified tool provider

`JvmToolchainProvider.verified(identities)` must verify immutable tool inventories before returning `JvmToolchainLayout`. The executor requires an exact identity map and additionally hashes the canonical compiler distribution manifest before launch. The compiler worker independently verifies all compiler implementation artifacts and the serialization plugin. A provider that only compares unverified caller-supplied digests does not meet the production contract.

The required identity keys are:

- `jdk`: verified installed JDK identity, supplied by the integration tool verifier.
- `jvm-compiler`: SHA-256 of the canonical K06 compiler distribution manifest.
- `jvm-worker`: `jvmWorkerIdentity(workerTree, api, stdlib, junit)`.
- `junit`: SHA-256 and byte size of the JUnit Platform standalone jar.

`workerTree` hashes the canonical core `TreeManifest` of worker/core class files. The bundle function hashes canonical JSON `{schemaVersion:1,implementation:"kvik-jvm-worker-bundle-v1",workerTree:<Digest>,api:<Digest>,stdlib:<Digest>,junit:<Digest>}`. Jar identities are content digests. This encoding is shared with K14's source-built bundle producer.

The layout contains the matching identity map and absolute verified physical paths for `javaExecutable`, `workerClasses`, `btaApiJar`, `stdlibJar`, `junitJar`, `compilerArtifacts`, and `compilerManifest`. Tool paths cannot contain colon/classpath separators or dot/parent path segments. The provider must verify physical path ownership, links and contents; the adapter's syntactic path checks do not replace that verification. Production source-built bundle publication and installed tool verification are integration responsibilities.

No remote compiler portability, CLI wiring, general test discovery/KSP generator compatibility, JVM incremental compilation or complete real-consumer verification is claimed here. The existing K06 worker evidence remains separate.
