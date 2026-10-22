# K14 source-graph input lock v1 (proposal)

Status: approved source-graph contract. The first production slice implements strict parsing and read-only local observation in `bootstrap/source_graph.py`; acquisition, snapshotting and launcher integration remain pending. The example is `examples/k14-source-graph.lock.json`.

## Goal and trust boundary

Build the complete Kvik Native runtime from a pinned remote Kvik source package, selected remote build plugins, their transitive source dependencies, and an explicit complete Native artifact closure. Keep the existing local `bootstrap/composition.json` behavior when `--source-lock` is absent. This is not a consumer-project dependency resolver and must work before any Kvik executable exists.

The caller selects a trusted, declarative input lock. Acquiring sources never executes package Python, shell, Kotlin, Git hooks, checkout filters, or package build scripts. Remote `bootstrap/*.py`, `kvik`, package manifests and recipes are data only: the invoking launcher's versioned bootstrap implementation remains authoritative. Compiling/running the selected Native composition is the later, explicit execution boundary.

The source input lock is distinct from generated `runtime.lock.json`. Ordinary launch neither rewrites the input lock nor chooses versions. Pin updates are explicit maintenance operations. Local development nodes are the deliberate exception to immutable source bytes: their actual current inventories enter the observed/generated lock.

## Input document

Top-level required fields (unknown fields and duplicate JSON keys fail):

- `schemaVersion: 1`, `implementation: "kvik-source-graph-v1"`, `pluginApiVersion: 1`.
- `target: "macos_arm64"` and `kotlinVersion: "2.4.20"`: compatibility requirements against the invoking launcher's trusted tool lock, not tool-download instructions.
- `sources`: source nodes sorted by unique `id`.
- `runtime`: the selected Kvik source node and bounded support recipe.
- `plugins`: ordered static registry entries. Order is meaningful and retained.
- `nativeArtifacts`: immutable Native KLIB nodes sorted by unique `id`.
- `providedLibraries`: explicit bindings to verified compiler-distribution libraries.
- `nativeLibraryOrder`: each reachable external Native artifact ID exactly once, in the order passed to the compiler.
- `compilerOptions`: the existing bounded `-target macos_arm64`, `-entry kvik.cli.main`, optional `-opt`/`-g` vocabulary. File-bearing arbitrary flags remain forbidden.

A source node has exactly `id`, `pin`, `contentPaths`, `kotlinRoots`, `dependencies`, `nativeLibraries`. Content-path, Kotlin-root and dependency lists are duplicate-free and lexically sorted. Non-runtime plugin source nodes explicitly depend on the runtime source node for its BuildPlugin API; the generated registry is a separate composition input, not a reverse source dependency. The selected roots are `runtime.source` and every `plugins[].source`; all source nodes must be reachable. Reject cycles with their path, missing IDs, unused nodes, duplicate logical files, and overlapping Kotlin roots. A dependency is a source package compiled into this one Native composition; it does not introduce a JVM runtime or imply separately enforced Kotlin module visibility.

`contentPaths` is a required nonempty selection of package-relative files or directories, independent of `kotlinRoots`. Paths use normalized forward-slash components; absolute paths, empty components, `.`/`..`, wildcards, negations and normalization/case aliases are forbidden. Selections must not overlap: selecting a directory and any descendant is an error. A selected directory contributes its entire descendant file inventory; a selected file contributes exactly that file. Every selection must exist with the required supported type. Preserve original package-relative paths; selection never strips, flattens or relocates files. Empty selected directories contribute no file records but their existence is checked. The canonical input/observed graph binds the complete selection, even when two selections happen to yield identical file inventories.

The source package is this explicit selection, not the entire repository or transport archive. `kotlinRoots` selects compilation roots within that package; it does not define which support or resource bytes are acquired. Each Kotlin root must be a directory covered by an equal or ancestor `contentPaths` directory. Apply the same whole-directory coverage rule to `runtime.nativePlatform`, `runtime.jvmWorkerSources`, `core/src`, and every enabled fixed native recipe's source directory (`resolution/native`, `integration/toolchains/platform`). Reject incomplete support selection before compilation; a list of individual files cannot substitute for a required whole root. Check lexical coverage during declaration validation and actual existence/type during local observation or remote provisioning.

`pin` is one of:

