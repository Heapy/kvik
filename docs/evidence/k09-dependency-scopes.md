# K09 dependency domains, visibility and compiler scopes

This slice preserves separate consumer conflict domains instead of resolving every project root together. Project JSON remains version 1; dependency locks require version 2. Existing unscoped locks must be explicitly resolved again. No migration guesses which compilation owned an old selection, and build/test never rewrites a lock.

The model adds optional `scope: "compile" | "runtime"` and `exported: boolean` to external and module dependencies. Defaults are compile/private. Runtime-only dependencies cannot be exported. Direct compile dependencies reach their owner's compiler; only exported edges propagate through downstream module compilation. Runtime includes all transitive edges. This intentionally removes earlier implicit module dependency promotion: old consumers must declare public dependencies as exported or declare their own direct dependency. Optional `target.testCompilerOptions` is a full override; absent settings retain main options. Tests using another JVM target must remain compatible with their own main and dependency-module outputs.

`ResolutionDomain(module,target,compilation,usage,purpose)` and `ScopedPin(domain,coordinate)` are public in `kvik.locking`. Domains serialize as `{module,target,compilation,usage,purpose}`. The default purpose is `dependencies`; plugins use `compiler-plugin` and a hosted JVM25 producer request. `ResolverPolicy.scopedPins` is an appended optional constructor argument. Its JSON is a sorted array of `{domain,coordinate}`. A domain pin explicitly overrides the same-component global pin only in that domain. Duplicate component pins and unselected domain names fail. Resolution policy identity is `kvik-maven-v3`.

`ScopedSelection(domain, selection)` wraps unchanged K05 `LockedSelection` results. Lock v2 serializes each selection as `{domain,requested,variant,artifacts,metadata}` and checks that domain purpose/platform/usage agree with the producer request. Consumer scope is never encoded as a fake producer attribute. Every domain resolves and replays independently against its own pins and immutable CAS metadata. Ordered domain/root/variant identities must exactly match effective configuration; transplanting a production closure into a differently pinned test domain fails offline replay.

Native domains include consumer usage `kotlin-runtime`, while the actual producer request remains the published `kotlin-api`. The current Native adapter exposes a single library list, so the graph refuses different visible/link module or external-root closures and different compile/link artifact identities. It does not put private/runtime-only libraries on a compile classpath. A hidden-library/link adapter extension remains required for those Native graphs. Native tests recompile main sources; their opt-ins must include production opt-ins. The separate main prerequisite still applies production strict API/warnings settings. This is not a claim of full Native kotaml support.

Validation ran in `/private/tmp/kvik-k09-dependency-scopes` with Kotlin/Native 2.4.20 and JDK25.0.4:

```sh
python3 tools/test_contracts.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca

/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_dependency_locks.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache

python3 tools/test_project_graph.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca

/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tests/contracts/test_project_settings_schema.py
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/check_schemas.py
```

Results: 23 Native contract tests, 11 Native locking tests, 11 Native graph tests, 4 focused schema cases, and 11 generated schema examples passed. The locking harness retained `production-lock.json`, `junit-lock.json` and `scoped-lock.json` under `.kvik/tests/locking/case-_bl_uq3i`. Production-only tests preserve 15 captured JVM API/runtime and Native API closures and all 12 prior KTC binary identities. Actual JUnit5.13.4 API/runtime metadata and its BOM also replayed from CAS. Synthetic conflicting annotations13/23 closures prove explicit test-domain alignment leaves production13 intact; no network or current metadata participates in replay.

Adversarial cases cover external and module runtime-only dependencies, exported chains with private external edges, runtime-only output key changes isolated from consumer compilation, main/test compiler target and strict-API separation, test-domain artifact mutations isolated from production action keys, domain/pin mutation isolation, wrong-domain closure substitution, legacy/invalid lock rejection, and missing/corrupt/stale content without repair. The pure graph tests construct real adapter action contracts; they do not execute consumer compilers. Public composed CLI verification of all 2,038 unchanged kotaml JVM tests remains the coordinator's separate integration gate, including exact per-domain artifact comparisons. No test selectors or fixture sources were changed here.

Independent review approved `b8c3ec4` before integration as `fed8a46`. Eight focused Native regression tests and four schema cases passed independently. The CLI adds repeatable `--scope-pin MODULE:TARGET:COMPILATION:USAGE:PURPOSE=GROUP:ARTIFACT:VERSION` to inspection, resolution, lock checks and execution, retaining the complete policy in reproduction arguments. Invalid policies are checked before creating the content store and rechecked against captured configuration.

Composed runtime `1bafc36b49c979ccbb90b89e1a4d7f9a86c16f4204225167c17de6ee9c07d000` built successfully (42,767 ms reported compilation). Ten dependency CLI tests passed in 9.575 seconds, six inspection tests in 2.267 seconds, and all eleven schema examples passed. The dependency tests resolve actual annotations13 production and annotations23 test selections, validate lock v2 and effective inspection identity, and verify read-only offline replay. Independent probes exercised ten invalid policies across five commands: all 50 rejected before lock/tool processing, with file contents and file/directory metadata unchanged. The probe and retained evidence are `/private/tmp/kvik-policy-preflight-review.py` and `/private/tmp/kvik-policy-preflight-review-cn6y_b2k/summary.json`.

The full kotaml preparation now validates with no schema errors. Its first public resolution attempt at `.kvik/tests/kotaml-cli/case-r6zd4237` rejected a metadata `capabilities` field; no compiler or tests ran in that attempt. The full CLI parity gate remains open until those producer semantics are supported and all expected classpaths and test identities are checked.

The real two-module JVM harness also passed on this runtime at `.kvik/tests/build-cli/case-kcswfrtl`. Cold compilation took 97.366 seconds, first test 108.927 seconds, and repeated test 67.265 seconds. Repetition reused all three compilation actions and executed the test again with its own effects file. All four journal corruption probes rejected damage, and an added source made previous evidence stale. These timings include tool hashing and concurrent development work; they are not performance acceptance results. The fixture now explicitly exports its standard-library dependency, preserving its intended consumer classpath under the default-private model.

An independent fresh two-module Native run at `.kvik/tests/native-scope-review/case-6jrzkpwg` created a new lock v2 for its graph without external dependencies, executed all four actions and passed exactly `fixture.AnswerTest.dependencyAndInternalDeclaration` (one discovered/executed, no failures or skips, complete discovery). The retained action keys, journal, record and test stdout effect were checked. A separate post-exit query returned current/0 and the same record. Resolve took 0.011 seconds, test 191.622 seconds and query 30.137 seconds under concurrent load. Reproduction: `.kvik/schema-tools/bin/python /private/tmp/kvik-native-scope-success-review.py`; retained commands and summary are in that fixture directory. No old fixture, cache or production source was changed.
