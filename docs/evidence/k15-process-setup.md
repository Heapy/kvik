# Process setup diagnostics and terminal wait observations

This slice makes pre-execution failures observable. It does not explain the five
framed scanner exit125 results in the earlier public foreground case
`case-u9vz_93p`: that runtime had no setup-stage evidence. It also fixes a separately
reproduced stopped-child classification error in the guardian.

Source commits are `b89c70e` (typed setup evidence) and `1781f55` (terminal wait
classification), in `/private/tmp/kvik-process-setup-diagnostics`. They depend on
the separately frozen Unicode observation-message fix `0914d0e`.

## Contract and boundary

`ProcessResult.setupFailure` is optional, preserving existing constructor calls.
`ProcessSetupFailure` contains one of 24 fixed `ProcessSetupStage` values and a
positive errno captured at the failing operation. The guardian reports parent
pipe/descriptor/environment/fork/group failures and child group/bind/cwd/open/exec
failures. An executable intentionally exiting125 or127 has no setup failure.
An exec failure reports127; other noncancelled setup failures report125.

The child writes at most one three-int setup record through a dedicated CLOEXEC
pipe. Successful exec closes that pipe without a record. The guardian closes its
own writer immediately after fork and reads bounded child evidence only after
the existing group/barrier/reap cleanup. Unknown, partial, extra or malformed
records fail closed through the existing ownership poison mechanism.

The final guardian frame has a new magic and six integers. The existing poll API
is unchanged; `kvik_process_setup_error` exposes validated fields only after a
verified terminal result. Old-frame magic and wrong lengths are rejected. The
Native wrapper constructs the same typed result. Cancellation130 or timeout124
may retain setup evidence already written by the child; contradictory flags,
codes, stages and errno payloads are rejected. This is diagnostic data, not
permission to activate an application after a protocol failure.

Session `observation-result.json` now records nullable setup evidence. Its new
standalone strict schema is `$defs/observationResult` in `schemas/v1/session.json`.
The bounded user diagnostic includes the stage and errno when available. No
arbitrary child text enters the setup record. Ordinary result serialization
outside this observation is unchanged unless a caller explicitly consumes the
new field. Runtime and helper identities change with their source bytes;
recursive ownership capability remains version1 because its barrier protocol is
unchanged. Mixed final-result frames are not silently accepted.

## Retained failure investigation

The first frozen gate (`31691`, terminal1) compiled successfully and passed ten C
cases, then the setup-error cancellation case failed protocol validation.
`.kvik/tests/process-setup/case-le4_42wu` preserves the exact build and failure.
A temporary C-only numeric trace, retained in
`.kvik/tests/process-setup/debug-o2e2xmb2/file-trace-report.json`, found:

- The child had written a complete cwd/ENOENT record and cleanup was unpoisoned.
- Its deliberate SIGSTOP was reported as145, with neither cancellation nor timeout.
- The guardian accepted any non-`CLD_EXITED` wait notification as a signal death.

The trace's first compile omitted SDKROOT and failed before launching any child;
`initial-missing-sdk.stderr` retains that failure. The corrected temporary compile
used an explicit SDK. No temporary trace or fault-injection hook entered production.

Commit `1781f55` accepts only `CLD_EXITED`, `CLD_KILLED` and `CLD_DUMPED` as terminal.
An actual follow-up trace observed `si_code=5` (`CLD_STOPPED`), status17, ignored
until cancellation130 or timeout124. A separate normal stop→SIGCONT→exit0 case
and actual SIGTERM death now exercise both outcomes.

The first corrected full invocation (`51832`, terminal1) exposed a different
failure in the cancellation fixture: parent group setup returned EPERM, reported
accurately as stage14/errno1/exit125. The exact case is `case-gglnjenr`. Ten traced
attempts followed by 100 additional bounded attempts did not reproduce that
parent error; each trace observed the expected child PGID and complete reaping.
Those records remain in `group-trace-nnp5glwb/{report,extended-report}.json`.
There is **no unconditional EPERM ignore or inferred cause fix**. The intermittent
parent error remains unresolved and must not be conflated with the earlier real
scanner failures, whose setup stage was unavailable.

## Passing focused gate

The matching compiled binaries from `1781f55` were reused with their complete
receipt, without recompilation:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_process_setup.py --no-build
```

Actual handle **17224 terminated0**. All **29 C cases and four actual Native
decoder cases passed**. The case is
`.kvik/tests/process-setup/case-juebvxuh`:

- `summary.json`: 8,696 bytes, SHA-256
  `831e18bac6cc70b8c928ea5b079f2ee36138488273b0f5141e3b10351f2c8423`.
- `build.json`: 17,326 bytes, SHA-256
  `4f4cb0926dbc42d654ccc303441484b5f37246443dbcb0675180253a953b2a80`.
- `native/stdout.json`: 268 bytes, SHA-256
  `0f281cffd9287e649712d913e5a2556bb24be5d72f363d9b56b9d31b78972af8`.

The receipt binds 50 source/harness/tool inputs, eight actual compile commands,
the explicit compiler environment, empty compiler log and six retained binaries.
The controlled environment excludes ambient Java/Konan options and tracks compiler
launchers, `run_konan`, `env_blacklist` and compiler JARs. It does not claim a full
independent SDK or installation inventory. The production guardian digest is
`28718ee08123e2a9b35e965c38d144f1b077434ef050d9861a6eabd1e9d9df14`
(52,936 bytes); Native probe digest is
`7c6f8b6dd2df3d4efe17f7a57b316040e0bff14a885bce22e853285dec90fa0e`
(1,832,712 bytes).

Actual cwd, stdout/stderr exclusive-create and missing-executable failures are
distinguished from intentional125/127. Test-only helpers inject fork/group
failures, malformed child/final frames and a stopped child after writing setup
evidence. Cancellation/timeout retain its evidence while completing ownership
cleanup. The Native probe independently exercises cwd/exec and intentional125/127.

Five focused Python schema groups also passed:
`python -B tests/foreground-applications/test_schema.py -v` (0.008s).
The pure Unicode and model-invariant tests in `ApplicationLifecycleTest.kt` still
await their foreground Native build at this evidence freeze; the setup probe does
not include those tests. A newly composed runtime and correlated real scanner
reproduction are still needed to diagnose the original foreground exit125.
