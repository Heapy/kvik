# Kvik implementation plan

Created: 2026-10-22

Kvik is a proposed Kotlin Multiplatform build system with local and remote artifact caching built into its execution model. Its first users are the existing Heapy Kotlin libraries and their CI workflows. Development will use coordinated AI agents, with a maintainer deciding scope and reviewing evidence at integration milestones.

Kvik and its build plugins are distributed as pinned Kotlin sources. A small launcher compiles the selected source composition with Kotlin/Native into a native executable, caches it and reuses it on subsequent invocations. Kvik runs without background daemons. Repeated development work uses an explicit foreground session with file watching and interactive commands such as `r` to reload.

The first milestone is a two-week experiment: build and test a real library on JVM and one Native target through the source-built native runtime, demonstrate the foreground reload loop, consume representative external dependencies, and safely reuse compiled outputs across machines. This timebox funds investigation and implementation; it is not a promise of production readiness. The result should support a concrete decision to continue Kvik, narrow its scope, or contribute the required capabilities to Kotlin Toolchain.

## Long-term goals for agent workflows

Kvik should help developers and AI agents produce correct, verifiable changes quickly. Build speed is one part of this goal; discovering the right checks, understanding failures and knowing precisely what a passing result proves are equally important. The intended library lifecycle covers dependency resolution, generation, compilation, tests, ABI checks, documentation, coverage, benchmarks and publication through one declarative project model and public artifact graph.

The agent interface should expose these capabilities:

| Capability | Intended behavior |
| --- | --- |
| Queryable project model | Map a file to its modules, source sets, targets and applicable checks. Expose generated-source origins, artifact relationships and effective settings with their configuration origins. |
| Package and dependency version queries | Let an LLM list available package versions, inspect dependencies of a selected package version, and read declared and resolved project dependency versions through structured CLI queries. |
| Change-aware verification | Select affected compilation, tests, ABI checks and generators from a change set. Explain selections and gaps. Start conservatively at module and target granularity; widen verification when impact is uncertain. |
| Structured diagnostics | Return versioned events with diagnostic codes, source locations, module, target, failed action and reproduction details. Distinguish compilation errors, test failures, missing tools and infrastructure failures. |
| Evidence tied to exact inputs | Identify the source snapshot, configuration, dependencies, toolchain and checks behind every result. Detect when edits or merges make evidence stale. |
| Safe concurrent work | Share immutable artifacts across worktrees while isolating mutable compiler state, outputs and process ownership. Cancellation of one run must not interrupt another. |
| Inspectable foreground sessions | Start a run, query progress, fetch events after a cursor and cancel a specific run. Retain results after exit; later reconnection is allowed only while an explicitly started foreground owner remains alive. |
| Validated configuration edits | Provide schema validation and operations for common dependency or target changes. Produce reviewable configuration and lockfile patches, guarded by file-digest preconditions against concurrent edits. |

The prototype commits to four agent-interface foundations: a versioned machine-readable project model and event schema; structured compiler and test results; verification records bound to input digests; and isolation between simultaneous worktrees sharing a cache. Advanced impact selection, live-session reconnection and configuration-edit operations follow after these contracts are reliable. The native runtime and basic foreground reload loop are architectural requirements with their own gates below. An MCP interface can later expose the same operations as the CLI.

Evaluate these capabilities against pinned KTC workflows on identical tasks. Do not assume an advantage from the presence of an agent interface alone; measure time to a verified fix, tool calls, log volume, redundant compilation and incorrect claims of completion.

Scripts should be first-class consumers of the same project model, dependency resolver, compiler adapters and artifact cache. Support small programs for code generation, migrations, repository maintenance and debugging without requiring each script to become a separately configured build project.

## Initial scope

| Included in the prototype | Deferred until the prototype passes |
| --- | --- |
| Multi-module library projects with explicit source sets and target variants | Android applications, manifests, resources, dexing and signing |
| JVM and macosArm64, with common code and expect/actual declarations | Apple application packaging, simulator management and SwiftPM integration |
| A constrained, explicit subset of Maven and KMP dependency resolution | Complete Gradle dependency semantics and arbitrary existing build plugins |
| Compilation, JVM tests and host Native tests | JS, Wasm and additional Native targets |
| Local execution with local and remote action-output caches | Distributed execution and remote worker scheduling |
| Source-built, cached native Kvik runtime with a minimal source build plugin | Broad build-plugin ecosystem and additional native host platforms |
| Foreground continuous mode with reload, watch and clean shutdown | Application-specific hot reload and live-session reconnection |
| Command-line diagnostics, action traces and cache explanations | Full IDE import, live reload and debugger integration |
| Machine-readable project model, structured results and input-bound verification evidence | Impact-based check selection, configuration-edit operations and MCP |
| One real library built without modifying its production sources | Moving the whole repository portfolio to Kvik |

The initial Native target matches the available macOS ARM64 development host. Add linuxX64 after the first target works to test platform separation in CI. Add iOS compilation and linking on macOS as a subsequent capability; successful macOS tests do not establish iOS support.

Start with a small multi-module fixture and a representative module from `../kotaml`. Use `../grogu-time` as the second, more demanding fixture once basic dependency and compilation behavior works. Pin fixture commits and work in separate copies or worktrees; preserve the existing builds as comparison implementations.

Script support is an early extension once JVM compilation, resolution and the local cache work. It can begin before full KMP support is complete, but does not block the original prototype decision. Start with ordinary `.kt` entry points on JVM; Kotlin scripting-host semantics and other script targets are subsequent capabilities.

## Architectural decisions

### Source distribution and native runtime

Distribute Kvik and Kvik build plugins as inspectable source packages, identified by immutable revisions and content digests. Support pinned repository/archive sources and local source directories for development. A separate runtime lock records the Kvik revision, build-plugin sources and transitive source dependencies, bootstrap toolchain, external library dependencies and native build settings. Branch names alone are not sufficient pins. Updates are explicit; local modifications are identified by content, not only a Git revision.

