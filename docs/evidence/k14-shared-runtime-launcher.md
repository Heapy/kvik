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
