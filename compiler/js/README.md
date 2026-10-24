# Kotlin/JS and Kotlin/Wasm-JS adapter

The adapter invokes the pinned Kotlin 2.4.20 compiler in a fresh owned JVM: `K2JSCompiler`
for `js`, `KotlinWasmCompiler` for `wasmJs`. Compilation produces a packed KLIB, then a
separate source-free link produces an ES module directory. Both actions are cacheable.
Declared source files, common sources, dependency KLIBs, published plugin JARs and compiler
settings participate in the action key. Compiler paths come from the verified JVM provider.

Tests use an explicitly provisioned, inventory-verified Node installation. There is no PATH
fallback, npm installation, compiler daemon or background test service. Test actions always
execute. The invocation owns the complete process group and cancels or times out that group
before inspecting outputs.

The runner implements the Jasmine-compatible `describe`/`it` hooks used by `kotlin.test`.
The compiler registers tests; the runner never scans Kotlin source for annotations. Wasm
registration uses the exported `startUnitTests()` exactly once. It awaits promise-returning
tests, inherits ignored suites, retains failures and elapsed nanoseconds, and rejects duplicate
IDs. Filters match qualified IDs such as `example.Sample.test`; `*` is the only wildcard.
Zero matching tests and selections containing only ignored tests produce an incomplete result.
A failed test produces a failed result even if later tests pass.

Executable links currently serve tests. They suppress application `main()`. The 2.4.20 Wasm
compiler ignores `-main noCall` when writing the loader (`WasmOutputGenerationPipelinePhase`
in upstream `compiler/cli/cli-js/src/org/jetbrains/kotlin/cli/pipeline/web/wasm`). The adapter
therefore removes the single exact generated `exports._start();` footer before capturing the
link output. An unfamiliar or repeated call fails validation; Wasm module initialization and
the test entry export are preserved. The integration fixture has a throwing application main.

Workspace plugins are compiled as JVM modules and loaded through `JsPluginWorker`
using verified compiler tools and the shared typed plugin bridge. Options preserve order,
repetition, commas and newlines. Declared input files and output directories are bound to
the compilation workspace; plugin outputs are checked before publication. Plugin classes,
runtime dependencies, options and files participate in the compilation key. Source-free
links consume only KLIBs. Main-scoped plugins are inherited by test compilation, which
includes production sources. Only one plugin per compilation is supported, without mixing
published and workspace plugins. See `tools/test_js_workspace_plugins_cli.py` for a real
IR transformation on both targets.

Browser execution, npm dependencies, Wasm-WASI, application launching and packaging are
separate capabilities. Arbitrary compiler flags are not accepted.

Run `tools/test_js_compiler.py --help` for the offline integration checks. Supply explicit
compiler artifacts, Maven cache and Node paths. `--konan-home` also runs native contract tests.
