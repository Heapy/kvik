# K14/K15 cached source-graph foreground handoff

This gate connects the reviewed source-graph launch descriptor to the real Native
foreground coordinator. It reuses both compatible runtimes from the independently
verified full-source bootstrap gate. It does not recompile a Native runtime and
does not replace production launcher, process, compiler or transport code.

## Isolated inputs and intended full gate

`tools/test_source_graph_handoff.py` copies the approved trusted bootstrap, both
published v1/v2 runtime bundles, both source locks, and the source cache from:
`/private/tmp/kvik-k14-source-bootstrap/.kvik/tests/source-bootstrap/case-kgcfr28z`.
The original fixture remains untouched. The private active source-lock and cache
paths contain spaces. Descriptor queries use nonexistent Java/Native compiler
paths with `--offline --no-build`; both runtimes must already be verified cache
hits. The trusted bootstrap and both runtime/source-cache inventories are retained
and rechecked after the full gate.

The consumer is a private copy of:
`/private/tmp/kvik-source-runtime-consumer-evidence/case-kyjizwh6`.
Only `library/common/New.kt`, whose exact known bytes belong to the prior
stale-query probe, is excluded. All original common/actual, module dependency,
resource and JUnit assertions remain unchanged. Eight project/configuration/tool
files and 13 unique lock-referenced dependency/metadata blobs are rehashed and
recorded. No compiler action cache is copied.

The full gate is implemented to:

1. Start a real v1 JSONL dev session. Its initial child uses `{}` as the tools
   document and must explicitly fail; that failure is not a consumer success.
2. Atomically change only the private source lock to the already built helper-v2
   composition. Once the old session observes the stale composition, deliver a
   queued `t` control and a partial JSON status frame in one pipe write. Install
   the real copied tools document before handoff.
3. Observe controlled same-PID exec into v2, linked session directories, exact
   digest-bound resume v1, pending test intent, preserved partial bytes, and exact
   source-lock/source-cache/offline/no-build arguments. Complete the status frame
   after exec and observe its original request ID.
4. Run the actual JVM consumer with four freshly executed actions and exactly
   `[engine:junit-jupiter]/[class:fixture.PassingTests]/[method:dependencyAndResource()]`.
   Require passed/current evidence, a fresh filesystem effect, complete journal
   and completion-marker binding, action keys/output CAS verification, and a
   separate current verification query. The terminal test action must be never
   cached. Quit and verify owned process cleanup plus exact per-session
   stream/journal equality.
5. Start another v1 session with the deliberately invalid tools document. Hold
   the private v2 runtime publication mutex, mutate its source lock, observe an
   actual owned `--print-launch` child blocked behind that lock, confirm controls
   remain responsive, then SIGTERM the controller. Require bounded exit 130,
   complete owned-child cleanup and no handoff while the mutex is held.

The second case uses the actual production file lock, not a fake process or
modified launcher. This is JSONL resume-v1 coverage; it does not add a raw-terminal
lease test, application artifact/restart support, or online source acquisition
inside a foreground rebuild. The separate full source gate already establishes
real source/helper recompilation. Expensive consumer work remains outside the
session control loop.

## Reproduction

Preparation only (no consumer or compiler starts):

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python \
  tools/test_source_graph_handoff.py --prepare-only
```

After the coordinated consumer compiler slot is granted:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python \
  tools/test_source_graph_handoff.py \
  --case /private/tmp/kvik-k14-source-graph-handoff/.kvik/tests/source-graph-handoff/case-qlubc43l
```

Optional fixture/cache paths are explicit CLI arguments. A prepared case can be
continued before its first live session; the harness refuses to reuse an existing
session directory as though it were a new gate. Each preparation/continuation
retains its own attempt directory and command receipts. Process inspection needs
normal local `ps` permission for owned-tree cleanup assertions.

## Actual preparation result; live session pending

The final preparation handle 2985 completed with exit 0. Retained evidence:
`.kvik/tests/source-graph-handoff/case-qlubc43l/attempt-q0_qozct/`.

Both real `--print-launch` calls emitted only `runtime-cache-hit`, retained the
exact 16 bootstrap arguments, and returned the expected v1/v2 composition.
Both actual Native `runtime-info` calls verified their bundles and reported
`source-v1` / `source-v2` in both plugin schemas. All copied inputs were checked;
no dev session, test, compiler, resolution or network acquisition was launched.
Python syntax checks also passed.

- Summary SHA-256:
  `12ac5d2143e67ec8aed0b5df5cef08e2811e6fe559b8db5e94e20be961f18209`.
- Harness SHA-256:
  `47d647bb2a9d304dfa6a79cb28d9c1008742adfb22834019bad2cfc0e071e7b8`.
- Session observer helper SHA-256:
  `33a8998939e40e23f90bd3ab922b34fea03b46943aca71af0168be9ce4b504d8`.

The live handoff, fresh consumer and cancellation cases remain pending. The
preparation is not reported as completion of those gates or of K14/K15.
