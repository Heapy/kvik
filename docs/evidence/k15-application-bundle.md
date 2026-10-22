# Pure CAS application bundles

This slice implements `applicationBundleAction` and
`ApplicationBundleExecutor` in `kvik.application`. It prepares a portable bundle;
it never launches an application, reads a host path or invokes a process.
The caller supplies the resolved runtime closure. Graph integration is
responsible for selecting runtime modules/resources/libraries and excluding
unrelated compiler/source/plugin inputs; this executor includes exactly the
provided inputs and discovers nothing else.

The builder detaches and revalidates `ApplicationLaunch`, requires every ordered
JVM classpath entry or Native executable to exist in the declared input tree,
and creates a `kvik-application-bundle-v1` PACKAGE/LOCAL action. Its platform is
`{format:application-bundle-v1}`, target is the launch target, output root is
`application`, and timeout is 120 seconds. Application arguments/environment
live only in typed metadata; action process arguments/environment are empty.
All supplied tool identities and the composition remain key-bound. This does
not add remote eligibility or a toolchain provider.

The executor reconstructs the exact typed action before any CAS read, rejecting
changed implementation, kind, platform, target, output contract, process fields,
metadata, timeout or cache policy. Start detaches mutable input/metadata values.
Each poll verifies at most one input's SHA-256 and size. The final poll puts and
reads back bounded canonical metadata and validates the returned digest.
Successful outputs are exactly `application/launch.json` (not executable) and
all inputs under `application/input/`, with original digests/executable flags.
Input bytes are reused by CAS identity rather than copied into a second blob.

The action handle is thread-confined. Cancellation before terminal observation
returns CANCELLED/130 and no outputs; failure returns FAILED with no outputs.
Closing prevents subsequent polling. Terminal results return detached manifests.
The executor does not publish action-cache records itself; the existing local
cache wrapper may publish only a successful exact output tree. Metadata blobs
left by a failed/cancelled outer action are harmless unreferenced CAS data.

ContentStore is the durable immutable-byte boundary. Every consumed blob is
verified even if the store implementation claims verification; concurrent
external corruption after a read is not a filesystem snapshot guarantee. The
ByteArray interface allocates one whole input blob at a time; the store supplies
its own per-blob limit (the real test uses K04's 64 MiB default). Resource requests
reserve 128 MiB plus twice the largest blob's whole-MiB size. Metadata remains
bounded by the core 64 KiB settings / 256 KiB launch contract. This slice does
not add background workers or process execution.

## Focused Native gate

`tools/test_application_bundle.py` compiles core, K04 cache, the local action-cache
wrapper, the new application module and thirteen focused tests using Kotlin/Native.
Tests use real `NativeContentStore`, `NativeActionCache` and `NativeOutputTrees`:
JVM/resource and Native executable bundle shape, exact metadata, key invalidation,
undeclared references, tampered actions, missing/same-size corrupt input blobs,
lying storage, storage exceptions, cancellation, defensive values, cold/warm
reuse and absence of cache records after failed/cancelled preparation. A Native
input deliberately contains non-program bytes to establish packaging does not
try to execute it; this is not an actual application execution test.

```sh
python3 -B tools/test_application_bundle.py
python3 -B tools/test_application_bundle.py --no-build
```

The compiler is owned by the existing bootstrap guardian. The build receipt
binds source bytes, exact command, explicit environment, compiler launcher/support
files/JARs, Java executable and Python bootstrap support. `--no-build` checks that
receipt and executable/log hashes before running retained tests. Each run keeps
its own binary, build receipt, compiler log, stdout/stderr and filesystem CAS.
Unhashed installation/SDK support files must remain stable during compilation
and across reuse; this receipt is not full installed-tool inventory verification.

Preparation checks (`--help`, Python AST parsing and `git diff --check`) pass.
The first Native build succeeded; its retained run
`case-u5ol_9u4` passed 12 of 13 groups. The cold/warm comparison exposed
output-list ordering (identical entries and canonical digest): cold metadata
was first, while cache decoding sorted paths. The executor now sorts its final
manifest globally. A matching corrected Native rebuild is pending.
Application running/restarting, public CLI integration and foreground lifecycle
remain separate required slices.
