# Foreground application lifecycle

This slice adds `dev --mode application --module NAME --target jvm|macosArm64`.
The default remains library mode. An application target must declare application
settings. `--initial test` explicitly queues a fresh test operation followed by
application preparation in application mode; library tests never start programs.

Application mode watches application inputs. `r` requests a reload; `t` queues
fresh tests independently, without changing the watched operation. Duplicate
intents coalesce, but tests and reloads do not erase each other. Status exposes
active identity/staleness, candidate phase, and both queued intents.

The session owns two independent application supervisors at most: the active
program and a candidate. Readiness is a canonical, bounded activation record,
validated against the owned candidate token, current input revision, selected
composition, module and target. The candidate owns expensive preparation and
full verification. The session loop reads only readiness/start records; it does
not hash source trees, tools, artifacts, or full journals. Readiness is neither
an application health assertion nor a guarantee that later validation succeeds.

A failed or cancelled candidate keeps the old program. A current ready candidate
may cancel the old supervisor, but activation waits until its ownership tree has
terminated and its handle has closed. Input changes invalidate readiness and mark
the active identity stale. Program exit is reported and waits for reload or an
observed input change. Quit, EOF, signals and owner death retain owned cleanup.

During source recomposition the old application stays alive while bootstrap and
the new runtime prepare a replacement. Every control mode requires explicit v3
continuation capability and recursive process ownership version 1 from the actual
C boundary; a cached runtime advertising only generic v3 is rejected. Only a validated replacement permits stopping the old
application and waiting candidate; both must be reaped before exec. Resume v3
carries scope, independent pending intents and the digest-bound prepared pointer,
never process handles. The new runtime issues a new activation token and performs
full `stage-prepared` validation. A stale prepared pointer or invalidated handoff
queues one fresh preparation, not an unbounded retry of ordinary failed builds.
If validation fails after the old program was stopped, there is an observable
availability gap; the protocol does not promise zero downtime. Library v1/v2
continuations retain their existing serialized representation and behavior.

## Focused validation

Frozen controller/test source `02e65f0` passed the following command (with read-only
process inventory permission) in actual job `96687`, terminal exit 0:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_foreground_applications.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
```

The 25 Native tests comprise 17 application tests plus eight retained library
protocol, continuation and key capability tests. Nine actual owned-process
scenarios cover reload/test intent retention, delayed and failed preparation,
watch repair, crash recovery, quit/EOF/INT/TERM/owner SIGKILL, two independent
owners, and a failed then successful same-PID source handoff. The fixture worker
explicitly simulates readiness and verification. Its child is in the same
ownership group; it does not exercise nested real application supervisors.

Retained evidence under `/private/tmp/kvik-k15-foreground-applications`:

- `.kvik/tests/foreground-applications/case-grw9dmto/summary.json`: nine scenarios,
  recorded tracked PIDs/groups and harness-observed cleanup times 0.345–0.593 s.
- `case-grw9dmto/unit.log`: 25 RUN/OK results, 83 ms, SHA-256
  `ad26b846e43f6db00443ebba14b14611d9a7ad7e0aeb436df9eeb78909e9da1d`.
- `.kvik/tests/foreground-applications/case-grw9dmto/receipt.json`: preserved historical copy, 21,033 bytes, SHA-256
  `8c016119ba2e9c1d15d9cef6ba4f82ebd7e17e1262acca0fdfd82bd3423ce348`;
  source, compiler launcher/JAR inputs, controlled environment, commands and
  binary digests. SDK/JDK tracking is a focused test-build receipt, not a complete
  installed-toolchain inventory or portable binary reproducibility claim.
- Independent retained-evidence audit:
  `/private/tmp/kvik-foreground-focused-review-29xzrryc/report.json`, SHA-256
  `5d002898a48a347c646abf2cf11a89292f89db1467a613a0f1e2d65dc1b9b9f4`.
  It verified 35 committed source/harness inputs, tool bytes/binaries, exact test
  results and all nine retained event/effect sequences. Cleanup timing remains
  the producer harness observation.

Two strict Python schema groups also passed. The public JVM/Native foreground
harness is prepared separately in `tools/test_foreground_applications_cli.py`;
its schema-only fixture preparation invoked no runtime, resolver or compiler.
At this historical freeze the actual public nested gates were **unexecuted**. An independent process-boundary
probe confirmed that the previous nested guardian implementation could report
outer completion before a delayed inner guardian finished cleanup. The retained
reproduction is `/private/tmp/kvik-nested-guardian-review-cdiws6j9/report.json`,
SHA-256 `8a5b3f65641e8c18f2562cc6436b66839683f50d52386f2a178549d52e76296a`.
The process-boundary correction and public gates are prerequisites to a nested
application no-overlap claim. Later protocol-error cleanup regressions require
new source/test receipts; the 25-test receipt does not claim to cover them.

## Public gate preparation (historical)

After the recursive process boundary is reviewed, integrated and compiled with
this controller, the prepared command is:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_foreground_applications_cli.py \
  --runtime /ABSOLUTE/REVIEWED/RUNTIME/kvik.kexe \
  --schema-directory /ABSOLUTE/MATCHING/SOURCE/schemas/v1 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --artifact-cache /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts \
  --source-handoff
```

