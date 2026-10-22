# Explicit JVM JDK API release

JVM project compiler options now accept `-Xjdk-release=11` (or the separate-value
form). Supported releases are 11, 17, 21, and 25, matching the existing bounded
JVM target policy. Release sets the effective bytecode target and restricts JDK
APIs. If `-jvm-target` is also supplied, both values must agree. Missing, repeated,
unsupported, and conflicting settings fail; Native compilation rejects the JVM
option. This follows the [Kotlin compiler reference](https://kotlinlang.org/docs/compiler-reference.html#xjdk-release-version)
and was verified against the pinned Kotlin 2.4.20 BTA implementation on the
provisioned Liberica JDK 25.0.4.

For a release-11 library with strict main API and ordinary tests:

```json
{
  "compilerOptions": ["-Xjdk-release=11", "-Xexplicit-api=strict"],
  "testCompilerOptions": ["-Xjdk-release=11"]
}
```

The setting is opt-in. Existing defaults remain JVM target 11 with the host JDK
API surface. Omitting `testCompilerOptions` inherits main options; supplying it
replaces main options, including release. An explicit empty test option list
therefore clears release and uses the existing target-11 default.

The shared `jvmTargetSettings` interpretation supplies dependency variant target
selection and main/test/module bytecode compatibility checks. Compiler plugins
retain their separate JDK-25 host policy. Configuration identity records release,
and stale locks are refused. The graph passes `JvmCompilation.jdkRelease`, an
optional final constructor parameter, into the action parameters and compiler
arguments. The parameter is omitted from JSON when absent, preserving default
action serialization. Setting it changes action keys even when target remains
11. The worker independently validates release and target agreement before BTA.

## Actual compiler checks

`tools/test_jvm.py` compiles `Thread.ofVirtual().unstarted {}` using the real BTA
worker on JDK 25. This Java 21 API:

- compiles with `-jvm-target 11` alone, establishing the original API-surface gap;
- fails with a structured `KVIK_COMPILATION_ERROR`, source position, and no output
  publication for releases 11 and 17;
- compiles successfully for release 21 without a separately supplied target.

A long-established JDK API control compiles under releases 11/17/21/25, with
classfile major versions 55/61/65/69. Missing, invalid, repeated, and conflicting
release requests produce `KVIK_UNSUPPORTED_OPTION` before output publication.
The Native scheduler/worker bridge independently runs release11/release21 actions,
confirming the API rejection/success and exact forwarded release options.

Native graph tests cover inherited release, independent test override/clear,
release-driven dependency variants, lower consumer-target rejection, unchanged
unrelated production action keys, changed scoped compilation keys, and stale-lock
refusal. Typed-action tests cover strict JSON roundtrip and invalid release values.

## Reproduction

From the candidate worktree `/private/tmp/kvik-k06-jdk-release`:

```sh
python3 tools/test_contracts.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
python3 tools/test_project_graph.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
python3 tools/test_jvm.py \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --compiler-artifacts /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts
python3 tools/test_jvm_native.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --compiler-artifacts /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts \
  --jvm-artifacts /private/tmp/kvik-k06-jdk-release/.kvik/tests/jvm/artifacts \
  --offline
```

Results: 25 Native contract tests, 12 Native graph tests, six Native bridge unit
tests, the actual BTA/JUnit worker harness, and the Native bridge's 16 action
observations passed their expected outcomes. The bridge includes intentional
failure/cancellation/incomplete controls; it does not report those as passing
consumer tests. Evidence is retained under `.kvik/tests/jvm/evidence.json`,
`.kvik/tests/jvm/fixture/*release*.result.json`, and
`.kvik/tests/jvm-native/report.json`, with requests, diagnostics, and tool identities.

The integrated [complete kotaml public CLI run](k09-kotaml-cli.md) was repeated
with explicit release11 in both main and test options. All 2,038 exact test
identities passed, and all four compilation actions recorded `jdkRelease: 11`.
This validation covers the pinned
compiler/JDK distribution and Kotlin sources; it does not claim arbitrary JDK
distribution support, Java-source compilation, or runtime behavior on JDK 11.
