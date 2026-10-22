# Explicit Native compilation and link budgets

The retained public Native kotaml run `case-_4es_3sp` reached its root test
link's default 180,000 ms timeout after successfully executing JSON's 322
tests. Its failed verification and unpublished partial outputs are preserved;
see `k08-kotaml-native-cli.md`. This slice adds explicit project budgets rather
than changing defaults or reducing the fixture.

`TargetSpec.nativeTimeouts: NativeTimeouts? = null` is an additive target
setting. Its optional `compileMillis` and `linkMillis` are `Long` integers
in `1..3600000`; at least one must be present. Only `macosArm64` accepts the
setting. A test source set is not required, so production-only compilation
can declare a budget. Nulls, nonintegers, unknown fields, empty objects and
JVM use fail parsing. The generated schema describes the same supported
values and target restriction.

The parser retains the original document, so inspection exposes the setting
and effective configuration/lock identity includes it. Native main and test
klib compilation use `compileMillis`; the source-free test link uses
`linkMillis`. Omitted phases keep 180,000 ms. KSP generation remains 120,000 ms
and test execution remains 60,000 ms. No compiler arguments, adapter
implementation identifiers or default action recipes change. The existing
Native executor validates and enforces the timeout already present in each
canonical action.

Validation in `/private/tmp/kvik-k08-native-timeouts`:

```sh
python3 tools/test_project_graph.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --include-contracts
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tests/contracts/test_project_settings_schema.py
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/check_schemas.py
```

The single actual Kotlin/Native binary passed **47 tests**: 30 core contract
tests and 17 graph tests. Six Python settings-schema groups and 11 schema
examples passed. The new parser cases cover both inclusive bounds, absent
settings, optional phases, production-only targets, malformed types, overflow,
unknown fields, JVM rejection and document identity/round-trip preservation.
Graph cases cover built-in and Kotest tests, every phase's exact budget,
unchanged JVM actions and unchanged resolver requests. Explicit 180,000 ms
values produce exactly the same actions as omission and as the native
adapter's default recipe. A link-only override changes the link key while
leaving compilation, generation and test keys unchanged when producer bytes
are held constant. Compile-only overrides affect compile keys; downstream
content identities still depend on actual producer output bytes.

The retained receipt is `.kvik/tests/project-graph/timeout-receipt.json`;
`compile-command.json` contains the exact command, and `compile-inputs.json`
records all 27 Kotlin input hashes, rechecked after compilation. Binary
SHA-256: `fade21c08951625072e0c694def161cf223d0cbc415d7508b61d35b9d01a464b`.
The test log `/private/tmp/kvik-native-timeouts-tests.log` has SHA-256
`af2720f8be869e20b831d4e766fe590bd36c46e287bb09dad35e8a25e1c17d19`.
The source receipt is
`e26ca7d65c05d0232bdc13c691c7e11a09277d0eb92fb8544b4b5cc4222b3f51`.

The public harness now declares a 600,000 ms link budget for both Native
targets and asserts exact budgets for all five phases. Preparation preserved
the 61 source hashes and 1,880 golden test identities. No full consumer compile
or retry was run in this slice; a successful full cold/warm public gate remains
required before claiming all Native fixture tests pass through the CLI.

Independent integration review rehashed all 27 recorded Kotlin inputs, the
compiler command, compiler jar, binary and original log; rerunning the retained
binary passed all 47 tests. The generated schema matched its generator, and
the six schema groups and 11 examples passed again. Review evidence is
`/private/tmp/kvik-native-timeouts-review-pkhz4wbn/report.json`.
The separate timeout diagnostic change also passed independent review and
all nine Native unit groups; see `k08-native-timeout-diagnostic.md`.

Both changes are integrated in `e0c3c32`. The composed CLI built successfully
with composition `2bd5e75a4c6bb1f76c595545edf2f974a7bee9d25895c38f5ecb7242361e4620`
(10,421 canonical bytes), with a recorded compile duration of 50,053 ms.
Executable SHA-256 is
`8ee0020cba81683e2c8ec45c2fc42048072d37e493ca95f7af9b5882671d63dc`
(4,761,048 bytes). Integration rechecked the canonical runtime lock,
executable, guardian and complete worker bundle, and `runtime-info` reported
the Kotlin/Native backend. This proves the integrated runtime builds; the
full public consumer gate is tracked separately in `k08-kotaml-native-cli.md`.
