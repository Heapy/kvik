# K15 controlled source recomposition

This slice adds owned bootstrap composition polling and same-PID native session
replacement. It remains a library build/test session; application launch/restart,
runnable artifact selection and raw single-key controls are not implemented.

Independent review of frozen candidate `7677509` found no confirmed defects.
Four C probes rebuilt in a separate checkout passed same-PID pending INT/TERM
delivery, refusal after an observed interrupt, and mask/handler recovery after a
failed exec. The retained audit checked 78 events across seven sessions, exact
journals, replacement bindings, stale gating, partial controls, pending intent,
backpressure and a genuine compiler failure. Reports are retained at
`/private/tmp/kvik-recomposition-exec-review.json` and
`/private/tmp/kvik-recomposition-audit-f300c75.json`. This review does not replace
the pending successful consumer-after-handoff gate described below.

## Boundaries

The current `KVIK_BOOTSTRAP_LAUNCH` must bind the running native composition and
runtime. Direct native launches explicitly report unsupported source
recomposition. Two matching observations gate initial consumer work; changed or
unavailable source observations cancel old work. Replacement builds use captured
literal Python/launcher/bootstrap argv through ProcessHost. Offline/no-build
policy is retained. A failed build leaves controls usable and stale work disabled;
a changed source digest or r/t retries. Replacement descriptors preserve every
bootstrap binding, and a fresh post-build observation confirms the selected
composition. No daemon, shell, hotload or older-runtime fallback is involved.

The controller performs no source/tool hashing or compilation. It drains consumer
and bootstrap children and the complete output queue before exec. A blocked
stream closes within the existing bounded drain policy instead of mixing a
partial old event with a new event. The digest-bound, bounded continuation carries
literal options/pins, pending test intent, watched operation and partial control
bytes. New journals link their predecessor. Mutable project configuration is
checked in owned children, so invalid configuration across a handoff is
recoverable. Failed exec keeps old controls/journal/ownership alive. INT/TERM stay
blocked across exec until replacement handlers are installed.

Local source/tool/evidence ancestors must remain trusted and stable. No atomic
filesystem snapshot or continuous installed-tool freshness is claimed. Source
composition observations and child verification freshness are separate. Existing
process ownership limits remain: malicious detachment, a killed guardian and
uninterruptible kernel/filesystem stalls are outside a hard cleanup guarantee.
Session journal/handoff filesystem IO is synchronous.

## Reproduction

Run from this worktree with the provisioned tools (no Gradle/KTC):

```sh
PY=/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python
JDK=/Users/yoda/.sdkman/candidates/java/25.0.4-librca
KONAN=/Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20
FIXTURE=/Users/yoda/dev/pet/kvik/.kvik/tests/native-scope-review/case-6jrzkpwg
$PY tools/test_foreground_recomposition.py --java-home "$JDK" --konan-home "$KONAN" --fixture "$FIXTURE"
$PY tools/test_foreground_recomposition_lifecycle.py --java-home "$JDK" --konan-home "$KONAN"
$PY tools/test_foreground.py --java-home "$JDK" --konan-home "$KONAN"
RUNTIME=$(./kvik --offline --no-build --print-runtime)
$PY tools/test_foreground_recomposition_consumer.py --java-home "$JDK" --konan-home "$KONAN" --fixture "$FIXTURE" --runtime "$RUNTIME"
```

The real source harness copies production sources into a path containing literal
`$(literal)`, edits the Kotlin build plugin's schema description and compiles the
new native runtime. Native `runtime-info` confirms the changed plugin. Its
consumer tool document is deliberately invalid to isolate source lifecycle tests;
those consumer failures are not successful compile evidence. The separate
consumer harness below supplies real verified tools.

## Retained results

Under `/private/tmp/kvik-k15-recomposition/.kvik/tests/foreground-recomposition/`:

- `case-f300c75_`: actual source plugin v1 compilation/handoff, invalid project
  configuration across restart, carried partial status JSON and pending test,
  actual Kotlin syntax compiler failure, repair to v2 and another same-PID restart.
  The failure log reports Native compiler exit 1, not cancellation. No-build
  rejects v3 without starting a compiler. Concurrent sessions remain independent;
  cancellation during an observed nested Native compiler cleans tracked processes
  in 0.400 seconds. These are lifecycle measurements under concurrent machine
  load, not performance benchmarks.
