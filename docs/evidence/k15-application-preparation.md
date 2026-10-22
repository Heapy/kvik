# K15 prepared application handoff

This slice materializes one successful application bundle and binds it to a
portable descriptor. It does not execute an application or commit a completed
run. The caller must independently validate the completed journal, current
project/tools and current composition before consuming the handoff.

The source API in `kvik.application` is:

```kotlin
data class PreparedApplicationDescriptor(
    val runId: String, val actionId: String,
    val verification: Digest, val outputs: Digest,
)
fun materializePreparedApplication(
    record: VerificationRecord, content: ContentStore, runDirectory: String,
): PreparedApplicationDescriptor
fun validatePreparedApplication(
    record: VerificationRecord, descriptor: PreparedApplicationDescriptor,
    runDirectory: String,
): ApplicationLaunch
```

The descriptor has strict schema version 1 fields, canonical JSON and an 8 KiB
UTF-8 bound. `verification` hashes the complete canonical verification record;
`outputs` hashes the terminal output manifest. It contains no physical paths.
The helper snapshots all record collections, checks the 16 MiB canonical record
bound, rechecks record invariants and rejects duplicate actions. Exactly one
requested/completed `module:target:application` check and its successful terminal
are required. Status must be PASSED and completion freshness CURRENT; zero tests
is valid for preparation.

The bundle must contain nonexecutable `application/launch.json` (at most 256 KiB)
and only `application/input/*` files. The metadata bytes must be digest-verified,
strictly parsed and exactly canonical for the typed ApplicationLaunch. The helper
reconstructs the original input manifest and applicationBundleAction using the
record composition and the selected target's `target:name` tool identities with
the prefix removed. The reconstructed action key must match the terminal key.

Materialization uses K04 NativeOutputTrees into `<run>/prepared`, with staging,
exclusive atomic directory exposure and file/directory fsync. It rechecks all
materialized bytes and executable bits before atomically publishing canonical
`<run>/prepared.json` with K04 immutable publication. Existing prepared paths are
refused without overwriting. A failure after exposing the tree can leave an
uncommitted tree; callers must not treat it as runnable or reuse that destination.
Only a matching descriptor and independently committed/current run can authorize
later execution. No CAS writes or application processes occur in these helpers.

Read-only validation also reads and compares the descriptor on disk, checks
exact directory children (including rejection of extra empty directories), hashes
all files and rejects links/special files or changed executable bits. File reads
use each expected digest's size as their limit, up to Int.MAX_VALUE, consistent
with the existing whole-buffer filesystem/store boundary; there is no new 64 MiB
cap. Materialization also obeys the injected ContentStore's configured blob cap.
One file is held in memory during hashing. Host roots and files must remain
stable under the caller's ownership throughout verification and subsequent use;
this is not a filesystem sandbox or protection against hostile concurrent renames.

Reproduce with `python3 -B tools/test_application_preparation.py` in this worktree.
The controlled compiler environment and source/tool/support receipts follow the
bundle harness, with `--no-build` rejecting changed inputs/binary/log. Unhashed
SDK and installation support files must remain stable during compilation and
across reuse. The planned 13 Native groups use real CAS/filesystem materialization,
including a configured 65 MiB artifact, JVM/Native bundles, descriptor/record/key
mismatches, invalid bundle shapes, corrupted/missing blobs and files, extra files
and empty directories, links/FIFOs, executable changes, destination preservation
and mutation of caller-owned record collections. Help and diff checks pass;
matching Native execution is pending the coordinated compiler slot.
