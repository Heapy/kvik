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

The CLI response lists the output artifacts and both the source-run and report statuses. Its schema is `coverageReportExport`; `coverageReportGraph` describes `graph.json`. The data contracts are `coverageReport`, `jvmCoverageSpec`, `coverageSource`, `coverageClassRoot`, `sourceCoverage` and `coverageLine` in `schemas/v1/contracts.json`; `schemas/v1/coverage.json` selects the normalized report. Exclusions through CLI settings, merging separate reports or CI runs, and publishing to external services remain separate features.

## Check a threshold

Evaluate the retained report without rerunning tests or starting a JDK:

```sh
kvik coverage-check --report /path/to/coverage-directory/coverage.json \
  --metric line --minimum 80.25 --all-modules --output /path/to/new-threshold-directory
```

Use repeatable `--module ID` instead of `--all-modules` to select production modules within this report. The minimum is a decimal from 0 to 100 with at most two fractional digits; `80.25` is stored as 8025 basis points. The comparison uses exact integer arithmetic, without rounding a displayed percentage.

Only `line` is currently supported. `branch` and `function` produce a structured `unsupported` result rather than borrowing JaCoCo counters that lack normalized source mapping. The command verifies the original report artifacts against their retained digests and requires a new output directory outside the report directory.

Selected modules contribute one combined line ratio, not an average of module percentages. A shared logical source path counts once only when its content digest, line count and every line state agree across the selected modules. Conflicting copies yield an incomplete result. This combines modules within one report; it does not merge separate test scopes or runs.

A passing threshold requires complete, current-at-completion coverage from a passing source run and at least one executable line. Unknown lines, partial reports and zero executable lines cannot pass, even with a zero-percent minimum. Checking a historical report does not establish freshness against today's sources.

The output contains `threshold.json`, `evaluation.json`, `policy.json` and `graph.json`, including when coverage is below the threshold. Stdout returns the structured result and output artifact manifest. Exit codes follow the result status: 0 for passed, 1 for failed, 3 for incomplete, and 130 for cancelled. Unsupported metrics normally yield incomplete. A failed, incomplete or cancelled source report preserves its status even when the measured ratio passes or the metric is unsupported. Invalid command arguments use exit code 2.

The JSON contracts are `coverageThresholdPolicy`, `coverageThresholdResult`, `coverageThresholdExport` (stdout) and `coverageThresholdGraph` in `schemas/v1/contracts.json`. The result retains the source-report digest, selected policy, measured counts and diagnostics; `outcome` describes the comparison while `status` also preserves the source report’s outcome.
