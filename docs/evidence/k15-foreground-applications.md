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
The actual public nested gates are **unexecuted**. An independent process-boundary
probe confirmed that the previous nested guardian implementation could report
outer completion before a delayed inner guardian finished cleanup. The retained
reproduction is `/private/tmp/kvik-nested-guardian-review-cdiws6j9/report.json`,
SHA-256 `8a5b3f65641e8c18f2562cc6436b66839683f50d52386f2a178549d52e76296a`.
The process-boundary correction and public gates are prerequisites to a nested
application no-overlap claim. Later protocol-error cleanup regressions require
new source/test receipts; the 25-test receipt does not claim to cover them.

## Next public gate (not executed)

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
handoff remain unexecuted. Those gates require the final reviewed Python bridge
and a composed runtime containing both ownership boundaries. The first gate's
summary/log and historical receipt remain retained; rebuilding reused its binary
output directory, so the second receipt identifies the current binaries.