- Git: `{kind:"git", origin:<HTTPS repository URL>, revision:<full 40/64 lowercase hex object ID>, tree:<Digest>}`. Verify the requested commit object, whose identity binds the whole repository snapshot, and the selected content tree; never resolve a branch/tag as a pin. Read selected blobs/modes from Git objects without running a working-tree checkout. A selected Gitlink, or one below a selected directory, is unsupported and fails; an unselected Gitlink is not materialized or acquired. A dependency stored in a submodule must instead be a separately pinned source node with its own logical namespace. No implicit submodule/LFS acquisition occurs. Selection may not descend through a Gitlink.
- Archive: `{kind:"archive", origin:<HTTPS URL>, archiveDigest:<Digest>, format:"tar.gz"|"zip", rootPrefix:<normalized relative directory or empty string>, tree:<Digest>}`. Verify exact whole-archive transport bytes; apply the explicit package `rootPrefix`, then `contentPaths`, then verify the selected content inventory. No inferred stripping of a top-level directory. Valid unselected package payloads may remain in the transport and are never materialized. Entries outside a nonempty prefix may only be directory ancestors. Scan archive headers globally for traversal, duplicate/colliding entries and unsupported link/special types before publishing; selection does not make an unsafe archive acceptable. Bound header count and total expanded bytes even for unselected entries to avoid unbounded scanning/decompression.
- Local: `{kind:"local", origin:<"." or normalized relative directory below the input-lock directory>, tree:null}`. The origin `.` explicitly denotes the input-lock directory, allowing a repository-root lock to select that repository. Resolve containment and hash fresh bytes under the explicit selections on every observation; do not inventory unselected siblings. Reject symlink ancestors between the local package root and selected paths. No remote pin is silently replaced by a local path. Reject a selection that includes the input lock itself (directly or through a selected ancestor directory), preventing self-hash cycles. This exclusion compares normalized case-folded paths and checks opened file identity, so case-insensitive filesystem aliases cannot include the lock under another spelling. Apply this read-only validation even when no cache is opened. A pinned local tree can instead be represented as an archive/Git fixture. Physical paths never substitute for a tree identity.

`Digest` retains existing `{hash:<64 lowercase hex>, sizeBytes:<nonnegative integer>}` semantics. Tree size is canonical inventory byte length, not the sum of file sizes. Tree identity uses existing `bootstrap.identity.inventory` canonical regular-file records: original package-relative path, SHA-256/size, executable bit and sorted paths. `pin.tree` binds exactly the selected regular-file inventory; Git `revision` and archive `archiveDigest` separately bind the whole transport snapshot. Local observations use the identical selected-inventory format. `.git` components are forbidden selections or descendants: transport state is never package content. Remote snapshots consist of all selected package content, not only `.kt` files. Symlinks, hardlinks, devices/FIFOs, traversal, file/directory collisions and Unicode-normalization/case-fold collisions are rejected before publication. Local nodes follow the same selected-source restrictions. Unselected local `.git`, `.kvik`, caches and reference checkouts neither enter the inventory nor require traversal. The example Kvik selection covers its complete runtime/worker/native support inputs while leaving `references/gradle`, `references/kotlin` and `references/kotlin-toolchain` unselected; their Gitlinks therefore do not block provisioning the actual Kvik repository.

`runtime` has exactly `source`, `nativePlatform`, `nativeLibraries`, `jvmWorkerSources`. These paths are relative to its source node. `nativePlatform` must expose the existing four-file process/guardian recipe. `nativeLibraries` retains the current fixed `resolution` and `toolchains` recipe vocabulary and source locations. `jvmWorkerSources` is `compiler/jvm/src`; the runtime node must include `core/src`. These restrictions preserve current guardian, interop and worker contracts. Arbitrary package-defined commands/native recipes are not admitted by v1.

Proposal check against Kvik commit `a5f2643`: the example selects 22 directories, exactly the current composition's Native Kotlin/plugin roots plus JVM worker and three native support roots. `git ls-tree -r` finds 85 selected regular files and no selected Gitlinks; all three `references/*` Gitlinks are outside the selection. This is a declarative coverage check, not a provisioning or compilation result.

Registry entries have exactly `id`, `source`, `implementation`. The implementation is a bounded qualified Kotlin class name; `id` is unique and must match the instantiated BuildPlugin's id/API version. Its source node must select the implementation through `kotlinRoots`. The Kvik package can also supply the builtin `kvik.kotlin` plugin without duplicating its sources in another node.

## Native artifact closure

Each artifact has exactly `id`, `coordinate`, `variant`, `url`, `digest`, `metadata`, `uniqueName`, `dependencies`, `providedDependencies`.

