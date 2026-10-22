# Native compile visibility and source-free linking

Native source compilation and executable linking use separate input closures.
Main compilations remain `.klib` producers. Test compilation recompiles its own
main sources with its test sources, preserving common/actual declarations and
internal main access, and produces `tests.klib` with only compile-visible
module and external dependencies. Serialization plugins and the validated
Kotest launcher/runtime are applied during this compilation.

A new `module:macosArm64:test-link` node consumes that klib through `-Xinclude`
and the independently locked runtime closure. It has no source files and no
source compiler plugins. Private and runtime-only dependencies therefore enter
the linker without becoming source-visible. Runtime selections replace API
selections; the graph does not union both versions or invent compatibility.
Source-free links set the canonical `-Xpartial-linkage-loglevel=error` policy:
used missing/incompatible symbols must fail during linking. This is separate
from `-Werror`, which applies
in both phases. Tests still use `NEVER` caching.

The additive `NativeCompilation.includedLibrary` parameter is omitted when
null, preserving existing direct compilation JSON and action recipes. It must
be a declared `.klib`, distinct from the regular libraries, and requires a
source-free program/test link. Source-only compiler options are refused for
this form. The existing `kvik-native-compiler-v1` implementation dispatch is
retained. Library compilation may declare `kotestLauncher` to prepare the
pinned runtime; linking the resulting klib supplies the Kotest entrypoint.

Canonical inclusion uses a logical `input/` path. After exact typed action
validation and private materialization, the executor binds only that argument
to its absolute private input path: the pinned compiler fails to match relative
`-Xinclude` against its resolved library paths. This execution path is not part
of the portable action key.

No `-repo` path or implicit dependency search root is introduced. Every
non-toolchain library passed to either phase is an explicit digest-bound input.
The compiler/toolchain identities retain their existing offline validation.

The source reference is pinned Kotlin `f63b9f344c0a7e5d9d261480d1f81e5a4dad2981`:
`compiler/cli/cli-native-klib/src/main/kotlin/org/jetbrains/kotlin/native/pipeline/NativeConfigurationPhase.kt`
records included libraries separately and rejects inclusion while producing a
library. The installed compiler's argument metadata describes `-Xinclude` as
an intermediate library processed like source files. Reference source is
context; actual pinned 2.4.20 compiler execution is the decisive check below.
`CommonKlibBasedCompilerArguments.kt` marks the old partial-linkage disable
switch deprecated (the engine is always enabled) and defines the supported
`error` log level. Existing source-based direct compilation keeps its prior
policy; the stricter level applies only to the new source-free form.

## Validation

The focused adapter harness uses the real Kotlin/Native 2.4.20 compiler and
K03-owned processes. It reuses the explicitly supplied, stable tool layouts
and identities from the retained lifecycle request; this is a compiler
semantics proof, not a new installed-tool inventory or public CLI proof.

```sh
python3 tools/test_kotest_native.py --stage bootstrap \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --developer-directory /Applications/Xcode.app/Contents/Developer \
  --sdk /Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk
python3 tools/test_native_visibility.py \
  --tool-request /private/tmp/kvik-k08-kotest-native/.kvik/tests/kotest-native/lifecycle-ob02kohf/compile-first-request.json \
  --kotest-request /private/tmp/kvik-k08-kotest-native/.kvik/tests/kotest-native/lifecycle-ob02kohf/compile-first-request.json
python3 tools/test_project_graph.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
```

Retained worktree: `/private/tmp/kvik-k08-native-visibility`. Final semantic
requests, action recipes, keys, process outputs, results and CAS are under
`.kvik/tests/kotest-native/visibility-34lp2gm3`. The harness checks every
published output digest and refuses changed retained requests when `--resume`
is supplied. Bootstrap passed all 17 Native adapter/KSP unit tests. The semantic
harness passed all 18 retained requests, including the expected negative
results, with two exact builtin IDs and five exact Kotest IDs (four executed,
one skipped). All 16 Native graph groups passed, including API/runtime domain
isolation, private/exported module chains, runtime-only dependencies, malformed
prerequisite rejection, source option inheritance, resources and plugin
separation. The harness recipe now includes K16's pure `ResolutionGraph.kt`
source; its earlier missing-source frontend error was a test closure issue.
The latter reuses the unchanged lifecycle source and official
KSP launcher from its declared input manifest, builds a klib with the pinned
runtime adapter, then links and executes its entrypoint without recompiling
sources. This is a focused lifecycle fixture, not the full 1,880-test corpus.

The source fixtures prove private API behavior by compiling `bridge` against
`hidden`, then compiling a consumer and its tests against `bridge` alone.
Direct `hidden` and runtime-only symbol references fail compilation. The
consumer's common/actual declarations and internal main declarations remain
usable by its two builtin tests. A runtime-only eager initializer executes at
both builtin discovery and test execution without exposing its API during
source compilation. Changing private-library bytes changes the link key and
makes the assertion fail; a missing used private symbol fails linking and
publishes no output. Warm linking uses a local hit; tests always execute.

The initial retained `visibility-o6n3t217` run exposed partial linkage: omission
of `hidden` still produced an executable with default compiler policy. The
independent reviewer executed that binary and observed `IrLinkageError` in the
private-dependency test. Manifests contained logical dependency names, and
this was not evidence of ambient dependency lookup. The final strict request
`tests-link-missing.json` fails with exit 1 and identifies `hidden/privateValue`
and partial-linkage errors. The original evidence is retained rather than
relabelled as a successful gate.

## Relocation limitation

Forced cold linking in two private working directories has the same canonical
action key and both executables pass the same tests. **The output bytes differ.**
Mach-O executable data embeds the private output directory; DWARF contains
producer source paths and executable paths; relocation YAML has an absolute
`binary-path`. `summary.json.relocation` retains both output manifests and
physical working directories, so each unequal executable/debug digest and
size can be inspected. `path-scan.json`, reproducibly emitted by the same
harness, records the embedded path strings and complete content digests for
each output. For example, original/relocated `tests.kexe` digests are
`1cf86ed8b6ff28845bf7553ca1f68be4417c51488449042801e0a0b7f1c1ba32` /
`fa3f53d36e6b270e46d7339f2444cc164eb7969e4556e636eab9cbf239d2933a`;
both are 2,024,616 bytes. `Info.plist` is identical, while the executable,
DWARF and relocation YAML differ. The earlier direct Kotest executable also contains its
private output directory. This slice neither normalizes binaries nor claims
that Native outputs are reproducible across workspaces. Native compilation
and linking remain `LOCAL`; K10 path normalization/determinism is outstanding.

This change does not by itself establish full unchanged kotaml public CLI
parity or resolve the separate upstream Kotest Native metadata edge-policy
issue. General compatibility of arbitrary compiler plugins, Native platforms,
or different runtime-library ABI versions is not claimed. The plain `PROGRAM`
form shares the typed include path, but this evidence executes builtin TEST
and Kotest entrypoint forms; it has no separate standalone user `main()` gate.
