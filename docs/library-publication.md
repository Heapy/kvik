# Local library packages

Declare a base publication coordinate on a module in `kvik.json`:

```json
"publication": {"coordinate": "org.example:library:1.0.0"}
```

The declaration is optional. Coordinates must use an exact, immutable version. A JVM target produces `org.example:library-jvm:1.0.0`; a `macosArm64` target produces `org.example:library-macosarm64:1.0.0`. JS and WasmJS targets produce `org.example:library-js:1.0.0` and `org.example:library-wasm-js:1.0.0`. Duplicate emitted root or target publication coordinates across modules are rejected. A published module cannot declare application settings on any target.

`package-library` uses the normal build options to select modules, targets and tools:

```sh
kvik package-library --module library --target jvm --tools tools.json
```

The command prepares selected target packages and their workspace dependencies under the run's `library/` directory, using a Maven directory layout. Each target package contains a JAR or KLIB, a POM and [Gradle Module Metadata](https://docs.gradle.org/current/userguide/publishing_gradle_module_metadata.html) (`.module`). This is local packaging; the declaration has no remote repository or credential fields and the command does not upload artifacts.

JVM API dependencies appear in the compile/API metadata. Private implementation dependencies and runtime-only dependencies remain in runtime metadata and use POM runtime scope. KLIB loading requires compilation dependencies transitively, so Native, JS and Wasm API metadata includes private implementation dependencies as well as exported dependencies. Native runtime-only publication is rejected: Kotlin's Native publication model has no separate runtime resolution configuration to preserve link-only dependencies. Resources in Native, JS and Wasm KLIB publications are currently unsupported. JS/Wasm publishes separate API and runtime variants, including runtime-only dependencies in the latter.

For a published Native, JS or Wasm module, the compiler module name is the base `group:artifact` (for example, `org.example:library`). Native accepts an explicit module-name override; conflicting Native module names are rejected when a publication participates.

Packaging rejects effective version overrides, metadata exclusions, and transitive-only pins that cannot be proven unchanged in the published metadata. A direct dependency pin that is verified to leave its declared version unchanged is allowed.

Select all declared targets to produce the root Kotlin Multiplatform publication as well:

```sh
kvik resolve --project kvik.json
kvik package-library --module library --target jvm --target macosArm64 --tools tools.json
```

Consumers can then declare `org.example:library:1.0.0` in common code. The root JAR contains compiler-produced common metadata and Kotlin project structure metadata; its `.module` redirects the selected platform variants to the target packages. A multi-target request includes a separate `library:common:package` check; the run succeeds only when the root and every selected target package succeed. Common workspace dependencies are compiled and packaged recursively, and repeated invocations reuse unchanged compile and package actions from the local cache.

Gradle metadata references workspace dependencies by their root coordinates when those roots are included in the publication. Target POMs retain target dependency coordinates so POM-only consumers can use the JVM package directly.

Common metadata uses a separate locked `common` / `main` / `kotlin-metadata` dependency domain, including Kotlin stdlib 2.4.20. Run `resolve` again when upgrading a previously locked published project. The compiler runs in an invocation-owned JVM worker through the pinned Kotlin Build Tools API, without a compiler daemon.

Root publications support combinations of JVM, supported Native targets, `js` and `wasmJs`. Source sets shared by every selected main compilation are compiled together and published as `commonMain`; platform-only sources stay in their target artifacts. External dependencies must provide a compatible common metadata JAR. Selecting just one target produces its target package, without a root publication.

A compiler plugin enabled for `main` on every published target also runs during common metadata compilation. This supports the pinned serialization plugin and workspace compiler plugins, using the existing locked JVM host dependencies. Plugins enabled for only one target, or only for tests, stay in those compilations. Plugin implementations, options and declared inputs participate in the metadata compilation cache key. Declared plugin reports remain action outputs and are excluded from published metadata and consumer classpaths.

For a JS/Wasm-only library, use `--target js --target wasmJs`. Common metadata still
runs in the verified JVM compiler worker. The root metadata exposes both API and
runtime redirects for each web target, with packed KLIB attributes and separate JS
IR and Wasm-JS discriminators. The
[consumer fixture](../tools/test_js_library_package_cli.py) exercises Gradle Node
tests and Kotlin Toolchain common, main and test-source compilation. KTC's pinned
JS backend has no test runner; its compilation is reported separately from the
Gradle test executions.
