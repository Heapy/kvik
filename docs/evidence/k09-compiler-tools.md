# K09: production JVM tool provider

Date: 2026-10-22. This slice connects the reviewed Native JVM executor's provider interface to
the source-built worker bundle and complete installed-tool verifier. Verification runs in
Kotlin/Native, with POSIX reads and CommonCrypto streaming SHA-256. No JVM, subprocess,
network, path discovery or mtime-only cache participates in verification.

## APIs and trust

`kvik.compilertools.verifyJvmWorkerBundle(bundleRoot, expectedManifest)` takes an explicit
physical `jvm/` bundle root and caller-trusted manifest SHA-256/length. It returns
`VerifiedJvmWorkerBundle` with verified class/API/stdlib/JUnit/compiler-manifest paths, worker
and compiler identities, and `junitIdentity`. It validates every actual class and library,
exact directory contents, executable flags and the aggregate worker identity. The manifest
digest is checked before its fields become authority. Constructors of verified bundle values
are internal.

`cli/RuntimeWorkers.kt` is now a thin wrapper: the root still comes from the actual executable
directory, and the expected manifest digest still comes from constants compiled into that
executable. `RuntimeJvmWorker` is a typealias preserving the existing properties. Failures
retain the CLI's `RuntimeIntegrityFailure` category.

`InstalledJvmToolchainProvider(trustedToolLock, toolRoots, bundleRoot, expectedBundleManifest,
compilerArtifactsRoot)` implements `JvmToolchainProvider`. Each successful `verified(identities)`
call performs all of the following before returning a layout:

- Revalidate the complete worker bundle and compare the exact requested identity keys
  `jdk`, `jvm-compiler`, `jvm-worker`, `junit`, including every digest and byte length.
- Check the compiler manifest's raw pinned identity also equals its canonical JSON identity,
  validate schema/version/paths, and stream every jar in its ordered `artifacts` list against
  its SHA-256 and length. Classpath order remains the order in that manifest.
- If `serializationPlugin` is non-null, verify the existing bootstrap convention's
  `kotlin-serialization-compiler-plugin-embeddable-2.4.20.jar` against that digest. The current
  schema stores this plugin's digest without a path; no new schema field was introduced.
- Invoke `InstalledToolchainVerifier` for all six explicitly supplied roots and the trusted
  installed-tool lock. Verify the requested JDK identity and require its `bin/java` to be a
  regular executable before returning the checked absolute layout.

Unreferenced artifact-cache entries, such as lock files or other versions, are permitted;
they are never added to the compiler classpath. Bundle and artifact roots reject symlinks
in all directory ancestors. Installed roots follow the existing tool inventory policy;
the returned JDK root is resolved, and this provider additionally requires a real `bin`
directory and regular `bin/java` (a linked launcher is unsupported).

The caller supplies trusted pins and keeps bundle, artifact and installed roots stable through
verification **and subsequent use**. The provider copies the supplied roots/request identity
maps, never memoizes verification, and checks ordinary per-file size/mode/mtime/ctime changes
during streaming reads. This is not an atomic filesystem snapshot or a defense against an
adversarial writer replacing directories or changing and restoring bytes during/after reads.
The trust of the installed-tool lock itself and bootstrap composition wiring belong to the
run coordinator. No acquisition or automatic repair occurs here.

## Bounds and composition

Bundle limits are 1 MiB manifest, 4,096 class files, 8,192 total class-tree entries, depth 64,
8 MiB per class/library and 256 MiB total class bytes. Directory listing stops at 8,192 names.
The provider limits the compiler manifest to 1 MiB, 1–128 artifact entries, 128 MiB per compiler
jar, and 256 MiB total compiler/plugin bytes. File contents use a 64 KiB buffer rather than
whole-jar allocations. Installed-tool inventory bounds and host restrictions remain those
documented in `k09-toolchains.md`: Darwin arm64, Kotlin 2.4.20, exact uname/SDK compatibility.
Verification is synchronous, has no cancellation or elapsed-time quota, and belongs off a
responsive event loop. Full six-root verification on every provider call is deliberately costly.

This source-composed module reuses internal `kvik.toolchains.StreamHash` and K04 `NativeFiles`;
it is not a separately published Kotlin library. Compose `integration/compiler-tools/src`,
`integration/toolchains/src`, the existing Native JVM adapter and their dependencies, with
`integration/toolchains/platform/stream_hash.def` and the existing process interop. Bootstrap
recipe/registry changes are separate integration work. No Native compiler provider or remote
portability approval is included.

## Reproduction and evidence

```sh
python3 tools/test_compiler_tools.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --runtime /Users/yoda/dev/pet/kvik/.kvik/runtime/executables/103077d7b75ce6752733c958d4d0f58e963afa6188cb23a9dad589766e47420a \
  --artifacts /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts
```

`--fixtures-only` explicitly skips the real installed-provider/BTA scenario. The harness builds
the actual Native provider and thin CLI wrapper, supplying test-only compiled constants for
the wrapper. Python produces independent fixture inventory/manifest digests. The real fixture's
expected bundle digest is taken from its source-built publication metadata as a trusted test
input; production CLI code uses its compiled constant. Main's runtime, artifact cache and
installed tool roots are read-only inputs. Python uses `xcrun` only to discover test inputs;
the Native provider receives explicit roots.

Six scenario groups passed:

1. Complete provider success on independently pinned six-root/bundle/compiler/plugin fixtures.
2. Wrong values for each of four identities, missing keys and extra keys rejected.
3. The same provider instance rechecks and rejects same-size, restored-nanosecond-mtime edits
   to classes, API/stdlib/JUnit jars, compiler manifest, a multi-chunk compiler jar, serialization
   plugin, JDK and LLVM; restored bytes succeed again.
4. Missing, symlink and FIFO compiler jars; extra bundle files; executable-bit changes; linked
   bundle root; and wrong expected manifest rejected. Unreferenced artifact-cache content is
   permitted and does not affect selection.
5. Even trusted malformed compiler documents reject unknown fields/version, traversal,
   duplicate/empty artifact lists and oversized jar identities.
6. The real provisioned six tool inventories, source-built bundle, 13 compiler jars and pinned
   serialization plugin verify; the resulting layout drives one real BTA compile through
   `NativeProcessHost`. The owned worker exits successfully, returns the matching request ID,
   and produces `HelloKt.class`. The process is closed/reaped before the result is accepted.

The real provider plus owned BTA compile took **50,827 ms** in this run. Peak Native process
RSS, including the preceding fixture checks, was **21,250,048 bytes (20.3 MiB)** via Darwin
`getrusage`; this excludes the separate tool JVM. Timing combines verification and compilation,
with uncontrolled filesystem-cache state and concurrent machine load. It is not a cold-start
benchmark or resource guarantee. Native measurements and the worker response are retained in
`.kvik/tests/compiler-tools/installed-result.json` and `worker-result.json`. No broad compiler,
launcher or consumer suite was run, and no test-execution evidence is claimed by the compile probe.
