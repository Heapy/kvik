# Source graph launcher failure and concurrency gate

`tools/test_source_graph_lifecycle.py` copies the approved full source graph
fixture's exact trusted launcher, helper-v2 input lock and verified source cache
into an isolated case. It invokes that captured production launcher offline with
real pinned compiler installations and the existing artifact cache. It does not
modify the approved source/bootstrap fixture or replace tool runners. Default
invocation only prepares copies; `--run` requires a coordinated compiler slot.

The actual gate is designed to perform four successful complete runtime builds
and one interrupted Native compiler invocation:

1. Two same-key cold launchers run concurrently against one empty runtime cache.
   Both must return the same Native runtime/plugins, with exactly one building
   event, one built event and one cache-hit event. A process/staging observer
   retains the actual owned process tree and command receipts and requires one
   staging directory with one Native compiler command.
2. A new empty runtime cache runs until the actual `MainKt konanc` Java process
   and matching command receipt are observed. SIGTERM goes only to the launcher
   owner. All observed owned processes must stop, the exact runtime destination
   and staging must be absent, and `--no-build` must fail. A normal retry builds.
3. A private valid cache copy has one executable byte changed without changing
   its size. `--no-build` must fail with empty stdout; a real build repairs it,
   preserving the corrupt publication in quarantine.
4. Independently, a private valid cache copy has one source-input receipt byte
   changed. The same no-build refusal and real repair must occur.
5. A separate trusted-launcher copy pins an incompatible Kotlin/Native component
   identity. Desired composition must change, no-build cannot reuse the old key,
   and an attempted build must fail installed-tool verification before a new
   runtime/staging publication. The original runtime cache inventory must remain
   unchanged.

Successful publications receive the existing full source-bootstrap audit of
source graph, exact roles, input/command receipts, JVM worker/Native source argv,
ordered libraries and manifest-bound files. Full argv, constructed environment,
stdout/stderr identities, process observations and phase outcomes are retained
in each case's `summary.json`. The original copied bootstrap and source-cache
inventories must remain unchanged. The observer polls at 100 ms and records only
observed processes; it does not claim an exhaustive kernel process trace.

The final tool-mismatch case changes the *trusted expected identity*, which is a
composition input. It does not claim warm cache hits rehash installed tools: the
launcher intentionally permits a valid cached runtime with absent compilers.
All sources/tool roots remain caller-controlled and stable through each attempt.
This gate does not establish bit-reproducible Native output, cross-machine
portability, crash consistency under power loss, or all K14 requirements.

## Preparation

```sh
python3 -B tests/source-lifecycle/test_harness.py
python3 -B tools/test_source_graph_lifecycle.py --help
python3 -B tools/test_source_graph_lifecycle.py
```

Four lightweight predicate tests pass. Preparation succeeds without compilation
in `.kvik/tests/source-lifecycle/case-og3pe4ew`. The predicates reject duplicate
build events, mixed hit/build evidence, stale composition/backend/plugin values,
and unrelated process-tree membership; these tests are not compiler evidence.

Actual execution, after freeze and slot grant:

```sh
python3 -B tools/test_source_graph_lifecycle.py --run \
  --case /private/tmp/kvik-k14-source-lifecycle/.kvik/tests/source-lifecycle/case-og3pe4ew
```

A case containing `summary.json` cannot be resumed/overwritten; use a new case
for a fresh attempt.

## Actual full lifecycle result

Frozen harness `f1df6a40a73a747e7063762ab153f59c6d1695f0` ran unchanged on
2026-10-22 with:

```sh
cd /private/tmp/kvik-k14-source-lifecycle
python3 -B tools/test_source_graph_lifecycle.py --run
```

Handle `76479` completed with exit 0. All 13 launcher commands and five process
monitors are retained in
`.kvik/tests/source-lifecycle/case-ml9uwoxw/summary.json` (SHA-256
`9bf454e0cb4b48eaa9038c33414213bbea79352b48c2a1b1ab3f36da75a90158`).
The harness identity is
`463e68d7b74bd3e07066bd53bdad9120b8ac36f9cf9d2bd27adf40c1b969a687`
(16,712 bytes), independently rechecked along with both imported helper identities
and every retained stdout/stderr digest after completion.

