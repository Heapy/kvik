# Foreground library sessions

`kvik dev --module MODULE --target TARGET --tools TOOLS [build options]` runs a
foreground library build/test session. TTY input defaults to line controls;
non-TTY input requires `--control jsonl` or `--control lines`. All controls require
a newline. `--initial build|test` defaults to build. `--watch-ms 100..60000`
defaults to 1000. Output is always JSONL under `schemas/v1/session.json`.

- `r` queues a library build; `t` queues fresh tests; `q` closes the session.
- `?` returns help; `status` returns active and pending operations.
- JSON controls are exact objects such as
  `{"schemaVersion":1,"requestId":"check-1","command":"t"}`.
- There is one active operation and one pending intent; test intent dominates a
  coalesced build request. The latest r/t selects the operation used for watching.
- A source change needs two equal observations different from the last stable
  observation before its operation is queued. A transient unavailable observation
  that returns to the same stable inputs does not rebuild; confirmed unavailability
  queues one failed invocation and later repair queues a retry.
  A manual control queues immediately. Failure keeps the session usable.

The session is a Kotlin/Native loop. It does no compiler work, hashing, resolving,
or tool inventory verification. It owns one snapshot observer and at most one
build/test CLI child through the existing ProcessHost guardian. Bootstrap-backed
sessions also own a composition observer and, while consumer work is drained,
one replacement-build child. Every operation
uses a new mutable run directory and the existing authoritative CLI build/test
pipeline, including full tool verification and uncached test execution. No
resolve command is invoked. Global/scoped pins, selections and resource budgets
are forwarded explicitly; `--runs` is reserved for session-owned evidence.

Input polling covers declared sources/resources/compiler-plugin inputs, exact
configuration and lock bytes, and tools-document bytes. It delegates two
consistent read-only content scans to `snapshot-query`; preserved timestamps do
not hide edits. Generated `.kvik` paths are excluded by the existing snapshot
contract. **An input observation is not a verification freshness claim.** Tool
installation bytes can change without changing tools.json. The child record's
`freshnessAtCompletion` and the existing `verification --run` query remain the
full verification authority. Session events only describe input observations,
process outcomes and retained evidence locations.

Evidence lives under `<project>/.kvik/sessions/<unique session>/`: session.jsonl,
per-child command.json/stdout.jsonl/stderr.log and the operation child's runs.
Unchanged successful observer directories are removed to bound polling growth;
initial and changed observations remain. Run events retain their original schema
and are never merged into session events. Cancellation can leave partial child
run evidence; the session does not invent a completed verification record.

External shared content stores are supported when physically disjoint from
watched inputs. At startup and in each owned build/test or snapshot child, the CLI compares existing
ancestor inode identities and relative suffixes (including missing paths and conservative
case/Unicode aliases). Snapshot capture binds its configuration bytes to the
preflight digest before publishing any blobs; edits between preflight and capture
return indeterminate. Thus configuration edits cannot turn a previously disjoint
local store into a watched input. This requires trusted ancestors stable through use; it is
not an atomic filesystem snapshot or protection from hostile directory swaps.
Each project/session retains its own mutable evidence and control streams.

## Stream and process ownership

The session must be the exclusive reader of its input pipe and exclusive writer
of its output pipe. Independent sessions use separate pipes/PTYs. Children have
/dev/null stdin and private output files, so they never read or write session
streams. Pipes use readiness checks and writes of at most POSIX PIPE_BUF without
changing inherited open-file flags. TTYs are reopened independently with
O_NONBLOCK/O_NOCTTY and checked to match the inherited device. No termios state
is changed. Regular files and /dev/null are supported; sockets and other devices
are rejected. Local file/journal IO can be delayed by the filesystem.

Controls are limited to 4096 bytes; output buffering is limited to 256 KiB.
Non-reading output closes the session; remaining stream output is drained for at
most one second while the retained journal remains available. EOF, q, INT, TERM
and parent exit cancel children through their ownership pipes. Owner SIGKILL
closes those pipes and the invocation-owned guardians clean process groups.
There is no daemon, reconnect service or warm worker. The existing guardian's
limits still apply: deliberately detached process groups, a killed/stopped
guardian and uninterruptible kernel/filesystem stalls are not covered by a hard
shutdown guarantee.

## Source composition and native handoff

Sessions launched through bootstrap validate its bounded `KVIK_BOOTSTRAP_LAUNCH`
descriptor against the executing native composition and runtime path. Direct
native launches remain useful library sessions, but explicitly advertise
`source-recomposition-unsupported-without-bootstrap-context`.

A bootstrap-backed session initially disables consumer work until two owned
`--print-composition` observations match the running composition. A changed or
unavailable observation immediately closes that gate and cancels old consumer
work. Two equal changed observations start an owned `--print-launch` invocation
using the captured Python, launcher and literal bootstrap arguments. Offline and
no-build policy therefore survive every restart. Hashing, tool verification and
compilation remain outside the control loop. No shell, daemon, old-runtime
fallback or in-process plugin reload is used.

A failed rebuild retains its command/logs and keeps stale work disabled. Controls
remain available; a new source digest or manual r/t retries. A successful result
must preserve the bootstrap bindings and requested composition, then pass a new
post-build composition observation. After all owned children drain, the session
writes a linked `session-restarting` event and completely drains its output queue.
It will close for bounded output backpressure rather than exec across a partial
JSONL frame. It writes a bounded, digest-bound `handoff.json` in a fresh sibling
session directory, then executes the replacement native runtime in the same PID.

The continuation carries exact build options and pins, pending/watched operation
(test intent wins coalescing), and the bytes of a partially read control line.
Unread kernel input stays on the inherited stream. New session events start at
sequence one and link the prior session directory; authoritative child run
evidence remains in its original directory. Mutable project configuration is
not reparsed during the handoff itself, so invalid configuration remains a
recoverable child failure. INT/TERM are blocked at the exec boundary and unblocked
after the new session installs handlers. Failed exec restores the signal mask
and leaves the old journal, streams, controls and stale-work gate usable.

The descriptor, source roots, installed tools and evidence ancestors are trusted
local bindings. Digests detect inconsistent content; they do not provide a
filesystem snapshot or defend against a hostile replacement of trusted roots.
Composition polling describes source composition only, not continuous full tool
freshness. Local journal/handoff filesystem operations remain synchronous.

Application launch/restart, runnable artifact selection, graph reuse and raw
single-key terminal controls remain future K15 slices.