It requires process-inventory permission, pinned installed tool bytes and cached
compiler artifacts. Resolve may contact the configured Maven repository. The
public applications retain the one-shot fixture program sources and assertions;
acceptance-only entry wrappers check exact prior PIDs/groups before their first
effect, then invoke those programs. The harness checks the actual
session→guardian→stage→guardian→application chain and child/grandchild links;
process counts alone are insufficient. Runtime-only dependencies, resources,
literal arguments and declared environment assertions remain in the programs.
Two added explicit tests exercise fresh JVM/Native test execution while an app
remains active. Failure cleanup captures owned trees, waits for drain, retains
failure receipts and restores intentionally broken source files.

The optional source gate copies runtime sources, exercises an actual failed
Native runtime compilation while the old JVM app lives, then a successful changed
runtime with a failed application candidate. It carries a partial JSON control
and test intent across same-PID exec, deliberately stales the prepared pointer,
and requires a fresh preparation before the replacement runs. This source gate
uses an actual JVM application; Native application reload/ownership is separate,
and an actual Native application across source exec remains unclaimed until
specifically exercised. No unexecuted harness assertion is evidence of success.

## Follow-up source preparation before the second focused gate

Application admission and replacement now separately require Native recursive
process ownership version 1 and, when source recomposition is enabled, bootstrap
bridge ownership version 1. The additive `processOwnershipVersion` descriptor
field remains optional for library sessions. Resume v3 requires it; a selected
runtime advertising resume v3 alone is insufficient. Missing/malformed old-owner
acknowledgement or close failure cannot authorize activation. Session cleanup
attempts owned-child closure, journal closure and terminal restoration even when
an earlier boundary fails, preserving the original exception.

Three focused Python schema groups passed after these additions. At source freeze,
their Native regressions and public entry witnesses had not been compiled or run;
the earlier 25-test receipt remains bound only to `02e65f0`. The actual recursive
C boundary and Python bridge are separate dependencies undergoing review.

## Updated focused gate with recursive Native ownership

The second gate ran from clean `b633dc3`: controller follow-ups `efdeded`,
`43403df`, `2a9a6e9`, plus reviewed recursive boundary `f48694b` cherry-picked as
`b633dc3`. It used the same focused command above; process-inventory permission
was granted. Actual handle `24012` terminated with exit 0. No source changed
while compiling. The two compiler invocations reported only the existing
unnecessary-safe-call warning in `Session.kt`.

- **29 Native tests passed** in 150 ms. The four additional tests cover old-owner
  poll/close failures preventing activation, complete cleanup with primary error
  preservation, and bootstrap ownership capability admission. Existing capability
  tests also reject missing/wrong Native ownership versions.
- **Nine synthetic lifecycle scenarios passed**, using the real corrected Native
  process host and protocol-fixture workers. They cover reload/test intent
  retention, ownership signals/EOF/owner loss, independent owners and same-PID
  synthetic handoff. Observed cleanup times were 0.488–0.685 s.
- `.kvik/tests/foreground-applications/case-dgn89hkl/summary.json`: 3,699 bytes,
  SHA-256 `b69c66decad16eef788d26ea7bac2234e43d57530e34002b887a278c3bfec118`.
- `case-dgn89hkl/unit.log`: 7,013 bytes,
  SHA-256 `8890f85d2218c5f1c5f35f054e72bbe48706b1b520ad008784f9c89a59b6e526`.
- `case-dgn89hkl/receipt.json`: retained source/tool/environment/command/binary
  receipt, 21,035 bytes,
  SHA-256 `133865bb3494866b27ddd22f2d75b9283fb46837159676b1f8c7b08d4c6d4010`.
  All 42 recorded inputs were checked again after compilation. The matching
  binaries remain in the parent output directory.

This focused gate does not build or exercise the real Python bootstrap bridge;
its handoff descriptor is explicitly produced by the synthetic fixture. The
public entry witnesses, actual JVM/Native foreground programs and compiled-source
handoff remained unexecuted at this focused freeze. Those gates require the final reviewed Python bridge
and a composed runtime containing both ownership boundaries. The first gate's
summary/log and historical receipt remain retained; rebuilding reused its binary
output directory, so the second receipt identifies the current binaries.