Use a small versioned launcher and a bounded bootstrap recipe that work before a Kvik executable exists. The launcher reads the runtime lock, verifies or acquires pinned sources and tools, selects a compatible cached executable or builds one, then runs it. Source acquisition must not require executing plugin code. Keep this bootstrap independent of the consumer project action graph to avoid a self-hosting cycle. Downloaded compiler distributions and SDKs are pinned bootstrap tools; Kvik and build-plugin sources remain the authoritative implementation.

Compile Kvik core, selected build-plugin sources and a generated static plugin registry into one native executable per composition and compatible host environment. Build plugins implement versioned contracts for configuration, actions and artifacts. Their configuration schema must be discoverable through the composed executable. Project configuration is parsed at runtime: ordinary application source, dependency or configuration edits do not rebuild Kvik unless they change the selected build-plugin composition or native build inputs.

Cache the executable using the complete runtime source graph, plugin API/registry version, external dependencies, bootstrap recipe, compiler and native backend identities, ordered build options, declared build environment and relevant host OS/architecture/ABI/SDK identities. Publish binaries and manifests atomically, with per-key coordination for concurrent launchers. Verify cached binary digests and host compatibility before execution. Remote executable reuse is an optional cache optimization subject to the same trusted-writer policy; local compilation from sources must work without a remote binary service. A failed rebuild must not silently launch an executable for a different source composition.

Implement the runtime and Kvik build plugins in Kotlin/Native. This is a fixed architectural requirement. K01 must validate and pin the Kotlin/Native compiler, platform libraries and interop boundaries through source-plugin, TLS, process-control, file-watching and dependency-transport probes. JVM-only libraries belong in explicitly owned tool workers where necessary; choose native-compatible implementations for the orchestrator. GraalVM Native Image and JVM runtime implementations do not satisfy this requirement.

### Bootstrap and compiler integration

Kotlin Toolchain 0.13.0, Kotlin/Native and Kotlin/JVM compiler 2.4.20, and JDK 25 are candidate bootstrap pins where applicable, pending artifact checksums and compatibility results in K01. Pin required platform tools as well. Changing a pin is an explicit compatibility change. The bootstrap itself must obey the no-daemon policy; verify any bootstrap build-tool use and use direct compiler invocations if that cannot be established.

Consumer compilation must use compiler adapters directly, without nested KTC or Gradle commands. Kvik can build itself later, after its own required features are supported; retain a reproducible bootstrap path.

Use Kotlin's Build Tools API for supported JVM operations through an invocation-owned JVM worker, with compiler daemon mode disabled. A native Kvik runtime does not require every invoked compiler or tool to be native. Define a versioned request/result protocol between native orchestration and JVM workers, with explicit compiler and plugin classpaths. Isolate Native compiler and linker invocation behind a versioned adapter using the pinned compiler distribution. Keep compiler implementation classes out of the core model. The API currently documents JVM support as Beta and JS/Wasm support as Alpha; capability checks and compatibility tests must accompany each adapter upgrade. [Build Tools API](https://kotlinlang.org/docs/build-tools-api.html).

Distinguish Kvik build plugins from Kotlin compiler plugins: build-plugin sources are compiled into the Kvik executable; compiler plugins are built or resolved into the artifacts required by their compiler host, as described in K13. They are not loaded into the native orchestrator. Changing a consumer compiler plugin invalidates its dependent compilation actions and does not inherently require rebuilding Kvik.

Reuse mature components for Maven transport, POM processing and protocol handling where their contracts fit. Kvik still owns KMP variant selection, source-set metadata interpretation and the supported dependency semantics. A POM resolver alone does not provide KMP dependency resolution. [KMP publication structure](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-lib-setup.html).

### Project model and dependency lock

Use a small declarative project format. The first schema should describe modules, source-set dependencies, target variants, declared dependencies, compiler options, generators and test settings. Avoid executable configuration in the prototype. K02 selects versioned JSON in `kvik.json` for a small native parsing boundary and machine-readable schema validation; richer authoring formats can lower to the same model later. Freeze the initial schema before parallel implementation.

Resolve dependencies into an explicit lock containing selected versions, target variants, artifact digests, repository identities and metadata digests. Separate resolution from execution: compilation consumes locked artifacts and cannot silently resolve a different dependency graph. Unsupported metadata or ambiguous variants must produce a diagnostic rather than fall back to a guessed JVM artifact.

For the prototype, support the dependency patterns exercised by the pinned fixtures, including transitive KMP dependencies. Explicitly reject unsupported capabilities, constraints or conflict cases. Expand this subset with compatibility fixtures rather than claiming general Gradle compatibility.

### Workspace compiler plugins

Allow a compilation to reference a compiler plugin built from another module in the same project, as well as a published plugin artifact. The action graph must build the plugin and its required dependencies before compiling its consumers, with no intermediate Maven publication or nested build invocation. Model compiler-plugin dependencies separately from ordinary library dependencies and expose them through project inspection.

A workspace reference identifies a module's plugin artifact; its content digest and required plugin classpath participate in consumer action identities. Plugin implementation changes must invalidate affected compilations even when the plugin's public ABI is unchanged. Keep the plugin classpath separate from consumer runtime dependencies. Detect cycles through plugin and ordinary dependency edges, including a plugin that would require itself to compile; report the dependency path and require an explicit bootstrap arrangement.

Allow explicit application to targets, production/test compilations and scripts, with declared options, input files and generated outputs. Isolate plugin outputs per action and include their contracts in caching and verification evidence. Distinguish the host artifact loaded by the compiler from the consumer's compilation target; a JVM-hosted compiler plugin may support Native consumers, but support must be demonstrated per adapter and plugin. Diagnose compiler incompatibility and retain unsupported combinations explicitly.

Implement this as K13 after the JVM foundation. Validate JVM consumers first, then script consumers with K12 and Native consumers with K08. The intended development loop is edit the plugin, compile an example and run its tests in one dependency graph.

### Scripts and reusable dependency profiles

Compile the initial scripts as ordinary Kotlin `.kt` sources with a `main` entry point through the JVM adapter. Named project scripts declare their source files, entry point when ambiguous, dependency references, compiler options and compiler plugins. Reusable helpers can be declared source inputs or workspace module dependencies. Treat `.kts` top-level execution, script imports and dependency annotations as a later adapter with separate compatibility tests.

