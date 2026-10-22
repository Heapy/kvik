# K09 explicit JVM target compatibility

The initial JUnit 5.13.4 failure was caused by its integer `org.gradle.jvm.version: 8` attribute. `junit-platform-commons` also publishes plain Java variants without Kotlin's platform attribute. Resolution now supports these declared semantics under an explicit consumer target policy.

Core `jvmTargetFromOptions(arguments)` accepts the existing adapter targets 11/17/21/25 and defaults to 11. `jvmVariantRequest(usage, target)` creates the matching lock request. Effective configuration, scoped transitive requests and graph lookups all use this shared policy. Compiler plugins request the separately recorded hosted JDK 25 runtime variant. A lower-target dependency module is compiled against its own API variant even when its consumer uses a newer version; consumers cannot depend on modules compiled for a newer JVM.

The effective resolver implementation is now `kvik-maven-v2`, including explicit default-consumer, plugin-host and ambiguity policy. Existing locks become stale and require explicit resolution. The v1 lock schema is unchanged. Native variant requests remain unattributed and their published selections remain unchanged.

Gradle's documented compatibility direction allows an older producer JVM version for a newer consumer. This bounded resolver rejects multiple compatible candidates instead of implementing Gradle's highest-compatible disambiguation. See [Gradle variant selection](https://docs.gradle.org/current/userguide/variant_aware_resolution.html). Unknown attributes, malformed numeric versions, unsupported environments/bundling, mixed Native attributes, ambiguous variants and too-new producers fail. No `.module` parse/compatibility error falls back to POM. POM-only artifacts retain the explicit consumer target in the request but offer no metadata proof of their bytecode version; jars are not bytecode-scanned.

Five original metadata files were retrieved directly from Maven Central and retained without rewriting. `tests/resolution/fixtures/manifest.json` records their exact source URLs, SHA-256 and byte lengths. `junit-artifacts.json` records the four selected artifact URLs and digests. Those binary digests independently match the installed KTC `.m2.cache`:

| Artifact | SHA-256 |
| --- | --- |
| junit-jupiter-api 5.13.4 | `d1bb81abfd9e03418306b4e6a3390c8db52c58372e749c2980ac29f0c08278f1` |
| junit-platform-commons 1.13.4 | `1c25ca641ebaae44ff3ad21ca1b2ef68d0dd84bfeb07c4805ba7840899b77408` |
| opentest4j 1.3.0 | `48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b` |
| apiguardian-api 1.1.2 | `b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38` |

The JUnit API closure contains all four jars; runtime contains the first three. Both include junit-bom 5.13.4 metadata and enforce its constraints. Tests verify API/runtime variant names, ordered closure membership, artifact digests, immutable metadata provenance and CAS-only locked replay. This establishes these JUnit API/runtime dependency closures; it does not claim the complete kotaml Kotest/KSP test closure.

Reproduce on the provisioned host:

```sh
python3 tools/test_resolution.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_dependency_locks.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache
python3 tools/test_project_graph.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
```

Observed: all 13 resolver, nine locking and nine graph tests passed (the optional live-HTTPS smoke case was not enabled). Original K05 direct requests still match the unchanged ten-selection production fixture; all twelve production artifacts still match KTC cache bytes. The K09 production-lock test still proves fifteen API/runtime/Native closures and unchanged metadata/artifact digests. New request attributes and effective policy identity are intentional changes. No consumer compiler, Gradle or Kotlin Toolchain process is used for dependency resolution.
