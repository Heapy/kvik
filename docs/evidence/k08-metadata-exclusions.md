# Explicit immutable metadata-edge corrections

This slice adds an opt-in consumer policy that excludes one ordinary dependency edge from one exact Gradle module metadata variant. It does not infer platform-provided libraries, filter JARs, change project source scopes, or change metadata when the policy is empty.

Each rule identifies a consumer resolution domain, the terminal producer coordinate, SHA-256 and size of its original `.module` bytes, the selected variant, the raw declared dependency coordinate before pins, and a nonblank reason. Rules are canonicalized, limited to 32, and included in effective configuration and dependency lock v2 only when nonempty. Duplicate parent/dependency rules within one domain fail. CLI text parsing is bounded to 4 KiB per rule. The underlying values additionally bound producer/dependency coordinates to 1,024 characters, variant to 256, reason to 1,024, and metadata to 1 MiB.

A visited producer component with a different version, metadata digest, metadata format, variant, missing/duplicate dependency edge, platform edge, or redirect fails closed. POM dependency management, constraints, direct roots and available-at edges cannot be excluded. Rules may remain unvisited in a partial per-root resolver call, but full-domain lock resolution and replay require every rule to have applied. Effective configuration rejects rules for empty or unselected domains, so grouping requests cannot silently bypass this audit.

The query graph records an `excluded` edge from the original producer node with its declared requirement, complete rule and canonical rule digest. Its target and selected coordinate are null; it does not create an omitted component node or claim an artifact was provided. Original parent metadata remains content-addressed and is reverified during offline lock/query replay. Query filtering occurs after full-lock verification. An empty policy preserves ordinary effective-configuration, lock, and graph serialization.

## Why the Native kotaml fixture needs an explicit policy

The retained public resolve failure is `POM has no matching Kotlin Native variant`. Published `io.kotest:kotest-common-macosarm64:6.2.5` metadata (SHA-256 `fec23c54a9a7fae891d8697b34e3a17ed79cc3e71fe59e5ee40ae290e78bbe2e`, 4,708 bytes), variant `macosArm64ApiElements-published`, declares `org.jetbrains.kotlin:kotlin-reflect:2.2.21`. That dependency has no matching Native variant. Native's stdlib supplies reflection interfaces used by Kotest's non-JVM implementation; it does not supply JVM reflection.

The pinned KTC source uses `nativeTaskUtil.kt`'s `isKlibFile()` to drop `.jar` and `.aar` dependencies (FIXME AMPER-3862); both `NativeCompileKlibTask.kt` and `NativeLinkTask.kt` apply it. Kotest 6.2.5's published non-JVM sources implement `BasicReflection` with limited/null results and explicitly unavailable instantiation. These sources explain the retained reference artifact set, but do not establish a general right to ignore incompatible libraries. The public Native harness instead supplies explicit rules for its four test API/runtime domains. Its source files remain unchanged; this corrective metadata policy deliberately differs from KTC's blanket filtering.

## Verification

The dedicated harness builds one Kotlin/Native test executable containing new policy tests and the existing resolver, scoped locking, and package-query suites. Existing resolver binary fixtures are read-only aliases into the provisioned KTC cache and retain normal size/hash checks. No network acquisition is intended. New cases exercise exact matching before pins, stale-rule failures, forbidden edge kinds, partial/full traversal, canonical identity and duplicate/bound/domain validation, real Native CAS lock replay, policy tampering, and corrected project-query evidence. JSON Schema checks cover emitted locks/graphs and invalid excluded-edge combinations. Existing suites retain wildcard/POM/redirect/platform/constraint, scoped replay, query and ordinary artifact-identity checks.

Reproduction (from this worktree):

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_metadata_exclusions.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache
```

Status: verified. The combined Kotlin/Native executable passed all 58 groups (27 resolver, 13 locking, 13 query, five policy) in 9.778 seconds. All 12 prior selected artifact identities still match the KTC cache. Generated graph, lock and project-query outputs validate; six invalid graph combinations and four invalid reason cases fail schema validation. Light checks also pass: 11 schema examples/canonical vectors, seven existing contract-schema tests, five package-schema tests, and the new scoped-rule schema regression (trailing newlines in parent, dependency, metadata hash, domain module, variant and reason).

Retained evidence:

- Final results: `.kvik/tests/metadata-exclusions/case-66lgsqqg/summary.json`, `excluded-graph.json`, `excluded-lock.json`, `excluded-project.json`, plus existing suite outputs under `locking/` and `queries/`.
- Build commands and exact source hashes: `.kvik/tests/metadata-exclusions/case-66lgsqqg/{commands,source-identities,build-stamp}.json`.
- Matching executable: `.kvik/tests/metadata-exclusions/unit.kexe`, SHA-256 `78570f8334c84c44966a5ad622cd9f2e22133a50d21981678ad94bf90053884e`.
- Final full-build log: `.kvik/tests/metadata-exclusions/stamped.log`. Earlier `combined.log` passed all Native groups but its Python wrapper incorrectly applied the package-result root union to a bare graph; corrected `verified.log` passed the post-checks. The final stamped run rebuilt from source and passed all checks, without relying on those earlier binaries.

The harness writes a build stamp only after successful compilation and unchanged input checks. It binds source and native-interop bytes, exact tool commands, relevant compiler environment, SDK path, compiler launchers/jars, Java executable, trusted lock and harness bytes, and the executable SHA-256/size. `--skip-build` rejects absent or mismatched stamps; a focused Python test challenges missing stamps, changed inputs and changed binary bytes. This stamp is test-reuse provenance, not a replacement for production installed-tool verification; caller-controlled tool roots/SDK must remain stable during compilation.

The positive direct-root regression proves an excluded parent→child edge does not suppress the same valid Native child when separately requested as a root. A redirect/owner-alias regression proves the rule binds terminal lookup coordinate and exact terminal metadata bytes, including when the published component owner names the common module. Full-domain resolution also rejects an additional unvisited rule even when another rule applied successfully.

This slice does not claim successful public Native kotaml resolution or its full 1,880-test CLI gate; those require the separate CLI policy wiring and Native API/link visibility integration.
