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
across reuse. The 13 Native groups use real CAS/filesystem materialization,
including a configured 65 MiB artifact, JVM/Native bundles, descriptor/record/key
mismatches, invalid bundle shapes, corrupted/missing blobs and files, extra files
and empty directories, links/FIFOs, executable changes, destination preservation
and mutation of caller-owned record collections. Help and diff checks pass. The matching Native build/run completed with exit 0;
all 13 groups passed in 2458 ms, including the 65 MiB artifact in 2210 ms.

Source/test freeze: `8e5a160`. No source changed after the matching build.
Retained case: `/private/tmp/kvik-k15-application-preparation/.kvik/tests/application-preparation/case-sii4bkyh`.

- Native binary SHA-256: `d3b71de0ce79c17537764b1ec1f3dc935442faa7e94c0f6d6f12ecc14c553535` (2,567,480 bytes).
- Build receipt SHA-256: `31d5b10f4e92cfc4012fa67f52781ceb7a6e3ab5210f154e133d359dd9a3b3a6`.
- Summary SHA-256: `01c56e1788d4836289ec0739958f856ee2a165bc87da017ed336144be5fd23a0`.

The case retains the exact source/compiler/support receipt, compiler log, binary,
test stdout/stderr and all materialized/CAS trees. The independent reviewer can
use `--no-build` to verify input/log/binary bindings and rerun with fresh case data.
