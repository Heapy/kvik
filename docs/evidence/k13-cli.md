# Workspace compiler plugins through the public CLI

The source-built Kotlin/Native runtime resolves, compiles and tests the workspace
plugin fixture through public `resolve`, `test` and `verification` commands.
The fixture is copied from tracked sources; no Maven publication, nested build
tool or changes to the original fixture are required.

Runtime composition:
`d359ee862035119875cbab737e4f1e06fda7e631250d96d877edd88372a43751`.
Executable SHA-256:
`3ffbb2b3a527e8d702a5a8164084259309d2b89a4bb1a742b7ef14d3b84f487e`
(4,028,168 bytes). Native construction took 62,604 ms. This composition contains
the integrated K13 adapter and resolver, before the later foreground handoff
and JVM tool-verification changes.

Retained evidence:
`/private/tmp/kvik-k13-cli-integration/.kvik/tests/workspace-plugins-cli/case-g16mhb1r`.
The first run is `runs/kvik-run-BH4SZ9`; the repeat is `runs/kvik-run-Jsa67C`.

- A fresh store and lock resolution preceded 13 executed actions: ten
  compilations and three tests.
- The repeat restored all ten compilations and executed all three tests again.
  Every action key remained identical between runs.
- Each consumer reports the exact JUnit identity
  `[engine:junit-jupiter]/[class:fixture.consumer.ConsumerTest]/[method:transformedProductionAndTestHaveExpectedBehavior()]`,
  scoped separately to `consumer` and `consumerTwo`. The third is
  `[engine:junit-jupiter]/[class:fixture.unrelated.UnrelatedTest]/[method:ordinaryBehavior()]`.
- The producer uses JVM target/API release 25; consumers use release 11. Both
  production and test compilations receive the declared workspace plugin.
  Four generated reports contain the expected ordered options and helper,
  private-dependency, runtime-only dependency and input-file effects.
- Action keys, all output CAS bytes, complete event journals and completion
  digests were rechecked. Events and verification records validate against the
  public schemas. Plugin outputs are absent from ordinary test classpaths.
- The final verification query returns `current`; all 14 copied input/config/
  tool files, the dependency lock and the original fixture remain unchanged.

Independent review of the harness and retained evidence passed: 26 action keys,
62 output/CAS identities, exact test IDs, compiler release settings, plugin
reports, runtime classpath separation and complete journal/verification
identities were checked across both runs. No compiler rerun was needed for
that audit.

Observed durations: resolution 34.883 s, cold test 656.612 s, repeated test
144.647 s, and final freshness query 24.209 s. These single observations occurred
under concurrent local load and are not a benchmark. The tested runtime freshly
verified all six installed tool inventories even for JVM actions. The separate
JVM verification-scope improvement was not part of this executable or result.

Reproduce with the retained graph request and tool document:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python tools/test_workspace_plugins_cli.py \
  --runtime /Users/yoda/dev/pet/kvik/.kvik/runtime/executables/d359ee862035119875cbab737e4f1e06fda7e631250d96d877edd88372a43751/kvik.kexe \
  --fixture /Users/yoda/dev/pet/kvik/tests/workspace-plugins/fixture \
  --graph-request /private/tmp/kvik-k13-workspace-plugins/.kvik/tests/workspace-plugins/case-1173e5ef/first.request.json \
  --tools /Users/yoda/dev/pet/kvik/.kvik/tests/build-cli/case-kcswfrtl/tools.json \
  --schema-root /Users/yoda/dev/pet/kvik
```

The harness creates a fresh case/store each time and retains commands and raw
streams. It adds only explicit test selectors to the retained project model.
Implementation/input invalidation, scope, negative worker and producer-failure
cases are covered by `k13-workspace-plugins.md`; this gate verifies their public
CLI integration. Script/Native consumers, plugin-specific cancellation and
remote cache eligibility remain separate work.
