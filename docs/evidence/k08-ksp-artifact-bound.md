# K08 bounded KSP runtime artifacts

The pinned KSP 2.3.12 analysis jar is 80,992,357 bytes (SHA-256
`862f0042fc2467cdcf7752e15880f97396a29e2469741dc6453f37584f1b4c69`).
Only the `ksp-runtime` resolution domain permits artifacts up to 128 MiB.
Ordinary dependencies, compiler plugins, and symbol processors retain 64 MiB;
metadata retains 1 MiB and resolver/lock aggregate limits retain 256 MiB.

`resolutionArtifactByteLimit(domain)` supplies the per-domain policy.
`EffectiveConfiguration.maxArtifactBytes` supplies the maximum reader/transport/CAS
bound needed by its requested domains. Callers must use that bound when creating
their bounded stores and readers; it does not relax individual domain checks.
The effective resolver identity records `maxKspRuntimeArtifactBytes` only when
KSP runtime is selected, leaving ordinary configuration serialization unchanged.
Persisted locks validate each domain occurrence, including a shared URL/digest
that appears first in the larger domain and later in an ordinary domain.

Reproduction (macOS arm64, installed tools and read-only KTC artifact cache):

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_dependency_locks.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache
```

All 13 Native locking tests passed (7.289 seconds test execution), including
actual pinned analysis-jar bytes stored and reread through Native CAS and
persisted metadata replay. Synthetic metadata supplies this focused fixture;
this result does not claim resolution of the complete upstream KSP closure.
The test rejects the same large artifact in an ordinary resolver, rejects a
declared 128 MiB + 1 artifact before fetching its jar, and rejects four distinct
artifact URLs whose aggregate exceeds 256 MiB. It also rejects a persisted
lock sharing that artifact with an ordinary JVM runtime domain or a
symbol-processor domain, with either domain order. Existing production closure
checks retain all 12 prior KTC binary identities.

Retained local evidence:
`.kvik/tests/locking/case-uloj8zma/large-ksp-lock.json` and its sibling CAS trees.
This slice does not yet prove public CLI transport, action materialization,
KSP process startup, generation repeatability, or Native Kotest execution;
those are separate integration gates. ByteArray APIs can retain multiple
copies of large artifacts, so the byte bounds do not imply a matching process
resident-memory ceiling.