Allow scripts to reference version-catalog entries, explicit named dependency profiles and workspace modules. Resolve script dependencies into the normal lock model and reuse downloaded artifacts and eligible compiled module outputs. Build only the script's prerequisite graph. Inherit dependencies explicitly; do not silently place the entire project classpath on a script's classpath. Conflicts use the resolver's documented rules and unsupported cases produce diagnostics.

Support standalone scripts outside a project with a small declarative companion configuration and a durable lockfile. Use the same resolver and shared artifact cache as project scripts. Decide the companion filename and schema during K12; running a locked script must not silently change its dependency selections. Configuration remains declarative and is not executed to discover dependencies.

Declare compiler plugins and ordered options per script or through an explicit shared profile. Support pinned published plugins initially and workspace plugin modules through K13. Lock external plugin artifacts and their dependencies separately from the script's runtime dependencies; workspace plugin artifacts use graph dependencies and content identities. Verify compatibility with the pinned compiler and retain a supported plugin fixture, initially serialization. A successful fixture proves that integration, not compatibility with arbitrary plugins.

Represent compilation and execution as separate actions. Cache compilation using all declared source and resource inputs, ordered dependency artifacts, compiler identity, plugin artifacts and options, and the normal action contract. Plugin-specific file inputs must be declared and hashed; plugin actions with unresolved host dependencies remain local-only under the existing cache policy. Runtime arguments do not invalidate compilation unless an adapter explicitly makes them compilation inputs.

Execute the script on every requested invocation, even when compilation is restored from cache. Define argument forwarding, working directory, environment, standard streams, exit status, timeout and cancellation behavior. Propagate compilation and runtime failures through the existing structured diagnostics and run evidence. Keep script output separate from machine-readable build events. A successful cached compilation must never be reported as evidence that the script's effects occurred.

A script may later serve as a cacheable generator by declaring inputs and outputs and satisfying the same controlled execution and reproducibility contract as other generators. Merely declaring output paths does not establish that contract.

### Action graph

Represent each compilation, generation, linking, packaging and test operation as a typed action with:

- An implementation identifier and version.
- Declared files and directory trees, identified by logical paths and content digests.
- Toolchain identity, ordered arguments, declared environment, execution platform and target variant.
- Declared output files or directory trees.
- Explicit dependencies on other actions' artifacts.
- Execution policy, including timeout, cancellation and cache eligibility.

Keep graph construction deterministic and separate from execution. A source edit should change the relevant input identities without requiring plugins to inspect mutable build directories. Preserve ordering where it is meaningful, especially classpaths and compiler arguments.

The scheduler should deduplicate identical actions within a build, run independent actions concurrently within resource budgets, and stop dependent actions when a prerequisite fails. A successful action publishes a complete result atomically. Failed or cancelled work must not become a successful cache entry.

Expose artifact contracts for main and test classes, source roots, platform classpaths, KLIBs, linked executables and generated resources. Expose test launch settings and instrumentation hooks. These contracts address the missing APIs that currently lead BCV, Dokka, Kotest and coverage adapters to start nested builds.

### Research: compiler-checked task effects

Explore an `@Task` compiler plugin for Kvik build plugins. It could generate task registration and input/output metadata, reject known undeclared side effects, and enforce explicit capabilities for reading inputs, writing outputs, launching pinned tools, network access, environment, time and randomness. Capability declarations must describe the relevant resources and their cache implications; permission to perform an effect alone does not make the result reproducible.

Evaluate a restricted task API and conservative call-graph checking, including transitive helper calls. Unknown external code, reflection and native calls require explicit handling; compiler acceptance must not be presented as proof of hermetic execution. Combine static checks with controlled execution and runtime access enforcement before granting portable cache eligibility. Consider the bootstrap ordering for the checker itself and use the workspace compiler-plugin mechanism once available. Keep this exploratory until a small fixture demonstrates useful diagnostics, acceptable restrictions and sound treatment of unsupported calls.

### Local and distributed cache

