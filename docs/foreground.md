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
