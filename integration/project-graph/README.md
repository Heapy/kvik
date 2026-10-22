# Pure project action graph

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

Stable action IDs are `module:target:main`, `module:target:test-compile` and `module:target:test`. Node creation is deterministic and dependency-first. The graph validates configuration/lock identity and exact locked root/variant requests, then requires the effective scope policy to cover requested checks. It does not replace the lock layer's CAS and metadata verification. Project files outside selected source/resource closures do not enter action inputs. Explicit stdlib and test-library dependencies are the caller's responsibility; no dependencies or alignment pins are injected.

Main/test source sets use their declared `dependsOn` closure. Tests consume main sources/dependencies even without an explicit main edge. Common fragments are source sets shared by distinct configured targets, plus the bounded conventional names `commonMain` and `commonTest`; these names also support single-target common/JVM projects. Other target-specific ancestors, such as `jvmIntermediate`, remain platform sources. The model has no general fragment-platform annotation: single-target common fragments with other names require a future explicit model extension. Source directories contain Kotlin files only; non-Kotlin source files and overlapping source ownership fail explicitly.

JVM test compilation excludes source files already compiled into main and declares the main output as a friend path. Its classpath uses locked `java-api` variants. Execution uses `java-runtime` variants, compiled test/main/transitive module classes, and resource roots. Empty caller test classes are passed through to the worker, whose zero-selection result remains incomplete. If there are no separate test source files, the graph omits only the redundant test compilation and still creates a test action with nonempty main/runtime classpath. A selected test compiler plugin without any test sources is explicitly unsupported.

Native main compilation produces a klib using `kotlin-api` variants. Native tests compile the module's main and test sources together into a built-in test runner, which preserves access to main internal declarations without unsupported friend-module flags. They link dependency-module klibs, not the module's own separately compiled main klib. The own-main compilation remains a prerequisite; its bytes are not used to link this combined runner. Main-scoped compiler plugins are also applied to these recompiled main sources. Even with no test-source files, the runner is produced from main sources and a Native test action performs discovery/execution; zero discovered tests cannot be claimed as passing. All test actions use `CachePolicy.NEVER`.

Outputs from prerequisite actions are validated and remapped inside `node.resolve`, so changed dependency output digests change consumer keys. Local JVM `classes/` outputs become `modules/<module>/jvm/main|test/`. Local Native klibs become `modules/<module>/macosArm64/main.klib`; test executables/symbols become `modules/<module>/macosArm64/test/tests.kexe[.dSYM/...]`. Source files use `src/<original-project-path>`. External artifacts use `deps/<sha256>-<size>.jar|klib`; compiler plugins use separate `plugins/<sha256>-<size>.jar` paths. Every merged file has a declared digest. Missing, failed, mismatched or malformed prerequisite results fail resolution.

Resources retain paths relative to each resource directory under `runtime/<module>/main|test/`. A duplicate relative path in the same compilation's resource directories is an unsupported overlay and fails. JVM test resources precede main resources in the classpath; dependency main resource roots also enter it. Resource bytes enter runtime inputs and affected action identities. Main resources are also declared compile inputs, but the compile action does not package or copy them into its class output. Native test resources are declared files at `input/runtime/<module>/main|test/<relative>` relative to the executor's working directory. There is no Native classloader or automatic resource lookup API; tests must explicitly use those input paths. Empty resource directories are not artifacts.

Supported project compiler options are `-jvm-target` (11/17/21/25), JVM language/API version 2.4, repeated qualified `-opt-in`, `-Xexplicit-api=strict`, `-Werror`, derived/common-fragment `-Xmulti-platform`, and Native `-module-name`. Options accept separate values or `=` where applicable. Duplicated non-repeatable settings and other options fail. Target options apply to both main and test compilation. Custom JVM module names are not representable separately from module identity in the current adapter and are rejected. Main compilations require at least one Kotlin source; resource-only modules, Java sources and arbitrary linker/compiler flags are not implemented.

Only an externally locked, single-artifact `org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20` plugin is supported. Its closure is taken from the JVM runtime lock, separately from consumer libraries. Options, extra plugin inputs, mismatched pins, other plugins and workspace module plugins fail explicitly. Workspace compiler plugins remain K13 work. Artifact digests present in both plugin and consumer compile classpaths are refused.

The graph does not run compilers, materialize files, validate installed toolchains, publish caches or verification records, provide an atomic project snapshot, or reinterpret passing/incomplete test reports. Those remain coordinator responsibilities.
