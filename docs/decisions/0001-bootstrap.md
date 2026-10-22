# K01: Kotlin/Native bootstrap feasibility

Date: 2026-10-22. Status: accepted for the next prototype step; production gates remain open.

## Decisions

Kvik and Kvik build plugins compile together with Kotlin/Native. The initial host is macOS ARM64. A generated registry statically instantiates the selected source plugins. JVM-only compiler APIs run in invocation-owned workers with the Build Tools API's in-process execution policy. Neither GraalVM nor a JVM orchestrator satisfies the runtime requirement.

Use Kotlin/Native and Kotlin/JVM 2.4.20, a provisioned Liberica JDK 25.0.4, and the host's Xcode SDK for this spike. Maven artifact URLs, lengths and SHA-256 values are in `experiments/k01/artifacts.lock.json`. The observed native/JDK installations are recorded in `docs/evidence/k01-summary.json`; these installation manifests are evidence, not a finished portable tool provisioning lock. K14 must pin downloaded distributions, native dependencies and SDK compatibility and validate them before use.

The macOS feasibility boundary uses CommonCrypto for SHA-256, kqueue for watching, libcurl for HTTPS, and POSIX process groups for child ownership. This validates access from Kotlin/Native; it does not establish Linux portability. No third-party implementation source was copied into the spike. Upstream reference submodules retain their own licenses and are not build dependencies.

Use bazel-remote 2.6.4 as the first existing remote cache backend. Its REAPI v2 gRPC Capabilities, CAS batch operations and ActionCache services passed the probe. Production K07 needs bounded requests, authentication/TLS, streaming for large blobs, corruption/outage behavior and the full cache conformance suite. The backend probe creates and reaps a temporary foreground server; ordinary Kvik invocations will be cache clients.

Use kotaml at `a5c94c77b220cfc7b6335668766e8f26930968c3` as the first real fixture. Preserve its production sources. Its root and `kotaml-json` modules exercise common/JVM/Native sources, serialization, SnakeYAML Engine KMP and Okio. Start resolution with exact Maven versions, explicit repositories, Gradle module metadata variant selection, `available-at` redirects, and transitive target dependencies. Unsupported constraints, capabilities or ambiguous variants must fail explicitly. Validate the supported subset against the actual fixture metadata in K05 before claiming it works. Grogu-time remains the later fixture; its pinned configuration has no macosArm64 target.

## Initial budgets

Hardware: Apple M4 Max, Mac16,5, 36 GiB RAM. Software: macOS 27.0.1, Xcode 27.1 build 27A9275, macOS SDK 27.0. These are prototype investigation thresholds, not measured production guarantees:

| Operation | Initial budget |
| --- | --- |
| Provisioned cached launcher + help/inspection | p95 below 150 ms, no JVM worker |
| Native orchestrator help/inspection | peak RSS below 64 MiB |
| Provisioned cold source composition | below 120 s for the small composition |
| Source-plugin edit and native recomposition | below 60 s for the small composition |
| Foreground control acknowledgement | below 100 ms while a build is running |
| Cancellation | graceful shutdown within 2 s, forced cleanup within 5 s |

Measure larger projects independently. Tool downloads, full installation hashing, compilation and execution require separate timing. The spike's 8.8 ms median measures the already-built executable directly, not the launcher. The bootstrap currently recompiles the JVM worker on each invocation and does not meet the cached-launcher contract.

## Remaining boundaries

K01 proves minimal native composition, platform access and direct compiler execution. It does not implement the build system. In particular: native executable publication is not yet atomic or concurrency-safe; toolchain identity is incomplete in the spike's key; child cleanup on abrupt owner loss is unproven; the worker uses a temporary CLI rather than the K02 protocol; native compile outputs have not passed relocation or reproducibility gates. The source-plugin interface in `experiments/k01` is disposable and is not the public build-plugin API.

The next step is K02's versioned contracts and canonical identity vectors. K03/K14/K15 must address the lifecycle and launcher gaps before the prototype runtime gate can pass.
