# K12 script graph and execution evidence

Status: isolated implementation; focused Native validation passed. Public script acceptance remains pending.
This does not enable `run-script` on main.

`scriptActionGraph` constructs `script:NAME:jvm:compile` and `script:NAME:jvm:run`
without publishing a synthetic module. Required workspace modules and plugin producers
use the existing module graph. The script compilation sees exported compile dependencies;
the runtime receives the full runtime closure, including private dependencies and resources.
Workspace plugin classes, service resources, dependencies and declared inputs stay in the
compiler plugin classpath. Standalone companions use their own retained authoring document.

Compilation is `COMPILE/LOCAL`. Source, helper, resource, dependency, compiler-plugin and
tool/composition identities participate in its key. Main selection, runtime argv, environment
and timeout are carried only by the `SCRIPT/NEVER` action. The runtime adapter must discover
or validate main from the script's own compiled classes without loading them, and perform a
fresh owned launch. The graph alone does not prove entrypoint discovery or child execution.

Run evidence recognizes distinct `script:NAME:jvm:prepare` and `script:NAME:jvm:run`
checks. The graph currently emits only the latter. A run check requires its canonical run
terminal and rejects local/remote hits, including incomplete coordinator records with no
terminal mapping. Persisted completed script checks require that exact executed action.
Compilation success or preparation never supplies execution proof. The existing record
format still relies on the coordinator's action identities; it is not an independently
authenticated process attestation.

The independent source review inspected graph, typed action and evidence logic, including
tests. It found and verified corrections for a missing repository in a standalone fixture,
launch-name validation, and aggregate/parser consistency for a cached result without a
terminal mapping. Source-only approval through `37549f0` is retained at
`/private/tmp/kvik-k12-graph-source-review.json`, SHA-256
`f2b2febb036fd90892c188a93bed355aa1b268d5c6192aa401eba6cd3be4658b`.
This review did not execute Kotlin or a public CLI command.

Focused verification includes nine script graph tests, four script evidence tests and the
existing graph, core-contract and run-evidence suites. Reproduction:

```sh
/Users/yoda/dev/pet/kvik/.kvik/schema-tools/bin/python -B tools/test_project_graph.py \
  --java-home /Users/yoda/.sdkman/candidates/java/25.0.4-librca \
  --konan-home /Users/yoda/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20 \
  --include-contracts --include-evidence
```

The harness binds compiler inputs, environment, source files and its own support code to
the resulting binary. Its model outputs are deliberate fixtures, not compiled consumer
classes. Real standalone, workspace-dependency and compiler-plugin invocations, repeated
compile hits with fresh execution, invalidation, child statuses/streams, timeout/cancellation
and retained CLI verification require the later integrated runtime acceptance gate.

Retained Native gate at `/private/tmp/kvik-k12-script-graph/.kvik/tests/project-graph/case-vt9msboe`
passed all 106 tests in 15 classes (583 ms), including the nine script graph and four script
evidence cases. Build receipt SHA-256 is
`681601c12d17ae379b9879f6f842091c52ecd6688c5a2bc3ef7907b51882f110`;
runner SHA-256 is `5a099c9ed872b2e5e9b43ed0bb9b328ca488e2e9e8b4e0d7f2836686e9ae157a`.
An independent audit matched 42 committed Kotlin files, two harness files, five compiler/support
files, two fixture outputs, runner, log and summary. Its report is
`/private/tmp/kvik-k12-graph-receipt-review-b1it_o_q/report.json`, SHA-256
`b005dc88d934456835d53135eef630b89d0003747a6c8198481fe5c58666890c`.
The executor implementation is outside this gate: supplying the pinned standard library to
actual script compilation needs the separate public CLI acceptance.