| Phase | Actual result | Wall seconds |
| --- | --- | ---: |
| Concurrent cold owners | Both exit 0; one build, one hit | 74.867 / 74.866 |
| Interrupt actual Native compilation | Owner exits 130; no publication/staging | 39.320 |
| Interrupted cache, no-build | Refuses with exit 2 | 0.118 |
| Interrupted retry | Full build and runtime-info pass | 77.544 |
| Corrupt executable, no-build | Refuses with exit 2 | 0.088 |
| Executable repair | Full build passes; corrupt bytes quarantined | 73.255 |
| Corrupt source receipt, no-build | Refuses with exit 2 | 0.094 |
| Receipt repair | Full build passes; corrupt receipt quarantined | 71.319 |
| Incompatible tool identity, no-build | Refuses with exit 2 | 0.073 |
| Incompatible installed-tool verification | Refuses with exit 2; no compile/publication | 9.707 |

The successful composition is the approved helper-v2 identity
`7db70e5516292ccfe1fefc2c015885beac0db801e8f3730425b78c976bdb9cae`.
All successful runtime-info calls report the Native backend and both added
plugins expose `source-v2`. The mismatched trusted Native identity changes the
composition to `4f0f02e60ec1d8cef215d1c7c5bc3c666f5589c497b1e415ee1851dd57521bf8`;
the original executable directory inventory stays unchanged in that phase.

Each successful build passes the complete existing publication audit: eight
actual build commands and 92 exact composition inputs, including the full
selected Kvik runtime, both plugins, their shared transitive helper and pinned
Native serialization closure. There are exactly four complete successful builds
and one interrupted Native compiler invocation. The cold concurrent pair records
one staging directory and one Native command, with exactly one building/built
pair and one cache-hit event. Its 38 observed processes all stop.

SIGTERM is delivered 38.853 seconds into the interrupted attempt, after observing
`org.jetbrains.kotlin.cli.utilities.MainKt konanc` and its exact recorded argv.
All 37 observed owned processes stop; the owner exits about 0.468 seconds after
the signal. The three subsequent successful build monitors also report every
observed owned process stopped (41, 38 and 35 observed PIDs). Only the owner is
signalled; the production guardian owns descendant cleanup. These counts include
launcher, guardian and intermediate tool processes, not just compilers.

The successful executable file hashes differ across the four builds, consistent
with the earlier Native output-path/nondeterminism limitation. They are retained
in each manifest and audit; this gate asserts correctness/identity checks and
atomic publication, not deterministic executable bytes. The copied trusted
launcher and source cache inventories remain byte-identical to preparation.
No production fix was needed.

An initial attempt in `case-og3pe4ew` stopped immediately because the restricted
execution environment denied `/bin/ps`. Its two just-started owners were cleaned
up; no compiler stage was reached. That failed attempt remains retained. The
successful fresh attempt used the granted process-observation permission; no
harness source changed between attempts and no failed case was overwritten.

## Independent retained-evidence audit

A separate reviewer approved frozen `f1df6a4` and `d01f324` after rehashing all
13 command logs, four complete runtime publications, 32 build commands and
368 source-input records. The review checked exact Native/worker arguments and
ordered libraries, JVM bundles, composition/source graph, corrupt quarantines,
repairs and unchanged trusted/source-cache inventories. Actual retained compiler
interruption observations, exit 130 and subsequent no-build refusal support the
interruption result. Four lightweight harness tests passed again. Report:
`/private/tmp/kvik-source-lifecycle-review-olzjoq6q/report.json`; script:
`/private/tmp/kvik-source-lifecycle-independent-audit.py` (SHA-256
`248b48e98cbf3a0dfdf06d70a550b15fe4dffb4269f5c3d7ee64988dfaa63ecb`).
No compiler rerun was needed; sampled process observations and Native binary
nondeterminism remain the documented limits.
