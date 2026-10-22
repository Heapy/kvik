# Composed JVM foreground integration

The integrated Kotlin/Native runtime now has actual JVM foreground evidence in
addition to the [Native lifecycle fixture](k15.md). Composition
`21e25288040f1c25dae89eab37312c77cf4604dd91f056ecf464b8b2dba22e48`
includes the reviewed foreground implementation, bootstrap context contract,
parent-POM resolver and JDK-release support. Native construction reported
37,293 ms. This run's JVM fixture uses its existing compiler settings; it does
not add a release11 API claim for that fixture.

Reproduction:

```sh
.kvik/schema-tools/bin/python tools/test_foreground_jvm.py \
  --runtime .kvik/runtime/executables/21e25288040f1c25dae89eab37312c77cf4604dd91f056ecf464b8b2dba22e48/kvik.kexe \
  --fixture .kvik/tests/build-cli/case-kcswfrtl
```

The harness copies only declared fixture inputs and tools configuration into a
fresh project, then resolves an isolated external store before starting `dev`.
The supplied fixture and copied inputs are checked unchanged afterward. This
copy includes `library/common/New.kt`, added by the earlier one-shot freshness
probe; the exact eight-file input inventory is retained. It is not represented
as the earlier fixture's pre-mutation snapshot.

Retained results are under
`/private/tmp/kvik-k15-jvm-integration/.kvik/tests/foreground-jvm/case-g__ymzxa`.
The session is `project/.kvik/sessions/kvik-run-4FGWt7`:

- First run `kvik-run-W0DfFV/runs/kvik-run-SjZafU` executed all four actions:
  library production, consumer production, consumer tests compilation and tests.
- A JSONL `t` request produced `kvik-run-w68SQS/runs/kvik-run-pnWcqt`: three
  compilation hits and a freshly executed test, with unchanged action keys.
- Both runs passed exactly
  `[engine:junit-jupiter]/[class:fixture.PassingTests]/[method:dependencyAndResource()]`.
  The test exercises the common/actual dependency and a nonempty resource, and
  writes `effects.txt` in each distinct invocation workspace. Both effects were
  present; cached compilation did not substitute for test execution.
- `status` responded during the first operation in 0.049 seconds. The
  two-second observation window after the second run produced no extra operation.
  `q` exited successfully, with complete session journal/stdout agreement.
- Both child verification records and event streams passed their schemas and
  sequence checks. A post-exit verification query reported current/0. Source,
  configuration, tools-document and resolved lock bytes stayed unchanged.

The session plus final verification took 217.785 seconds, excluding resolution.
These are observations under concurrent development work, not accepted latency
benchmarks. The same composed runtime also passed ten dependency CLI regressions
in 9.700 seconds and six inspection checks in 1.029 seconds.

This establishes the composed library JVM path and fresh tests after local
compilation reuse. It does not complete K15: source-plugin recomposition,
application restart, raw-key controls and graph retention remain separate work.
The detailed ownership and filesystem limits in the Native lifecycle evidence
continue to apply.
