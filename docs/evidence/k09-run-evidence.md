# K09 coordinator evidence aggregation

The pure implementation is `integration/run-evidence`. No core, compiler, CLI, scheduler or cache files are changed. It exposes `aggregateRunEvidence(...)` and strict `verificationRecordFromJson(...)` for the coordinator and its persisted-verification query.

Reproduction on the provisioned host:

```sh
python3 tools/test_run_evidence.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
```

The Kotlin/Native suite contains thirteen tests:

- Exact snapshot/configuration/lock/composition/toolchain/action identities and deterministic ordering.
- Build-only complete zero counts and compilation cache hits.
- Missing initial setup, mappings, prerequisites, action results and test observations stay incomplete; cancellation remains explicit.
- Failed compilation/prerequisites or real tests take failure precedence over incomplete reports and stale freshness; container failures can have zero failed leaves.
- Per-check proof across JVM and Native scopes prevents another module's executed tests masking zero, skipped-only, missing or incomplete discovery.
- Stale and indeterminate snapshots never pass; cancellation has precedence over failures.
- Contradictory report statuses/counts, foreign/duplicate IDs, cached tests, invalid graph/result evidence and omitted planned actions cannot prove success.
- Canonical persisted-record roundtrips retain every field and status; unknown/missing/mistyped nested fields, invalid enums/digests/integers, duplicate evidence and contradictory passing records are refused.

These tests use synthetic scheduler results and adapter-neutral reports to exercise the evidence boundary. They do not execute consumer compilers/tests or prove filesystem freshness, CAS content or persisted-record authenticity. The coordinator must bind those inputs to its actual run. The complete API and failure semantics are documented in `integration/run-evidence/README.md`.

Observed: all thirteen Kotlin/Native tests passed. Python harness syntax and staged diff checks also passed.