- `url` is an exact HTTPS `.klib` resource and `digest` identifies its complete bytes. No JAR/AAR substitution, directory search or arbitrary host library path.
- `metadata` is a nonempty list of at most 32 exact `{url,digest}` immutable metadata provenance records. The trusted input lock records the selected closure; bootstrap does not perform version selection, Maven conflict resolution or execute a second build to produce it. Full published metadata-resolution policy remains the responsibility of the explicit lock-generation/review operation.
- `uniqueName` is the decoded KLIB manifest `unique_name`. `dependencies` names external artifact IDs; `providedDependencies` names entries in `providedLibraries`. Both lists are sorted, duplicate-free and exact.
- After byte verification, read the bounded KLIB manifest and require NATIVE platform, requested native target, matching unique name, and an exact match between its decoded `depends` names and declared external/provided references. Reject duplicate unique names, cycles, missing dependencies and unreachable extra artifacts. This proves the locked compiler-input closure; it does not retroactively prove Maven version mediation.
- Compiler/ABI compatibility is pinned to the requested compiler and validated by the real compile/link gate. A producer compiler version need not equal the consumer compiler version. Do not fabricate an ABI-compatibility guarantee from the version strings.

V1 `providedLibraries` initially permits only `{id:"stdlib", toolchain:"kotlinNative", path:"klib/common/stdlib", uniqueName:"stdlib"}`. The complete installed Native distribution is verified through its existing tool lock; check that manifest's unique name and target. This is the actual Native stdlib, not a claim that JVM libraries such as kotlin-reflect are provided. Existing runtime platform/interop recipes continue to bind their platform libraries through the complete trusted Native/SDK inventories.

Every `sources[].nativeLibraries` entry is a root artifact ID. `nativeArtifacts` must equal the transitive closure of all these roots; `nativeLibraryOrder` must contain that exact set once. The entire order, source-to-library edges, metadata identities and provided bindings enter composition identity. No implicit transitive acquisition or ambient KLIB cache lookup is allowed.

The example uses real retained serialization-json/core macosArm64 1.11.0 KLIB and `.module` pins. Their decoded manifests contain `json -> core + stdlib` and `core -> stdlib`, producer compiler 2.3.20 and ABI 2.3.0. That is evidence for the example's declared dependency names, not a completed Kvik composition compatibility test. Example source URLs/revisions/tree hashes are explicitly illustrative and are not provisionable.

## Declared identity versus verified provisioned bytes

`observe_graph` validates the complete input document and computes:

1. `inputLock`: SHA-256/size of canonical parsed input (whitespace alone is immaterial).
2. `observedGraph`: canonical full graph plus fresh local tree digests; remote nodes retain their declared immutable tree pins.
3. `sourceGraph`: SHA-256/size of that canonical observed graph.

All source files have logical names `packages/<source-id>/<package-relative-path>`; cache paths, checkout roots and staging directories are excluded from portable identity. Source order is deterministic dependency-first with lexical tie breaks. The registry and Native library order remain explicit arrays. Existing fixed worker/interop recipes receive a source-root mapping; their logical paths are resolved relative to `runtime.source`, not guessed from arbitrary package directories. The worker exclusion in Native compilation must use an explicit role, not the old physical/logical prefix heuristic.

Observation does **not** fetch, create caches, write bytecode, verify a cached executable or claim remote bytes are present. Thus `--print-composition` can report the desired declared remote composition while offline with an empty source cache, and still freshly observes local development sources. A missing/unreadable local node is an explicit query error. The desired key additionally binds the invoking bootstrap recipe, complete tool/host identities, JVM worker artifact lock and effective native settings exactly as today.

Provisioning returns verified physical roots only after every remote transport/tree and Native artifact/metadata blob has passed its declared identities. Before compiler/plugin code execution, copy selected source/support bytes into private staging while checking their observed identities. Re-read the input lock and re-observe local source trees before atomic runtime publication; changed inputs discard that attempt. Verified immutable remote cache entries must be revalidated on use; paths alone are not proof. Per-key producers publish only complete manifests/trees, repair corrupt entries under coordination, and never expose partial files.

The generated runtime lock retains all existing source/tool/recipe/environment/worker bindings and adds a versioned source-graph field containing `inputLock`, `sourceGraph`, the full observed graph and exact Native artifact order. Root owns deciding the additive runtime-lock schema/version migration; simply hashing an opaque input-lock path is insufficient. No runtime-lock schema change is made by this proposal.

## Python boundary API

