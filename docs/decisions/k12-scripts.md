# K12: named JVM scripts and standalone companions

Status: model/schema candidate. This slice does not enable script resolution, compilation, execution,
inspection or CLI commands. It must be integrated with consumers that reject unsupported scripts or
implement them explicitly; parsing a script must never cause an older consumer to silently ignore it.

## Authoring contract

Scripts are ordinary Kotlin `.kt` programs with JVM main methods. No `.kts` engine, executable
configuration, implicit classpath inheritance or automatic resolver update is introduced. The
project document retains schemaVersion 1 and gains optional `dependencyCatalog`, `dependencyProfiles`
and `scripts` objects. Existing omitted fields and empty reserved objects remain valid and produce
the same `ProjectSpec` values as existing three-argument constructors.

`dependencyCatalog` maps identifiers to exact immutable Maven coordinates. References occur in
script dependencies as exactly one of `coordinate`, `catalog` or `module`, with optional `scope`
(`compile`, the default, or `runtime`). Script dependencies cannot be exported. Catalog references
are exact and case-sensitive; no prefix matching, version inference or alternate alias spelling.
The catalog is script-only in this first contract; module source-set dependency syntax is unchanged.

`dependencyProfiles` maps identifiers to objects containing optional `dependencies`, ordered
`compilerOptions` and ordered `compilerPlugins`. Profiles are script-only, nonrecursive and cannot
inherit other profiles, source roots or environment. Earlier schemas described profile arrays, but
the Native parser rejected every nonempty profile map. Those unimplemented arrays are explicitly
replaced by objects; there is no array shorthand. Empty profile objects are valid.

Each named `ScriptSpec` has required nonempty `sources`, and optional `resources`, `profiles`,
`dependencies`, `compilerOptions`, `compilerPlugins`, `mainClass`, `timeoutMillis` and `environment`.
All paths are logical relative paths under the project root. A source declaration may identify a
regular `.kt` file or a directory of `.kt` files; resources may be regular files or directories.
Filesystem snapshotting must reject unsupported file kinds, symlinks, non-`.kt` explicit source
files, normalization aliases and input/output overlaps. The model rejects lexical traversal,
control characters, `.kts` source declarations, duplicate/ancestor roots and case aliases within
the source/resource declarations. Source and resource roots cannot overlap. It does not claim
filesystem existence, NFC identity, source discovery or entrypoint discovery without observing files.

Profile declarations expand in the exact order of the script's `profiles` list, followed by the
script's direct declarations. No implicit profile applies. Repeated profiles, exact duplicate
external coordinate-and-scope references (including aliases that expand to that exact reference),
repeated workspace modules even across scopes, duplicate plugin producers or plugin IDs fail with
diagnostics; there is no last-wins override. Distinct external versions remain ordered requests with
origins, so the resolver's existing conflict/pin policy can accept an explicit pin or diagnose an
unresolved conflict. Compile/runtime external references remain distinct scopes. Compiler options
retain order. The existing JVM target/release parser diagnoses repeated/conflicting target flags
after expansion; compiler-adapter validation remains responsible for its complete supported option
set. Unused profiles are validated too, so they cannot hide broken catalog/module references.

Workspace module references and workspace compiler plugin producers must name an existing JVM
target. Expansion retains direct references and never flattens a module's private dependencies.
Later graph construction must use the ordinary module compile/export/runtime closure and build
only required prerequisites. A script and module may have the same name; downstream domains and
action IDs must preserve distinct typed consumers rather than use colliding synthetic modules.

Script plugin declarations require exactly `targets:["jvm"]` and `scopes:["script"]`. Module-level
plugin declarations reject `script` scope. Published exact coordinates preserve the existing
published-plugin representation. Workspace producers retain their explicit plugin ID, pinned
compiler version `2.4.20`, ordered literal/path options, declared inputs and bound output roots.
Cross-plugin output overlaps and overlaps with declared script/plugin inputs fail. Plugin and
application runtime classpaths stay separate; the model does not expand the current worker's
supported number/combinations of plugins. Unsupported combinations must be diagnosed by consumers.

Limits: 256 scripts/profiles, 1024 catalog entries, identifier length 128 UTF-16 units; per script
256 source roots, 256 resource roots, 32 profiles, 256 expanded dependencies/options and 16 expanded
plugins. Paths are at most 1024 UTF-16 units. A script/profile canonical declaration is at most
256 KiB UTF-8. Environment has at most 128 keys, key length 128 and value length 4096; NUL and
launcher/JVM/loader control variables are rejected. Script metadata does not contain runtime argv.
These are bounded initial APIs, not a claim that every maximum combination fits the byte limit.

