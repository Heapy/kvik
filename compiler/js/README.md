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

Completed test outcomes survive timeout, cancellation and early Node exit. The runner
appends them to a bounded progress journal; the Native adapter reads it after the owned
process tree has stopped. Recovery preserves observed failures and skips, marks discovery
incomplete, and never invents an outcome for the interrupted test. Cancellation remains
cancelled; other interrupted runs with observed failures remain failed. Only a validated
final report from a normally completed process can establish success.

Executable links serve tests and explicit application entry bridges. They suppress implicit application `main()`. The 2.4.20 Wasm
compiler ignores `-main noCall` when writing the loader (`WasmOutputGenerationPipelinePhase`
in upstream `compiler/cli/cli-js/src/org/jetbrains/kotlin/cli/pipeline/web/wasm`). The adapter
therefore removes the single exact generated `exports._start();` footer before capturing the
link output. An unfamiliar or repeated call fails validation; Wasm module initialization and
explicit entry exports are preserved. The integration fixture has a throwing application main.

Workspace plugins are compiled as JVM modules and loaded through `JsPluginWorker`
using verified compiler tools and the shared typed plugin bridge. Options preserve order,
repetition, commas and newlines. Declared input files and output directories are bound to
the compilation workspace; plugin outputs are checked before publication. Plugin classes,
runtime dependencies, options and files participate in the compilation key. Source-free
links consume only KLIBs. Main-scoped plugins are inherited by test compilation, which
includes production sources. A compilation can combine up to 16 isolated workspace plugins with the
pinned serialization plugin; their classpaths stay separate from consumer dependencies.
See `tools/test_js_workspace_plugins_cli.py` for a real IR transformation on both targets
and `tools/test_module_plugin_composition_cli.py` for composition with serialization.

Node applications use a separate generated entry KLIB and a source-free link. The
bridge invokes the configured public top-level `() -> Unit` or `(Array<String>) -> Unit`
function; callers do not need export annotations. The pinned backends expose its
`kvikApplicationMain` as a named ES export. `launch.mjs` invokes it once, while the
bridge reads Node arguments at runtime. The normal application bundle retains the
ES modules, Wasm binary and declared resources. Node is verified and launched through
the shared application owner, including BSP cancellation and foreground replacement.
Compilation and linking exclude Node identity and runtime arguments; packaging records
launch settings and the desired Node identity. Preparation never executes Node.

Browser execution, npm dependencies and Wasm-WASI are separate capabilities.
Arbitrary compiler flags are not accepted. Overloaded and suspend application entries
are not supported. `tools/test_js_application_cli.py` exercises preparation, cached
replay, arguments, explicit entry selection, failures and BSP cancellation on both targets.

Run `tools/test_js_compiler.py --help` for the offline integration checks. Supply explicit
compiler artifacts, Maven cache and Node paths. `--konan-home` also runs native contract tests.
`tools/test_js_execution.py` exercises the Native adapter with real owned Node processes,
including timeout/cancellation and retained JSON/JUnit reports, without compiling consumers.
