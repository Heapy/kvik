# K09: Composed Native build and test commands

The Kotlin/Native CLI connects captured project inputs, explicit dependency locks, the pure
project graph, the owned scheduler, JVM/Native adapters, local compilation caching and
per-check verification records. This is one-shot orchestration; K15 foreground sessions,
remote compiler reuse and the full real-library CLI validation remain separate gates.

## Invocation and tool installations

Resolve explicitly before execution. `build` and `test` never resolve dependencies or repair
missing locked blobs. Both require `--tools`, at least one `--module` and one
`--target` (`jvm` or `macosArm64`). `--project` defaults to `kvik.json` in the current directory.
Repeated selections request their Cartesian product.
Prerequisite modules are built automatically. `--jobs` defaults to 2; `--memory-mib` defaults
to 4096. `--runs` defaults to `.kvik/runs` and `--store` to `.kvik/cache`, relative to the
project root. Explicit alignment `--pin` options must match resolution.

The `--tools` file conforms to `toolInstallation` in `schemas/v1/contracts.json`:

- `schemaVersion`: 1.
- `toolLock`: the complete trusted installed-component lock, initially
  `bootstrap/toolchains.lock.json` for the provisioned development host.
- `roots`: absolute paths keyed by `jdk`, `kotlinNative`, `llvm`, `libffi`, `sdk` and
  `xcodeToolchain`.
- `konanData`: absolute Kotlin/Native dependency directory, containing `dependencies/`.
- `compilerArtifacts`: absolute directory of checksum-pinned JVM compiler artifacts,
  initially the checkout's `.kvik/tool-artifacts`.

