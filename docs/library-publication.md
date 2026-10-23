# Local library packages

Declare a base publication coordinate on a module in `kvik.json`:

```json
"publication": {"coordinate": "org.example:library:1.0.0"}
```

The declaration is optional. Coordinates must use an exact, immutable version. A JVM target produces `org.example:library-jvm:1.0.0`; a `macosArm64` target produces `org.example:library-macosarm64:1.0.0`. Duplicate target publication coordinates across modules are rejected. A published module cannot declare application settings on any target. Compiler plugin declarations remain available.

`package-library` uses the normal build options to select modules, targets and tools:

```sh
kvik package-library --module library --target jvm --tools tools.json
```

The command prepares selected target packages and their workspace dependencies under the run's `library/` directory, using a Maven directory layout. Each target package contains a JAR or KLIB, a POM and [Gradle Module Metadata](https://docs.gradle.org/current/userguide/publishing_gradle_module_metadata.html) (`.module`). This is local packaging; the declaration has no remote repository or credential fields and the command does not upload artifacts.

JVM API dependencies appear in the compile/API metadata. Private implementation dependencies and runtime-only dependencies remain in runtime metadata and use POM runtime scope. Native KLIB loading requires compilation dependencies transitively, so Native API metadata includes private implementation dependencies as well as exported dependencies. Native runtime-only publication is rejected: Kotlin's Native publication model has no separate runtime resolution configuration to preserve link-only dependencies. Native resources are also currently unsupported.

For a published Native module, the default compiler module name is the base `group:artifact` (for example, `org.example:library`). An explicit module-name override is retained; conflicting module names are rejected when a publication participates.

Packaging rejects effective version overrides, metadata exclusions, and transitive-only pins that cannot be proven unchanged in the published metadata. A direct dependency pin that is verified to leave its declared version unchanged is allowed.

These are target-specific packages. The base coordinate is not a consumable root Kotlin Multiplatform publication: common metadata compilation and root metadata publication are separate work. Consumers must use the target package coordinate until that support exists. Kotlin documents the distinction between [target-specific and root publications](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-lib-setup.html#target-specific-and-root-publications).