## Independent focused and merged-source audits

The 29-test/nine-scenario receipt was independently audited without recompilation:
`/private/tmp/kvik-foreground-updated-review-cx5rxn29/report.json`, SHA-256
`d84213f8dbfcb59c45b6cbca20f9253f7ca5dc242025ef686dcb6c409695b8e0`.
The review checked 35 committed source/harness inputs, seven tool inputs, four
binaries, six commands, all tests and the event/journal/effect/token/resume bindings.
The serial integration audit checked 38 files against their reviewed branches:
`/private/tmp/kvik-foreground-bootstrap-merged-audit-81_p2d3q/report.json`, SHA-256
`76ca8bee2882d71d693e1e092563f46f457dbff3fd2ba10c16bda0a158bc0a4b`.

The first complete composed runtime from `77bb61f` was independently audited in
`/private/tmp/kvik-foreground-runtime-review-kwlge9ct/report.json`, SHA-256
`11ce0f467d8ed57d32d05e9176a37d6d95dee67ec7fd14a2f8c4fdc8d7e3cd36`.
That audit checked 23 source roots, 21 recipe files including the Python ownership
bridge, composition identity, runtime/guardian bytes, 111 JVM worker classes and
bundle metadata. It did not freshly hash installed toolchains or claim a public
application result.

## Retained public failures and observation recovery

All paths below are relative to `/private/tmp/kvik-k15-foreground-applications`.
No failed attempt was deleted or relabeled as passing.

1. Handle `9152`, case `case-ostkh626`, terminated 1 after real JVM cold/warm
   execution: the harness expected `local-hit`, while the established status is
   `local_hit`. Retained `warm-assertion-review.json` confirms identical keys and
   outputs for all four warm hits. Commit `6e1af6a` corrects only that spelling.
2. Handle `16073`, case `case-w1ce63o3`, demonstrated a real controller stall.
   After readiness had stopped the old app, a transient unavailable observation
   cancelled its replacement. Restoring the same fingerprint did not enqueue
   another application, leaving no active/candidate process. The harness was
   deliberately interrupted only after preserving that state; it terminated 130.
   `controlled-stall-stop.json`, `controlled-stop-result.json`, `failure.json` and
   `cleanup.json` retain the failure and all 33 captured PIDs/22 groups were gone.
   The invalid-source predicate was also corrected to require a genuine failure,
   excluding cancellation and timeout; the cancelled Native candidate is not
   compiler-failure evidence.
3. Commit `195b6ba` retains exactly one application intent when observation or
   composition invalidates preparation, and waits for available, debounced input
   before consuming it. Ordinary failed compilation or a crashed program still
   waits for explicit reload or changed input. A bounded observation result binds
   exit flags and error to the exact scanner directory.
4. Focused handle `28827` compiled successfully and passed 30 Native tests, then
   its new diagnostic failed the strict session schema. Commits `94d579f` and
   `a8d9a5a` add the diagnostic code and its 512-character schema bound. Matching
   binaries were replayed with `--no-build` at handle `64181`: 30 Native tests
   and ten actual owned synthetic lifecycle scenarios passed. All 183 retained
   session events were revalidated against the final schema.

The focused recovery case is
`.kvik/tests/foreground-applications/case-g52ca93g`:
`summary.json` SHA-256 `789c01fa719e5c434f5c35e3af2e5ad22c89318887526f250de2448846a18bf6`
(4,156 bytes), `receipt.json` SHA-256
`44a3d020aa879b1380e3d22a5c7284a62e984d9a4be69c972643014b978eb244`
(21,035 bytes), and `unit.log` SHA-256
`12fb0db418ce0b89cfd4b6406203b5a98a8688489e211cac6b18cdb0a2897d40`
(7,232 bytes, 61 ms). Its synthetic injected exit125 proves recovery behavior,
not the cause of the separate real scanner failures.

## Complete public gate from frozen `a8d9a5a`

The complete source receipt `.kvik/foreground-public/source-recovery-receipt.json`
binds 547 tracked files to `a8d9a5a45d511a87bed25ede4db77d11c90c336e`.
All were rechecked against that exact commit after the gate. Build handle `76210`
terminated 0 in 53.214 s; `.kvik/foreground-public/recovery-build-launch.json`
and `recovery-build-stderr.jsonl` retain the bootstrap evidence. The runtime is:

