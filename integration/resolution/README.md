# Dependency lock integration

This Kotlin/Native-compatible layer connects the v1 project model, K05 Maven/Gradle metadata resolver and K04 content store. It performs no lock-file IO and contains no JVM APIs.

```kotlin
val project = parseProjectConfiguration(parseJson(projectBytes.decodeToString()))
val policy = ResolverPolicy(pins = listOf(Coordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")))
val effective = effectiveConfiguration(project, policy)
val locks = DependencyLocks(NativeContentStore(casDirectory), NativePomParser())

// Only an explicit resolve/update command gets a network transport.
val resolved = locks.resolve(effective, NativeHttpsTransport())
val publicationBytes = resolved.bytes() // Caller atomically publishes after explicit resolve/update.

// Build/test/locked/offline use has no transport parameter and never updates a lock or CAS.
val verified = locks.loadLocked(existingLockBytes, effective)
```

`effectiveConfiguration(...).digest` is the shared configuration identity for inspect, lock freshness and downstream actions. Its canonical document contains the complete validated project document, resolver policy version, exact pins, resolver resource bounds and selected dependency scopes. There are no implicit pins. A Kotlin stdlib alignment pin must be passed explicitly, as in the example. Changing a repository, compiler option, source-set declaration, pin, resolver bound or selected scope changes the identity. Equivalent scope/pin ordering is normalized; project declaration order is retained.

Default scopes cover each declared main/test compilation: JVM `java-api` and `java-runtime`, Native `kotlin-api`. Callers can pass explicit `DependencyScope(module, target, scope, usage)` values to lock a subset. This produces a different configuration identity. A build must recompute identity for its intended scope policy, not accept the identity advertised by a supplied lock. Tests include their main dependencies and main compiler plugins even if the test source set does not explicitly inherit main. Ordinary module edges traverse the dependency module's main compilation. Applicable external compiler plugins request a JVM runtime closure; module plugins traverse their JVM main dependencies. Source sets outside the selected compilations are not resolved. The resulting ordered `requests` are deduplicated root/variant pairs; each selection preserves K05's depth-first artifact order.

`resolve` collects compatible roots together per variant, downloads through the injected bounded transport and durably stores each response in CAS. It returns a schema-compatible `DependencyLock` only after the same validation used by locked consumers. Failed resolution may leave reusable content-addressed blobs but cannot publish or overwrite a user's lock file.

`loadLocked` strictly parses v1 fields, rejects duplicated selection/metadata/artifact entries and inconsistent identities across selections, checks the effective identity and exact ordered requests, and reads every referenced metadata/artifact digest from CAS. It then replays K05 against the lock's URL-to-digest mapping only and requires identical selections. This is validation of the recorded immutable graph: it never accesses current repository metadata or adds a newly selected component. It detects missing transitive closure entries, wrong variants/repositories, violated pins, reordered artifacts and extra metadata. A missing URL behaves as an offline 404 during the proof; missing bytes for a listed digest fail before replay. Missing/corrupt/stale locks require explicit resolution or repair and never trigger a download or lock rewrite.

The lock's digest is the SHA-256 and size of its canonical serialized bytes. `bytes()` emits the existing `schemas/v1/contracts.json` `dependencyLock` shape, with no additional fields. Lock parsing is bounded to 16 MiB, 8192 distinct URLs, 1 MiB per metadata blob, 64 MiB per artifact and 256 MiB of distinct referenced content. The caller owns bounded reading and atomic file publication; it must not write during ordinary build/test execution.

The supported metadata subset is exactly K05's. External plugin roots are included but their closures may still fail on unsupported metadata. A project-wide request with the same variant and conflicting versions across otherwise independent modules is deliberately refused unless an explicit compatible pin reconciles it. Native test/Kotest/KSP compatibility is not established by this integration. There is no dynamic dependency selection, repository refresh during locked use, implicit plugin resolution or unverified fallback.
