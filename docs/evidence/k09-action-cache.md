# K09: local action-cache executor integration

Date: 2026-10-22. `kvik.actioncache.LocalCachingActionExecutor(delegate, cache, content,
diagnostic)` wraps the existing `ActionExecutor`, `ActionCache`, and `ContentStore` contracts.
Use `NativeActionCache` and `NativeContentStore` for the production local implementation.
The optional diagnostic callback receives the existing structured `CacheDiagnostic` code/message.

## Behavior and ownership

`resources` forwards the delegate's resource request unchanged and performs no lookup. A cache
hit consequently retains conservative executor resource scheduling. `start` snapshots eligible
actions, hashes the complete resolved action using SHA-256, and looks up that key. Only COMPILE,
GENERATE, LINK and PACKAGE are eligible. NEVER and all effect kinds bypass the cache entirely.
REMOTE policy permits local reuse here; there is no remote client, compiler portability approval
or remote execution in this wrapper.

A usable cache record must have the requested key and a valid manifest. Every artifact must be
equal to or beneath a declared output root, and every declared root must contain an artifact.
Overlapping output roots are rejected because the contract cannot distinguish ownership of the
same file between them. Empty artifact sets are supported only for actions declaring no roots;
empty directories are not artifacts. Every returned blob is fetched and checked independently
for SHA-256 **and** size, even if the supplied store/cache claims it already verified the content.
Executable bits and logical paths are preserved. A hit returns `LOCAL_HIT`, exit zero, the current
action ID and the current key, with detached output-list snapshots. It never returns an old run ID.

The wrapper returns CAS manifests; it does not restore mutable action directories. Downstream
executors/materialization use the existing `NativeOutputTrees`, which rejects actual-filesystem
case/normalization aliases and exposes new destinations atomically. Directory alias behavior is
not simulated by string folding in this generic wrapper. Capture and cache storage remain the
existing adapter/store responsibilities.

On a miss, the wrapper starts the delegate exactly once. Polling remains cooperative. Only a
matching `EXECUTED`/exit-zero result with exact, available outputs can publish; failed, blocked,
cancelled, cached delegate results and NEVER actions cannot publish. Delegate-provided successful
cache statuses still receive output verification but are not republished. `NativeActionCache`
provides durable blobs-before-metadata ordering and atomic immutable publication.

Cancellation before a terminal result is observed sets the wrapper's cancellation flag before
forwarding `cancel`. Even a delegate returning success after cancellation, or throwing from
cancel, cannot publish. Repeated cancellation is idempotent. `close` forwards cleanup exactly
once; it never skips owned-process cleanup on a successful result, publication failure or poll
exception. Callers must still close handles after poll exceptions. A cached hit cancelled before
its first poll returns CANCELLED. Cancellation after an already observed terminal result does
not retroactively invalidate that completed action/cache record.

## Misses versus failures

- Missing/corrupt metadata or blobs at lookup, wrong cache key, incomplete/undeclared cached
  outputs, and cache read exceptions are diagnosed misses; the delegate executes normally.
- A newly executed result with wrong action identity, incomplete/undeclared outputs, or missing/
  corrupt output blobs becomes FAILED with no output artifacts and no cache publication.
- Ordinary cache publication failure emits `local-cache-publish-failed` and retains EXECUTED
  only after re-verifying that output blobs are still usable. Losing those blobs becomes FAILED.
- A valid existing action record with different outputs is a correctness conflict, not an outage.
  The local store now throws the explicit `CacheConflictFailure`; the wrapper returns FAILED and
  emits `local-cache-conflict`. The original record is preserved. This detects undeclared inputs
  or nondeterministic output when recomputation reaches an existing key; it does not rerun valid
  hits to search for nondeterminism.

Other wrapper diagnostic codes are `local-cache-miss`, `local-cache-result-invalid` and
`local-cache-output-invalid`. Configure the underlying local stores' diagnostic callbacks too,
since their ordinary misses return null with their own detailed diagnostics. Diagnostic callbacks
are synchronous observational hooks: they must not reenter the wrapper or mutate its dependencies.
Exceptions from callbacks are not silently discarded.

## Integration boundaries and limits

This is a single-thread-confined decorator for the cooperative scheduler. It does not add threads,
processes or background work. SHA verification uses the current ByteArray content contract and
therefore inherits the Native store's default 64 MiB per-blob limit. Blobs are handled one at a time;
there is no aggregate transfer/time budget. Large lookup/publication work can delay scheduler
polls. Action/cache/content roots must retain their existing ownership and stable-path guarantees.

A hit intentionally bypasses `delegate.start`. The coordinator must construct trusted typed actions
and establish source/tool freshness before cache reuse; adapter-specific validation and tool
provider rehashes that occur only inside delegate.start do not run on a hit. The wrapper cannot
infer those checks from the generic ActionSpec. The full action key includes runtime composition,
tool identities, source/dependency artifacts, ordered arguments and declared configuration, but
identity correctness still depends on the producing coordinator. No CLI wiring changes are part
of this slice.

## Reproduction and evidence

```sh
python3 tools/test_action_cache.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
```

The script compiles the integration and tests to Kotlin/Native, using a fake executor with
observable poll/cancel/close counts and real Native filesystem CAS/action-cache instances. It
also composes the actual `SchedulerRun` for dependency-key evidence. Java is used only by the
compiler toolchain while building this Native test binary, not by the cache runtime.

Eight scenario groups cover repeat hits/current IDs, resource forwarding, detached mutable
lists and action snapshots, executable-bit restoration, mutable destination independence,
real missing/corrupt CAS recovery, malformed injected cache results and lying content bytes,
failed/cancelled/blocked and partial outputs, cancellation exceptions and cleanup, every NEVER
effect, local-only handling of REMOTE policy, publication outage, blobs lost during publication,
real same-key output conflict preserving old metadata, and prerequisite-output key changes.
The scheduler fixture executes a requested test on every run, including when both compilation
nodes hit cache. This establishes executor/cache integration behavior, not a full real compiler
suite or remote-cache portability result.
