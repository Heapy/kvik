# K15 nested process ownership

Status: implemented in the Native process boundary; focused evidence is recorded
in docs/evidence/k15-nested-ownership.md. Foreground and mixed bootstrap
integration require their own acceptance gates.

The current guardian launches its worker in a new process group, but a guardian
spawned by that worker inherits the worker group. Cancelling an outer owner can
therefore kill an inner guardian before its independently grouped application is
cleaned. The outer result currently proves only its immediate guardian exited.
The retained independent reproduction is
`/private/tmp/kvik-nested-guardian-review-cdiws6j9/report.json` (SHA-256
`8a5b3f65641e8c18f2562cc6436b66839683f50d52386f2a178549d52e76296a`):
outer cancellation completed in 0.273 seconds while the application group was
still alive 400 milliseconds later. The reviewer explicitly cleaned the fixture.

## Per-level completion barrier

Each process guardian runs in its own group, outside its worker group. It owns a
fresh completion pipe: the guardian retains only the reader, and its worker
receives only the writer. The worker's Native process boundary forwards its
writer explicitly to every guardian it creates. Such a nested guardian retains
that upstream writer until its own worker group and its own downstream completion
pipe have drained. Its forked worker closes the upstream writer and receives
only the newly created downstream writer. This creates a per-level proof:
completion at an intermediate level includes every nested level, without each
child inheriting an ever-growing list of ancestor descriptors.

The upstream pipe reaches EOF only after the worker has exited and every nested
guardian releases its writer through process exit. Empty EOF acknowledges
verified cleanup. Any received byte poisons the acknowledgement: a guardian
that cannot verify cleanup writes one nonblocking byte upstream, and each
ancestor propagates that poison. A full pipe is already poisoned; a missing
reader yields EPIPE without SIGPIPE. The guardian drains bytes with a fixed
256-byte buffer while waiting, and must wait for EOF before publishing a result. No elapsed grace interval substitutes for completion.
A paused nested guardian delays completion rather than allowing replacement
activation. Group TERM/KILL grace remains a cancellation mechanism only.

## Capability and descriptor ownership

A bounded internal `KVIK_PROCESS_BARRIER_V1` marker identifies one inherited
nonblocking write-only FIFO by descriptor number and device/inode identity.
The marker value uses canonical unsigned decimal fields, with bounded parsing
and overflow rejection. Descriptor 6 is
reserved across guardian/worker exec (3/4 are control/result; terminal mode uses
5 for its terminal). Native boundary startup validates and captures the marker,
removes it from the process environment, and marks the descriptor close-on-exec.
Invalid markers fail future owned launches and same-PID handoff before spawning.
Request environment maps cannot supply or override this reserved marker.

Owned spawn explicitly duplicates only the captured capability into the new
guardian, independently of the request's declared environment. The guardian
validates/removes the upstream marker, and injects the new downstream marker
into its worker. Ordinary non-Kvik workers inherit that one intentional ownership
capability; their descendants remain in the owned group. No sibling control,
result, or unrelated ownership descriptors survive the spawn boundary.

Same-PID foreground exec explicitly preserves the captured capability and
reinjects its marker; failed exec restores descriptor flags. Terminal guardians
also retain the upstream completion capability until terminal restoration ends.
They do not create a downstream pipe because they start no worker.

This is an ownership protocol for cooperative Kvik boundaries, not a sandbox for
hostile in-process code that closes/forges descriptors. Existing external
`setsid` escapes, direct guardian SIGKILL, and uninterruptible kernel children
remain separate failure boundaries. Mixed old runtimes lacking this protocol
cannot be inferred to provide transitive completion. The compiled boundary
exports `kvik_process_ownership_version() == 1`; application controllers must
require the corresponding runtime/bootstrap capability before admitting a
replacement. Python bootstrap ownership forwarding is implemented separately,
and cannot be inferred from the Native boundary alone.

## Completion and failure

Keep the worker leader unreaped while cleaning its group, reserving its PID/PGID.
After group SIGKILL, query Darwin `KERN_PROC_PGRP` with a buffer bounded to 4 MiB and wait
until every remaining member is a zombie (or the group is absent). The installed
SDK declares `kinfo_proc.kp_proc.p_stat` and `SZOMB` for this purpose. A query
error must not be treated as an empty group: it poisons completion. Drain the
downstream barrier while waiting, then reap the leader. Only verified cleanup
publishes a result; poisoned cleanup exits with a protocol failure and propagates
poison upstream. A stopped nested guardian or uninterruptible child can delay
completion indefinitely; the implementation never substitutes a time limit for
a positive cleanup acknowledgement.

A valid four-int guardian packet (version magic, exit code, timeout flag,
cancellation flag), exact stream EOF, and normally reaped guardian are required
for terminal completion. C poll returns 0 while pending, 1 for verified terminal,
and -1 for missing/malformed result or unexpected guardian exit. Owner-detected
transport failures also poison its inherited barrier, even if user code catches
the local exception. Application exit 125 remains legitimate when accompanied
by a valid cleanup acknowledgement.

C close always consumes/frees its handle and returns -1 on protocol failure.
NativeOwnedProcess clears its pointer and membership before calling close, so
an exception cannot leave a retryable freed pointer. NativeProcessHost cancels
all children, closes every sibling even if an earlier close fails, and then
rethrows the first exception. A controller must let poll/close protocol failures
escape before activating a replacement. This failure does not claim that an
externally killed guardian's application was cleaned.

## Evidence obligations

A deterministic test pauses an inner guardian after observing its application,
cancels the outer owner, and proves no outer completion beyond the old grace
interval. After resuming the guardian, all captured process groups must be
stopped before terminal completion or subsequent application effects. Repeat
with at least three ownership levels and verify intermediate completion too.
Cover cancellation, owner SIGKILL, ordinary leader exit, zero and positive
deadlines, independent invocation isolation, same-PID exec and failed exec,
malformed capability/result, setup failures and descriptor leaks. Retain exact
commands, binaries/source receipts and checked kernel process observations.
Existing direct process lifetime and terminal restoration tests remain required.
