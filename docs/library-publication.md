# Local library packages

Declare a base publication coordinate on a module in `kvik.json`:

```json
"publication": {"coordinate": "org.example:library:1.0.0"}
```

The declaration is optional. Coordinates must use an exact, immutable version. A JVM target produces `org.example:library-jvm:1.0.0`; a `macosArm64` target produces `org.example:library-macosarm64:1.0.0`. Duplicate emitted root or target publication coordinates across modules are rejected. A published module cannot declare application settings on any target.

`package-library` uses the normal build options to select modules, targets and tools:

```sh
kvik package-library --module library --target jvm --tools tools.json
```

The command prepares selected target packages and their workspace dependencies under the run's `library/` directory, using a Maven directory layout. Each target package contains a JAR or KLIB, a POM and [Gradle Module Metadata](https://docs.gradle.org/current/userguide/publishing_gradle_module_metadata.html) (`.module`). This is local packaging; the declaration has no remote repository or credential fields and the command does not upload artifacts.

JVM API dependencies appear in the compile/API metadata. Private implementation dependencies and runtime-only dependencies remain in runtime metadata and use POM runtime scope. Native KLIB loading requires compilation dependencies transitively, so Native API metadata includes private implementation dependencies as well as exported dependencies. Native runtime-only publication is rejected: Kotlin's Native publication model has no separate runtime resolution configuration to preserve link-only dependencies. Native resources are also currently unsupported.

For a published Native module, the default compiler module name is the base `group:artifact` (for example, `org.example:library`). An explicit module-name override is retained; conflicting module names are rejected when a publication participates.

Packaging rejects effective version overrides, metadata exclusions, and transitive-only pins that cannot be proven unchanged in the published metadata. A direct dependency pin that is verified to leave its declared version unchanged is allowed.

Select both targets to produce the root Kotlin Multiplatform publication as well:

```sh
kvik resolve --project kvik.json
kvik package-library --module library --target jvm --target macosArm64 --tools tools.json
```

Consumers can then declare `org.example:library:1.0.0` in common code. The root JAR contains compiler-produced common metadata and Kotlin project structure metadata; its `.module` redirects JVM and Native variants to the target packages. A multi-target request includes a separate `library:common:package` check; the run succeeds only when the root and both target packages succeed. Common workspace dependencies are compiled and packaged recursively, and repeated invocations reuse unchanged compile and package actions from the local cache.

Gradle metadata references workspace dependencies by their root coordinates when those roots are included in the publication. Target POMs retain target dependency coordinates so POM-only consumers can use the JVM package directly.

Common metadata uses a separate locked `common` / `main` / `kotlin-metadata` dependency domain, including Kotlin stdlib 2.4.20. Run `resolve` again when upgrading a previously locked published project. The compiler runs in an invocation-owned JVM worker through the pinned Kotlin Build Tools API, without a compiler daemon.

The current root publication supports JVM plus `macosArm64`. Source sets shared by both main compilations are compiled together and published as `commonMain`; platform-only sources stay in their target artifacts. External dependencies must provide a compatible common metadata JAR. Common metadata compilation currently rejects main compiler plugins; target-only packaging retains existing plugin support. Selecting just one target produces its target package, without a root publication.