- Composition `783b5e0ed9e6105a71c20bfbcc288818758bde7f1518985dbee89f3bee572619`.
- `.kvik/foreground-public/runtime/executables/783b5e0ed9e6105a71c20bfbcc288818758bde7f1518985dbee89f3bee572619/kvik.kexe`.
- Executable SHA-256 `6c9f28945b0db1850843f7f841da0e7a18fdb6bfa62988139e2e45c0c27557af`,
  5,074,856 bytes. Its actual help includes `--mode library|application`.

The public command above used this runtime, this worktree's `schemas/v1`, and
`--source-handoff`. Exact arguments are retained in
`.kvik/foreground-public/acceptance-recovery-command.json`; stdout/stderr are in
`acceptance-recovery.log`. Actual handle **17659 terminated 0**. The authoritative
case is `.kvik/tests/foreground-applications-cli/case-u9vz_93p`:

- `summary.json`: passed, 69,384 bytes, SHA-256
  `3f1223bb63628b84e9ec696b1a532550b5ac76897b1940afd38992808557c7f8`.
- `invocation.json`: fixture/test/witness/schema/runtime/harness identities,
  2,438 bytes, SHA-256
  `6caba8499fbd8626dffc5623f54fed203c7df52c8a6f95aacc4a50d0e0f7c8e6`.
- `cleanup.json`: all five session owners drained, 866 bytes, SHA-256
  `5cce8c868d894b85f3a9190b5c40e6d4a9f1661f928186481be91b9933f6dffa`.
  A separate post-terminal process inventory found no captured PID/group alive:
  60 distinct PIDs and 40 groups (`post-terminal-owned-processes.json`).

Both targets executed the unchanged one-shot application assertions through
acceptance-only entry witnesses. Runtime-only module/external dependencies,
resources, ordered literal arguments, declared environment, cwd and stdin
assertions therefore remain exercised. Exact guardian/stage/app/descendant
relationships were captured. Before replacement's first program effect, its
witness verified absence of all previously captured ownership PIDs/groups.

JVM initial preparation executed four actions; warm reload and repaired-source
preparation each reused all four. Native compilation/linking completed during
preparations interrupted before the first program start; the first successfully
started Native generation (3), warm generation (5), and repaired generation (8)
each reused five cached actions. This is not a claim that the first *started*
Native generation was a cold five-action build. Native cache policy remains local.
Both real invalid-source candidates exited 1 without cancellation/timeout and
left the old application running. Repair started a new program. Fresh tests ran
while each application remained alive, one discovered/executed/passed test each:

- JVM: `[engine:junit-jupiter]/[class:fixture.ApplicationTests]/[method:dependencyRemainsAvailable()]`.
- Native: `fixture.ApplicationTests.dependencyRemainsAvailable`.

Simultaneous JVM and Native owners were exercised: killing the JVM owner did not
stop the Native application; terminating the Native owner then drained its tree.
The source gate used JVM applications and retained PID **56183** across exec.
A genuine failed Native runtime compilation and a genuine failed application
candidate under the repaired runtime both left the old application alive. The
successful replacement composition is
`43f9bb47ad22cd4a31f591f0fd7d8b72311eeb661d7ed1afc23142ac4899c983`.
The resumed process preserved partial JSON control bytes and pending test/reload
intents. The deliberately stale prepared pointer failed with exit3 and triggered
one full fresh preparation. The queued test passed, the new application started,
and the changed plugin schema marker was observed through `runtime-info`.

### Limits and follow-ups

Five actual scanner observations returned framed exit125 with neither cancellation
nor timeout. Their exact `observation-result.json` directories remain in the case,
including `kvik-run-WsKUqT/kvik-run-cwTEOW` and `kvik-run-WsKUqT/kvik-run-sHgdf4`.
These command-only worker directories narrow the failure to setup before normal
output creation but do not identify an errno or failing syscall. The new retry
behavior recovered successfully; **the cause is not fixed or explained by that
success**. A separate bounded guardian setup-stage/errno tranche is pending.

Independent source review also found that truncating a valid supplementary
Unicode path at 512 UTF-16 units could split a surrogate pair. Follow-up `0914d0e`
fixes both new observation message sites and adds a focused regression. That fix
was made after this public run, and its Native test is pending; it is not covered
by the `a8d9a5a` runtime claim above.

Actual Native application reload/test/ownership passed; actual Native application
across runtime-source exec was not exercised. This gate does not establish
arbitrary compiler/plugin compatibility, remote cache eligibility, deterministic
Native executable bytes, or low-latency startup. Full Native tool validation and
several automatic retries made the run costly; no fast-start claim is made.
Readiness performs the existing full current-evidence validation, but no separate
post-exit `verification` query is claimed by this harness. Independent retained
public evidence review is still pending at this documentation freeze.
