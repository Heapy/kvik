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

Native execution is pending the coordinated compiler slot. No Native pass is
claimed here yet. Python-only checks currently pass: six new script integration
schema groups and all 27 existing contract schema groups. The requested-check
schema distinguishes `script:NAME:jvm:prepare` from `script:NAME:jvm:run`; run
evidence semantics and uncached execution belong to the root-owned follow-up.
