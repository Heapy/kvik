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
exact API artifact sets, and exact independent KSP/processor artifact closures.
The API expectations are 4/19 klibs for kotaml main/test and 2/17 for
kotaml-json main/test. Runtime sets remain 4/19 and 5/20 respectively.
JSON's exported root-module edge exposes serialization-core but not the root's
private SnakeYAML, Okio or transitive urlencoder. Tests inherit that same main
visibility. Coroutines and io still appear in the test API closure because the
published Kotest engine API declares them independently; their presence does
not promote this project's runtime-only overrides into compile roots.
Artifact byte sets are compared exactly; baseline classpath order is not claimed. Offline
check-lock and resolve must replay the same lock. The action cache begins empty.
The full test gate uses the production installed-tool providers and executors.
It does not invoke the fixture adapter probe or precompiled reference binaries.

The reviewed API/link split has five actions per module: main, KSP generation,
test klib compilation, source-free test link, and test execution. The harness
requires all ten cold actions to execute; the eight reusable warm actions must
hit local cache, while both test actions execute again. It verifies the full
source membership, canonical action keys, exact verified tool identities, raw
KSP launcher preservation, API-only compilation inputs and runtime link inputs.
Every action input/output blob is independently rehashed from CAS. Every
module klib input is matched to the exact producer output digest and logical
namespace, including own-main KSP inputs, JSON's root-module prerequisite,
test-klib inclusion and executable/debug outputs consumed by the test action.
Extra stale module inputs are rejected. Passed library/plugin paths must match
the audited declared inputs, and source-free link recipes must retain strict
partial-linkage errors. Warm reusable
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
scoped, digest-bound explicit metadata correction policy is declared for the
four module test API/runtime domains. Review of the pinned primary metadata
found the same raw `kotlin-reflect:2.2.21` edge in three producers, so there are
12 explicit rules (not a blanket reflection filter):

| Producer (`io.kotest`, version 6.2.5) | Metadata SHA-256 | Bytes |
| --- | --- | --- |
| kotest-common-macosarm64 | `fec23c54a9a7fae891d8697b34e3a17ed79cc3e71fe59e5ee40ae290e78bbe2e` | 4708 |
| kotest-framework-engine-macosarm64 | `9b378db5439180e9a0de9b5d4237e16601f732e08b13a3ba62e00bf13a211559` | 6476 |
| kotest-assertions-core-macosarm64 | `6baae8acf2bbebca67ce040b3d826bc3b2c7509d82c80c4a1c019e9022a7d348` | 5081 |

All three rules name `macosArm64ApiElements-published` and the raw edge before
pins. The corresponding Maven Central `.module` URLs are retained in
`metadata-correction-evidence.json`, alongside rehashed CAS content identities,
selected variant attributes and the original dependency requirement. Every
rule's producer metadata must be present in the same domain's lock selections.
Rules and reasons are persisted in `metadata-exclusions.json`, passed as
`--exclude-metadata-edge`, and checked exactly in both lock and reproduction.
This deliberately differs from KTC's blanket JAR filtering: unchanged fixture
sources do not imply unchanged dependency metadata policy.

Light validation of the stronger harness passed 35 controls/mutations,
including missing API bytes, private API leaks, changed/stale module outputs,
all 12 exact producer metadata rules, and a mismatched raw requirement.
Retained script `/private/tmp/kvik-native-cli-harness-audit.py` was run with
`/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python`; its report is
`/var/folders/yh/h124ks9x1vqd0v6sz6slshkc0000gn/T/kvik-native-cli-audit-_2twlw1r/report.json`.
Preparation `case-295emnl9` again verified all 61 source hashes and 1,880 IDs.
No compiler ran for this review.

The production metadata policy and API/link split are integrated, but a new
public resolve-only attempt (`case-pkxxb5hr`, runtime `47bd832...`) reached an
unsupported `version: {prefers: "2.2.0"}` stdlib dependency in atomicfu 0.32.1
metadata. That needs a separate bounded rich-version resolver implementation,
not another exclusion. Full public Native execution remains pending; this
document makes no public execution success claim.


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
