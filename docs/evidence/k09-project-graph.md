# K09 pure project-to-action graph evidence

The implementation is `integration/project-graph`. No shared core, schema, CLI, bootstrap or compiler-adapter files are changed. It constructs existing typed JVM/Native actions and returns requested-check/terminal-action mappings to the run coordinator.

Reproduce on the provisioned host:

```sh
python3 tools/test_project_graph.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
```

The harness builds a Kotlin/Native test runner. Eight tests exercise the pure graph with concrete manifests and prerequisite results:

- Dependency-first module edges, source-set inheritance and namespaced prerequisite class/klib outputs; changing a dependency output digest changes the consumer action key.
- A three-module transitive closure includes both prerequisite outputs and dependencies' runtime resources, without leaking unselected targets/tests. Changing a dependency resource changes the test key while leaving an unaffected consumer compilation key stable.
- JVM test compilation uses API variants and main friend paths; test execution gets runtime-only artifacts, test/main/dependency resource roots and `NEVER` cache policy. No-source/empty-class selections still create a test action with nonempty classpath.
- Native runner actions combine main/test sources, link dependency klibs, apply main-only serialization plugins when recompiling main, retain resource files at declared runtime input paths and perform non-cacheable tests even without test-source files.
- Common fragments work for both shared multi-target source sets and conventional `commonMain` in a JVM-only project; a JVM-specific intermediate ancestor remains a platform fragment. Typed options preserve JVM target, opt-ins, API/warning policy and Native module names; unsupported options fail.
- Serialization plugin artifacts are separate from consumer classpaths and absent from test runtime inputs. Workspace plugins, unsupported coordinates, options and extra inputs are refused.
- Stale locks, insufficient scope policies, missing selections, missing/failed/malformed prerequisites, overlapping source/resource roots, Java sources, missing main sources, unknown targets and missing explicit JVM class selections fail.
- The unchanged existing `tests/jvm/fixture/Common.kt` and `Platform.kt` bytes are hashed into a project snapshot and planned as a JVM-only common/platform compilation. No implicit stdlib is added.

Fixtures model already-verified lock selections. K09 lock tests independently prove metadata/CAS verification; this suite does not recast fabricated test lock records as resolver evidence. This slice executes no consumer compiler, test suite, Gradle or Kotlin Toolchain process. The existing-source case proves planning and identity, not compilation. Runtime resource loading, full kotaml graph execution, CLI/provider/cache wiring and verification publication remain integration work. The graph's bounded source convention, option/plugin support and Native resource path contract are documented in `integration/project-graph/README.md`.
