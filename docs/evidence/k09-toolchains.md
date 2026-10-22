# K09/K14: Native installed toolchain verification

Date: 2026-10-22. This slice verifies the six complete installed-component inventories in
`bootstrap/toolchains.lock.json`. It does not wire a CLI or JVM/Native compiler provider, acquire
tools, execute compilers/tests, or establish cross-machine cache eligibility.

## API and identity

`integration/toolchains/src/kvik/toolchains/InstalledToolchains.kt` exposes
`InstalledToolchainVerifier.verify(lockJson, roots)`. `roots` is an explicit
`Map<ToolComponent, String>` containing JDK, Kotlin/Native, LLVM, libffi, SDK and Xcode toolchain
roots. No path discovery, subprocess, Java runtime or `xcrun` occurs inside the verifier.
Successful verification returns `VerifiedToolchains`, whose indexed components contain an
absolute resolved root, verified inventory digest, file/symlink counts and total regular-file
bytes. Constructors for verified values are internal; callers do not receive partial verified
results after failure. Missing roots, incompatible locks, I/O failures and inventory mismatches
fail verification rather than returning unchecked paths.

Each call hashes all six roots again. A provider must invoke verification for each provider
verification call, rather than retaining a prior result as evidence of current bytes. Consumers
can use the JDK component directly or combine related verified component digests into their
adapter's required toolchain identity. BTA/JVM compiler distributions, worker classes and JUnit
are separate artifacts and are not covered by these six roots.

The inventory matches `bootstrap/identity.py:inventory(root, allow_internal_symlinks=True)`:

- Each regular file contributes its relative path, SHA-256 plus byte length, and whether any
  execute bit is set. Empty directories do not contribute identity; hidden files do.
- Internal symlinks contribute path and original link text, without traversal of linked
  directories. The final strictly resolved target must remain within the resolved root.
  External, broken and cyclic links, invalid UTF-8 names, and special files are rejected.
- Paths sort component-by-component in Unicode code-point order, as Python `Path` objects do.
  This differs both from flat path-string ordering and from Kotlin's default UTF-16 ordering.
  No case folding or Unicode normalization occurs.
- The canonical JSON bytes match `bootstrap.identity.canonical`, including key order and
  control-character escaping. **Component digest `sizeBytes` is the canonical inventory byte
  length**, not the sum of file bytes. `InventorySummary.fileBytes` reports the latter separately.

The canonical stream is hashed incrementally, entry by entry. File hashing uses a reusable
64 KiB buffer and a small CommonCrypto SHA-256 context through the owned Native cinterop header.
Core types and the core ByteArray hasher are unchanged. There is no whole-file, whole-SDK or
whole-inventory byte buffer. CommonCrypto comes from macOS libSystem.

## Host and bounds

This adapter supports Darwin arm64 and Kotlin 2.4.20. Lock schema version and exact required
fields, all six digests, and `uname` OS/architecture/kernel release must match. SDK version is
read from `Version` in the regular `SDKSettings.json` bytes captured while hashing the SDK;
the SDK's full inventory digest must match before those bytes are trusted. A missing regular
settings file or missing Version is explicitly reported as unsupported host data. A different
SDK version fails compatibility. Host data is not filled from a caller assertion or guessed
from a directory name. The lock is a caller-trusted pin, not an authenticated distribution.

Defaults bound each inventory to 2,000,000 directory entries, 200,000 children per directory,
and depth 128 (configurable depth cannot exceed 256). Lock input and captured SDK settings are
limited to 1 MiB each; symlink text is bounded below 64 KiB. Traversal retains sorted child
names along the active directory stack, so worst-case metadata memory depends on directory
width and depth, not only the file-read buffer. There is no total-file-byte/time quota or
asynchronous cancellation: the synchronous full scan belongs off a responsive event thread.

Tool roots must remain stable and caller-controlled throughout verification **and subsequent
compiler use**. Per-file inode/type checks plus before/after size, mode, mtime and ctime checks
catch ordinary mutation during reads; they are not an atomic tree snapshot or protection
against adversarial directory replacement. Root rename, additions after a directory scan,
changes between components, and changes after verification remain the caller's TOCTOU boundary.
Use immutable installations/snapshots or serialize mutation through that entire interval.
Every new verification still reads raw bytes even when size and mtime are unchanged.

This verifies the exact selected Xcode toolchain and SDK directories, not the entire Xcode.app
or operating-system filesystem. Host-release matching and verified installed bytes do not prove
compiler portability. Absolute symlink text can itself make an inventory location-dependent.

## Reproduction and observations

From the checkout, on the provisioned Darwin arm64 host:

```sh
python3 tools/test_toolchains.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
```

The harness builds the CommonCrypto cinterop and Kotlin/Native executable. Java is used only
to run those build tools. Test-only Python uses `xcrun` to discover explicit installed roots;
the Native process receives those roots in its request. `--konan-data` overrides the dependency
directory. `--fixtures-only` explicitly skips the full installed-root check. All child processes
are waited/reaped. Generated executable, cinterop and installed measurements live under
`.kvik/tests/toolchains/` and are not committed.

Six fixture groups pass in a single long-lived Native verifier process. Python independently
computes expected fixture inventories. Cases include empty trees/files; SHA padding boundaries
55/56/63/64/65 bytes and 64 KiB read boundaries; a 4 MiB file; nested component ordering;
supplementary Unicode; control/quote/backslash filenames; executable bits; internal file,
directory, chain and root symlinks; external/broken/cyclic symlinks; FIFO; invalid UTF-8;
depth/entry limits; chmod000 unreadable file with explicit capability-bypass skip; six-root
verification; same-size edits with original nanosecond mtime restored; added/renamed files;
executable changes; malformed/duplicate/version/host/digest/missing-component locks and roots;
and missing SDK host data. The verifier recovers after rejected scans. No permission-bypass
skip occurred on the observed host. The invalid-UTF-8 filename case was explicitly skipped
because the filesystem rejected creation with EPERM; strict Native UTF-8 decoding is implemented,
but rejection of such an actual directory entry is not demonstrated on this filesystem.

The additional full installed-root scenario matched every digest and inventory length in the
existing Python-generated lock, including all real SDK symlinks:

| Component | Regular files | Symlinks | Raw file bytes | Canonical inventory bytes |
|---|---:|---:|---:|---:|
| JDK | 447 | 0 | 474,365,165 | 72,848 |
| Kotlin/Native | 52,359 | 0 | 1,344,007,455 | 13,524,784 |
| LLVM | 287 | 4 | 520,710,873 | 49,331 |
| libffi | 9 | 0 | 77,734 | 1,411 |
| SDK | 33,464 | 8,142 | 931,393,813 | 7,601,360 |
| Xcode toolchain | 5,683 | 79 | 2,860,381,909 | 1,284,802 |

Observed full-scan elapsed time was **26,805 ms** for **6,130,936,949 raw file bytes**;
process peak RSS including preceding fixtures was **20,414,464 bytes (19.5 MiB)**, measured
by Darwin `getrusage`. This is one run with uncontrolled filesystem cache state, not a cold-cache
benchmark or performance bound. Full verification on every provider call has substantial cost;
mtime shortcuts are deliberately absent. No broad compiler or launcher suite was run for this slice.

Independent review found no confirmed defect. Twelve independently generated nested trees with supplementary/BMP Unicode, control/quote/backslash names, executable-bit changes and internal/root symlinks produced identical Native and Python digests and canonical byte lengths. The documented stable-root and synchronous-scan limits remain applicable.
