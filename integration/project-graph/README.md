# Pure project action graph

`ActionNode.outputContract` exposes output roots before prerequisite actions run.
Built-in graph nodes provide an `ActionOutputContract` containing `OutputArtifact`
values with a logical path, semantic kind and `ArtifactShape` (`FILE`, `TREE` or
`FILE_OR_TREE`). These paths are relative to the producer's output manifest, not
the project or the consumer's staged input directory. Tree roots can be empty;
contracts describe allowed outputs without promising an exact file inventory.
Compiler plugins use `FILE_OR_TREE` because their existing declarations name roots
without specifying their shape. Source resources are inputs and are not falsely
listed as compilation outputs.

Source plugins can supply the same contract on additive or provider actions:

```kotlin
ActionNode("generate", prerequisites,
    ActionOutputContract(treeOutput("generated", "generated-sources"))) { results ->
    generationAction(results)
}
```

The legacy constructor remains available with an unknown (`null`) contract.
`copy` and graph composition retain contracts. The scheduler checks the resolved
recipe's output roots before starting it and validates successful output paths
against declared shapes, including cached and deduplicated results. Mismatches
fail the action and block consumers. The `inspect-graph` CLI/MCP query serializes
these declarations without invoking any action resolver.

`ProjectActionGraph.testLaunches` maps test action IDs to `JvmTestLaunch`,
`NativeTestLaunch` or `JsTestLaunch`. Providers and contributors can inspect these
settings before prerequisites run. Each launch uses the existing adapter to
create its action via `action(inputs, composition, toolchains)`. Composition
rejects resolved recipes that disagree with a declared launch. Launch constructors
snapshot caller collections; paths describe staged runtime inputs, not host paths.

`JvmTestLaunch.selection` exposes the ordered runtime classpath, selected classes,
engines, Java agents and `JvmCoverageSpec` instrumentation settings. Coverage includes pinned
agent/reporter identities, production class roots, source digests and exclusions;
the adapter still validates these against actual inputs during resolution.
Native launches expose their executable and runner; JS/Wasm launches expose their
entry point and filter. All launches expose timeout and scheduler resources.
The CLI/MCP `testLaunch` object serializes the same parameters. It describes runner
settings; test discovery still happens during execution.

Source plugins implement `ProjectTestInstrumentationContributor.instrumentTests`
to return `JvmTestInstrumentation` declarations for existing JVM test actions.
Each declaration adds ordered `JvmTestAgent` descriptors, prerequisite action IDs
and an input resolver. A plugin can also implement `ProjectGraphContributor` to
provide those prerequisite actions. The input resolver receives only its declared
prerequisites and returns a manifest of agent JARs and supporting files; the
executor materializes their verified CAS contents. Instrumentation preserves the
original checks, classes, classpath, resource budget and timeout. Contributors run
in plugin-ID order, and later contributors see earlier instrumentation.

An agent's options are ordered `JvmAgentOption` fragments. `LITERAL` fragments are
passed unchanged; `INPUT_FILE` and `OUTPUT_FILE` fragments become paths beneath
the action's isolated `input/` and `output/` directories. Concatenation defines the
agent-specific option syntax, for example a `LITERAL` fragment containing
`destfile=` followed by an `OUTPUT_FILE` fragment. Each output must be declared under
`instrumentation/<agent-id>/`; output parents are created before launch. Agent JARs,
option inputs, option order and outputs participate in the ordinary test action
key. Agents execute after the built-in JaCoCo agent when coverage is enabled.

Tests always run freshly, with the same foreground ownership and cancellation as
ordinary tests. Agent output files are retained with the test report and exposed
as `test-instrumentation` artifacts. Missing or unreadable declared files produce
`KVIK_TEST_INSTRUMENTATION_INCOMPLETE`; a successful test run then becomes incomplete.
Undeclared files invalidate output capture. Limits are 16 agents, 256 output files
in total, 8 MiB per output and 64 MiB per agent. This hook currently supports JVM
Java agents; Native and JS instrumentation require their own adapter contracts.