The parser/observation boundary is implemented with frozen dataclasses backed by canonical bytes; exposed document, digest, inventory and local-root maps are defensive copies. Source ordering chooses the lexical minimum currently ready node at each dependency-first step. The provisioner/snapshot boundary remains proposed for the next slice:

```python
# bootstrap/source_graph.py: pure strict parsing and declared/local observation
parse_source_lock(data: bytes) -> SourceGraph
observe_graph(graph: SourceGraph, lock_directory: Path, *,
              lock_path: Path) -> ObservedGraph
# SourceGraph: document_bytes, source_order, document (defensive parsed copy).
# ObservedGraph: graph, input_lock_digest, graph_digest, observed_document,
#               local_roots, local_inventories; no acquired remote paths.

# bootstrap/source_store.py: explicit effects, no package code execution
provision_graph(observed: ObservedGraph, cache: Path, offline: bool,
                owned_runner: OwnedRunner) -> VerifiedGraph
# VerifiedGraph: observed identity, verified source roots/manifests,
#                verified artifact and metadata files; never unchecked paths.

snapshot_graph(verified: VerifiedGraph, destination: Path) -> CompositionInputs
# CompositionInputs: native Kotlin files, JVM worker files, native support files,
#                    ordered verified KLIBs, registry; each has role + logical path + digest.

revalidate_observation(observed: ObservedGraph, lock_bytes: bytes,
                       lock_directory: Path, *, lock_path: Path) -> None
# Fail if current canonical lock/local tree observations differ before publication.
```

`lock_path` is required to identify self-inclusion; it names a nonsymlink file directly in `lock_directory` (the file may be absent when observing an already parsed declaration). All local origins and selected ancestors are opened through directory handles without following symlinks. Observation hashes file content in 1 MiB chunks and checks descriptor size/mtime/ctime after reading; it does not provide a transaction over concurrent edits. Callers must retain stable local roots through snapshotting and re-observe before publication. Inventory records are retained in memory, bounded by 100,000 selected files across the graph, 100,000 visited entries per node, the path/depth limits and 16 MiB per canonical manifest. Each source has at least one Kotlin root in this initial version. Physical root maps are caller-local handles and never enter `observed_document`.

`OwnedRunner` is injected by the trusted launcher, with explicit argv/environment, deadline, bounded diagnostics and process-group cleanup; source modules cannot construct detached subprocesses. Archive HTTPS acquisition uses streaming digest/size verification and a whole-transfer deadline. Git transport uses the explicitly selected trusted host Git executable (never package content or an inherited PATH search), with its resolved path/version recorded in acquisition evidence, through an owned, sanitized process with system/global/repository overrides and hooks/filters disabled, explicit HTTPS protocol policy and no interactive credentials. Git's deadline and post-fetch object/tree publication limits must be distinguished from a hard network-byte quota: stock Git does not make the latter promise. An implementation must document and test this boundary rather than claim a nonexistent strict transfer cap. Verified commit/tree bytes remain authoritative regardless of acquisition-tool version; this does not claim a newly verified full Git installation. No remote execution service/daemon is involved.

Proposed initial limits: input lock 1 MiB; 64 source nodes/256 edges; 128 Native artifacts; 4 KiB URLs; 1,024-byte logical paths; depth 64; 100,000 files; source file 64 MiB; selected source node 512 MiB; complete selected source graph 1 GiB; archive entry count 100,000 and total expanded bytes 512 MiB including unselected payload; archive transport 256 MiB; KLIB 64 MiB; Native artifact and metadata bytes aggregate 256 MiB; individual metadata 1 MiB; tree manifest 16 MiB. All limits are checked without silently truncating the graph. Git fetch has a 120-second whole-process deadline and rejects oversized fetched content before publication. Actual fixture inventories must confirm the limits before any completion claim.

## Launcher integration and ownership

Root owns additive `--source-lock <path>` and `--source-cache <path>`, launcher/core/schema integration and generated runtime-lock compatibility. With no source lock, preserve the local default. A source cache without a source lock is rejected to avoid an ineffective option. Resolve physical lock/cache paths once; capture both as absolute arguments in the bounded foreground launch descriptor along with existing offline/no-build/tool options. Preserve canonical source-lock policy on restart; never replace it with a fetched package's launcher or Python module.

