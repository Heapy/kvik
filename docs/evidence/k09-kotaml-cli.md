# K09: Full kotaml JVM tests through the public Native CLI

The source-built Kotlin/Native CLI resolved, compiled and tested both unchanged
kotaml modules at commit `a5c94c77b220cfc7b6335668766e8f26930968c3`.
All 2,038 expected test identities passed: 1,716 in kotaml and 322 in kotaml-json,
with complete discovery, no failures and no skips. All six actions executed:
two production compilations, two test compilations and two test invocations.
The harness used public `resolve`, `test` and `verification` commands, without
nested KTC/Gradle builds or direct compiler/worker execution.

Runtime composition:
`1f28d55e15685648fca978bf145ec42e964f1bc185625ce63d0268f733c3d3b1`.
Native construction reported 34,520 ms. The run used the explicit installed tools
described in [the CLI evidence](k09-build-cli.md).

## Reproduction and retained results

```sh
./kvik --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --offline -- runtime-info
.kvik/schema-tools/bin/python tools/test_kotaml_cli.py \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20
```

The retained fixture is `.kvik/tests/kotaml-cli/case-wztnvhb2`, with run
`.kvik/runs/kvik-run-bw0HNM`. It includes exact commands, output, timing,
configuration, global/scoped pins, tool configuration and the dependency lock.
Resolution took 47.991 seconds and the cold compilation/test command 200.223
seconds. Timings include tool verification and concurrent development work;
they are observations, not performance acceptance results.

The harness verifies all 67 pinned source/resource files before and after execution
in both the original fixture and its separate working copy. It preserves the 29
kotaml and three kotaml-json test selectors. Fixture configuration and source
manifests are retained separately from Kvik's declarative translation.

## What passed

- Public resolution retained 54 selections, 41 distinct artifacts (including the
  separately resolved serialization plugin) and 64 metadata identities.
- All eight module/compilation/API-or-runtime external artifact sets match the
  pinned reference bytes. Production annotations13 and test annotations23 remain
  separate. Runtime-only declarations do not widen compilation closures; a
  coordinate may still be present through an independent API dependency path.
- Actual action inputs have the expected external artifacts and module/resource
  directories. Private dependencies are not promoted into downstream compilation.
  The serialization plugin's exact bytes are separate from consumer classpaths.
- Main compilation uses strict explicit API; test compilation does not. Both use
  JVM target 11 and the preserved module names; tests use their own main outputs
  as friends. Global/scoped pins are retained exactly in reproduction arguments.
- Both test actions have `never` cache policy and report `executed`. Exact sorted
  test identities match `tests/jvm/kotaml-test-identities.json`, not only counts.
- Events and retained records validate against their schemas, journal sequences
  are contiguous, and stdout agrees with the retained journal. Prefix corruption,
  middle deletion, reordering and final-line-only truncation are rejected.
- A separate post-exit verification query reports current/0. Original and copied
  fixture inputs remain unchanged.

Ten dependency CLI regressions also passed on this runtime in 8.345 seconds.
The small JVM fixture separately proves repeated compilation hits with freshly
executed tests; see [dependency-scope evidence](k09-dependency-scopes.md).

## Limits

Artifact sets and bytes match; classpath order differs from the reference in all
eight resolution domains and all six action comparisons. This is not ordered
classpath equivalence or a general compatibility result. The harness checks the
implemented compiler options; it does not establish equivalence of the JDK API
surface selected by the reference build's release setting.

This fixture has no resource files. Nonempty resource loading is demonstrated by
the separate small JVM CLI fixture, not this run. Explicit JVM test selectors
reproduce the expected tests without running the upstream Kotest KSP generator;
test-identity parity does not establish generator execution.

The golden test-identity manifest records its reference XML/worker/source
provenance and the historical reports' unsealed-input limitation. This run binds
its own evidence to exact captured inputs; it does not retroactively seal those
reference invocations. Native upstream Kotest/KSP tests, compiler portability,
remote compiler reuse and full K15 lifecycle gates remain separate work.

Independent retained-run review recomputed all six action keys, checked every
materialized input/output digest and prerequisite module namespace, and compared
worker request classpaths/sources/plugins with the action records. All 67 source
files also matched the pinned Git commit. Test IDs were independently re-extracted
from the reference XML and prior worker records before comparison with this run.
All eight coordinate/digest sets, policy/options, test counts, journal mutation
results and the current post-exit query passed that review.
