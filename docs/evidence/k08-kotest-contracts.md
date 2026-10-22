# K08 Native Kotest contracts (adapter integration pending)

The optional target field `nativeTestSettings` explicitly selects the initial supported combination:

```json
{"engine":"kotest","kspVersion":"2.3.12","processor":"io.kotest:kotest-framework-symbol-processor:6.2.5"}
```

All three fields are required. Unknown fields, different versions/engines, JVM ownership, and a missing test source set are rejected. Omission preserves the existing model defaults and raw configuration identity. Existing `testSettings` remains JVM-specific. This slice does not enable Kotest execution: the project graph explicitly rejects the new Native test selection until the dependent adapter lands, preventing fallback to the built-in kotlin.test runner. Ordinary Native builds remain available.

Resolution adds `ksp-runtime` and `symbol-processor` purposes, currently limited to a macosArm64 test owner with java-runtime usage. Their producer platform is JVM with the verified host's fixed JVM 25 consumer policy. The three KSP roots are symbol-processing-api, symbol-processing-aa-embeddable, and symbol-processing-common-deps at 2.3.12. The processor root is the declared Kotest coordinate. Each domain owns conflict resolution, metadata identities, ordered artifact closure, scoped pins, and CAS replay independently. This is necessary because the pinned KSP CLI and Kotest processor currently reference KSP API 2.3.12 and 2.3.9 respectively. No changes to global pins are inferred. No new test-count or action-kind schema is needed.

## Executed validation

Run from this worktree:

```sh
python3 tools/test_contracts.py --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tests/contracts/test_project_settings_schema.py
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_dependency_locks.py --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca --ktc-cache /Users/yoda/Library/Caches/JetBrains/Kotlin/.m2.cache
```

29 Native contract tests, 5 schema tests, and 12 Native lock tests passed. The new real-CAS fixture resolves and replays different KSP API versions in the two host domains, rejects a target-platform producer in a host domain, and refuses replay against the old configuration. Existing production dependency identity tests also passed. Lock evidence: `.kvik/tests/locking/case-dacihxff`.

The focused project-graph regression has been added; execution is deferred while other owned compiler gates finish, at the coordinator's request. Its command is:

```sh
python3 tools/test_project_graph.py --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
```

## Investigation supporting the dependent adapter

Retained scratch evidence is `/private/tmp/kvik-k08-kotest-investigation-n6t7z80e`; the upstream fixture and reference build were not modified. Direct `com.google.devtools.ksp.cmdline.KSPNativeMain` invocation with copied JSON test sources, local output/home/tmp directories, pinned CLI/processor closures, and Native klibs produced a byte-identical official launcher (1,161 bytes; SHA-256 `b2a079b3e71203ff3f5326dfb5c2985beaf80c71273afdd2c7391d1a17c733ba`). `ksp-json/command.json` retains the full invocation. KSP target spelling is `macosArm64`; the compiler uses `macos_arm64`.

Copies of the reference executables emitted exactly 1,558 root and 322 JSON distinct leaf identities. Their combined 1,880 identities matched both retained K01 Native cold/warm logs. This is reference protocol evidence, not a claim that Kvik rebuilt all upstream tests. `baseline-identities.json` retains the sorted IDs and reference digests.

The official generated launcher invokes Kotest during eager initialization: even `--ktest_list_tests` executed all 322 JSON tests. A separately compiled four-case FunSpec and a completion main proved one execution (`leaf\nmain\n` effect), one ordinary failure with exit 0, one ignored leaf, zero selection with exit 0, and a process crash with an unfinished leaf and no completion marker. See `lifecycle/results.json`; no passing result may be inferred from exit 0 alone.

Pinned engine sources reveal that raw TeamCity output omits ignored specs, does not identify ignored containers, and inserts synthetic tests for spec/container errors. The dependent adapter will therefore retain/hash the raw KSP output and perform a strict, versioned replacement of its single `invokeTestEngine` import with an adapter-owned typed-listener wrapper. The official spec list, config, and eager invocation remain intact. A separate fixed main proves startup returned. Typed callbacks distinguish leaves from containers and engine errors; a complete stream establishes observed discovery only after execution. Missing/truncated completion, crashes, unavailable leaf discovery, and zero executed leaves cannot pass. No pre-discovery total, remote cache eligibility, or full K08 completion is claimed here.
