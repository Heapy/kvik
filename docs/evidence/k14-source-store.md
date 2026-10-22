# K14 selected source store and private snapshots

This slice implements provisioning and snapshot APIs for the approved [source-graph contract](../decisions/k14-source-graph.md). It does not complete the composed-runtime acceptance gate. No launcher, shared schema, core or PLAN edits are part of this slice.

## Production boundary

`bootstrap/source_store.py` exports frozen dataclasses. Paths are invocation-local handles; they never enter the portable observed graph. Digest and inventory properties return defensive parsed copies of immutable canonical bytes.

- `VerifiedFile(path: Path, logical_path: str, digest_bytes: bytes, executable: bool = False)`, with `.digest`.
- `VerifiedSource(id: str, root: Path, inventory_bytes: bytes)`, with `.inventory`.
- `RegistryEntry(id: str, source: str, implementation: str)`.
- `VerifiedNativeDistribution(root: Path, identity_bytes: bytes)`, with `.identity`. The trusted launcher constructs this only after freshly verifying the complete Native distribution against its trusted tool lock. Construction alone does not authenticate an installation.
- `VerifiedProvidedLibrary(id: str, root: Path, distribution: VerifiedNativeDistribution, inventory_bytes: bytes)`, with `.inventory` and `.digest`.
- `VerifiedGraph(observed, sources, artifacts, metadata, provided, acquisition_evidence_bytes, _lock_path, _cache_root)`. Collections are tuples; `.acquisition_evidence` returns a defensive parsed copy. The two private physical paths support overlap checks only.
- `CompositionInput(file: VerifiedFile, roles: frozenset[str])`.
- `CompositionInputs(root, observed, files, native_libraries, registry, runtime_source_root, provided)`, with `.native_kotlin_files`, `.jvm_worker_files` and `.native_support_files`.

The call signatures are:

```python
provision_graph(observed, cache, offline, owned_runner, *, lock_path,
                native_distribution=None, git_executable=None, opener=None)
snapshot_graph(verified, destination)
```

`lock_path` must be the same actual path captured for observation and later revalidation. Provisioning re-reads the input lock and freshly verifies local inventories before creating cache entries. `native_distribution` is required whenever provided libraries are declared. V1 permits only the verified distribution's stdlib; its complete inventory, manifest platform, target, unique name and dependencies are checked again here. Stable tool roots through execution remain the caller's existing toolchain boundary.

`git_executable` is an explicit absolute trusted executable, supplied as `/usr/bin/git` by the launcher. `owned_runner` consumes the separately implemented `OwnedProcessRequest` and returns `OwnedProcessResult`; it owns all Git subprocess groups. `opener` exists for focused transport injection in tests. Production callers omit it: the downloader uses HTTPS with system TLS verification, rejects redirects and ambient proxies, verifies complete SHA-256/size, and applies a 120-second whole-transfer deadline to headers, body and output flushing. Cancellation and failures never yield a verified graph.

Input roles are `source-data`, `native-kotlin`, `jvm-worker`, `native-support`, `native-library` and `metadata`. All selected package bytes are snapshotted under `packages/<source-id>/<original-relative-path>`. Runtime core Kotlin files have both Native and worker roles; worker-root Kotlin files are explicitly excluded from Native compilation. The snapshot also contains exact ordered KLIBs under `libraries/<artifact-id>.klib` and metadata by digest. The stdlib stays in the verified compiler distribution; it is rehashed before snapshot creation and returned through `provided`.

Snapshotting hashes every copied file and verifies executable flags. It stages the complete result, fsyncs files/directories, and exposes it with an atomic no-replace rename. Even an unrelated empty destination directory is preserved. `runtime_source_root` supplies the existing fixed worker/interop recipes with their original package-relative paths. The caller still re-reads/re-observes the input lock and local sources immediately before publishing the compiled executable.

## Acquisition, cache and limits

The helpers are separated by boundary:

- `source_files.py`: bounded file verification/copying, selected inventories, physical/case-fold overlap checks, fsync and no-replace publication.
- `source_archives.py`: ZIP/TAR headers, selected extraction and global unsafe-entry rejection. No `extract`/`extractall` operation writes archive-selected paths. TAR extended headers are individually bounded; links, sparse/special files, malformed headers and trailing non-padding payload fail. Valid unselected payload inside the declared root prefix is permitted; unsafe entries anywhere fail.
- `source_git.py`: sanitized owned Git fetch and binary object reads, with no checkout, hooks, filters or implicit submodule/LFS acquisition. The full commit is verified by its Git object hash; selected blobs also match their Git object hashes and the final selected SHA-256 inventory pin. Selected Gitlinks fail; unselected reference Gitlinks are not acquired. Pathspecs are batched by a 64 KiB byte budget, and object batches fit the runner's bounded binary stdout.
- `source_klib.py`: bounded ZIP manifest reading, Properties escape decoding, exact Native target/unique-name/dependency checks. Artifact bytes and metadata are independently pinned. This verifies the supplied complete closure; it does not perform Maven mediation or infer compiler ABI compatibility.

