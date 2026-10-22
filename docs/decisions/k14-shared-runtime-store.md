# K14 shared local runtime store

The optional bootstrap flag `--runtime-store PATH` selects a caller-owned local
K04 content store. It is separate from `--cache-dir`, which holds materialized
executable directories and logs. Remote executable transport is not part of this
slice. Both local source compositions and explicit source graphs use the store.
The path is invocation policy, excluded from composition identity and retained in
foreground launch descriptors. `--print-composition` never creates store files.

Use K04's exact `<root>/blobs/<sha256>-<size>` layout and persistent blob locks
under `<root>/locks`. Runtime records occupy the separate
`<root>/runtime-actions/<sha256>-<size>.json` namespace, with canonical K04
`{schemaVersion:1,key,outputs}` records and complete regular-file tree manifests.
The tree includes the executable, guardian, JVM worker bundle, runtime lock,
publication manifest and graph receipts. No compiler/source inputs are omitted
from the existing runtime identity. Records are not eligible for remote reuse.

Per-composition coordination under `<root>/runtime-build-locks` covers lookup,
restore or compilation, and record publication across distinct materialized
runtime caches. Native linking is not byte-reproducible; a valid existing shared
publication remains the chosen bundle. A local valid bundle can seed an absent
record. Publishing different outputs over an existing valid record is a conflict
and must preserve it. Blob publication is durable and no-replace; verified valid
blob inodes remain unchanged. Records publish only after every referenced blob.

Restoration verifies all referenced bytes and executable metadata into a private
tree, validates the complete bundle with the launcher's existing `cached` check,
then re-observes desired inputs before replacing a corrupt local publication.
Invalid shared records or missing/corrupt blobs are explicit misses; an allowed
build can repair them under coordination. `--no-build` may restore a verified
shared bundle, but cannot acquire sources or compile on a miss. `--offline` uses
only this local store. No failed restore/build may execute a different key.

The store follows K04's 64 MiB per-blob and 16 MiB record bounds, regular files,
normalized relative paths, executable modes, fsync ordering and trusted stable
ancestor boundary. Reject source-lock/selected-source overlap and overlap with
other bootstrap cache roots before writing. No garbage collection or network
filesystem guarantees are introduced.

The Python store implementation must interoperate with actual Native K04 code
in both directions: Python publication read/restored by Native, and Native
publication read/restored by Python. Verify corruption, valid-record conflicts,
concurrent writers, interruption and no-overwrite restoration separately from
the real launcher build/restore gate. A synthetic bundle only proves storage
protocol behavior; a real Native runtime must prove compiler-free restored use.
