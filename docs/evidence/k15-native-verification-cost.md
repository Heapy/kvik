# Native verification cost before foreground reuse

The public application gate demonstrates correct execution and cache reuse, but
Native replay is not yet fast. The retained continuation from
[the application gate](k15-application-cli.md) reports:

| Operation | JVM seconds | macosArm64 seconds |
| --- | ---: | ---: |
| Prepared replay after settings change | 1.533 | 26.161 |
| Program returning exit code 7 | 1.275 | 27.874 |
| Current verification query | 0.476 | 24.056 |

These are individual acceptance observations, not benchmark distributions.
The source path and an earlier inventory measurement identify repeated installed
tool verification as the principal explanation. No new profiling was performed.

## Retained inventory and call path

`/private/tmp/kvik-k09-toolchains/.kvik/tests/toolchains/installed-result.json`
has SHA-256 `2ddfc7e86b638ae13786b9cc5cce39fbe4f847d9084b0b81e799c25b317ae740`
(2,489 bytes). Its six component digests were independently compared with the
current `bootstrap/toolchains.lock.json` and match exactly. The earlier measurement
reports 26,805 ms and 20,414,464 maximum RSS bytes. Summing its component inventories
gives **6,130,936,949 file bytes, 92,249 regular files and 8,225 symlinks**.
An inventory digest's `sizeBytes` measures canonical metadata, not these file bytes.

`checkRun` in `cli/src/kvik/cli/Verification.kt` brackets `BuildTools.verify` with
two current-fingerprint queries. Each fingerprint query observes project inputs
twice. The Native branch of `BuildTools.verify` calls
`InstalledNativeToolchainProvider.verified`, which calls
`InstalledToolchainVerifier.verify`. That verifier deliberately rereads all six
installations on every call. The Native application bundle in the acceptance case
contains only 3,546,152 bytes across eight files.

Build preflight verifies the tools, and `NativeCompilerExecutor.start` verifies
them again for each uncached action. For the acceptance fixture, the call graph
therefore implies:

| Path | Full tool inventories | File bytes read by those inventories |
| --- | ---: | ---: |
| Cold three-module preparation, including PROGRAM link | 5 | 30,654,684,745 |
| Warm preparation followed by immediate execution | 2 | 12,261,873,898 |
| Detached prepared replay or current query | 1 | 6,130,936,949 |

These counts are derived from the source path and fixture action statuses. They
are not newly measured I/O counters and exclude other source, CAS and runtime
reads. Per-component timing and byte observations should precede a performance
claim or a choice between traversal and hashing optimizations.

## Reuse boundary to implement

Reusable planning state and current verification authority have different
lifetimes. Parsed configuration, effective resolver policy, lock and typed graph
can be reused under their exact content identities. Source observations, run
journals, cancellation and application/test execution remain invocation-local.

An internal verified build generation could retain verified tool layouts and
identities, physical roots, tool-document bytes, composition and Native data
directory policy. Reusing that authority requires an explicit immutable snapshot
or a coordinated stable-root lease covering the whole execution generation.
External mutable installations retain fresh verification until that ownership
contract is implemented and tested. A path, TTL, mtime or persisted digest alone
does not establish current contents. Detached replay must not deserialize an old
verification as fresh authority.

The foreground controller should own the verifier/build child and remain
responsive while it runs. Cancellation, source changes or composition changes
invalidate pending generations; late results cannot activate an application.
Graph reuse can be implemented independently of tool-authority reuse.

Acceptance must include same-size/restored-mtime tool edits, root replacement,
Native dependency-marker changes, source changes during verification, cancellation
during large reads, stale-generation results and independent-owner isolation.
For an explicitly protected generation, prove the intended inventory count while
preserving uncached application/test execution. This document records investigation
and required boundaries; no tool-verification optimization is implemented here.
