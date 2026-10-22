# Application preparation contracts and graph

This slice adds optional target application settings, portable launch metadata,
runtime-closure graph construction and cacheable bundle preparation. It is the
artifact foundation for K15. Public application preparation/run commands and
foreground application replacement are still required; no application has been
executed by these gates.

The [lifecycle decision](../decisions/k15-application-lifecycle.md) defines the
separation between compilation, packaging and execution. `ProjectMode.APPLICATION`
requires explicit settings and authorized runtime resolution domains. JVM bundles
contain ordered main/runtime module classes, resources and locked runtime jars.
Native preparation adds a separate PROGRAM link from the main KLIB, all runtime
module/KLIB dependencies and the configured function entry point. Packaging
excludes compiler plugins, sources and Native link intermediates. Runtime arguments
and environment change the package identity without changing compilation/link
keys; a Native entry-point change changes the link identity.

The typed package implementation and real local-cache evidence are documented in
[application bundles](k15-application-bundle.md). Build/test modes preserve their
existing meaning. A successful preparation proves artifact production only.

## Contracts and schemas

From `/private/tmp/kvik-k15-application-contract`:

```sh
python3 -B tools/test_contracts.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B -m unittest discover \
  -s tests/contracts -p 'test_*schema.py'
```

All 34 Kotlin/Native tests across seven cases passed, including four new tests
covering project parsing, ordered launch metadata, Native executable requirements,
strict fields, Unicode/NUL rejection and payload bounds. All 19 schema tests passed,
including exact generator parity and regressions for trailing-newline paths.
The schema suite also passed after integration on main. Kotlin remains authoritative
for UTF-16 lengths, valid Unicode and total canonical UTF-8 byte limits.

The retained Native case is
`/private/tmp/kvik-k15-application-contract/.kvik/tests/contracts/case-by46xt39`.
Its receipt binds 18 Kotlin sources, the harness/bootstrap Python sources, compiler
launch inputs, Java executable, explicit environment, logs and executable. Compiler
and test processes use the bootstrap ownership guardian. This focused receipt does
not inventory every installed SDK/tool-support file; those installations must stay
stable during the gate.

- Receipt SHA-256: `51876e0b0cbf3d4bd540b92dfdedaefe953008c8d4512dc09bc150f969b154a1`.
- Executable SHA-256: `74a82a3967f54404d02e5c835a94d9dbb7daaac08176b620b1dfcafd5dbd0e97`.
- Independent committed-byte/receipt audit and 34-test replay:
  `/private/tmp/kvik-application-contract-receipt-review-3qus8hwx/audit.json`.
- Independent corrected-schema audit:
  `/private/tmp/kvik-application-schema-review-jzenqc6r/report.json`.

## Pure graph and Native action contracts

From `/private/tmp/kvik-k15-application-graph`:

```sh
python3 -B tools/test_project_graph.py \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --include-contracts \
  --application-actions /private/tmp/kvik-k15-application-bundle/integration/application/src/kvik/application/ApplicationActions.kt
```

All 53 tests across eight cases passed: 21 graph tests, two new Native action tests
and the then-existing 30 core contract tests. The four new root contract tests
above are additional, not part of this 53-test run. Application graph cases cover
API versus runtime closure, private/runtime-only dependencies, resources, exact
prerequisites, failed/malformed results, excluded plugin outputs, phase-specific
keys, explicit runtime domains and Native link budgets. These are typed graph and
command-contract tests; they do not invoke the consumer compiler/linker.

Retained case:
`/private/tmp/kvik-k15-application-graph/.kvik/tests/project-graph/case-kpe3kyxc`.
The build receipt binds 30 sources, including the exact external application action
builder, compiler launch inputs, explicit environment and fixture. All integrated
production bytes were independently reviewed; the external action builder did
not change during the bundle executor correction.

- Summary SHA-256: `b7f6c40b96bed6679da38a69b1feab3ad9b7cd9d57db4497684348585f84b5d0`.
- Executable SHA-256: `5b2519781d0c152eeec1c0a69412c5236f8dbbce8ea789b8dd58d5b13dee6399`.
- Independent receipt audit and 53-test replay:
  `/private/tmp/kvik-application-graph-review-eje68tvc/audit.json`.

The bundle implementation received a separate independent receipt audit and
13-test replay against a fresh real CAS:
`/private/tmp/kvik-application-bundle-review-8iuseefg/audit.json`.

## Integration boundaries

The bootstrap composition, source-graph example content/Kotlin roots and workspace
plugin test harness now include `integration/application/src`. The latter also
includes the pure KSP action declarations already required by the project graph.
This source wiring is separate from public CLI dispatch and foreground lifecycle.
Actual JVM/Native application compilation, execution, crash/reload recovery,
descendant cleanup, concurrent sessions and application-running source-plugin
handoff remain mandatory subsequent gates.

The integrated source composition compiled successfully through the public
launcher in 44.304 seconds, then its `runtime-info` command ran successfully both
directly and through an offline `--no-build` cache hit. The composition was
`fef27d0d4eab3922c8d5ead15511756628676e29e6d6b0715b63d5d37fc6d641`;
its 4,838,664-byte Native executable has SHA-256
`b170abb9aaa49f9b990f0e3e9545bb8179ad4a5143ddde2e459f797aba3c7b3b`.
Runtime manifests, lock, JVM worker and executable remain under
`.kvik/tests/application-runtime/runtime/executables/<composition>/` on main;
`.kvik/tests/application-runtime/summary.json` records exact read-only launch
commands, environments and output hashes. The controlled-environment replay uses
the explicit Python executable: an initial `/usr/bin/env python3` replay selected
the older system Python without `hashlib.file_digest` and failed before runtime
selection; that failed attempt is retained separately and is not passing proof.

Independent post-merge comparison of 20 integrated files and the three source
wiring changes is retained at
`/private/tmp/kvik-application-merged-audit-sv6ltak7/audit.json`.
The full workspace-plugin consumer gate was not rerun in this slice. This Native
composition gate covers the artifact foundation before the subsequent
application-verification grammar extension; it is not evidence for application
execution or later source compositions.
