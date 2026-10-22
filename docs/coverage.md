# JVM line coverage

Capture coverage while running JVM tests:

```sh
kvik test --project /path/to/kvik.json --tools /path/to/tools.json \
  --module library --target jvm --coverage
```

Tests still execute on every invocation. Compilation may reuse its normal cache. Coverage uses the pinned JaCoCo 0.8.14 agent; its identity and the original production classes and source digests are part of the test action. Coverage is opt-in and currently supports JVM tests only. Native targets and mixed-target selections reject `--coverage`.

Generate a report from the retained run:

```sh
kvik coverage-report --run /path/to/.kvik/runs/RUN_ID \
  --module library --tools /path/to/tools.json --output /path/to/new-coverage-directory
```

Select a module when the run contains multiple JVM test scopes. Each report describes one test scope, including the production modules recorded in that scope's coverage specification. The output directory must be new and outside the retained run. The tools file selects the verified JDK and pinned coverage reporter. Report generation analyzes retained execution data, classes and sources; it does not rerun tests or compile current sources.

The normalized report preserves original source paths, modules, source sets and content digests. Each source line has one of four states:

- `covered`: at least one measured instruction on the line executed.
- `uncovered`: the line contains executable instructions, with none measured as executed.
- `non_executable`: the mapped line has no executable instructions.
- `unknown`: a trustworthy measurement or source mapping is unavailable.

Missing or incomplete execution data, mismatched class bytes, missing debug information and ambiguous source names remain unknown, with diagnostics. Unknown lines never count as uncovered lines or complete coverage. Generated code without a declared source mapping also prevents a complete normalized report.

The output contains `coverage.json` (normalized data), `coverage/<module>/jacoco.xml` (original JaCoCo XML), `worker-result.json`, and `graph.json` (the report-generation actions and outcomes). Original execution bytes are retained as `coverage.exec` when available. Missing execution bytes produce an incomplete report with unknown lines; no execution data is invented. The normalized capability is **line coverage only**. JaCoCo XML retains its instruction, branch, method and class counters; those are not advertised as normalized capabilities. Kotlin source mapping follows the pinned JaCoCo version's filters and debug information.

Coverage completeness is separate from test success. A failed, incomplete or cancelled test execution keeps that outcome; reporting cannot turn it into a passing run. Retained freshness describes completion time and does not verify today's source tree. Use `verification --run PATH` for current source/tool freshness.

The CLI response lists the output artifacts and both the source-run and report statuses. Its schema is `coverageReportExport`; `coverageReportGraph` describes `graph.json`. The data contracts are `coverageReport`, `jvmCoverageSpec`, `coverageSource`, `coverageClassRoot`, `sourceCoverage` and `coverageLine` in `schemas/v1/contracts.json`; `schemas/v1/coverage.json` selects the normalized report. Coverage thresholds, exclusions through CLI settings, merging scopes or CI runs, and publishing to external services are separate features.
