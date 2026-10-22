# K13 bounded JVM workspace compiler plugins

This slice compiles a workspace module as a JVM Kotlin 2.4.20 K2 compiler
plugin, then applies its classes directory and service resources through BTA.
It requires no publication or JAR packaging. The producer compiles against its
own API dependencies; application uses its complete runtime module/external
closure, including private and runtime-only helpers, in a separate plugin
classpath. Plugin runtime resolution uses host JVM 25 independently of the
consumer's JVM target and JDK API release 11.

The approved additive contract is described in `k13-contract.md`. Applications
support ordered literal options, typed declared input-file/output-directory
options, ancillary declared inputs, and main/test scopes. Every declared output
root must be bound and contain a file in this initial adapter. Output roots are
under `plugins/<id>/`; actual filesystem aliases (including case and Unicode
normalization on the tested macOS volume) are rejected before plugin loading.
Plugin outputs never become ordinary module classes. The full producer/runtime
bytes, resources, options and inputs participate in consumer action identity.

## Executed fixture

From `/private/tmp/kvik-k13-workspace-plugins`:

```sh
python3 tools/test_workspace_plugins.py \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --compiler-artifacts /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts \
  --jvm-artifacts .kvik/tests/jvm-native/artifacts --offline
python3 tools/audit_workspace_plugins.py .kvik/tests/workspace-plugins/case-1173e5ef
```

The retained run is `.kvik/tests/workspace-plugins/case-1173e5ef`; console output
is `/private/tmp/kvik-k13-fixture-final.log`. `--offline` replays previously
acquired pinned artifacts and metadata; the Native resolver initially acquired
the compiler closure over HTTPS. Every scenario validates the persisted lock
against CAS before building. Resolver provenance and wildcard-all exclusion
semantics are recorded separately in `k05-wildcard-exclusions.md`.

The first graph executes 13 actions: ten compilations and three real JUnit
tests. Two independent consumer modules observe the plugin's IR string rewrite;
an unrelated module provides the invalidation control. All eight scenarios pass:

* Initial execution, followed by ten compile cache hits and three fresh tests.
* A producer implementation edit with identical `javap -public` output changes
  consumer keys and observed behavior; unrelated compilations remain cached.
* A typed input edit changes behavior and consumer keys while reusing producer
  compilation. An ancillary input edit also changes consumer keys.
* A producer compilation failure blocks consumers despite older cached success,
  with no successful output manifest for the failed action.
* Main-only and test-only applications produce their distinct asserted behavior.

Eight separate worker failures cover wrong plugin ID/version, missing input,
missing registrar, incompatible mixed protocol, undeclared generated output,
and case/Unicode output aliases. Each publishes neither classes nor the report.
The original tracked fixture remains unchanged. The fixture invokes a private
helper and reflectively loads a runtime-only helper; the latter is absent from
the producer compile classpath. Ordered repeated option values are checked in
the generated report, including an embedded `=`.

The retained audit independently recomputes all 13 action keys, verifies 164
materialized input files and 31 materialized/CAS output files, compares exact
artifact digest sets for 14 compilation/plugin resolution domains, and checks
the exact three JUnit IDs and per-module counts. It also checks prerequisite
event ordering, action-to-worker descriptor mapping, release options, and
producer/consumer classfile major versions 69/55. `audit.json` records its result.

The harness uses the Native resolver, pure project graph, scheduler, process
ownership, local CAS/action cache and BTA worker. It is **not** a composed public
CLI gate. A pristine `public-cli/kvik.json` with explicit test selections is
retained for that independent gate. Select modules `consumer`, `consumerTwo`,
`unrelated`, target `jvm`, with explicit pins
`org.jetbrains.kotlin:kotlin-stdlib:2.4.20` and `org.jetbrains:annotations:13.0`.

## Regression checks

The existing focused harnesses passed with the same JDK/Native paths:

* `tools/test_contracts.py`: 28 Native tests; plugin schema suite: 6 tests.
* `tools/test_project_graph.py`: 14 Native tests, including plugin graph edges,
  closure separation, cycle rejection, missing input and content-key changes.
* `tools/test_dependency_locks.py`: 11 Native tests and persisted schema checks
  (using the root `.kvik/schema-tools/bin/python` and `--ktc-cache
  /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache`).
* `tools/test_resolution.py`: 24 Native tests before the parent's additional
  independent resolver probes, including real pinned compiler POM replay.
* `tools/test_jvm_native.py`: 6 Native unit tests and 16 real observations,
  including existing published serialization, failure, zero-test, cancellation
  and timeout cases. Its legacy-plugin branch is unchanged by the final
  workspace-output validation added afterward.

## Remaining bounds

This is one workspace plugin per compilation, pinned Kotlin 2.4.20 K2, JVM-only.
Exactly one modern registrar and at most one command-line processor are accepted;
legacy ComponentRegistrar and mismatched compiler API bytes fail explicitly.
Preflight instantiates service implementations to verify ID/K2 compatibility,
then BTA loads them for compilation. Constructors must tolerate repeated service
instantiation. This does not establish compatibility with arbitrary plugins.

Plugin code is trusted JVM code, not an OS sandbox. Declared output validation
does not prove absence of arbitrary external I/O or nondeterminism. Generated
reports are action outputs, not a new generated-source compilation phase.
Plugin-specific sleep/cancellation has not been exercised; only the existing
generic owned-worker cancellation/timeout regression has passed. Script and
Native consumer plugins, multiple simultaneous applications, and the complete
K13 PLAN gate remain unclaimed.
