# K08 public Native kotaml CLI gate (prepared, execution pending)

`tools/test_kotaml_native_cli.py` prepares a private copy of exactly 61 unchanged
common source/test files at fixture commit
`a5c94c77b220cfc7b6335668766e8f26930968c3`. It validates them against the pinned
source inventory and compares all 1,880 expected Native leaf identities with
`tests/native/kotaml-test-identities.json` (1,558 kotaml, 322 kotaml-json).
The original fixture and its source configuration are checked again on exit.
No source assertions, exports, or dependency scopes are rewritten.

The public configuration retains exported serialization dependencies, private
SnakeYAML/Okio dependencies, the exported kotaml module dependency, and the
runtime-only coroutines/io version declarations. Native compiler module names
and main-only explicit API mode follow the source configuration. Separate KSP
runtime and symbol-processor domains explicitly pin host stdlib 2.3.20; Native
stdlib remains 2.4.20. Version pins do not add dependency roots or exports.

## Intended gate

Resolution uses the production CLI, metadata resolver and CAS, then checks
exact Native runtime/link artifact digests against retained KTC classpaths,
API subsets, and exact independent KSP/processor artifact closures. Offline
check-lock and resolve must replay the same lock. The action cache begins empty.
The full test gate uses the production installed-tool providers and executors.
It does not invoke the fixture adapter probe or precompiled reference binaries.

The pending API/link split has five actions per module: main, KSP generation,
test klib compilation, source-free test link, and test execution. The harness
requires all ten cold actions to execute; the eight reusable warm actions must
hit local cache, while both test actions execute again. It verifies the full
source membership, canonical action keys, exact verified tool identities, raw
KSP launcher preservation, API-only compilation inputs and runtime link inputs.
Every action input/output blob is independently rehashed from CAS. Warm reusable
outputs must exactly match cold outputs; tests are checked by exact identities,
not callback ordering or aggregate totals alone.

Both runs must retain complete independent typed Kotest journals with distinct
request tokens, one engine invocation and completion per module, 1,880 passed
leaves, current verification evidence, schema-valid contiguous event journals,
and unchanged lock/runtime bytes. The existing journal mutation audit challenges
corrupt/deleted/reordered/truncated history without modifying the original run.

## Preparation and current blocker

Schema/source/identity preparation and Python syntax checks passed in
`.kvik/tests/kotaml-native-cli/case-klnlxrt2`. The explicit-policy preparation also passed in `case-msni3tmy`, preserving all 61 source hashes and 1,880 identities. No compiler was invoked.

A real public `--resolve-only` attempt against runtime
`0dcc6a76fd24a957a064a4bfb823205e17f7b188a592353141c1f9850195d678`
failed in `.kvik/tests/kotaml-native-cli/case-kmzh6glc` with
`POM has no matching Kotlin Native variant`. The selected
`io.kotest:kotest-common-macosarm64:6.2.5` Native metadata declares
`org.jetbrains.kotlin:kotlin-reflect:2.2.21`, which publishes a JVM POM/jar.
The exact parent metadata digest is
`fec23c54a9a7fae891d8697b34e3a17ed79cc3e71fe59e5ee40ae290e78bbe2e`,
4,708 bytes. No implicit jar filtering or scope promotion was added.

Pinned Kotlin Toolchain source explains why its Native compiler input list
omits the jar: `tasks/native/nativeTaskUtil.kt` filters jar/aar with an upstream
FIXME, while dependency resolution reports unsupported non-JVM POM libraries.
This is not evidence that the Native distribution implements full JVM reflection.
The published Kotest non-JVM sources use basic reflection interfaces and explicitly
return unsupported/default results for full reflection operations. A narrowly
scoped, digest-bound explicit metadata correction policy is now declared by the harness for the four module test API/runtime domains. Each rule pins those exact metadata bytes, the `macosArm64ApiElements-published` variant, and the raw `kotlin-reflect:2.2.21` edge before version pins. It is persisted with an explicit reason in `metadata-exclusions.json`, passed as `--exclude-metadata-edge`, and required in the lock and reproduction arguments. This policy deliberately differs from KTC's blanket JAR filtering; unchanged fixture sources do not imply unchanged dependency metadata policy. Its separate production service candidate passed the combined Native resolver/locking/query checks; integration into the public runtime is pending. The faithful Native API/link graph split is also
pending. Consequently this document makes no public Native execution success
claim yet.

## Reproduction

Preparation needs only the pinned fixture and schema Python:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_kotaml_native_cli.py \
  --fixture /Users/yoda/dev/pet/kvik/.kvik/fixtures/kotaml --prepare-only
```

Once the reviewed resolver/graph/CLI composition is available, run:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_kotaml_native_cli.py \
  --fixture /Users/yoda/dev/pet/kvik/.kvik/fixtures/kotaml \
  --runtime /absolute/path/to/composed/kvik.kexe \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --konan-data /Users/yoda/.konan \
  --developer-directory /Applications/Xcode.app/Contents/Developer \
  --compiler-artifacts /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts
```

`--resolve-only` performs metadata/artifact resolution and offline replay without
compiling or executing consumers. Full execution is explicitly coordinated with
the shared compiler queue. Tool roots must remain stable during verification/use;
this is a local macosArm64 gate and does not establish remote eligibility,
cross-machine portability, or general Native target support.
