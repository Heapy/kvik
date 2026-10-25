# MCP project queries, builds and tests

Start Kvik as an MCP stdio server for one local project:

```sh
./kvik -- mcp --project /absolute/path/to/kvik.json
```

An MCP client's server configuration can launch the source distribution directly:

```json
{
  "mcpServers": {
    "kvik": {
      "command": "/absolute/path/to/kvik/kvik",
      "args": ["--", "mcp", "--project", "/absolute/path/to/project/kvik.json"]
    }
  }
}
```

The launcher uses the cached Kotlin/Native composition, building it when needed.
Launcher diagnostics go to stderr. The native process reserves stdout for MCP
messages. Closing stdin, terminating the server or losing its parent stops its
owned processes, including active compilers and tests. There is no background
service or listening socket.

The connection implements the [MCP stdio transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
and negotiates protocol version `2025-11-25` or `2025-06-18`. Initialize the
connection, send `notifications/initialized`, then use `tools/list` and
`tools/call`. `ping` and `notifications/cancelled` are supported.

| Tool | Inputs | Result |
| --- | --- | --- |
| `kvik_inspect` | None | Current project model, source ownership, targets and effective settings from `inspect` |
| `kvik_config_validate` | None | Configuration, project graph and configured resolver policy validation; no lock check or repository access |
| `kvik_dependencies` | Optional `modules`, `scripts`, `targets`, `compilation`, `usage`; `availableVersions` enables `packages`, `channel`, `limit`, `cursor`, `refresh` | Declared and locked dependency graph, optionally available versions |
| `kvik_package_versions` | `package` as `group:artifact`; optional `channel`, `limit`, `cursor`, `offline`, `refresh` | Published versions with repository observations and freshness |
| `kvik_package_dependencies` | `coordinate` as `group:artifact:version`, `target`, `usage`; optional `jvmTarget`, `pins`, `offline`, `refresh` | Dependency requirements and supported resolved selections |
| `kvik_verification` | `run` | Retained checks, outcomes and test counts, with current input freshness |
| `kvik_explain` | `run`, `action` | Historical action inputs, dependencies, result and cache decision |
| `kvik_run_stats` | `run` | Historical action counts, timings, CPU/memory observations and remote transfers |
| `kvik_session_status` | `session` | Foreground state and current owner liveness |
| `kvik_session_events` | `session`; optional `cursor`, `limit` | Paginated foreground events, completeness and owner liveness |

To enable execution tools, configure the server with the same pinned tools file
used by CLI builds:

```sh
./kvik -- mcp --project /absolute/path/to/kvik.json --tools /absolute/path/to/tools.json
```

| Additional tool | Inputs | Result |
| --- | --- | --- |
| `kvik_build` | Nonempty `modules` and `targets` arrays | Build result, verification record, diagnostics and retained output paths |
| `kvik_test` | Nonempty `modules` and `targets` arrays; optional `coverage`, `coverageExcludes` | Fresh test execution with eligible compilation cache reuse, counts and retained output paths |
| `kvik_verify` | Nonempty `modules` and `targets`; `since` baseline directories or `full: true`; optional `checks`, `planOnly`, `coverage`, `coverageExcludes` | Affected-check selection, omission reasons and execution results |

These tools execute project code, including compiler plugins, generators and
tests. They use the CLI executor, configured dependency lock, cache and resolver
policy. Coverage is available for JVM-only test selections. Each call selects
the cross product of its modules and targets. For example:

```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "method": "tools/call",
  "params": {
    "name": "kvik_test",
    "arguments": {"modules": ["library"], "targets": ["jvm"]},
    "_meta": {"progressToken": "test-library"}
  }
}
```

Build/test results have `kind: "mcp-build-result"`. `complete` means a committed
run was read successfully; a complete run can still fail. `isError` is false
only when the process exits successfully and the committed verification passes
with current inputs at completion. Inspect `verification`, `diagnostics` and
`diagnosticsTruncated` for details. A preflight failure may have no verification
record or run directory. Use `kvik_verification` on `runDirectory` to check
freshness after subsequent edits.

The server retains `request.json`, stdout and stderr in `requestDirectory`, with
the build run under its `runs` subdirectory. Result fields `stdout` and `stderr`
are paths to these logs. The default parent is `.kvik/mcp-runs`; `--runs PATH`
selects another project-relative or absolute parent. Output must be separate
from project inputs, configuration, locks, tools and caches. Retained results
remain after cancellation or connection closure and are not automatically pruned.

Supply a unique string or integer `_meta.progressToken` to receive progress
notifications. The initial notification includes the request directory at
`params._meta["kvik.dev/requestDirectory"]`; subsequent notifications arrive
approximately every five seconds. The progress counter is a heartbeat, not a
completion percentage. `notifications/cancelled` stops the request's owned
processes and suppresses its final response. The directory received in progress
remains available for inspecting partial output.

`kvik_verify` exposes the CLI's change-aware verification. `checks` defaults to
`["test"]` and can contain `build`, `test` and `abi-check`. `since` accepts up to
32 retained build/test/ABI run directories, absolute or relative to the configured
project directory. Supply `executionRuns[].directory` from a previous verification
as baselines; the verification selection directory itself is not a baseline.
Compatible baselines can omit completed, unaffected checks. Invalid baselines or
uncertain impact widen selection. `full: true` selects every requested check.

For example, inspect affected checks first:

```json
{
  "name": "kvik_verify",
  "arguments": {
    "modules": ["library"],
    "targets": ["jvm"],
    "since": ["/absolute/path/to/previous/run"],
    "checks": ["build", "test"],
    "planOnly": true
  }
}
```

Call again with `planOnly: false` to select against current inputs and execute
the affected checks. Selected tests run freshly. The response has
`kind: "mcp-verify-result"`, `selection` containing the CLI selection document,
and `result` containing the CLI change-verification result. It retains the same
request/log fields as build results. `complete` means the final result was read,
including failed or incomplete verification. `isError: false` accepts `planned`
for planning, or `passed`/`unchanged` for execution. `planned` ran no checks;
`unchanged` omitted checks using retained baselines. `freshChecks` lists newly
completed checks; compilation and ABI checks may use the artifact cache.

Selection and result documents remain under the project's `.kvik/verification`;
execution runs are retained under the MCP request directory. Both locations
survive connection closure. Progress, cancellation, execution limits and
concurrent-query behavior are the same as for `kvik_build` and `kvik_test`.

Enable configuration planning and application independently of compiler tools:

```sh
./kvik -- mcp --project /absolute/path/to/kvik.json --config-edits
```

This adds `kvik_config_plan` and `kvik_config_apply`. Planning accepts the same
[structured edit operations as the CLI](../README.md#configuration-changes),
including dependencies, targets, source sets, catalog entries, profiles and scripts:

```json
{
  "name": "kvik_config_plan",
  "arguments": {
    "edits": {
      "schemaVersion": 1,
      "operations": [{
        "op": "set-dependency-version",
        "module": "library",
        "sourceSet": "main",
        "package": "org.example:dependency",
        "version": "1.2.0"
      }]
    }
  }
}
```

Planning returns the CLI `configuration-edit-plan` in `structuredContent`.
Inspect its `configuration.content` and `dependencyLock.content`, then call
`kvik_config_apply` with `{"plan": <complete structuredContent object>}`.
Inline documents are staged in request-owned scratch and removed after the
worker exits. Configuration and lock contents remain unchanged during planning;
online resolution can populate the dependency store. Startup `--offline` requires
an existing compatible lock and does not resolve new dependencies.

Application accepts only local plans for the configured project, lock, store
and resolver policy. It verifies original-file digests, returning
`KVIK_EDIT_CONFLICT` if either file changed. Repeat the same plan to resume
interrupted publication or receive `status: "unchanged"` after completion.
Publication retains its recovery journal under `.kvik/configuration-edits`.
Cancellation stops the worker; it does not roll back already published files.
Retain the plan before applying it so that it can be retried after disconnect.

Configuration application cannot overlap another application or build/test/verify
request on the same connection. Queries remain available. Other connections and
CLI invocations use the existing publication locks and file preconditions.

Repeated selectors and pins are arrays. `channel` is `all`, `stable` or
`prerelease`; package query `limit` is 1–200. `kvik_dependencies` also accepts `offline`.
Use the returned opaque cursor with the same query filters. Project version
pagination requires exactly one package in `packages`.

Tool results carry the existing CLI JSON in `structuredContent`, with the same
JSON serialized in text content for clients that consume text. Check `status`,
`freshness`, `diagnostics` and truncation fields: an incomplete result is returned
with `isError: true` and retains its partial structured data. Available versions
are candidates, not compatibility assessments. Each call reads current project
files as needed; the connection does not retain an obsolete imported model.

`run` and `session` are directory paths, absolute or relative to the configured
project directory. Use the directories returned by build or foreground-session
commands. Historical explanations and statistics do not read the original
sources or installed tools and do not claim that a recorded result remains
current. Use `kvik_verification` to recheck freshness; stale, indeterminate,
failed or incomplete verification retains its structured result with
`isError: true`. Once the MCP connection has started, retained-run and session
queries still work if its configured project file is removed or becomes invalid.

Session event `limit` is 1–256. Pass the returned `nextCursor` object unchanged
as `cursor` on the next call. Check `hasMore` and `pendingTail`; a partial line
from a live writer does not advance the cursor. A partial journal after its
owner exits is incomplete and returns `isError: true`. Reading status or events
does not restart a session, keep it alive or submit controls.

Add `--session-controls` at server startup to expose `kvik_session_control` for
an existing foreground `dev` owner. This does not require `--tools` on the MCP
server and does not start a session:

```json
{
  "name": "kvik_session_control",
  "arguments": {
    "session": "/absolute/path/to/foreground/session",
    "requestId": "reload-42",
    "command": "r",
    "waitMillis": 1000
  }
}
```

`command` accepts `r` (rebuild/reload), `t` (tests), `q` (exit), `status` or
`cancel`. Only `cancel` requires `operationId`, taken from operation/application
events or current session status. A stale ID cannot cancel a newer operation.
Session paths are absolute or relative to the MCP project's directory and have
the CLI's trusted-local-filesystem boundary; they may address another project's
explicit foreground session. The owner uses its own tool, cache and offline
settings, independently of the MCP server's settings.

Use a stable `requestId` of 1–128 printable ASCII characters without spaces,
separate from the JSON-RPC request ID. Retrying the same ID and arguments returns
the original acknowledgement, including after owner exit; changing the arguments
is rejected. `waitMillis` accepts 0–10000 and defaults to 1000.

The tool preserves the CLI `session-control` document. `isError: false` means
`status: "acknowledged"` with an `accepted` outcome; this confirms receipt only.
Follow `kvik_session_events` to determine whether the requested operation
succeeded. Rejected acknowledgements (`not-active`, `closing`) return
`isError: true`. `pending`, `busy` and `owner-exited` also return `isError: true`:
`pending` was submitted without an acknowledgement yet, `busy` did not submit,
and `owner-exited` does not establish whether the command ran. Retry with the
same ID when recovering an uncertain outcome.

Cancelling an MCP call or closing its connection stops the submission/wait
worker. A command already submitted may still execute. The independent
foreground owner continues until its own stdin closes or it is stopped; an MCP
connection cannot keep it alive after that. Use explicit `cancel` or `q` controls
to stop its work. On runtime recomposition, explicitly use `nextSessionDirectory`
from session status and start a new event cursor.

Startup options `--lock`, `--store` and `--query-cache` have the same meanings and
project-relative paths as their CLI counterparts. Repeated `--pin`, `--scope-pin`
and `--exclude-metadata-edge` options set the policy for project inspection and
locked dependency queries, matching the policy used when resolving the lock.
Package dependency queries use their own optional `pins` argument.
With `--tools`, startup also accepts `--jobs`, `--memory-mib` and `--remote-cache`
with their CLI meanings. Execution settings require `--tools`; individual tool
calls cannot override the configured tools or output/cache paths.
`--offline` at server startup
(or the launcher's offline setting) prevents network queries for the whole
connection; a tool's `offline: false` cannot override it. A tool can additionally
request offline operation, or explicitly refresh repository metadata when the
connection permits network access.

Queries do not modify project configuration, locks or the dependency artifact
store, and never execute package code. Online package queries may update the
metadata query cache. Opt-in configuration planning may also populate the
dependency store; configuration application replaces project and lock files.
Per-request scratch files are removed after the owned
process exits. Project queries and MCP builds currently address local projects;
source-provider selectors remain available through the CLI. Session controls can
address existing local or source-provider owners. Retained-run queries can inspect results from either local
projects or source-provider builds.

The server allows four concurrent operations, with at most one build/test/verify per
connection. Queries have a 120-second timeout; builds and tests have a 30-minute
timeout. Read-only queries remain available while a build or test is running.
Excess calls return a tool error and can be retried when another call completes.
Requests are limited to 1 MiB, or 32 MiB on `--config-edits` connections so a returned
plan can be submitted for application. Child output is limited to 16 MiB per stream, and complete
MCP responses to 32 MiB. Excess child output stops the operation. Oversized
results return an error; build errors include the retained output directory.
Invalid framing or an oversized input closes the connection; malformed
JSON in a complete line receives a protocol error without losing the next line.
