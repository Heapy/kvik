# Public API baselines

`abi-dump` extracts the public API of selected main compilations on JVM, supported
Native targets, JS and WasmJS. `abi-check` compares each candidate with its configured
baseline. Publication coordinates are optional; tests are neither compiled nor run.
JS/Wasm ABI operations read packed KLIBs and do not require Node or a browser.
A `node` declaration is needed only for test execution.

Configure a separate baseline on each target in `kvik.json`:

```json
"targets": [
  {"name": "js", "main": "jsMain", "test": null, "compilerOptions": [],
   "abi": {"baseline": "api/library.js.api"}},
  {"name": "wasmJs", "main": "wasmJsMain", "test": null, "compilerOptions": [],
   "abi": {"baseline": "api/library.wasmJs.api"}}
]
```

Resolve the project, then generate candidates with the normal project and tool
selectors:

```sh
kvik resolve --project kvik.json
kvik abi-dump --project kvik.json --tools tools.json --module library --target js --target wasmJs
```

Each `abi-ready` event identifies the module, target and paths to `api.txt` and
`abi.json`. Review the API text and copy it to that target's configured baseline.
Dumping works before baselines exist and never writes them automatically.

```sh
kvik abi-check --project kvik.json --tools tools.json --module library --target js --target wasmJs
```

A missing baseline is an error. A mismatch fails the check and retains the new
candidate for review. Comparisons require an exact public API match, accepting
CRLF or LF and an optional final newline. Both additions and removals require a
baseline update; the result does not classify binary or behavioral compatibility.

JVM snapshots use `bcv-jvm-v1`; Native, JS and Wasm snapshots use `bcv-klib-v1`.
Snapshots bind their target, module and input artifact identity and record the
pinned reader and compiler versions. Extraction runs in an invocation-owned JVM
worker with the normal verified compiler tools.

Compilation, extraction and successful comparison results are cached separately.
Changing a baseline reruns its comparison while reusing compilation and extraction.
Changing an implementation reruns compilation and extraction even when the public
API text remains identical. A baseline change also makes a retained verification
stale; inspect a run with `kvik verification --run /path/to/run`.
