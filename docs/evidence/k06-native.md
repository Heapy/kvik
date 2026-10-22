# K06 Native JVM action bridge evidence

Host: macOS ARM64, Kotlin/Native 2.4.20, JDK 25.0.4-librca. The bridge is a Native `ActionExecutor`; compiler and test JVMs are foreground-owned one-request tools through K03. No Gradle, Kotlin Toolchain consumer build, JVM orchestrator or daemon is invoked.

Reproduce from the repository root:

```sh
python3 tools/test_jvm_native.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --compiler-artifacts /Users/yoda/dev/pet/kvik/.kvik/k01/artifacts
```

Optional `--jvm-artifacts <directory> --offline` seeds the pinned serialization/JUnit jars without downloading. The observed offline run used `/private/tmp/kvik-k03-execution/.kvik/tests/jvm/artifacts` read-only. Artifact acquisition reuses the reviewed K06 direct/pinned helper, checks all lock digests, and compiles worker/core sources with the pinned compiler. It does not run a nested build tool. Outputs stay under `.kvik/tests/jvm-native` in this worktree.

The harness records full JDK file/link inventory, the complete worker class tree, bootstrap jar digests and the canonical compiler manifest, and checks immutable tool inputs before/after execution. Its provider uses that harness-verified local snapshot. This is fixture integration evidence, not a production implementation of installed-tool/bundle verification; K14's provider must perform fresh verification before accepting paths. The compiler worker also verifies its manifest and implementation bytes on every request.

Five Native unit tests passed, covering result identity/exit/count/scope consistency, typed input/options rejection, bundle identity sensitivity, modified action arguments/environment and empty test classpaths before launch, cancellation after successful process completion, and compiler result/request/exit/output consistency. Review regressions also reject an `incomplete` result containing a failed test or container/infrastructure diagnostic, and reject a `failed` result supported only by an incomplete diagnostic. Valid failed and zero-test incomplete outcomes retain their distinct semantics.

The real `SchedulerRun` graph compiles common `expect` plus JVM `actual` sources into `library`, then a serialization-plugin consumer into `consumer`, then test sources into `test-classes`. Prerequisite output manifests become verified dependent input trees. The scheduler admits two workers within a 4096 MiB declared budget; compiler requests reserve 2048 MiB, tests 512 MiB. This is scheduler accounting, not a JVM RSS guarantee.

Two identical passing test actions have equal keys but execute in separate fresh JVMs/action directories. Each records three JUnit IDs, two executed passes (including a dynamic test), one skip, and its own actual `effects.txt` write. Their IDs match across invocations. The assertions execute the serialization-generated consumer and actual JVM platform implementation.

Additional real worker outcomes are retained in `report.json`:

- Source compilation error with `src/Broken.kt`, line 2, column, and structured compilation diagnostic.
- Failed assertion with one concrete failed identity.
- Zero tests, a disabled class and assumption-aborted setup remain incomplete; they cannot pass on exit alone.
- A JVM test writes truncated result JSON then exits zero; another exits zero without any result. Both produce structured invalid-result diagnostics and incomplete test evidence.
- A slow JVM test writes `started.txt`; the Native owner cancels it and confirms `finished.txt` was never written. Action/test statuses are cancelled with unknown discovery and zero accepted execution records.
- A 300 ms test-worker timeout remains failed/incomplete with unknown discovery.

The durable scheduler journal contains one start and finish for each of 12 graph nodes, contiguous sequence numbers and action-only events. Cancellation and timeout are additional direct executor probes, yielding 14 retained observations total. Raw worker stdout/stderr and all requests/results remain in isolated action directories. Compiler failure/cancellation cannot produce successful output manifests; test artifacts never enter cache paths.

Independent JSON Schema checks passed for all 24 events, 14 action results, diagnostic/test records and every valid persisted worker request/result against the shared core and K06 adapter schemas. Deliberately truncated and missing results were checked through the bridge's rejection path. Final pinned extra-artifact hash checks and Python syntax compilation also passed.

The real suite exercises the small multi-module fixture, not the full unchanged kotaml graph. Prior K06 JVM worker evidence for 2038 tests is not recast as Native scheduler evidence. No remote portability approval or full CLI verification is claimed. K03's documented guardian limitations still apply; this slice adds adapter cancellation tests and does not repeat its entire parent-death/process-tree matrix.

Independent review reproduced two boundary inconsistencies: a failed test could be labeled incomplete, and an empty runtime classpath reached the worker despite violating its protocol. After the fixes, the unchanged original review probes were rebuilt and rerun. The contradictory result is now rejected, and empty classpaths fail during typed selection construction before any request, worker log or result is created. Both fixes were verified before integration; neither original issue could produce a false passing result.
