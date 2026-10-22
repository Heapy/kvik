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

Actual result pending; preserve failed attempts rather than resume/overwrite a
case containing `summary.json`.