Use a content-addressable store for immutable blobs and directory trees, plus an action cache mapping an action identity to its output manifest. Resolve each eligible action by checking local results, then remote results, then executing locally and publishing its outputs. [Remote cache architecture](https://bazel.build/remote/caching).

Use the existing Remote Execution API protocol and an existing compatible cache server. Implement only the cache services needed initially; remote execution is a separate future milestone. Prototype against one concrete backend and record its version and required capabilities. Do not create a custom cache server as part of the first experiment. [Remote APIs](https://github.com/bazelbuild/remote-apis).

Add `gradle-http` as a second remote-cache provider alongside REAPI. Keep action identities, cache eligibility and the local content-addressable store independent of the transport. Use HTTP GET/PUT by cache key to store a versioned Kvik archive containing the output manifest and files. Use a Kvik-specific key namespace; protocol compatibility does not imply compatibility with Gradle's cached task outputs. Validate archive paths, sizes and content digests before atomic materialization, and retain local-execution fallback for misses, outages and corrupt entries.

Use BuildFetch for the first `gradle-http` integration: endpoint `https://cache.eu-central-a.buildfetch.com/b1CXAN/gradle/`, HTTP Basic username `token-auth`, password from `BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN`. Keep credentials outside source files, action identities and logs; supply the token through the environment locally and a GitHub Actions secret in CI. Enable the provider only when configured with credentials. Default to reads locally and allow writes in trusted CI, matching the supplied Gradle configuration's intent. Check cold writes and warm restores across two CI runners, with fresh test execution, plus invalidation and malformed/incomplete archive handling. This is a planned adapter; the existing REAPI workflow does not exercise this BuildFetch Gradle endpoint.

The canonical action identity must include the action implementation, compiler and compiler-plugin digests, toolchain and SDK identities, target and variant, ordered options, declared environment, logical input paths and input content digests. Include all relevant dependency artifacts. Define canonical serialization, schema versioning, symlink handling and executable permissions before relying on cross-machine reuse.

Action implementation identity must account for the Kvik/build-plugin code that defines its behavior. Initially include the native runtime composition digest conservatively; a later narrower identity requires evidence that changes elsewhere in the runtime cannot alter the action. A plugin version label alone must not permit stale output reuse after a local source edit.

Materialize inputs into controlled working directories and constrain the environment. Isolated directories alone do not enforce hermetic execution: undeclared filesystem and environment access must be prevented or accounted for. Initially permit remote caching only for controlled adapters whose input contract has passed the relocation and invalidation suite. Actions with unresolved host dependencies stay explicitly local-only.

Treat compiler incremental state separately from portable outputs. Keep mutable compiler state local initially. A restored output should be usable without the producer's incremental database; later edits can trigger a clean action execution until local incremental support is reliable.

Validate downloaded blob digests and materialize outputs atomically. A cache miss, outage or corrupt entry must permit local execution with a diagnostic. Publish action metadata only after its referenced outputs are available. Use trusted CI cache writers initially, with credentials supplied outside action descriptions. Define cache schema namespaces and size limits; use backend eviction for the prototype.

### Platform and process isolation

Model execution platform and compilation target separately. Sharing a cache does not allow execution of an incompatible binary or replace the SDK required for an uncached build. Apple final binaries require a supported macOS host. [Native host and target support](https://kotlinlang.org/docs/native-target-support.html).

Retain isolated JVMs where the tool requires them: compiler workers, compiler-version-specific ABI readers, leak-prone analysis tools and benchmark measurement processes. Their compilation artifacts can still be cached and shared. Each process belongs to a foreground invocation or session. Start compilation without coverage instrumentation; attach coverage agents only to explicitly instrumented test launches.

Cache compilation and deterministic generation first. Tests run on every requested invocation in the prototype, with independent test actions scheduled in parallel by default within resource budgets. Benchmark measurements, publication, deployment and development servers remain non-cacheable execution actions. Add test-result caching only with a separate environment and reproducibility contract.

### Test avoidance, parallelism and fixture discovery

Test avoidance and parallel test execution by default are design priorities. The long-term goal is to run only the checks needed for the current inputs and execute independent work concurrently. Distinguish selecting affected tests from reusing a previously successful test result; each needs evidence that omitted execution is safe. Explain every selection or cache decision, and provide an explicit full-run mode that bypasses avoidance. The prototype's always-run test policy is a correctness baseline, not the intended final optimization boundary.

Start default parallelism at independent module/target test actions. Consider finer suite/class/test partitioning as adapters establish isolation guarantees. Coordinate CPU and memory limits across compilation, test processes and framework threads to avoid oversubscription. Provide explicit serial execution or exclusive-resource declarations for tests sharing ports, databases, files or other mutable state. Preserve test identities, complete failure reporting and foreground process ownership regardless of scheduling.

Investigate automatic fixture discovery as a future capability, without committing to an API or implementation yet. Candidate approaches include a small test fixture API, HTTP-client adapters and controlled recording/replay. Kvik could generate a dependency manifest during execution, store response snapshots by content digest and resolve those dependencies before considering test-result reuse. Explore snapshot, revalidation and live-execution policies, reviewable fixture refreshes, first-run discovery and behavior when test code changes. Observing requests alone does not establish that all external inputs are known; undeclared access and other uncontrolled inputs must prevent unsafe reuse. Cached replay results must remain distinguishable from fresh live-service verification.

Begin fixture research at suite/action granularity. Per-test caching, transparent interception of arbitrary networking and cross-platform enforcement remain open questions. Record findings and compatibility limits before expanding the test-result caching contract.

### Optional multiplatform test framework

Consider a Kvik-owned test runtime and framework spanning Kotlin targets if it materially improves test avoidance, fixture dependency tracking, default parallelism and consistent agent-visible results. This is a research direction, not a prerequisite for the build-system prototype. First evaluate what existing framework adapters can provide and identify concrete gaps that justify owning a framework and its maintenance.

Explore a common execution protocol with stable test identities, discovery separate from execution, filtering/sharding, structured results, suspend-test lifecycle, timeouts, cancellation, fixture inputs and exclusive-resource requirements. Keep the portable API, target runners and build-tool scheduling separate. Evaluate compatibility with existing `kotlin.test` assertions and test declarations, and the ability to use the runtime outside Kvik. Consider compiler-assisted registration or dependency instrumentation only where simpler adapters cannot meet the contract.

The ambition is coverage across Kotlin targets, delivered and verified incrementally: JVM and host Native first, then JS, Wasm and remaining platform runners. Maintain an explicit capability matrix for hosts, browsers, devices/simulators, process isolation and parallelism; shared test APIs do not establish equivalent runtime guarantees. A framework cannot by itself make arbitrary external effects deterministic or make result caching safe.

Before committing, run the same conformance fixtures through existing adapters and a minimal candidate runtime. Compare discovery accuracy, failure/skip reporting, asynchronous cleanup, cancellation, concurrency isolation, fixture tracking, reproducibility and execution overhead. Proceed only if the benefits justify adoption and maintenance costs. A successful shared protocol or runner layer may be sufficient without introducing a new test declaration DSL.

### No daemons and foreground continuous mode

One-shot commands terminate their owned workers and child processes before returning. Continuous mode is an explicitly invoked foreground process that owns its terminal, watchers, mutable state and child processes. No Kvik service, compiler daemon or build-plugin service may detach or persist beyond its owner. Session-owned workers may remain warm across rebuilds, with compiler versions and mutable state isolated; independent invocations share immutable disk caches, not worker processes. Configure external tools to obey this policy and report unsupported lifecycle behavior explicitly.

Provide a basic `dev` capability with illustrative keys: `r` requests rebuild/reload of the selected target, `t` reruns its tests, `q` exits and `?` shows help. File watching triggers the same operations, debounces bursts and excludes generated outputs. Keep rebuild state explicit, coalesce repeated requests and never publish stale work as current. In the initial implementation, reload means rebuild followed by restarting the selected application; application-specific hot reload can be added through an explicit adapter capability. Failed builds leave the session usable and identify whether the running application still corresponds to the previous successful inputs.

The session retains the project model and graph for responsiveness, but revalidates changed inputs and configuration for every run. Kvik or build-plugin source edits require a new native executable: finish or cancel owned work, rebuild the composition and explicitly restart the foreground session through the launcher. Do not attempt to load new plugin code into the running native executable or silently continue under an obsolete composition.

Restore terminal state and stop the complete owned process tree on `q`, Ctrl-C, termination and parent loss. Use parent-liveness mechanisms as well as normal shutdown hooks so abrupt owner failure does not leave detached workers. Define bounded graceful shutdown followed by forced termination. Each worktree/session has independent cancellation and output ownership.

Provide an explicit noninteractive control/event mode for CI and agents using the same operations; it must not depend on simulated keypresses or a TTY. End-of-input closes that session and its workers. Persist logs and evidence after exit. Future live reconnection may attach to an already running foreground owner, but must not create a hidden daemon or keep a disconnected one-shot invocation alive.

### Agent interface and verification evidence

Define versioned JSON schemas for project inspection, diagnostics, run events and final results in K02. Give runs, actions and diagnostics stable identifiers within their documented scope. Machine-readable output must remain parseable without terminal decoration or interleaved tool output; retain raw logs as separately addressable artifacts. Automation must not hang on interactive prompts: report missing required input explicitly. Define exit statuses for failed and incomplete verification alongside the result schema.

Provide read-only package and dependency queries for LLMs, with illustrative commands:

- `kvik packages versions <group:artifact> --json`: list published versions from configured repositories, with explicit stable/prerelease filtering and bounded pagination.
- `kvik packages dependencies <group:artifact:version> --target <target> --json`: inspect direct and transitive dependency version requirements and supported resolved selections for a specific package version and target/usage.
- `kvik dependencies --project <path> --json`: list the project's declared and locked versions, including transitive dependencies, module/target/compilation scope, selected variants and dependency paths. An explicit available-versions query can show upgrade candidates alongside the locked version.

Use versioned JSON with package coordinates, repository/source metadata identities, observation time, cache freshness and explicit incomplete/unsupported results. Keep declared requirements, locked selections and available versions distinct; availability alone does not prove compatibility. Queries may cache repository metadata but must not edit project configuration or lockfiles or execute package code. Support explicit refresh and offline queries that identify stale or missing metadata. Validate version listing, prereleases, multiple repositories, scoped dependency graphs, offline/cache behavior and unchanged project/lock bytes. Deliver these commands as K16 using the K05 resolver and K09 machine interface; future MCP tools should expose the same operations.

Each verification record must contain the requested scope, actual checks and targets, input-manifest digest, configuration and lock identities, toolchain identities, action outcomes and reproduction details. Record test identities and discovered, executed, failed and skipped counts, including any unavailable counts or incomplete discovery. Distinguish restored compilation outputs from tests executed during this run. A filter matching no tests must be explicit and must not satisfy a requirement to run tests. Missing required checks or unavailable targets produce incomplete verification even when every executed check passes.

Evidence applies to the recorded inputs and scope. Capture inputs before execution and run against that snapshot, or detect changes during execution and mark the result stale or indeterminate. Rechecking freshness must consider content, added and deleted files, configuration, dependencies and toolchain identity; a Git commit alone is insufficient. Later change-aware verification must account for committed, staged, unstaged and relevant untracked changes relative to its declared baseline. Unknown impact must widen the check set or report a gap.

Use run-specific working directories and mutable state. Shared caches contain immutable artifacts and support concurrent publication. Actions and cancellation belong to identified runs; any worker reused between runs belongs to their explicit foreground session and must isolate or reset per-run state. For the prototype, deduplication remains within a build; sharing in-flight work across runs would require a separate lifecycle contract. Persist verification records so an agent can inspect the evidence after its session ends.

## Proposed repository layout

These directories describe ownership boundaries. Create build modules only where dependencies or isolation justify them; avoid turning every package into a module immediately.

| Directory | Responsibility |
| --- | --- |
| `core/` | Project model, artifact types, action graph, canonical action identity and verification contracts |
| `bootstrap/` | Launcher, source/runtime lock, native composition, static build-plugin registry and executable cache |
| `plugin-api/` | Contracts for source-distributed Kvik build plugins |
| `execution/` | Scheduling, process lifecycle, working directories and output publication |
| `cache/` | Local store, remote protocol client and output materialization |
| `resolution/` | Repository access, dependency graph, KMP variants and lockfile |
| `adapters/jvm/` | Kotlin/JVM compilation, test launching and initial script compilation/execution |
| `adapters/native/` | KLIB compilation, host linking and Native tests |
| `cli/` | Configuration, commands, foreground interactive sessions, project inspection, diagnostics, machine-readable events and evidence queries |
| `tests/fixtures/` | Small compatibility and invalidation projects |
| `tests/integration/` | Cross-process, cross-checkout and cross-machine verification |
| `docs/decisions/` | Versioned decisions and their consequences |
| `docs/evidence/` | Reproduction instructions and summaries of milestone results |

Proposed CLI capabilities are `resolve`, `build`, `test`, `explain` and `doctor`. Exact syntax is a first-milestone decision. `explain` must show an action's declared inputs and why it executed or reused a cached result. Keep these as proposed capabilities until executable implementations exist.

Add `dev` for the foreground continuous session, with an explicit target selection and separate machine-control mode. Launcher help must distinguish source/native bootstrap work from the consumer build, and expose which cached runtime composition is being used.

Add project inspection and evidence/freshness queries to the prototype CLI contract. Later, a change-aware verification operation should combine check selection and execution. Illustrative names are `inspect --file ... --json`, `evidence --run ... --check-current --json` and `verify --since ... --json`; these are proposed interfaces, not implemented commands.

For K12, add script execution and inspection to the CLI contract. Illustrative usage is `kvik run-script update-index -- --input articles/`, with a separate explicit way to select a standalone script file. Inspection should expose the resolved dependencies, compiler plugins and prerequisite actions. Final syntax is a K12 decision.

## Agent execution model

Use one integration agent and up to three implementation agents concurrently. This is an initial coordination limit, adjustable after observing integration throughput. Agents own explicit directories and must preserve one another's changes. Use separate worktrees for concurrent edits and merge completed changes serially.

The integration agent owns public contracts, dependency ordering, fixture selection and the main branch. Implementation agents own a bounded task and its verification. Assign a separate review pass after each substantial merge; the reviewer should challenge cache inputs, failure behavior and the claimed evidence.

Start parallel implementation only after agreeing on the core artifact and action contracts. Agents may propose changes to those contracts, but the integration agent incorporates them before dependent branches proceed. Avoid simultaneous incompatible edits to shared types.

Each task handoff must contain the change, exact reproduction commands, test results, unsupported cases and outstanding decisions. Do not accept generated code or a self-reported success in place of executable verification. Agent throughput determines how many tasks can be attempted; acceptance gates determine which capabilities can be claimed.

## Implementation backlog

| ID | Task and owner | Dependencies | Acceptance evidence |
| --- | --- | --- | --- |
| K01 | Integration agent establishes bootstrap, source pins, Kotlin/Native runtime spike, fixture copies and baseline builds | None | Reproducible JVM and host Native baselines; Kotlin/Native CLI with one source build plugin; platform interop and no-daemon worker feasibility evidence |
| K02 | Integration agent defines configuration, action, artifact, source/runtime lock, build-plugin, worker-protocol, machine-interface and verification contracts | K01 | Schema examples, canonical identity vectors, native composition identity, event/result examples, lifecycle and freshness semantics |
| K03 | Execution agent implements local action execution and scheduling | K02 | Dependency ordering, deduplication, bounded concurrency, run isolation, worker ownership, cancellation and failure propagation tests |
| K04 | Cache agent implements local store and action cache | K02 | Immutable output round trips, atomic publication and corruption rejection tests |
| K05 | Resolution agent implements the supported Maven and KMP subset | K02 | Locked selections matching the comparison fixtures, offline reuse and explicit rejection cases |
| K06 | Compiler agent implements JVM compilation and tests | K03, K05 | Common/JVM fixture and real module compile and test without invoking another build tool; structured diagnostics, test identities and counts |
| K07 | Cache agent connects a compatible remote backend | K03, K04 | Successful fetch and upload, unavailable-server fallback, interrupted upload and concurrent-client tests |
| K08 | Compiler agent implements Native compilation, linking and host tests | K03, K05 | Common/Native fixture and selected real module compile and execute on macosArm64; structured diagnostics, test identities and counts |
| K09 | Integration agent connects CLI, project inspection, diagnostics, action traces and verification records | K03, K04 | Schema-valid output, cache explanations, input-bound evidence, freshness queries and usable reproduction data; adapter integration completed with K06/K08 |
| K10 | Verification agent challenges runtime lifecycle, relocation, invalidation, evidence freshness and concurrent worktrees | K06, K07, K08, K09, K14, K15 | All mandatory native runtime, lifecycle, cache and agent-interface cases below pass with retained traces |
| K11 | Integration and review agents evaluate the experiment | K10 or timebox expiry | Evidence-backed decision, agent-workflow comparison, incomplete items and prioritized next milestone |
| K12 | Compiler and integration agents add project and standalone JVM scripts | K04, K06, K09 | Standalone, workspace-module and compiler-plugin fixtures; dependency reuse, compilation-cache invalidation and execution behavior pass the script milestone gates |
| K13 | Compiler and integration agents add workspace compiler-plugin references | K04, K06, K09; K12 for scripts, K08 for Native consumers | Plugin module builds before consumers without publication; edit–compile–test loop, invalidation, application scope and cycle diagnostics pass the plugin milestone gates |
| K14 | Integration and cache agents implement source composition, native bootstrap and executable reuse | K02 | Clean source bootstrap, compatible cached startup, source-plugin rebuild, atomic publication and invalidation tests; shared cache integration with K04 |
| K15 | Execution and CLI agents implement foreground continuous mode | K03, K06, K09, K14 | Reload/watch/test loop, recovery after failure, non-TTY control, process cleanup and concurrent-session isolation |
| K16 | Resolution and CLI agents add package/dependency version queries for LLMs | K05, K09 | Structured available-version lists, package dependency requirements/selections and scoped declared/locked project versions; source/freshness, offline and read-only behavior verified |

Roles are logical assignments, not a requirement to run all named agents simultaneously. For example, the resolution agent can take the compiler role once dependency contracts are stable.

K12 and K13 are early extensions, not prerequisites for K10 or K11. Their JVM implementations may start as soon as the JVM prerequisites pass, without waiting for Native support; unfinished work carries into the next milestone.

K14 and K15 implement the required runtime architecture and belong to the prototype gate. Backlog numbers are identifiers, not execution order. The timebox remains two weeks; if native bootstrap or lifecycle constraints consume it, report that evidence at K11 rather than claiming the gate passed with a JVM runner or detached workers.

## Two week experiment

### Days 1 and 2 establish the contracts

Complete K01 and K02. Record baseline cold and warm builds for the selected fixtures. Verify the chosen compiler APIs with minimal JVM and Native invocations. Test connectivity and protocol capabilities against the chosen cache backend. Resolve uncertain infrastructure choices before writing a general implementation around them.

Prove a Kotlin/Native executable containing one source build plugin, launching a daemon-free compiler worker, and performing the required filesystem/network operations. Measure native bootstrap cost and cached startup. Pin native platform dependencies and set initial latency and memory budgets on named hardware before parallel implementation depends on those contracts.

### Days 3 through 5 build the local path

Run execution, cache and dependency-resolution work in parallel. Integrate a minimal JVM build as soon as its dependencies are available. Prefer a complete action trace for a small working project over broad configuration coverage. Keep Native compiler exploration bounded and feed its requirements back into the shared contract.

Integrate K14's launcher and cached native composition with the local path. Keep bootstrap executable caching independent of consumer compilation so the launcher can work before the project graph is available.

### Days 6 through 9 exercise real KMP inputs

Connect remote caching and the Native adapter. Build the first real library fixture through Kvik. Compare selected artifacts, visible APIs and test identities against the existing build. Record unsupported dependencies or source-set features explicitly. Do not reduce the fixture's meaningful assertions merely to obtain a passing result.

Integrate K15's foreground reload and test loop with the working JVM fixture, including build-failure recovery and worker shutdown.

### Days 10 through 14 challenge the cache and decide

Run fresh-checkout, cross-machine, invalidation, evidence-freshness, concurrent-worktree and failure tests. Measure actual time spent in configuration, resolution, hashing, transfer, compilation and tests. Exercise the agent-workflow comparison below. Fix correctness failures before optimizing throughput. At expiry, report achieved capabilities and blockers even if the complete prototype gate has not passed.

The schedule is a task ordering hypothesis. A hard dependency-resolution or compiler portability issue may consume the timebox; that is useful evidence and should trigger a scope decision.

## Mandatory validation gates

### Native runtime and foreground lifecycle

- Bootstrap from pinned Kvik and build-plugin sources with no cached Kvik executable. Record native backend/toolchain identities and demonstrate that no preexisting Kvik process or executable is needed.
- Invoke again with provisioned sources/tools and a cached executable, including offline execution. Verify no native recompilation and no unnecessary JVM worker for help, inspection or eligible compilation cache hits.
- Change Kvik source, build-plugin source/dependencies, registry inputs and native build settings independently. Verify executable-cache invalidation. Change only consumer source/configuration and verify runtime reuse when the selected composition is unchanged.
- Start simultaneous launchers for the same composition; interrupt a native build and exercise a corrupt/incompatible cached binary. Verify coordination, atomic publication and explicit rebuild/failure without running a stale composition.
- Exercise file watching, repeated `r`, `t`, build failure followed by a fix, and program restart. Verify fresh inputs, output-watch exclusions and accurate identification of an older application still running after a failed build.
- Change a source build plugin during continuous mode. Verify controlled native recomposition and session restart; no new plugin code is loaded into the old executable.
- Exercise one-shot completion, `q`, Ctrl-C, termination, parent loss and a worker crash. Verify terminal restoration where possible, bounded shutdown and no surviving owned compilers, watchers or development servers.
- Run two independent foreground sessions and cancel one. The other's workers and outputs must remain valid. Exercise the equivalent machine-control operations without a TTY and verify end-of-input cleanup.

### Build and dependency correctness

- Compile a multi-module fixture containing shared code, platform actuals, resources and transitive dependencies.
- Run JVM and host Native tests and compare expected test identities, failures and skips with the reference builds.
- Resolve representative real KMP dependencies using the correct variants and metadata. Record the selected artifacts and digests.
- Reject unsupported metadata and unresolved variants with actionable diagnostics.
- Build the chosen real library from unchanged production sources.

### Cache correctness

- Run the same build from two different absolute checkout paths; explain any nonportable actions individually.
- Populate the remote cache on machine A, then use a clean local output directory and empty local action cache on machine B with a compatible pinned execution environment. Eligible unchanged compilation actions must be fetched rather than executed.
- Change source contents while preserving size and modification time. The affected action must miss the cache.
- Mutate compiler flags, compiler version, dependency contents, resources, source-set edges, declared environment and generator inputs independently. Check the expected invalidation boundary for each.
- Verify that input deletion and renaming invalidate results, while outputs for unrelated modules remain reusable.
- Build equivalent controlled inputs twice without using cached results. Compare output manifests and investigate nondeterministic files, embedded paths and archive timestamps.
- Interrupt a producer during execution and upload. Other clients must never accept a partial successful result.
- Simulate cache outage, missing blobs and corrupted data. The build must fail explicitly or execute locally as appropriate, never silently use an invalid result.
- Verify that different targets, variants and incompatible SDK/toolchain identities cannot collide.

Native portability is a separate assertion from JVM portability. If one Native action cannot be relocated safely, retain it as local-only and report that limitation; do not describe the entire Native pipeline as remotely cacheable.

### Agent interface and evidence correctness

- Validate inspection output and run events against the versioned schemas, including failure and cancellation paths. Check file ownership, targets and configuration origins against fixture expectations.
- Exercise compilation errors, failing tests, zero-test filters, skipped tests, missing SDKs and cache outages. Each result must identify its category, affected scope and reproduction details without requiring log parsing.
- Request checks for a target unavailable on the host. The result must identify the missing verification and must not claim complete success based on the remaining targets.
- Edit, add, delete and rename relevant inputs after a passing run, including untracked sources and configuration. Evidence queries must detect stale results. Mutate a source during execution and verify snapshot consistency or explicit stale/indeterminate status.
- Run two worktrees concurrently against a shared cache, with both identical and diverging source inputs. Verify independent outputs and mutable state; cancel one run and confirm the other completes correctly.
- Restore compilation outputs from the cache and run tests. The evidence must distinguish reused compilation from tests executed in the current run and remain inspectable after the invoking agent session ends.

### Performance and operability

Measure cold builds, repeated local builds, remote-hit builds on a fresh checkout, and small source edits. Record wall time, executed actions, hit counts, bytes transferred and hashing time on named hardware and pinned software versions. Compare useful work and output correctness as well as total time.

Track test wall time, executed versus avoided checks, parallel worker utilization and peak memory. Compare serial and default-parallel runs for equivalent test coverage and outcomes; evaluate avoidance against full runs before claiming it safe.

Measure first-use tool/source provisioning and native executable construction separately from cached CLI startup, no-op builds, session reloads and source-build-plugin rebuilds. Include process count, peak/resident memory and terminal input responsiveness. Compare one-shot compiler-worker startup with reuse inside an explicit foreground session. Report results against K01's budgets; fast cached startup must not hide an impractical cold bootstrap or plugin development loop.

A remote-hit run must avoid eligible compiler execution. Whether it is faster depends on artifact sizes, bandwidth and local incremental performance; the prototype must measure that rather than promise a universal speedup. Cache decisions and cancellation behavior must be diagnosable without inspecting private compiler state files.

Compare identical agent tasks against pinned KTC and Kvik: fix a compilation error, diagnose a failing test, identify the module and targets for a changed file, detect stale verification, and run independent worktrees. Hold fixture commits, agent model, instructions, resource limits and cache conditions constant; retain commands and transcripts. Record time to an independently verified fix, tool calls, emitted log volume, executed compiler actions and false completion claims. Repeat trials where feasible and report sample size and unsupported workflows. A small prototype comparison provides evidence for the next milestone, not a general superiority claim.

## Adoption after the experiment

Proceed only with the targets and dependency features that passed their gates. Keep current builds available until Kvik can reproduce required outputs and consumers can use its published artifacts.

Complete K12 early if it was not finished during the experiment. Its acceptance gates are:

- Run a standalone script with locked external dependencies, then run it offline with provisioned tools and dependencies. Inspect the selected artifacts and digests.
- Run a project script using a catalog or shared dependency profile and a workspace module. Reuse existing module artifacts, build missing prerequisites and verify that unrelated modules are not built.
- Compile and run a script requiring the serialization compiler plugin and its runtime library. Check incompatible plugin/compiler diagnostics and separation of plugin and runtime classpaths.
- Repeat a script invocation and prove compilation reuse while a fixture's observable runtime effect occurs on both invocations. Change runtime arguments and verify forwarding without unnecessary recompilation.
- Independently mutate source/helper files, dependency contents, compiler identity, plugin artifacts, plugin options and declared plugin input files. Verify the expected compilation-cache invalidation and unchanged unrelated actions.
- Exercise compilation failure, runtime failure, timeout and cancellation, including concurrent worktrees. Verify exit status, process cleanup, isolated outputs and structured events with separately captured script output.

After these gates pass, consider `.kts` support and script-backed generators independently. Preserve explicit dependency selection and cache eligibility in both extensions.

Complete K13 with a small workspace compiler plugin and consumer fixtures. Its acceptance gates are:

- From a clean checkout, build the plugin module and compile/test a JVM consumer without publishing the plugin to any repository. Verify plugin-first ordering and classpath separation.
- Change plugin behavior without changing its public ABI. Verify recompilation of affected consumers, changed fixture behavior and reuse of unrelated compilation outputs. Repeat with unchanged inputs to prove cache reuse.
- Apply the plugin to selected production/test compilations and targets, and verify excluded compilations remain unaffected. Check declared plugin input invalidation and isolation of generated outputs across concurrent actions.
- Exercise a plugin dependency cycle, plugin compilation failure and incompatible compiler/plugin pair. Report actionable diagnostics and prevent dependent compilation from proceeding with stale plugin artifacts.
- With K12 available, run a project script using the workspace plugin. With K08 available, compile and test a Native consumer using a plugin demonstrated to support that target. Record each supported combination independently.

The next milestones should be:

1. Add KMP publication and verify root metadata and target artifacts by consuming them from both Gradle and KTC fixtures.
2. Add linuxX64 CI, then iOS compile/link verification on macOS, with independent portability evidence.
3. Integrate one code generator, starting with a well-defined SQLDelight or KSP fixture, and prove its invalidation contract.
4. Adapt ABI checking, documentation and coverage to the public artifact graph. Share compiled ABI reader artifacts while retaining process isolation and foreground ownership. Instrument the ordinary test execution for coverage.
5. Add JS/Wasm and their test runtimes, followed by IDE integration based on a stable exported project model.
6. Consider Android library support and application packaging as explicit expansions with their own acceptance plans.

Benchmark adapters should share compilation between smoke and measurement profiles while preserving independent measurement processes. All measurement and tool processes remain owned by the foreground invocation/session. Publication and deployment remain explicit operations; a cache hit must never imply that an external side effect occurred.

Advance the agent capabilities alongside these integrations: first conservative change-aware verification with explained selections and omissions, then reconnection to explicitly owned foreground sessions, then validated configuration and lockfile edits. Add an MCP interface over the same versioned operations when a concrete agent workflow needs it, retaining explicit foreground/stdio ownership. Before narrowing check selection, prove that changed source-set edges, resources, generators, dependencies and configuration invalidate the appropriate verification scope.

## Decision at the end of the prototype

Continue Kvik if the experiment demonstrates the source-built native runtime, daemon-free foreground lifecycle, safe remote reuse, correct resolution for representative projects, direct compiler integration and a manageable path to the next required capability. Narrow the project if a specific target or integration dominates maintenance. Prefer improving KTC if the required runtime, artifact and cache capabilities can be addressed more economically than owning the broader build ecosystem.

The decision record must list passed gates, failed gates, reproducible evidence, measured build behavior, agent-workflow results and the next bounded set of tasks. Evaluate whether the agent contracts improve verification reliability and time to a correct change, and whether those benefits could be delivered more economically in KTC. It must distinguish a working internal prototype from a generally compatible KMP build system.

## Existing work to reuse

The sibling repositories provide tested behavior and fixtures, rather than a requirement to preserve their current orchestration:

- `../ktc-kover`: agent configuration, report parsing, test-failure propagation and fresh-session checks.
- `../ktc-bcv`: compiler-version-specific ABI extraction and JVM/KLIB fixtures.
- `../ktc-dokka`: source-set relationships and documentation validation cases.
- `../ktc-kotest`: JS/Wasm test discovery and Node/browser result handling.
- `../ktc-benchmark`: generated harnesses and measurement-process isolation.
- `../ktc-sqldelight`, `../ktc-application`, `../ktc-jib`: generation and artifact-consumer integration examples.
- `../ktc-to-gradle`: declarative-model translation and existing conversion fixtures.

Review the applicable licenses before copying implementations. Retain provenance and version pins for imported code and fixtures.

## First executable task

Begin with K01: initialize the repository structure and bootstrap, select and pin the fixtures, capture their current JVM/Native builds, and prove minimal direct compiler invocations. Prove native source composition with one Kvik build plugin and invocation-owned compiler execution. Produce a short decision record for the native backend, bootstrap/compiler versions, startup budgets, process lifecycle, cache backend and initial dependency subset. Then complete K02 before splitting execution, cache, resolution and native runtime implementation across agents.
