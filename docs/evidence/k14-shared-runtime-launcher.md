# Shared local runtime store integration

The bootstrap accepts `--runtime-store PATH` for complete K04 runtime bundles.
The path is invocation policy and does not change composition identity. Separate
materialized runtime caches coordinate through one shared composition lock, so a
second owner restores the first verified publication instead of compiling it.
Both legacy local compositions and source graphs use this path. Native output
nondeterminism remains explicit: an already valid shared publication is retained.

A shared hit is copied into private staging, checked by the existing full runtime
bundle verifier, and published only after observing the desired composition again.
The final launch check also runs after waiting and verification. `--no-build` can
restore but never acquire sources or compile. Ordinary storage errors preserve a
valid record; intrinsic output aliases or unrepresentable names, and semantically
invalid runtime bundles, quarantine their exact record before repair. Store roots
cannot overlap selected sources, other bootstrap caches, explicit compiler/data
installations, the selected SDK, or Xcode toolchain.

Foreground descriptors retain the optional store path through query, replacement,
and resume. Their bound is now 18 bootstrap arguments. Ten actual Kotlin/Native
producer/consumer contract tests passed in
`.kvik/tests/foreground-bootstrap/case-c17d3ytr`; the retained receipt binds the
Python producer function, Native source files, compiler command and test log.
This contract test does not establish a live shared-store foreground handoff.

Python launcher policy and restoration-boundary tests cover read-only identity,
path overlap, literal argument policy, source changes after actual materialization,
invalid tree recovery, and preservation on temporary storage errors. Synthetic
bundle validation in boundary tests is explicitly stubbed; actual compilation and
restored execution require the gate below.

`tools/test_shared_runtime_launcher.py` prepares a private current-source Kvik
node with the approved source graph's remote plugins/helper and cached KLIBs. It
is designed to run two concurrent owners with distinct empty materialized caches,
require exactly one build and one shared hit, validate complete compiler/input
receipts, and execute restored runtimes without compiler, artifact, or source
caches. It then challenges same-size/mtime blob corruption and a digest-valid
wrong runtime lock, requiring precise refusal and repair from a valid local copy.
Preparation and actual results are recorded separately; preparation is not a
successful runtime gate.

Reproduction (requires the approved retained source fixture):

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_shared_runtime_launcher.py \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --artifact-cache /Users/yoda/dev/pet/kvik/.kvik/tool-artifacts
```

Add `--prepare-only` to validate private inputs and the read-only composition
query without starting compilers. Every invocation uses a new retained case.

## Actual source-build and restoration result

Frozen `146e6bd` passed the full gate with exit 0 (handle 28444) in
`.kvik/tests/shared-runtime-launcher/case-3c9o7kim`. Its summary SHA-256 is
`2e3be01995dabc1f09fdaf922b6c33c80047ccc3c9ff110064fb1a98952b5990`.
Composition: `cf0d21ddfa0d0e5c19e684e72c148489cca8132fd5f82871122cc1f53b17f5f7`.

Two concurrent launchers using distinct empty materialized caches completed in
82.305 / 82.418 seconds with exactly one actual build, one shared publication and
one shared restoration. The build reports 49.350 seconds of compilation work;
its eight retained compiler commands consume 92 declared source/library/metadata
inputs, including 76 Native and 14 worker Kotlin files and ordered Native KLIBs.
The full bundle contains 122 files totaling 18,889,784 bytes. Both source plugins
report helper-v2 through actual Native runtime-info execution after restoration.

The first independent empty-cache restoration took 367 ms with --offline and
--no-build, nonexistent Java/Native homes, and absent source/artifact caches.
Every returned descriptor identifies its own requested materialized directory.
Restored files are copies, not hard links to CAS blobs. Runtime-info executes the
restored executable successfully; no compiler runs on these restore paths.

A same-size/mtime executable-blob corruption caused exact no-valid-runtime refusal
(exit 2, empty stdout). A valid local bundle repaired the store without compiling,
and a new destination restored and ran. A separately injected digest-valid but
wrong runtime lock likewise failed full runtime verification. Its exact record
bytes were quarantined, a valid local copy repaired the record, and another fresh
destination restored and ran. All trusted bootstrap/local-source/source-cache
inventories and the input source lock remained unchanged.

Independent review approved the frozen gate after rehashing all 13 command logs,
five identical full materializations, 123 CAS blobs (including the injected bad
lock), canonical record/composition, source/acquisition bindings, exact compiler
arguments/library order, worker bundle and 15 pinned JVM artifacts. Report:
`/private/tmp/kvik-shared-runtime-independent-uwxtncs_/report.json`; script:
`/private/tmp/kvik-shared-runtime-independent-audit.py` (SHA-256
`7d7ff9fee55d266ff733f84a0f5d8d8b61ecebbe58ab701752643fb05f86a5a6`).
No additional compilation was needed for that review.

## Restored JVM worker and consumer

The unchanged `tools/test_build_cli.py` then ran against the runtime restored in
`runtime-c`, explicitly selected with `--runtime`. Handle 24757 completed with
exit 0; evidence is `/private/tmp/kvik-shared-runtime-consumer-evidence/case-_8b8go30`.
The consumer compiler tools were explicitly provisioned for this separate run;
compiler-free runtime restoration does not imply compiler-free consumer builds.

Cold multi-module JVM compilation passed in 8.670 seconds. First tests passed in
4.331 seconds with both main compilations reused; repeated tests passed in 1.479
seconds with all three compilations reused and a fresh test execution. Both runs
report exactly `[engine:junit-jupiter]/[class:fixture.PassingTests]/[method:dependencyAndResource()]`,
with distinct execution effects. Three journals and completion bindings, eight
output blobs, exact test reports, four rejected journal mutations and the final
added-source stale query were independently checked by
`/private/tmp/kvik-shared-consumer-independent-audit.py`; report:
`/private/tmp/kvik-shared-consumer-independent-audit.json`.
The fixture intentionally retains the added source from that final stale probe.

This evidence covers same-host local CAS restoration of the full runtime bundle.
It does not claim cross-machine Native reproducibility, remote executable reuse,
or a live foreground handoff specifically between shared-store materializations.

## Integration review

Independent cache review approved storage commits `e8c1005` / `3fd8191` and
adapter `146e6bd`. It reran 15 Python storage groups and eight actual Native
interoperability calls without recompilation, checked their controlled compiler
launch receipt, and reproduced the original invalid-tree/tool-overlap failures
against the fixes. It then reran all 13 launcher policy/boundary tests and the
retained ten Native descriptor/resume tests after checking their exact inputs.
The launcher file changed after the Native contract build, but its descriptor
producer function and all consumed Native/schema/generated sources stayed equal.
Reports: `/private/tmp/kvik-runtime-store-receipt-audit-jwwyvrkl/report.json` and
`/private/tmp/kvik-runtime-fix-review-tr41fbgk/report.json`. Sixteen source-launcher
regression groups and seven legacy identity groups also passed before integration.

After serial integration, an independent hash comparison confirmed all 18 files
changed by the approved candidate match main `04e503d` byte for byte; the user
PLAN.md edit was preserved. Report:
`/private/tmp/kvik-runtime-integration-review-tzanv2h8/report.json`.