## Standalone files and durable locks

For `/work/update-index.kt`, default companion is `/work/update-index.kvik.json`; durable lock is
`/work/update-index.kvik.lock.json`. The companion has schemaVersion 1, `name`, `repositories`,
optional catalog/profiles, and exactly one `script` object. All paths are relative to its directory.
The standalone schema entry is `schemas/v1/script.json`, referencing the shared definitions.

The selected `.kt` path must be an explicit entry in `script.sources`, not merely some file under
a declared directory. `StandaloneScriptConfiguration.requirePrimarySource(LogicalPath)` checks
this lexical binding. The CLI must also verify that the selected path is the actual regular `.kt`
input under the companion root before resolving/building/executing. This prevents selecting X.kt
while accidentally running an unrelated declaration Y.kt. Source directories remain available
for helpers. Main class is optional; the selected primary file does not restrict where a unique
compiled main may reside among the explicitly declared helper sources.

Standalone companions cannot reference workspace modules or workspace compiler plugins, even in
unused profiles. Use a named project script for those dependencies. No nearby project discovery
or executable companion is allowed. Resolution is explicit and writes a durable lock using the
same resolver, conflict policy and artifact store. A locked invocation must reject stale/missing
lock metadata or missing offline content, not silently resolve another graph. Lock domain/schema
integration is a downstream slice; no fabricated standalone lock is included in this model commit.

## Pure model API

`ProjectSpec` adds default-empty fields:

```kotlin
dependencyCatalog: Map<String, Coordinate>
dependencyProfiles: Map<String, ScriptDependencyProfile>
scripts: Map<String, ScriptSpec>
```

`ScriptDependencyReference` has `External`, `Catalog` and `Module` variants, each with a
`DependencyScopeKind`. `ScriptDependencyProfile` and `ScriptSpec` expose `toJson`/`fromJson`.
`parseProjectConfiguration` validates every declared profile/script and retains the original
document. `parseStandaloneScriptConfiguration(JsonValue)` returns a
`StandaloneScriptConfiguration` retaining the original document and canonical digest.

```kotlin
fun expandScript(project: ProjectSpec, name: String): ExpandedScript
fun StandaloneScriptConfiguration.expanded(): ExpandedScript // member
fun StandaloneScriptConfiguration.requirePrimarySource(source: LogicalPath) // member
```

`ExpandedScript` retains `name`, `declaration: ScriptSpec`, and three ordered lists:
`dependencies: List<ScriptValue<Dependency>>`, `compilerOptions: List<ScriptValue<String>>`,
`compilerPlugins: List<ScriptValue<CompilerPluginSpec>>`. Each `ScriptValue` carries an origin
with a declaration JSON pointer, optional profile-selection pointer and optional catalog pointer.
For example an alias expanded from profile `maintenance` retains
`/dependencyProfiles/maintenance/dependencies/0`, `/scripts/update-index/profiles/0` and
`/dependencyCatalog/json`. Standalone selection pointers start at `/script` instead. Origins carry
no host paths; inspection can pair them with the retained configuration file. Map iteration order
does not alter expansion; explicit lists do. As with existing core models, callers must not mutate
collection backing storage while consuming these values; filesystem boundaries must snapshot
canonical configuration bytes rather than trust mutable caller objects.

## Compilation and execution contracts for downstream slices

Compilation is JVM `COMPILE/LOCAL`, never a SCRIPT cache hit. Its key includes only the controlled
compile recipe, all selected source/helper/resource bytes, ordered compile dependencies, verified
compiler/tool identities, plugin classpaths/options and declared plugin inputs. The snapshot and
run evidence bind the full configuration, but its whole digest must not be added to compilation
merely to carry invocation settings. Changed runtime argv, environment or timeout must not cause
an otherwise identical compile miss. Module outputs and compiler plugin producer outputs reuse
the ordinary action cache. No remote compiler eligibility is claimed.

If `mainClass` is absent, inspect only freshly compiled script class-file metadata for exactly one
public static `main(String[]):void`; Kotlin no-arg main's generated bridge qualifies. Never load
classes or execute program initialization during discovery. Zero/multiple candidates are explicit
diagnostics. An explicit class must be validated against the compiled script outputs. The separate
entrypoint helper and graph/adapter integration are not part of this model slice.