The two real CLI harnesses below generate an example from their explicit installation
arguments. These are trusted installation pins, not paths discovered from ambient `PATH`.
All six installed inventories are freshly verified before graph execution, including cache
hits. Providers verify tools again before uncached actions. This currently has substantial
synchronous cost and is not suitable for a responsive K15 event loop. Installations and their
ancestors must remain stable during verification and compiler use; see
[the verifier's exact boundary](k09-toolchains.md).

JVM test targets require explicit `testSettings.classes`; `engines` defaults to `["junit"]`
and can include `kotest`. The graph translates `junit` to the JUnit Jupiter engine identity.
An empty class list does not constitute successful verification. The bundled JUnit platform
is 1.13.4/Jupiter 5.13.4; arbitrary framework versions are not implied compatible.

## Retained evidence

Each invocation creates a unique run directory. Before actions start it retains exact project
configuration and lock bytes, the input manifest, installation configuration and run metadata.
Resolved action specifications, keys and dependency edges are retained under `actions/`, even
on cache hits. Executed-adapter observations under `observations/` link action IDs to owned
directories, logs, diagnostics and test reports. Compiler/test output is captured separately
from the stdout event stream. Diagnostic events carry categories and affected actions when
available.

The fsynced `events.jsonl` and stdout share globally increasing sequences. `verification.json`
records requested/completed checks, action outcomes, concrete test identities/counts,
composition/tool/configuration/lock/input digests, freshness and reproduction arguments.
Only successful prerequisite closures with complete, positive executed-test evidence satisfy
test checks. Compilation hits are recorded as `local_hit`; test actions remain `executed`.
Skipped/zero/incomplete discovery cannot manufacture a passing check.

Final publication writes the verification record, appends the final event, and writes a
`completion.json` digest marker binding the entire journal before printing `run-finished`. A publication failure after
run start reports only to stderr and exits unsuccessfully. It cannot leave an apparently
committed successful query. This is a consistency protocol for trusted local evidence, not
cryptographic authentication against a writer who can replace the run directory.
Retained evidence documents and the complete journal are currently bounded to 16 MiB each;
an oversized journal cannot publish a successful completion marker.

`verification --run PATH` is read-only. It validates final publication, rescans declared
source/resource inventories including untracked additions, checks configuration and lock,
compares runtime composition and freshly verified tools, then rescans inputs after tool
verification. Results distinguish `current`, `stale` and `indeterminate`. Snapshot scans
detect ordinary concurrent edits but are not atomic filesystem snapshots; the snapshotter's
stable-ancestor and observation bounds still apply.

Exit statuses: passing/current 0, executed failures 1, invalid configuration 2, incomplete
coverage or stale/indeterminate query 3, cancellation 130. Missing tool installations produce
incomplete coverage. SIGINT/SIGTERM latch cancellation; the scheduler owns child cleanup.
Synchronous setup/tool scanning delays observing the latch until that operation returns.

## Reproduction

On the explicitly pinned macOS ARM64 host, with the current composition built:

```sh
.kvik/schema-tools/bin/python tools/test_native_build_cli.py \
  --java-home "$JDK" --konan-home "$KONAN"
.kvik/schema-tools/bin/python tools/test_build_cli.py \
  --java-home "$JDK" --konan-home "$KONAN"
.kvik/schema-tools/bin/python tests/bootstrap/test_dependency_cli.py
.kvik/schema-tools/bin/python tests/bootstrap/test_inspection.py
.kvik/schema-tools/bin/python tools/check_schemas.py
cc -Wall -Wextra -Werror -Iexecution/platform tests/execution/InterruptProbe.c \
  execution/platform/process.c -o .kvik/tests/interrupt-probe
.kvik/tests/interrupt-probe
```

Harness directories under `.kvik/tests/` retain commands' events, errors, run directories and
summaries. The Native harness exercises two modules, common/actual declarations, internal
test access, repeated compilation hits with test execution, a failing test, zero tests and
stale evidence after a source edit. The JVM harness exercises two modules, common/actual
declarations, a transitive module dependency, resources, JUnit, repeated compilation hits
with an observable per-run test effect, and stale evidence after an added source. Results
and outstanding checks are recorded below only after execution.

## Observed results

Composition `7ada2005c1616eadf1a019672e8bcf88fed7e9b174ebc1ff0a42cf74f38af724`
compiled a Native library and linked/executed `fixture.AnswerTest.answerIsCorrect` through
the CLI. All three actions executed, one test passed, and the final record reported current
inputs and a completed `library:macosArm64:test` check. Retained run:
`.kvik/tests/cli-native/case-gki_vdpd/.kvik/runs/kvik-run-HS2wRF`.

The same composition passed all eight dependency CLI tests (6.630 seconds), six inspection
tests (1.100 seconds), and all eleven schema examples. The C interruption probe validates
SIGINT/SIGTERM latching, installation ownership, reset and restoration of prior handlers.
The two-module Native fixture at `.kvik/tests/native-build-cli/case-xxd0ggx4` passed its cold
test in 191.380 seconds and repeated test in 64.065 seconds under concurrent development
load. The repeated run reused all three compilation actions and executed the test again,
with its marker present in that run's independently retained test stdout. These timings
include repeated full tool verification and are not isolated performance benchmarks.
The same harness then changed only the expected assertion: the next run failed with one
executed/failed test (112.745 seconds) while both production compilations remained hits.
Replacing the test with a helper and no test declarations produced complete discovery of
zero tests, incomplete verification and exit 3 (109.849 seconds). The original passing
record became stale after the test source changed. Every run's events and verification
record passed schema validation; action traces were retained for every hit and execution.

Independent probes under `.kvik/tests/cli-adversarial/review-ku1ua414` passed on the same
composition: missing SDK produced incomplete/exit 3; a pre-created verification destination
caused exit 1 with event-only stdout and no final success event; an input addition during
tool verification produced stale/exit 3; SIGINT after the final compilation event produced
cancelled/exit 130. All stdout events were schema-valid and contiguous. The signal probe
covers that exercised timing, not arbitrary interruption schedules.
The permanent `tools/test_build_failures.py --runtime PATH --fixture PATH` harness reran
all four successfully against a separate copy at
`.kvik/tests/build-failures/review-jbklib7i`. It accepts a one-module Native fixture with a
retained passing run from that runtime and never changes the supplied fixture.

Independent review also reproduced acceptance of a corrupted earlier journal prefix while
the final event was intact. Completion now binds every journal byte; prefix corruption,
middle deletion, reordered history and final-line-only truncation have permanent regression
probes in `tools/check_run_journal.py`.

Rebuilt composition `4ac29e82e2d0632cabc2f880e59863541b858dec8707b829ded2d60870a0b0e0`
includes the journal fix and [explicit JVM variant policy](k09-jvm-variants.md). Construction
reported 41,033 ms. It passed eight dependency CLI tests (6.726 seconds) and six inspection
tests (1.348 seconds). An independent fresh Native run explicitly re-resolved its lock under
policy v2, built and passed one test, then passed all four journal mutation probes and the
original corrupted-prefix reproduction. A pristine verification query returned current/0
(31.19 seconds), while the corrupted-prefix query returned indeterminate/3 before tool
hashing (8 ms). Evidence is under
`.kvik/tests/cache-cli-review/journal-fixed-7uu04ss8`.

The complete JVM harness passed on that rebuilt composition at
`.kvik/tests/build-cli/case-2kmpj9al`: public resolution selected six artifacts with seven
metadata documents; cold two-module build took 95.411 seconds; first test took 107.622
seconds; repeated test took 56.476 seconds. Both test invocations executed the selected
JUnit test and wrote their own effects file. On repeat, all three compilation actions were
`local_hit` with unchanged keys and the test was `executed`. Resource loading and the
common/actual module dependency were asserted inside the test. All events, retained records
and queries passed schema validation; all four journal corruption probes rejected damage;
adding an untracked source made retained verification stale/3. Timings include full tool
verification and concurrent development work and are not performance acceptance results.

The full unchanged kotaml CLI fixture needs further dependency-model work: runtime-only
external edges, separate test compiler options, exported/private module dependencies and
scope-specific resolution (the reference selects different annotations versions for
production and tests). Its preparation harness verifies all pinned source files and test
selectors but deliberately does not claim execution through a widened classpath or a
global version substitution. Adapter-level 2,038-test evidence remains separate from
composed CLI parity. These observations do not establish the complete K10 gates.
