# K09 dependency lock integration evidence

The implementation in `integration/resolution` composes the framework-free project parser, Native K05 resolver and K04 CAS. It returns canonical v1 lock bytes to a caller and never writes a user lock file. Root CLI/bootstrap integration remains separate.

Reproduce with the existing schema-tools environment, or a virtual environment containing `tools/schema-requirements.txt`:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_dependency_locks.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache
```

The Python harness bootstraps a Kotlin/Native test executable with the existing libcurl/libxml2 interop and validates the persisted lock against the unchanged v1 JSON schema. All lock creation, CAS checks and replay execute in Native Kotlin. No consumer resolution is delegated to Gradle or Kotlin Toolchain, and the tests make no network requests. Fixture metadata and the KTC binary cache are read-only inputs; generated files live under this worktree's `.kvik/tests/locking/case-*`.

Seven native tests cover:

- Effective identity includes explicit resolver pins, bounds, scopes and the complete project document; scope ordering is deterministic, invalid scopes and conflicting pins are refused.
- Strict schema parsing and canonical round-trip, duplicate selections/metadata/artifacts, unknown fields/version, duplicate JSON keys, invalid UTF-8, trailing data and a legitimate empty dependency graph.
- Real K04 missing and corrupt metadata/artifact entries, explicit repair only during resolve, unchanged corrupt files after locked failures, defensive digest checks even for a non-verifying store, and stale project/scope/pin refusal.
- Metadata proof rejects missing closure components, omitted/extra metadata, fabricated variants/repository IDs, artifact reordering, wrong roots/usages and inconsistent URL identities across variants.
- Explicit compatible pins reconcile a concrete version conflict; without the pin the graph fails.
- Module edges and selected compiler-plugin roots enter the requested closure, including main-only plugins needed by a test scope.
- Real production kotaml dependencies: serialization core/JSON 1.11.0, SnakeYAML engine KMP 5.0.0, Okio 3.18.2 and explicit stdlib 2.4.20 pin across JVM API, JVM runtime and macosArm64 API.

The production probe records 15 root/variant closures and 12 distinct binaries. Ten JVM-runtime/Native closures match the exact ordered metadata and artifact digests captured by K05 in `tests/resolution/fixtures/selections.json`; all binary bytes come from and match the KTC cache. The five additional JVM API closures are resolved from the same captured metadata and validated offline. A specific assertion checks the meaningful API/runtime difference: SnakeYAML's JVM API closure has only SnakeYAML, stdlib and annotations, while its runtime closure also reaches URL encoder and Okio. `production-lock.json` preserves complete artifact URLs, selected variant names, repository IDs, digests and metadata provenance. `effective-configuration.json` preserves the exact scope/pin policy used.

This is production dependency evidence only. It does not establish Kotest/KSP generator compatibility or the upstream Native test closure. External plugin coordinates are included when declared, but no unsupported plugin metadata is silently accepted. K05's explicit metadata limitations remain in force. The default policy has no implicit alignment pin, and locked use never reads current remote metadata or silently resolves missing dependencies.

Independent review found no confirmed bug. Four focused Native checks were rerun against effective identity, schema/offline handling, missing/corrupt/stale CAS and forged closure/variant/repository/order data. All passed. Replay proves consistency with the recorded immutable metadata, not historical repository availability or publisher authenticity.