`--print-composition` stays read-only and never provisions. `--no-build` never acquires sources to rescue a missing runtime: a verified compatible executable may be reused from the declared key, otherwise fail. Ordinary allowed builds provision the complete graph; `--offline` requires all needed remote bytes already verified in cache. Failure has no stale-composition fallback. Source cache entries must be physically disjoint from selected local content, the input lock and private build output; reject either direction of ancestor overlap before writing. An unselected cache directory beneath a local package root is permitted, so an explicit selection can coexist with `.kvik/source-cache`. No traversal through symlinks is allowed to evade this check. Selection never authorizes build outputs to modify observed inputs.

After contract approval, cache-agent ownership is `bootstrap/source_graph.py`, `bootstrap/source_store.py`, focused tests/fixtures and their evidence. Root owns `bootstrap/launcher.py`, composition/default migration, shared schemas/contracts and foreground descriptor consumers. Coordinate the public API before either side edits production. No PLAN or unrelated source changes are part of this slice.

## Acceptance gates

- Real full Native Kvik bootstrap from remote pinned Kvik sources plus two source plugins sharing a transitive helper and the complete real serialization-json/core Native closure. Prove helper/library-dependent behavior through the composed executable, retain discovered schemas, and run an existing production consumer build/test smoke. A download-only fixture is insufficient.
- Cold acquisition/build, relocated checkout/cache, offline cached startup with absent compilers, and offline rebuild from verified provisioned inputs. Preserve no-daemon ownership for Git, tools and compiler workers.
- Independently mutate runtime/plugin/helper content (including same size/mtime), graph edges, registry order, Native artifact bytes/order, provided binding, recipe and tools. Consumer-only edits preserve runtime reuse.
- Prove the real Kvik commit with its three unselected reference Gitlinks provisions its complete selected runtime inputs; selecting or descending through a Gitlink fails without submodule acquisition. Check identical Git/archive/local selections preserve package-relative paths; missing support-root coverage, overlapping selections and selected unsafe entries fail. Changes to selected bytes or selection policy invalidate identity; unselected local reference/cache changes do not.
- Reject incomplete/cyclic/unreachable graphs, wrong IDs/trees/commits, wrong-target KLIBs, undeclared KLIB dependencies, conflicting unique names, corrupt metadata, archive traversal/collisions/special entries and excess bounds. Test interrupted transfers/producers, concurrent same-key launchers and corrupt-cache repair without overwriting valid publication.
- Prove queries create no files or acquisition processes; descriptor replay preserves source/cache/offline/no-build options; source-lock/local-source changes during build cannot publish stale results. Failed compilation cannot launch an older composition.
- Keep action-dispatch integration, arbitrary new C interop recipes, remote executable reuse and foreground recomposition completion as separate explicit gates. This source-distribution slice does not claim them merely because the static registry loads.

## Parser/observation slice verification

Reproduce with `python3 -B tests/bootstrap/test_source_graph.py` (24 tests). These cover the approved example, malformed fields/JSON/pins, source and artifact closure, library order and bounds, local file and directory selections, native recipe coverage, read-only missing-remote observation, actual selected Kvik inputs, relocation, unsafe local entries, and publication revalidation. Same-size edits with restored mtimes change the observed key; mtime-only edits and unselected local state do not. A deterministic mutation during hashing is rejected. The actual Kvik observation is a source inventory check; no remote transport, KLIB manifest verification, runtime compilation or full K14 acceptance is claimed by this slice.

The generated shared schema exposes `sourceGraphInput` and `sourceGraphObserved` in `schemas/v1/contracts.json`. Input local pins require `tree: null`; observed local pins require the measured digest. Remote pins retain the same immutable shape in both. Schema validation checks fields, pin alternatives, counts, digest bounds and basic path/URL syntax. The production parser remains mandatory for graph closure, ordering, exact support coverage, normalized path aliases, UTF-8 byte/aggregate limits and compiler option pairing. Raw JSON duplicate keys and decimal numeric tokens are parser errors; JSON Schema alone cannot enforce those token rules. Passing either validation layer does not prove acquisition or byte verification. The legacy generated `runtimeLock` schema and its identity vectors are unchanged in this slice.

Run `.kvik/schema-tools/bin/python -B tests/contracts/test_source_graph_schema.py` for the eight structural-schema groups, including actual local observations, malformed declarations, explicit semantic-validation gaps and legacy compatibility. HTTPS scheme matching is case-insensitive while preserving the original URL bytes in identity; fragments, including an empty trailing `#`, are rejected. The parser/observation commits passed independent review after the case-alias correction: 24 producer tests and 12 separate boundary probes, retained at `/private/tmp/kvik-source-observation-audit-m7iseo5c/report.json`. Integration reran all 24 tests and seven existing identity checks.
