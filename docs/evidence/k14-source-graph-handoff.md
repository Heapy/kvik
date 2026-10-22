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

## Actual preparation result

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

## Actual live handoff and consumer result

The frozen harness `351fefd` completed with exit 0 (handle 58561) on the prepared
case. Retained full attempt:
`.kvik/tests/source-graph-handoff/case-qlubc43l/attempt-d91n9cct/`.
Its original `summary.json` remains unchanged with SHA-256
`2bb6d106cace920cf86ab0d5415b612f8fd5e7e3729525d74d9e72bb4524ef39`.

- Controller PID 58916 remained unchanged across the actual v1-to-v2 exec.
  The 19 observed events span two linked sessions; both retained journals match
  their portions of stdout byte for byte. Session stream SHA-256:
  `fe6b426339aa83806c511601ecf7a2acd6470005398b016a9318365c2ed1a1e7`.
- Resume v1 retains the queued test, exact partial status bytes, all private
  source-lock/source-cache paths, and offline/no-build flags. The completed frame
  produces the original `carried` request ID after exec. Handoff SHA-256:
  `72d0d743fb3f9ee3b70e453faa154c199b6cfa2ed953cbf5c584f02768f628f4`.
- All four actual JVM actions executed: both main compiles, test compilation and
  the never-cached test. The exact JUnit identity above passed; counts are one
  discovered/executed, zero failed/skipped, discovery complete. The run retains
  a fresh `effects.txt` containing `executed\n`, passed/current verification,
  full journal/completion binding, recomputed action keys and rehashed outputs.
- Consumer evidence is under
  `case-qlubc43l/project/.kvik/sessions/kvik-run-12J4LX/kvik-run-OY5IsK/runs/kvik-run-JRX59t`.
  The separate verification query was current. Quit completed and all tracked
  owned processes disappeared in 0.157 seconds.
- The cancellation session observed an actual launcher Python child and its
  guardian while the private v2 publication lock was held. That lock prevented
  successful replacement acquisition. Status controls remained responsive and
  no session-restarting event occurred. SIGTERM returned 130 with all tracked
  owned processes gone in 0.349 seconds. All 11 events agree with its retained
  journal; stream SHA-256:
  `19f89c00ae1e41a36b179f58733a4db351ee159d205346e43775061280a3256e`.
- Seven retained bootstrap query/acquisition commands preserved the exact launch
  policy. Both runtime bundle inventories, trusted bootstrap and source cache
  remained byte-identical to preparation. No Native runtime was recompiled;
  reuse is deliberately distinct from the full source rebuild gate.

The initial invalid-tools children were expected setup failures and are not
counted as consumer successes. The cancellation case also intentionally replaced
the private tools document with `{}` after the successful run. A narrow follow-up
restored it byte-exactly from `valid-tools.json` and repeated the production
verification query after all sessions closed; that query returned current/exit 0.
The original full-attempt receipt was not rewritten. Supplementary evidence is
`final-restoration.json` (SHA-256
`65e00844b830a6482c3f20d797d871a85915d88377fc83b0e3058ae44584cc98`),
`final-current-verification.stdout`, and `.stderr` in the same attempt. The
reproduction script is `/private/tmp/kvik-handoff-final-verification.py` and its
hash is retained in that receipt.

The harness now performs the same restoration and final query automatically.
This additional tail was verified against the retained completed run, without
repeating the consumer compilation. Python syntax passed. No raw-terminal or
online foreground source acquisition case, application restart, full K14/K15
completion, or continuous tool freshness is claimed by this bounded gate.

## Independent retained-evidence audit

Root independently rehashed the original frozen harness and its helper, all outer
command logs, both complete runtime inventories, trusted bootstrap/source cache,
eight project files and 13 dependency blobs. The 19 handoff events and 11 cancel
events match their three session journals; resume bytes, all seven bootstrap
commands, four fresh action identities, eight output blobs, exact JUnit identity,
effect and completion bindings were checked. The separately restored final tools
and current query remain bound to the successful consumer record. Report:
`/private/tmp/kvik-handoff-independent-audit.json`; reproduction script:
`/private/tmp/kvik-handoff-independent-audit.py`. No compiler rerun was needed.
