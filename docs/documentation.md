# API documentation

Enable documentation on a module in `kvik.json`:

```json
"documentation": {"includes": ["Module.md"]}
```

Markdown includes can supply a module description:

```markdown
# Module library
The library's purpose and usage.
```

Run `resolve` after enabling documentation to capture its dependencies. Generate
one HTML site for a module and the targets you select:

```sh
kvik resolve --project kvik.json
kvik docs --project kvik.json --tools tools.json --module library --target js --target wasmJs
```

The `documentation-ready` event gives the site directory and `index.html` path.
Publication coordinates are optional. Supported targets include JVM, Native, JS
and WasmJS. Common and intermediate source sets retain their declared refinement
relationships when you select only part of a module's targets. Target-only APIs
appear for the selected targets, alongside their shared declarations.

Documentation uses production sources and compiled dependency artifacts from the
build graph. Common dependency metadata supplies types and members referenced by
shared sources; platform dependency artifacts supply target-specific declarations.
Tests are neither compiled nor run. JS and WasmJS analysis runs in Dokka's JVM
process; it does not launch Node or a browser. Web targets currently still require
a `node` declaration in the tools configuration, but documentation does not verify
or use that Node installation.

Settings include `includes`, `documentedVisibilities`, `reportUndocumented`,
`failOnWarning`, `skipDeprecated`, `suppressInheritedMembers` and `timeoutMillis`.
For example:

```json
"documentation": {
  "includes": ["Module.md"],
  "reportUndocumented": true,
  "failOnWarning": true
}
```

Dokka 2.2.0 runs offline in an invocation-owned JVM with a separately pinned tool
distribution. Successful sites are cached locally. Markdown edits regenerate the
site while reusing compilation results; source changes update the affected
compilations and site. Both make retained documentation verification stale. Query
a previous run with `kvik verification --run /path/to/run`.

Failed or cancelled generation does not expose a completed site. The command
creates per-module HTML; aggregation, custom Dokka plugins, external documentation
links, SDK/cinterop documentation and deployment require separate integrations.
