# Native compiler timeout diagnostics

A compile/link process reported by `ProcessHost` as timed out previously produced
only `KVIK_NATIVE_PROCESS_FAILED` with `Native process exited 124`; its
`ActionResult.diagnostic` was null. The executor now emits `KVIK_NATIVE_TIMEOUT`
with the action phase and configured budget, for example
`Native link timed out after 180000 ms`, and retains the same message in the action
result. Prior compiler diagnostics remain available. The action fails and exposes
no outputs.

The condition is the host's `timedOut` flag, with cancellation taking priority.
An ordinary explicit exit 124 remains a process failure. Host cancellation and
cancellation requested after process completion retain their existing semantics.
Native test discovery, builtin test reports and Kotest reports are unchanged.

Validation on the isolated `d878cf8` baseline compiled the real Native executor,
cache and execution implementation with pinned Kotlin/Native 2.4.20 and
JDK 25.0.4-librca, then ran all nine `NativeUnitTest` groups: **9 passed, 43 ms**.
The new deterministic host-boundary regression exercises compile and source-free
link for each of: timeout, timeout with an earlier compiler error, ordinary exit
124, host cancellation with the timeout flag, and action cancellation after the
process has completed. All ten outcomes verify empty output manifests, absence
of partial output bytes from CAS, stable repeated polling, and process closure.
The new group passed in 27 ms. This test controls host results; it does not rerun
the expensive upstream Native link or claim its successful completion.

Retained commands, controlled environment, source hashes, logs and binary hash:
`/private/tmp/kvik-native-timeout-diagnostic/.kvik/tests/native-timeout/`.
The source-build driver there invokes each compiler/tool through the existing
invocation-owned bootstrap guardian and performs no consumer compilation:

```sh
cd /private/tmp/kvik-native-timeout-diagnostic
python3 .kvik/tests/native-timeout/run.py
# Or rerun the already-built Native suite:
.kvik/tests/native-timeout/unit.kexe
```

No core/schema, graph, timeout policy or test-report semantics changed.
