# Foreground development

Start a library session with the same project, module, target and tool selections used by `build`:

```sh
./kvik -- dev --project kvik.json --module library --target jvm --tools tools.json
```

Press `r` to rebuild, `t` to run fresh tests, `q` to exit and `?` for status. File changes queue the same operations. For an application target, add `--mode application`: `r` prepares and starts a replacement application. A failed replacement leaves the previous application running and marks its inputs stale.

Agents can add `--control jsonl` and send newline-delimited commands:

```json
{"schemaVersion":1,"requestId":"test-1","command":"t"}
```

The session emits structured events and retains operation results under `.kvik/sessions`. Closing input closes the session. Ctrl-C and owner loss also stop its owned workers and applications.

A preparation worker retains up to two parsed project models and three action graphs for this session. Every operation still captures current inputs, replays the dependency lock and verifies tools. Source, configuration, lock, tool or runtime changes invalidate the matching graph. Reusing the graph does not reuse test execution.

Workers belong to the foreground session; independent invocations share disk artifacts. Runtime source changes build a new Kotlin/Native executable and explicitly replace the session after its owned work has stopped.

To follow a project reference in Kode, run the session from a local working directory:

```sh
./kvik -- dev --kode /path/to/kode --kode-home /path/to/store \
  --source-project my-library --revision main --project kvik.json \
  --module library --target jvm --tools /path/to/tools.json --control jsonl
```

The observer resolves the reference at each watch interval. Each build or test
receives the observed immutable revision, so later edits cannot change a run's
inputs. Repeated changes are coalesced before the next operation. `inputs-observed`
events include the source revision; run results retain the same source provenance.
Reference, configuration or provider failures make the observation unavailable and
hold queued work until a valid revision is observed. No local source checkout is
used as a fallback. `r`, `t`, application reload and runtime replacement keep the
same behavior as filesystem sessions.

`--project` and `--lock` identify paths inside the source revision (the lock is
relative to the configuration directory). Tool, cache and output paths resolve
against the local working directory; session results live in its `.kvik/sessions`.
The observer watches the full revision, including changes outside the selected
module. Offline sessions require an immutable revision and previously cached
source data; following a moving reference requires access to Kode.

The public acceptance runner `tools/test_foreground_applications_cli.py` retains
performance measurements alongside its JVM and macOS Native reload, test and
cleanup checks. Pass `--host-name` to identify the measured hardware. Each initial
build receives 20 JSONL status requests; matching request IDs bind send and receive
timestamps. The results report p95, maximum latency and whether every response
meets K01's 100 ms budget. Warm reload timing includes old-application cleanup and
replacement launch, with compilation actions required to be local cache hits.
Graceful shutdown and owner-loss cleanup report their 2 s and 5 s budgets
separately from functional success.

The runner also kills each target's application process while its foreground
session remains alive. It checks exit 137, retained failure events, descendant
cleanup and responsive status, then explicitly reloads with unchanged preparation
keys and local cache hits. Readiness requires the new application's complete,
unique effect record bound to its owned PID and execution directory. Closing
stdin after recovery must produce `end-of-input` in the session journal and leave
no captured owned processes. These checks cover real JVM and macOS Native
applications; they do not substitute for compiler-worker failure tests.

Each session retains `process-samples.jsonl` and `measurements.json`. Process counts
and RSS cover the live session owner and reachable descendants throughout builds,
applications, tests and fixture witnesses. The maximum observed owner RSS is
separate from summed descendant RSS. Sampling can miss short-lived processes and
memory peaks; summed RSS also counts shared pages repeatedly. These measurements
do not establish physical-key/TTY latency or isolated idle-runtime memory.

`tools/test_foreground_keys_cli.py` measures the terminal path separately. Supply
the exact runtime, its complete source checkout, a passed Native consumer fixture
and `--host-name`. It sends 20 serial `?` bytes through a PTY during each of the
initial build, source-plugin rebuild and replacement session's fresh test, plus one
before Ctrl-C. Reader timestamps bind every acknowledgement to the preceding key;
rebuild samples must fall between that session's rebuild-started/rebuild-finished
events. Reports retain every sample, p95 for the 20-sample phases, and whether
every response is below 100 ms. Graphical terminal rendering is outside this scope.

The same run measures `q` and Ctrl-C through owner exit, complete terminal-state
restoration, output-reader completion and captured owned-process absence against
the 2 s graceful-shutdown budget. Functional and budget outcomes are separate;
`--strict-budgets` exits 3 on a budget miss. Partial summaries and raw streams stay
under `.kvik/tests/foreground-keys/cli-*`, including on failure. The C inspector
comes from the supplied source checkout; the runtime uses its bundled guardian.