- `case-srvdoxqy`: repeated successful handoffs, schema-validated handoff files,
  partial `q` completed after a successful restart, no-build/concurrent sessions,
  nested compiler cancellation in 0.370 seconds. Its first rejected rebuild was
  cancelled; it is not used as syntax-error evidence. The permanent harness now
  requires a noncancelled, nontimedout failure and the Native compiler error log.
- `lifecycle-w9zj9sj4`: real bootstrap ownership chain with sleeping nested workers;
  both hashing and rebuild phases tested q, EOF, INT, TERM, owner SIGKILL and parent
  loss. All tracked descendants stopped in 0.305–0.415 seconds. Initial stale
  consumer sentinel remained absent. Actual failed exec retained status/q and
  stale gate; multiple already-read t/q/r controls closed without handoff.
  Unread stdout plus 700 controls filled the output pipe during a controlled
  rebuild; the journal retained the restart boundary and output-backpressure
  closure, with no exec attempt and no surviving descendants.
- Native `ResumeTest`: two tests preserve literal args, offline/no-build, pending
  test and split UTF-8 bytes; reject extra fields, excessive size, newline partial
  controls and invalid interval. The C probe verifies failed exec retains handlers
  and pending TERM survives same-PID exec.

The prior foreground regression also passed at
`.kvik/tests/foreground/lifecycle-3jd133ve`: initialization cleanup, q/EOF/signals,
owner kill/parent loss, concurrent sessions, PTY state, coalescing and watch
recovery, invalid/bounded input and non-reading stdout.

Actual native compositions in the retained source-copy proof:

- Initial: `b871ef9b90e669a78a47a1a9c2ae78e8bfe8681abbb1d478915675f36099fa58`.
- Plugin v1: `3669e3bb334910eb71998235110e90e28f84a5addc70a63322543ff52b7a9be2`.
- Plugin v2: `55967cf57a4e3a11303ebf3b8d3e145bfd8119d0b102d287c98db3639b4efad0`.

Successful replacement runtimes remain in the isolated worktree runtime cache.
Failed bootstrap logs and session child commands/output remain retained. The
immutable input fixture and main worktree were not modified.

The first separate consumer attempt (`consumer-vonu2bpm`) crossed the real v1
handoff and executed two Native klib actions. A later unavailable composition
observation correctly cancelled that invocation. Its original harness stopped on
that cancellation; it is not successful consumer evidence. The permanent harness
now waits through cancelled attempts, uses the default one-second polling
interval, and requires a completed four-action result with a freshly executed
test, exact ID and current verification query. An immediate retry
(`consumer-9c9noe8h`) was interrupted to reduce concurrent host load; neither
attempt is counted as a passing consumer test. Subsequent process checks found
no remaining children from either attempt.

## Successful consumer after handoff

The final isolated `tools/test_foreground_recomposition_consumer.py` run passed
with the valid immutable Native fixture's tool document and a new private CAS:

- Evidence: `.kvik/tests/foreground-recomposition/consumer-7frum2vd/`.
- PID 45861 remained the foreground owner across initial composition `b871ef9b…`
  to plugin v1 `3669e3bb…`; sessions `kvik-run-NsmuJN` → `kvik-run-QGTnvZ` link
  through the retained handoff.
- Child `kvik-run-nDPk3W`, authoritative run `kvik-run-mQ7sFK`: all four actions
  executed (two production klibs, Native test executable, fresh test execution).
- Exact test ID `fixture.AnswerTest.dependencyAndInternalDeclaration` passed;
  discovered/executed 1, failed/skipped 0, complete discovery.
- `freshnessAtCompletion=current`; a separate full verification query returned
  `current`. Child stdout exactly matches its retained run journal and record.
- The session accepted q, exited 0, and every tracked owned descendant stopped.
  No source-unavailable cancellation occurred in this successful retry.

`summary.json`, `verification-query.json`, literal `command.json`, both session
journals/handoff and full child run evidence are retained at that path. This
closes the successful post-handoff consumer gate; it does not extend the slice to
application restart or raw-key terminal behavior.

Independent review of frozen implementation `7677509` found no production
issues. The reviewer rebuilt its C boundary in a separate checkout and passed
pending INT/TERM across same-PID exec, already-observed INT refusing exec, and
ENOENT restoring the original signal mask and handler. Retained source handoff,
partial controls and output-backpressure evidence were independently audited.
