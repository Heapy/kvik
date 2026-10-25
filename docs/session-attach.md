# Attach an agent to a foreground session

Start the owner with `kvik dev --control jsonl ...`, keeping its original input
and output connected. Use the directory in its `session-started` event:

```sh
kvik session attach --session /absolute/session/directory
```

The client reads JSONL controls from stdin and streams versioned JSONL to stdout.
It emits the current `session-reconnect` state, `session-follow-events` pages,
`session-control` acknowledgements, and a final `session-attach-finished` record.
The protocol is defined by `schemas/v1/session.json` (`sessionAttachControl` and
`sessionAttachOutput`). Raw build/application logs remain in their retained run
directories, as referenced by the session events.

Send controls with an explicit session identity from the streamed state:

```json
{"schemaVersion":1,"sessionId":"kvik-run-EXAMPLE","requestId":"build-1","command":"r"}
{"schemaVersion":1,"sessionId":"kvik-run-EXAMPLE","requestId":"cancel-1","command":"cancel","operationId":"kvik-run-OPERATION"}
{"schemaVersion":1,"command":"detach"}
```

Commands are `r`, `t`, `q`, `status`, and `cancel`. Each request ID retains the
existing per-session idempotency contract. Pending/busy replies are followed by
their acknowledgement while attached. Up to 32 requests may await acknowledgement.
Retry a request with the same session ID, request ID and contents to recover its
reply. Commands already published may complete after the client disconnects.

EOF, `detach`, client interruption, parent exit, and client output failure release
only the attachment. Explicit `q` stops the owner. The owner's original streams
and lifetime remain independent; attaching cannot revive an exited owner or keep
it alive after its original input closes.

Resume with `--cursor '<nextCursor JSON>'` from the last fully received page;
`--limit 1..256` bounds each page. Events follow reciprocal source-composition
handoffs. Controls remain bound to their explicit session ID and are never
automatically replayed against a successor. A missing successor has a ten-second
handoff grace period. Exited owners can be replayed through their final retained
events, after which the attachment exits.

Input frames are limited to 4096 bytes and require a final newline. Output buffers
are limited to 512 KiB. A blocked writer or final drain gets one second; incomplete
streams exit with status 3 and can be resumed from a received cursor. Malformed
complete controls produce `session-attach-diagnostic` records and leave the client
usable. Incomplete journal tails are reported explicitly. Client interruption
returns 130.