`ProjectActionGraph.generatedResources` records resource generators separately
from Kotlin source generators. Resource declarations name exact relative files,
so runtime classpaths are available before generation. Consumers depend directly
on the producers and use their verified file digests. Missing, extra, executable
or overlapping outputs are rejected. Resource roots use the same module/scope
mapping as static resources and survive provider/contributor composition.

`kvik.projectgraph.projectActionGraph` has no filesystem, CAS, network, process or clock access. Its inputs are a validated `EffectiveConfiguration`, a dependency lock already verified by `DependencyLocks.loadLocked`, an immutable project-relative `TreeManifest` whose bytes are already in CAS, runtime composition identity, target toolchain identity maps, and an explicit selection:

```kotlin
val graph = projectActionGraph(
    effective, verifiedLock, snapshot, composition, targetToolchains,
    ProjectGraphRequest(
        modules = setOf("library", "consumer"),
        targets = setOf("jvm"),
        mode = ProjectMode.TEST,
        jvmTests = mapOf(
            "library" to JvmTests(listOf("example.LibraryTests")),
            "consumer" to JvmTests(emptyList()),
        ),
    ),
)
```

`ProjectActionGraph.nodes` are ordinary core `ActionNode`s suitable for `SchedulerRun`. `requestedChecks` contains only the explicitly requested `module:target:build` or `module:target:test` checks. `terminalActions` maps each check to its terminal action. Transitive module compilations are prerequisites, not additional claimed checks. Modules and targets form an explicit Cartesian selection; missing targets fail rather than being skipped. JVM test selections must be supplied for exactly the selected JVM test modules. No test class scanning or inferred engine selection occurs.

Stable action IDs are `module:target:main`, `module:target:test-compile` and `module:target:test`. Node creation is deterministic and dependency-first. The graph validates configuration/lock identity and exact locked domain/root/variant requests, then requires the effective scope policy to cover requested checks. It does not replace the lock layer's CAS and metadata verification. Project files outside selected source/resource closures do not enter action inputs. Explicit stdlib and test-library dependencies are the caller's responsibility; no dependencies or alignment pins are injected.

Main/test source sets use their declared `dependsOn` closure. Tests consume main sources/dependencies even without an explicit main edge. Common fragments are source sets shared by distinct configured targets, plus the bounded conventional names `commonMain` and `commonTest`; these names also support single-target common/JVM projects. Other target-specific ancestors, such as `jvmIntermediate`, remain platform sources. The model has no general fragment-platform annotation: single-target common fragments with other names require a future explicit model extension. Source directories contain Kotlin files only; non-Kotlin source files and overlapping source ownership fail explicitly.

JVM test compilation excludes source files already compiled into main and declares the main output as a friend path. Its classpath uses locked `java-api` variants. Execution uses `java-runtime` variants, compiled test/main/transitive module classes, and resource roots. Empty caller test classes are passed through to the worker, whose zero-selection result remains incomplete. If there are no separate test source files, the graph omits only the redundant test compilation and still creates a test action with nonempty main/runtime classpath. A selected test compiler plugin without any test sources is explicitly unsupported.

Native main compilation produces a klib using `kotlin-api` variants. Native tests compile the module's main and test sources together into a built-in test runner, which preserves access to main internal declarations without unsupported friend-module flags. They link dependency-module klibs, not the module's own separately compiled main klib. The own-main compilation remains a prerequisite; its bytes are not used to link this combined runner. Main-scoped compiler plugins are also applied to these recompiled main sources. Even with no test-source files, the runner is produced from main sources and a Native test action performs discovery/execution; zero discovered tests cannot be claimed as passing. All test actions use `CachePolicy.NEVER`.

Outputs from prerequisite actions are validated and remapped inside `node.resolve`, so changed dependency output digests change consumer keys. Local JVM `classes/` outputs become `modules/<module>/jvm/main|test/`. Local Native klibs become `modules/<module>/macosArm64/main.klib`; test executables/symbols become `modules/<module>/macosArm64/test/tests.kexe[.dSYM/...]`. Source files use `src/<original-project-path>`. External artifacts use `deps/<sha256>-<size>.jar|klib`; compiler plugins use separate `plugins/<sha256>-<size>.jar` paths. Every merged file has a declared digest. Missing, failed, mismatched or malformed prerequisite results fail resolution.

