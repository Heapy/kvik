# MCP project, dependency and run queries

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
owned query processes. There is no background service or listening socket.

The connection implements the [MCP stdio transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
and negotiates protocol version `2025-11-25` or `2025-06-18`. Initialize the
connection, send `notifications/initialized`, then use `tools/list` and
`tools/call`. `ping` and `notifications/cancelled` are supported.

| Tool | Inputs | Result |
| --- | --- | --- |
| `kvik_inspect` | None | Current project model, source ownership, targets and effective settings from `inspect` |
| `kvik_dependencies` | Optional `modules`, `scripts`, `targets`, `compilation`, `usage`; `availableVersions` enables `packages`, `channel`, `limit`, `cursor`, `refresh` | Declared and locked dependency graph, optionally available versions |
| `kvik_package_versions` | `package` as `group:artifact`; optional `channel`, `limit`, `cursor`, `offline`, `refresh` | Published versions with repository observations and freshness |
| `kvik_package_dependencies` | `coordinate` as `group:artifact:version`, `target`, `usage`; optional `jvmTarget`, `pins`, `offline`, `refresh` | Dependency requirements and supported resolved selections |
| `kvik_verification` | `run` | Retained checks, outcomes and test counts, with current input freshness |
| `kvik_explain` | `run`, `action` | Historical action inputs, dependencies, result and cache decision |
| `kvik_run_stats` | `run` | Historical action counts, timings, CPU/memory observations and remote transfers |
| `kvik_session_status` | `session` | Foreground state and current owner liveness |
| `kvik_session_events` | `session`; optional `cursor`, `limit` | Paginated foreground events, completeness and owner liveness |

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

Startup options `--lock`, `--store` and `--query-cache` have the same meanings and
project-relative paths as their CLI counterparts. Repeated `--pin`, `--scope-pin`
and `--exclude-metadata-edge` options set the policy for project inspection and
locked dependency queries, matching the policy used when resolving the lock.
Package dependency queries use their own optional `pins` argument.
`--offline` at server startup
(or the launcher's offline setting) prevents network queries for the whole
connection; a tool's `offline: false` cannot override it. A tool can additionally
request offline operation, or explicitly refresh repository metadata when the
connection permits network access.

Queries do not modify project configuration, locks or the dependency artifact
store, and never execute package code. Online package queries may update the
metadata query cache. Per-request scratch files are removed after the owned
process exits. Project queries currently address local projects; builds,
mutations, source-provider selectors and session controls remain available
through the CLI. Retained-run queries can inspect results from either local
projects or source-provider builds.

The server allows four concurrent queries, each with a 120-second timeout.
Excess calls return a tool error and can be retried when another call completes.
Requests are limited to 1 MiB, child output to 16 MiB per stream, and complete
MCP responses to 32 MiB. Oversized results return an error requesting a narrower
query. Invalid framing or an oversized input closes the connection; malformed
JSON in a complete line receives a protocol error without losing the next line.