Execution is a fresh `SCRIPT/NEVER` action consuming the compiled script and exact runtime closure.
It runs every invocation, including compilation hits. Argv after `--` is passed literally. Cwd is
project root or standalone companion directory; stdin is EOF. Environment starts from a controlled
launcher base plus explicit declaration values, never arbitrary inherited JVM injection variables.
Stdout/stderr are separate retained program streams, distinct from structured CLI event stdout.
Timeout is finite, default 60 seconds, configurable from 1 to 3,600,000 milliseconds. Owned
cancellation and complete descendant cleanup use the existing process host before terminal proof.

`run-script` propagates an observed ordinary child exit status 0..255 exactly where the host supports
it. Failures before child execution use existing CLI statuses: configuration/tool selection 2,
compilation/build failure 1, incomplete evidence/freshness 3. Cancellation is 130; deadline expiry is
124. Full child termination (`exitCode`, `cancelled`, `timedOut`) and failure phase are retained
separately, so an application choosing e.g. 2 or 124 is distinguishable in evidence from a launcher
failure or timeout. Successful compilation alone never completes a script execution check.

Planned CLI: `run-script NAME --project PATH -- ARGS`, `run-script --file X.kt -- ARGS`, standalone
`resolve-script --file X.kt` / `check-script-lock --file X.kt --offline`, and `inspect-script` with
either selector. Project resolve/check-lock include typed script domains; inspection exposes source
ownership, reference/profile provenance, artifacts/digests, plugin/runtime classpath separation and
only required prerequisite actions. Exact command wiring remains a downstream reviewed change.

## Verification boundary

Independent integration review verified the tested `b5b8e01` source against the
retained Native receipt: 20 source files, 19 harness/support files, five compiler
launch inputs, the binary and raw logs, with all 50 exact Native test identities
including the 15 script tests. The 27 Python schema tests also passed independently.
Report: `/private/tmp/kvik-script-model-review-hlci3885/report.json`, SHA-256
`d4596a74383c4b51d31b659a0c209c7d21f8f6a06fa32b8a7b79c4a8acec9f23`.
This was a read-only Native receipt audit, not a compiler rerun or complete
installed-toolchain inventory. No script runtime capability follows from this
model/schema approval.

This slice supplies focused Native model tests and Python structural schema tests. The fixtures in
`tests/scripts/fixtures` are declarations, not claims of successful compilation or downloaded locks.
Python tests cover old empty fields, new shapes, strict string endings, bounds, context-specific
plugins and standalone entry schema. Native tests cover ordered expansion/provenance, duplicate
references/plugin conflicts, prerequisite targets, serialization, no implicit module flattening,
standalone selected-file binding and invalid declarations.

Verified source freeze: `1e33107` plus portable test correction `b5b8e01`. The existing contract
harness compiled all core sources and ran all **50 Native tests**, including **15 script model
tests**, successfully on 2026-10-22. Session `53218` reached terminal status 0; total harness time
27.082 seconds, test-runner time 46 ms. No source changes occurred during compilation/execution.
The compiler log is empty. Retained evidence is
`/private/tmp/kvik-k12-script-model/.kvik/tests/contracts/case-qp_r8rzb/`:

- `receipt.json` SHA-256 `5054e33a68281679dc9d3364881a4137ef8b1572cea5337554d1a5c06b4ae45c`.
- `contracts.kexe` SHA-256 `50b932ab73178785093afd6e79559a3e54d8c3f867d8af110ba9d26fddaf4eff`, 3,094,912 bytes.
- `tests.log` SHA-256 `5f10ff056c068d702dca7813336291fa409cc5906407ee2a80b8d5390c6061cb`, 10,976 bytes.

The receipt binds 20 Kotlin source files, 19 harness/bootstrap files, five compiler launch inputs,
the exact command/environment and resulting binary/log hashes. This is the existing focused Native
contract harness, not a full installation-inventory or composed-runtime acceptance gate. Reproduce:

```sh
cd /private/tmp/kvik-k12-script-model
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_contracts.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B -m unittest discover \
  -s tests/contracts -p 'test*schema.py'
```

All **27 Python schema tests** passed (six new script groups). Running
`python3 -B tools/generate_schemas.py` reproduced both contracts.json and script.json byte-for-byte;
`git diff --check` passed. These model/schema results do not imply successful script runtime effects.

Remaining K12 acceptance: production lock/replay/snapshot/graph/inspection/CLI integration;
standalone offline repeat; project catalog/profile/module and both plugin forms; serialization
effect proof; cached compilation with effects on every invocation and changed argv; independent
source/helper/dependency/tool/plugin/options/input invalidation; failure/timeout/cancellation,
concurrent worktree isolation and complete structured evidence/stream/process cleanup.
