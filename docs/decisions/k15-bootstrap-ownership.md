# Recursive ownership through bootstrap

Status: implementation and targeted validation in progress. This does not yet
establish a complete foreground source-recomposition acceptance gate.

Native foreground sessions launch Python bootstrap workers. Those workers use
separate guardians for compiler invocations and source acquisition. Previously,
`pass_fds` forwarded only each Python guardian's control/result pipes. Killing
the Python owner could therefore let its Native ancestor report completion
before the independently grouped Python guardian finished cleanup.

`bootstrap/process_barrier.py` implements the same version-1 completion capability
as the Native boundary: descriptor 6, an identity-bound nonblocking FIFO writer,
and reserved `KVIK_PROCESS_BARRIER_V1` transport metadata. Imports capture and
remove the inherited marker; caller environment maps cannot forge or replace it.
Python owners explicitly forward it to their guardians and across launcher exec.
Failed exec restores the original descriptor flags.

Each guardian creates a new downstream completion pipe. Its private,
single-threaded child setup transfers that pipe to descriptor 6 and closes the
upstream writer in the child. The guardian retains its own upstream writer until
both its worker group and the downstream pipe are drained. Any byte poisons
completion; only an empty EOF acknowledges successful recursive cleanup. The
reserved marker is visible to ordinary tools as internal process metadata,
separate from their declared environment and cache inputs.

The Python fork hook performs only descriptor operations and is restricted to
the single-threaded private guardian. Control/result descriptors do not reach
workers. Passing descriptor 6 explicitly preserves it through Python's
`close_fds`; the parent never replaces its own upstream writer. This preserves
Popen's ordinary exec-error reporting without introducing another executable
wrapper or a background service.

Darwin's `proc_listpids(PROC_PGRP_ONLY)` and
`proc_pidinfo(PROC_PIDT_SHORTBSDINFO)` provide a bounded process-group snapshot.
The structure matches the pinned SDK's `proc_bsdshortinfo`. The leader remains
unreaped until no non-zombie group members remain and downstream acknowledgement
is complete. Query errors, truncated snapshots and poisoned completion fail
closed. Additional host platforms need their own process-inventory boundary.

Source acquisition retains its bounded cleanup deadline. Reaching that ceiling
poisons upstream before forced guardian termination; it must never become a
successful cleanup acknowledgement. Direct hostile guardian SIGKILL, deliberate
descriptor forgery and processes that escape supported ownership remain outside
the cooperative process contract.

The bootstrap launch descriptor advertises `processOwnershipVersion: 1`.
Application sessions require both a bridge-capable bootstrap descriptor and a
compatible Native runtime before source recomposition. Library descriptors that
predate this optional field retain their legacy compatibility behavior.

Required evidence includes existing compiler/acquisition ownership regressions,
paused Python guardian chains, FD collisions/leaks, same-PID exec and failed exec,
reserved/malformed metadata rejection, and an actual Native → Python → Native
chain with both successful recursive cleanup and propagated protocol failure.
These focused tests precede the full public JVM/Native foreground and source
handoff gates.
