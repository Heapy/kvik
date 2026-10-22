# K14 shared local runtime store protocol

This slice adds `bootstrap/runtime_store.py`. It stores complete runtime bundle
files in the existing K04 blob layout and a separate `runtime-actions` record
namespace. It does not choose a compiler, validate runtime semantics, execute a
runtime, or alter composition identity. Launcher integration owns those checks.

The public API is `RuntimeStore(root)`, `coordinate(key)`, `lookup(key)`,
`publish(key, bundle)`, `materialize(record, destination)`, and
`invalidate(record)`. Keys are K04 `{hash, sizeBytes}` digest objects.
`RuntimeRecord` is frozen and keeps canonical `record_bytes`; `key_bytes` and
`outputs_bytes` are immutable, while `key`, `outputs`, and `record_digest` return
fresh dictionaries. Construction and lookup do not create store files.
Publication and invalidation require an active `coordinate(key)` context on the
same instance and thread. Coordination and K04 blob/record locks keep persistent
inodes. Same-thread recursive coordination is rejected.

Every regular output is captured with its executable flag. Blobs are bounded to
64 MiB and records to 16 MiB. Canonical JSON, UTF-16 path ordering, SHA-256/size
addresses, and `0555`/`0444` restored file modes match Native K04 publication.
Malformed or noncanonical records, invalid paths, missing blobs and corrupt
bytes cannot produce a hit. Empty directories are not K04 artifacts. Symlinks
and special files cannot be captured or materialized; real directory ancestors
are required. The caller owns stable roots and ancestors during each operation.

Publication fsyncs blobs before exposing metadata, preserves valid existing
inodes, and refuses different outputs for a valid existing key. Confirmed
invalid metadata is quarantined under `runtime-actions/quarantine`; unreadable
regular files and unexpected directories are preserved and reported. A record
whose referenced bytes are missing or corrupt may be repaired by a new allowed
publication. Native K04's generic publisher has a stricter conflict rule for a
syntactically valid record with missing blobs; no Native implementation changes
are made here. Valid cross-language records have identical semantics.

Restoration writes independent files into a private sibling tree, fsyncs it,
and exposes it with an atomic absent-only rename. It never hardlinks mutable
materialized files to CAS. A failed restoration never exposes a partial tree.
Invalidation compares the observed record digest under its record lock before
quarantining, so a stale rejection cannot remove a replacement record.

## Focused checks

Run the Python filesystem/process suite:

```sh
python3 -B tests/bootstrap/test_runtime_store.py -v
```

The twelve groups cover defensive values, canonical/path/type/boundary rejection,
actual case/Unicode aliases, corruption/missing data repair, preserved valid
inodes, conflicts, actual mode-000 read errors and injected read-I/O errors,
symlinks/special files/ancestors, absent-only restoration, independent copied
inodes, same-record invalidation, four concurrent producers, persistent locks,
and SIGKILL during durable blob staging, record staging, and after blobs but
before record publication. Orphan staging after SIGKILL is harmless; this slice
does not add garbage collection.

The Native interoperability harness is:

```sh
python3 -B tools/test_runtime_store.py
```

It compiles unchanged production `NativeContentStore`, `NativeActionCache`, and
`NativeOutputTrees` plus the small protocol probe. It retains the exact Kotlin
source digests, command, compiler entrypoint/JAR and Java executable digests,
compile log, and binary digest under `.kvik/tests/runtime-store/build.json`.
`--no-build` requires that receipt to match before rerunning light interop checks.
The receipt is a focused compiler-input record, not a fresh inventory of the
entire installed Native/JDK toolchain.

The synthetic five-file bundle includes an executable flag, empty file, binary
bytes, UTF-8 text, and supplementary/private-use Unicode filenames whose UTF-16
ordering differs from Unicode code-point order. The harness checks Python
publication through actual Native lookup/restore and Native publication through
Python lookup/restore, exact record bytes, inode preservation, corrupt-byte
miss/repair, no-overwrite restoration, and valid-record conflicts in both
implementations. Synthetic executable bytes are never executed. Actual restored
Native runtime use remains the separate launcher gate.

Actual interoperability passed on the provisioned macOS arm64 host with Kotlin
Native 2.4.20 and JDK 25.0.4. The compile invocation completed successfully with
an empty compiler log. Its fourteen Kotlin source digests and command are in
`.kvik/tests/runtime-store/build.json` (SHA-256
`bf6ebe6e337e312a5107b9b617069aae8d71b2de249f0ed63d3c72a12519d067`).
The 1,166,664-byte probe has SHA-256
`1165c5888dbef6ce3b5d7a29e7ce5dbafb77955fd28e899503b568a9a29f3a22`.

The final matching light replay was:

```sh
python3 -B tools/test_runtime_store.py --no-build
```

It returned zero with twelve Python groups passing in 1.238 seconds and eight
actual Native calls, including the expected nonzero conflict/no-overwrite
probes. Retained final case:
`/private/tmp/kvik-k14-runtime-store-impl/.kvik/tests/runtime-store/case-s8h15730`.
Its `summary.json` SHA-256 is
`e9d7b33204543e37960e22ac715eb7d2c3db254b2eecfe069f0624aa575f526d`.
The summary binds the seven Python implementation/harness dependency files,
Python executable, complete native command/output receipts, canonical record,
and both restored tree inventories. Earlier cases remain retained; the final
case matches the frozen production and test source bytes.
