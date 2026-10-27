# Library packaging and publication

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

## Publish a retained package run

Upload the completed run with an explicit, non-cacheable operation:

```sh
kvik publish-library --run .kvik/runs/RUN_ID \
  --repository https://repo.example.org/releases/ \
  --username publisher --password-env MAVEN_PUBLISH_PASSWORD \
  --output .kvik/publications/attempt-1
```

The first backend supports immutable release coordinates in an HTTPS Maven
repository with authenticated GET and PUT. Credentials come only from the named
environment variable and use HTTP Basic authentication. The repository must enforce
conditional creation with `If-None-Match: *`; this prevents another writer from
replacing a file between the preflight GET and PUT, as defined by
[HTTP conditional requests](https://www.rfc-editor.org/rfc/rfc9110.html#name-if-none-match).
Redirects are refused. Plain HTTP is accepted only for explicit `127.0.0.1` fixture
ports. Repository credentials belong to this operation, separately from dependency
resolution and remote build-cache credentials.

Before contacting the server, the command validates the committed run journal,
successful package results and every artifact's digest. It saves all package bytes,
verification records, the upload manifest, a `publish` action with `cachePolicy:
never`, and a durable intent in the new attempt directory. It uploads the selected
root and target artifacts, their workspace dependency packages, and SHA-256
sidecars in the existing [Maven layout](https://maven.apache.org/repositories/layout.html).
Known conflicting bytes anywhere in the release stop preflight before the first
PUT. Matching remote files are reused; new files are read back and verified.

The JSON result and `outcome/result.json` report each file as `present`,
`published`, `conflict`, `missing`, `rejected`, `unknown` or `not_attempted`.
Exit 0 means every file was observed with the requested bytes; exit 3 means an
incomplete publication; exit 130 means cancellation. A partial publication is not
rolled back. A lost response or abrupt process termination can leave remote state
unknown; an intent without an outcome is not success.

Retry into a new attempt directory, using the retained inputs even if the original
project, run, toolchain or build cache is no longer available:

```sh
kvik publish-library --retry .kvik/publications/attempt-1 \
  --repository https://repo.example.org/releases/ \
  --username publisher --password-env MAVEN_PUBLISH_PASSWORD \
  --output .kvik/publications/attempt-2
```

Retry requires the same repository and rechecks every file. It neither recompiles
nor reruns tests. No background process or automatic retry performs further writes.
Each request has a 30-second deadline; transfer work has a five-minute deadline.
The backend accepts at most 1,000 package files, 64 MiB per file and 256 MiB of
retained inputs, plus derived checksum sidecars.

Repositories own version-list metadata (`maven-metadata.xml`) and release visibility.
This backend does not provide atomic multi-file deployment, timestamped snapshots,
signing, or Maven Central staging. Use a repository-specific adapter when those
capabilities are required. Exact-coordinate consumers use the uploaded POMs and
module metadata; server-managed version indexes must be configured separately.

The loopback acceptance fixture uses a real retained KMP package run and checks
partial failure/retry, lost responses, conflicting and concurrent writers,
read-back corruption, redirects, malformed inputs, cancellation and abrupt process
termination, with compiler tools deliberately absent:

```sh
.kvik/schema-tools/bin/python tools/test_maven_publication_cli.py \
  --runtime /path/to/kvik.kexe --run /path/to/completed/package/run
```

The Native publication fixture also runs this test after its Gradle/KTC consumer
checks, including in macOS CI. Its summary and command logs are retained alongside
the consumer logs.

## Publication contents and consumers

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

For a Native-only library, common metadata uses the verified Native stdlib and
platform libraries commonized for every declared target. Shared APIs can expose
types such as `platform.posix.timespec`, including through workspace dependencies;
an API available on only one target is rejected from shared metadata. The
commonizer runs in an invocation-owned JVM process and its result is locally
cached. SDK and commonized platform libraries remain compiler inputs and are not
bundled in the published root JAR.

The [Native publication fixture](../tools/test_native_publication_cli.py) packages
a macOS/iOS library and a workspace dependency, checks cache reuse and rejection
of a macOS-only shared API, then compiles common metadata and both targets in
Gradle and Kotlin Toolchain 0.13.0 consumers:

```sh
python3 tools/test_native_publication_cli.py \
  --runtime /path/to/kvik.kexe --tools /path/to/tools.json \
  --java-home "$JAVA_HOME" --konan-home "$KONAN_HOME" \
  --gradle /path/to/gradle --ktc /path/to/kotlin
```

This fixture requires a macOS ARM64 host with Xcode and tools configured for both
`macosArm64` and `iosArm64`. The [macOS workflow](../.github/workflows/ios-frameworks.yml)
runs it with Gradle 9.8.2 and a checksum-pinned Kotlin Toolchain 0.13.0 wrapper,
retaining producer and consumer logs. Consumer compilation uses the local Maven
repository emitted by Kvik, without publishing to an external service.

A compiler plugin enabled for `main` on every published target also runs during common metadata compilation. This supports the pinned serialization plugin and workspace compiler plugins, using the existing locked JVM host dependencies. Plugins enabled for only one target, or only for tests, stay in those compilations. Plugin implementations, options and declared inputs participate in the metadata compilation cache key. Declared plugin reports remain action outputs and are excluded from published metadata and consumer classpaths.

For a JS/Wasm-only library, use `--target js --target wasmJs`. Common metadata still
runs in the verified JVM compiler worker. The root metadata exposes both API and
runtime redirects for each web target, with packed KLIB attributes and separate JS
IR and Wasm-JS discriminators. The
[consumer fixture](../tools/test_js_library_package_cli.py) exercises Gradle Node
tests and Kotlin Toolchain common, main and test-source compilation. KTC's pinned
JS backend has no test runner; its compilation is reported separately from the
Gradle test executions.
