# K12 script dependency and input integration

This slice adds typed script consumers to the existing resolver, lock replay,
project input snapshots and project-dependency queries. It does not execute a
script or infer an entrypoint. The root-owned graph/CLI work is a separate gate.

`ResolutionConsumer.Module(name)` retains the original module domain JSON.
`ResolutionConsumer.Script(name)` emits `script` in place of `module`, fixes the
target/compilation to `jvm`/`script`, and supports ordinary dependency and compiler
plugin domains. Equal module/script names are distinct policy and lock identities.
The lock remains version 2; domain parsing requires exactly one consumer field.
Script pins use `script:NAME:jvm:script:USAGE:PURPOSE=COORDINATE`. Exact metadata
corrections use the same typed domain and existing producer digest rules.

`effectiveConfiguration` accepts a separate `scriptScopes` selection. Empty
script scopes are omitted from its document, preserving legacy no-script bytes.
The standalone overload retains the original companion under `standaloneScript`
and its parser branch. It never publishes a synthetic module or rewrites the
companion into a project document. Expansion origins stay relative to the original
project/companion; project queries expose those origins for script declarations.

`scriptDependencyClosure` preserves direct compile visibility and exported
transitive edges; runtime includes private/runtime edges. Producer compilation
prerequisites are computed separately by `scriptPrerequisiteModules`, including
workspace compiler plugins and their private compilation/runtime prerequisites.
Those prerequisites do not broaden the script's compile classpath.

`SnapshotSelection.Project` is the legacy default; `Script(name)` and
`StandaloneScript` must be retained in optional run metadata `inputSelection`.
`ProjectInputPlan` distinguishes directory roots, required plugin input files and
script file-or-directory roots. A selected script captures its declared inputs,
expanded profile plugin inputs and prerequisite module JVM main source sets. It
excludes unrelated modules, their tests and other scripts' source bytes. Exact
configuration/lock bytes still participate, so any configuration change is stale.
The default project capture includes all declared script inputs. Missing script
roots are inventoried so appearance is detected; source discovery/compilation is
responsible for refusing absent executable source inputs.

Snapshot recomputation performs two full selected observations without CAS reads
or writes. Existing physical-path, size/depth, alias and generated `.kvik`
exclusions remain. Trusted, stable ancestors are required; this is not an atomic
filesystem snapshot and does not exclude ABA edits or edits after observation.

## Verification

The focused runner is `tools/test_script_resolution.py`. It compiles Native
core/resolver/locking/query/snapshot code plus CLI scope-pin/store preflight code,
14 new script integration tests and the original 10 snapshot tests. All resolver
fixtures are generated in memory, with no network or consumer compiler. Native
HTTP/XML adapters are deliberately outside this focused compilation. Build reuse
requires matching source, compiler/support, command/environment, harness, binary
and compile-log receipts; full installed SDK/platform inventories remain a
caller-stability assumption.

Frozen production/test source `606ef54` + `b17147a` passed all 24 Native tests
(139 ms test time) on 2026-10-22; compiler/test handle `84450` exited 0. The
complete output names all eight resolver/query tests, six script snapshot tests
and ten original snapshot tests. Four emitted lock documents and three project
query documents passed their generated JSON schemas. The no-script configuration
also matches its fixed legacy canonical encoding (1,569 bytes,
`3c8fbf17aca94e929e0ebde4f4470b8bf82800e5381e9d2c57b114b20f233389`).

Retained case:
`/private/tmp/kvik-k12-script-resolution/.kvik/tests/script-resolution/case-99dyc1ui`.
The receipt binds 28 Kotlin inputs, five compiler/launcher-support inputs and 19
Python harness/ownership inputs. All tracked source/harness bytes were checked
against committed `b17147a` after the run. No source changed during compilation.
The log contains two existing `StoreInputs.kt` interop warnings, no errors.

| Artifact | SHA-256 |
| --- | --- |
| `summary.json` | `93506ea1d2637abd2be2b4216926441dd767d6535e229eac72c91eb294031231` |
| `build.json` | `1abb01554a351b1dfbe258d63f87f5dc1035d891e7d6bbc4cd4c9a5139605fa6` |
| `unit.kexe` (3,651,704 bytes) | `cece484fe386f7af8da1d48a6b2bd613c90f86abe5dd96576b92cd309eca66e4` |
| `compile.log` | `69cc673944c2b0d36fc04bfd8b950225ce8bb5100a8da7d836adb927df341bf8` |
| `tests.stdout` | `0e3eb2e1dc87a4ac490b605515334994f096b1e817dd66646aa569da4343066e` |

Reproduction from the isolated worktree (defaults select provisioned Kotlin
Native 2.4.20 and JDK 25.0.4-librca):

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_script_resolution.py
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_script_resolution.py --no-build
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tests/script-resolution/test_schema.py
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B -m unittest discover -s tests/contracts -p 'test_*schema.py'
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B -m unittest discover -s tests/package-queries -p 'test_*schema.py'
```

The build command above ran once; the listed cached replay command is available
for independent verification, not claimed as an additional run here. Python
checks passed six new integration groups, all 27 contract schema groups and five
package-query schema groups. The requested-check schema distinguishes
`script:NAME:jvm:prepare` from `script:NAME:jvm:run`; run-evidence semantics and
uncached execution belong to the root-owned follow-up. `Verification.kt`'s
optional selection plumbing is source-reviewed here; the complete composed CLI
query and real script execution still need their separate integration gate.