Every cache read hashes bytes. Coordinated same-key producers preserve an already valid publication; corrupt entries are moved into quarantine under the same lock before publishing a complete replacement. Verified archive transports can reconstruct damaged selected trees offline. This version does not retain bare Git repositories, so a missing/corrupt Git tree needs online re-acquisition. Interrupted staging and quarantined entries are never reused; cache garbage collection is not implemented.

Historical Git process receipts are intentionally not persisted/replayed from mutable cache entries. Cold receipts describe acquisition in the current invocation. Warm receipts are reconstructed from freshly verified source pins, selections and trees and explicitly say `verified-cache`. Cache contents cannot manufacture an asserted past Git invocation; unexpected metadata files invalidate that tree entry. A retained cold invocation receipt is separate evidence, not a trusted portable cache input.

Cache and snapshot roots must be physically disjoint, in both ancestor directions, from the input lock and selected local content; snapshot/cache writes also cannot overlap a verified tool distribution. NFC/case-fold aliases and existing physical identities are checked. An unselected `.kvik/source-cache` under a local package root remains supported. Cache/output ancestors are caller-controlled and must stay stable through use. No transaction over concurrently edited source directories is claimed; verification during copying and caller revalidation prevent stale publication under this boundary.

The parser's graph/file/manifest limits still apply. Git adds a 1 GiB **post-fetch disk** bound and a 120-second fetch deadline; this is not a hard network-byte quota. Git batches can retain up to approximately 64 MiB of raw output plus bounded parsing/hash copies. Archive transports stream to disk; selected files are capped at 64 MiB, archive expansion at 512 MiB, and streaming copies/hash operations use at most 1 MiB chunks. Canonical inventories and ZIP metadata remain bounded in-memory structures. The process runner's ownership exclusions (escaping process groups, credential-changing children and uninterruptible kernel children) remain explicit.

## Reproduction and evidence

```sh
python3 -B tools/test_source_store.py --public
```

The harness runs the parser, store, real owned-Git fixture and concurrent-producer suites, then performs actual public HTTPS/Git and Native artifact checks. Its receipt includes source hashes, exact commands, Python version, identities and timings. It performs no Native/JVM compiler invocation.

The public transport fixture is [octocat/Hello-World at the full pinned commit](https://github.com/octocat/Hello-World/tree/7fd1a60b01f91b314f59955a4e4d4e80d8edf11d). Git and the separately pinned codeload TAR produce the same selected `README` inventory. The fixture pin was established explicitly for this test; provisioning never resolves a branch/tag. The archive transport digest is `39a4b97b9d108782fa7466b07160a2c9227a7da07725ad4110c41be6014e4160`, 261 bytes. No test-only transport substitution is used for this public check.

Separate deterministic Git tests use an explicitly trusted fixture executable that translates a test HTTPS URL to a local repository. They exercise actual owned Git commit/tree/blob operations, binary bytes, executable flags, selected/unselected Gitlinks, bounded request batching, offline/corrupt behavior and post-fetch quotas. That transport substitution is a fixture limitation, not a public remote-acquisition claim.

The real closure check downloads the example's pinned serialization-json/core 1.11.0 macosArm64 KLIBs and both `.module` files from Maven Central, verifies their actual manifests and exact dependency names, and checks offline replay. Before supplying stdlib it freshly matches the complete installed Kotlin/Native component against the trusted tool lock. This check does not replace the launcher's full host/six-component validation.

The same run copies the complete 85-file Kvik selection, provisions it as local input, and verifies a private snapshot with 73 Native Kotlin, 14 JVM-worker and 8 native-support role assignments (roles overlap for core files). Real separate processes prove cold-writer coordination, concurrent corruption repair without replacing a newly valid inode, and recovery after SIGKILL during a blocked producer. Source/tool corruption, transport mismatch, deadline/interruption, private snapshot mutation and no-overwrite failures have focused regressions.

Retained successful public evidence before final freeze: `.kvik/tests/source-store/case-541guv_9/summary.json` (43.248 seconds; full Native component hashing 14.772 seconds). That run had 24 parser, 19 store, 4 Git and 3 process test groups. Final matching-source run `.kvik/tests/source-store/case-rzkf8dpt/summary.json` passes all 24 parser, 21 store, 4 Git and 3 process groups plus the public gate in 42.214 seconds (Native component hashing 15.430 seconds). It additionally covers binary/executable Git files, artifact mutation before snapshot, and blocking opener/body deadlines. The first exploratory public run reached receipt serialization but failed because timing values were floats; the harness now records integer milliseconds, and no success receipt is claimed for that attempt.

## Remaining full K14 gates

The parser/store and source-process APIs are necessary components, not completion of K14. Still required: reviewed launcher/schema integration; a complete compiled Native Kvik runtime from pinned remote Kvik plus two source plugins sharing a transitive source helper and the real Native library closure; observable plugin/helper/library behavior; existing production consumer build/test smoke; invocation descriptor/restart propagation and executable reuse/freshness checks. The small public repository proves transport correctness only. The full 85-file snapshot proves input coverage only. Neither is presented as the complete remote runtime gate or a two-machine portability result.