Resources retain paths relative to each resource directory under `runtime/<module>/main|test/`. A duplicate relative path in the same compilation's resource directories is an unsupported overlay and fails. JVM test resources precede main resources in the classpath; dependency main resource roots also enter it. Resource bytes enter runtime inputs and affected action identities. Main resources are also declared compile inputs, but the compile action does not package or copy them into its class output. Native test resources are declared files at `input/runtime/<module>/main|test/<relative>` relative to the executor's working directory. There is no Native classloader or automatic resource lookup API; tests must explicitly use those input paths. Empty resource directories are not artifacts.

Supported project compiler options are `-jvm-target` (11/17/21/25), JVM language/API version 2.4, repeated qualified `-opt-in`, `-Xexplicit-api=strict`, `-Werror`, derived/common-fragment `-Xmulti-platform`, and Native `-module-name`. Options accept separate values or `=` where applicable. Duplicated non-repeatable settings and other options fail. Target `compilerOptions` apply to main and are inherited by tests unless `testCompilerOptions` supplies a complete override. Tests may use a newer JVM target than main, but cannot use an older target than any consumed module output. Custom JVM module names are not representable separately from module identity in the current adapter and are rejected. Main compilations require at least one Kotlin source; resource-only modules, Java sources and arbitrary linker/compiler flags are not implemented.

Only an externally locked, single-artifact `org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20` plugin is supported. Its closure is taken from its owning compilation’s `compiler-plugin` domain at hosted JVM25, separately from consumer libraries. Options, extra plugin inputs, mismatched pins, other plugins and workspace module plugins fail explicitly. Workspace compiler plugins remain K13 work. Artifact digests present in both plugin and consumer compile classpaths are refused.

The graph does not run compilers, materialize files, validate installed toolchains, publish caches or verification records, provide an atomic project snapshot, or reinterpret passing/incomplete test reports. Those remain coordinator responsibilities.

Dependency visibility is explicit: `scope=compile` (the default) reaches the owner’s compiler and runtime; `scope=runtime` reaches runtime only. `exported=true` makes a compile dependency visible to downstream module compilation; default `false` keeps it private. Exported chains remain visible only while every traversed edge is exported. Runtime includes all transitive dependencies, including private external dependencies and runtime-only modules. A runtime-only module is built for a test’s runtime prerequisites, but its output/resources never enter the consumer compile inputs. Each compilation/usage looks up its own domain in lock v2, preserving independently pinned main/test versions and preventing test-only artifacts from changing main action keys.

The Native adapter currently has a single library list. This graph explicitly rejects differing visible/link module or external root closures and differing locked compile/link artifact identities. It does **not** promote private or runtime-only dependencies to visible API to make linking work. Supporting such Native graphs requires a separate hidden-library/link adapter extension. Native tests recompile main sources, so their opt-ins must include main opt-ins; other unsupported compiler flags remain explicit errors. Their separate main prerequisite still checks production’s strict API/warnings policy. This slice does not claim full Native kotaml compatibility.

## Source-plugin executors

`ProjectExecutorContributor` registers invocation-scoped `ActionExecutor`
implementations named `<plugin-id>:<implementation-version>`. It can be combined
with `ProjectGraphContributor` or a graph provider. `ProjectExecutionContext`
provides the private retained workspace, composition, content store and owned
process host. The CLI composes these executors beneath the ordinary local cache
and scheduler for builds, scripts and foreground/IDE requests.

Custom artifact/verification actions support local caching or always-run policy;
remote reuse and custom test/script/publication/server adapters are not enabled
by this contract. Registration is side-effect-free, callbacks are cooperative,
and running handles must reap their work on close. The wrapper validates action
ownership, enforces deadlines/cancellation and closes before cache publication.
See the root README's source-plugin section for lifecycle and caching rules.
