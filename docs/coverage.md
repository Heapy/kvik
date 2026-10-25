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

The normalized report preserves original source paths, modules, source sets and content digests. Each source line has one of five states:

- `covered`: at least one measured instruction on the line executed.
- `uncovered`: the line contains executable instructions, with none measured as executed.
- `non_executable`: the mapped line has no executable instructions.
- `unknown`: a trustworthy measurement or source mapping is unavailable.
- `excluded`: only explicitly excluded classes contribute executable instructions to this line; it is omitted from coverage counters.

Missing or incomplete execution data, mismatched class bytes, missing debug information and ambiguous source names remain unknown, with diagnostics. Unknown lines never count as uncovered lines or complete coverage. Generated code without a declared source mapping also prevents a complete normalized report.

The output contains `coverage.json` (normalized data), `coverage/<module>/jacoco.xml` (original JaCoCo XML), `worker-result.json`, and `graph.json` (the report-generation actions and outcomes). Original execution bytes are retained as `coverage.exec` when available. Missing execution bytes produce an incomplete report with unknown lines; no execution data is invented. The normalized capability is **line coverage only**. JaCoCo XML retains its instruction, branch, method and class counters; those are not advertised as normalized capabilities. Kotlin source mapping follows the pinned JaCoCo version's filters and debug information.

Coverage completeness is separate from test success. A failed, incomplete or cancelled test execution keeps that outcome; reporting cannot turn it into a passing run. Retained freshness describes completion time and does not verify today's source tree. Use `verification --run PATH` for current source/tool freshness.

The CLI response lists the output artifacts and both the source-run and report statuses. Its schema is `coverageReportExport`; `coverageReportGraph` describes `graph.json`. The data contracts are `coverageReport`, `jvmCoverageSpec`, `coverageSource`, `coverageClassRoot`, `sourceCoverage` and `coverageLine` in `schemas/v1/contracts.json`; `schemas/v1/coverage.json` selects the normalized report. `coverage-publish` sends retained report summaries to GitHub Checks without rerunning tests. Compatible reports can be combined with `coverage-aggregate` as described below.

## Exclude classes

Use repeatable class-name patterns when capturing JVM coverage:

```sh
kvik test --project /path/to/kvik.json --tools /path/to/tools.json \
  --module library --target jvm --coverage \
  --coverage-exclude 'example.generated.*' --coverage-exclude 'example.Model$*'
```

The patterns follow [JaCoCo class-name matching](https://www.jacoco.org/jacoco/trunk/doc/agent.html):
`*` matches any number of characters and `?` matches one. Dotted and slash-separated
package names are accepted; match nested classes with their JVM `$` names. Quote
patterns so the shell does not expand wildcards or `$`. At most 100 unique patterns
are accepted. The flag requires `--coverage`, also works with `verify --check test`,
and applies to all production class roots in the selected test actions.

Kvik applies the same policy to instrumentation and report analysis. Original
JaCoCo XML omits excluded classes. Normalized JSON retains every source and marks
executable lines belonging only to excluded classes as `excluded`. If included
and excluded classes contribute instructions to the same line, only the included
instructions determine its coverage. Comments and other non-executable lines keep
their own classification. Missing debug information or incomplete execution stays
unknown rather than becoming an exclusion.

Excluded lines contribute neither covered nor executable counts. Excluding all
executable lines does not satisfy a threshold, even a zero-percent threshold.
Sonar XML omits excluded lines and records that loss of classification in its JSON
sidecar; original JaCoCo exports preserve the already filtered producer report.

Patterns are retained in the coverage specification and reproduction command.
Changing them changes test and report action identities while allowing ordinary
compilation reuse. Tests still execute freshly. Aggregation rejects different
exclusion policies or contradictory excluded-line maps; it cannot silently merge
an excluded line with a measured line. Historical reports without exclusions
remain readable.

## Check a threshold

Evaluate the retained report without rerunning tests or starting a JDK:

```sh
kvik coverage-check --report /path/to/coverage-directory/coverage.json \
  --metric line --minimum 80.25 --all-modules --output /path/to/new-threshold-directory
```

Use repeatable `--module ID` instead of `--all-modules` to select production modules within this report. The minimum is a decimal from 0 to 100 with at most two fractional digits; `80.25` is stored as 8025 basis points. The comparison uses exact integer arithmetic, without rounding a displayed percentage.

Only `line` is currently supported. `branch` and `function` produce a structured `unsupported` result rather than borrowing JaCoCo counters that lack normalized source mapping. The command verifies the original report artifacts against their retained digests and requires a new output directory outside the report directory.

For a single report, selected modules contribute one combined line ratio, not an average of module percentages. A shared logical source path counts once only when its content digest, line count and every line state agree across the selected modules. Conflicting copies yield an incomplete result. For an aggregate, checks use its already merged source lines separately for each selected target.

A passing threshold requires retained sources for every selected module, complete, current-at-completion coverage from a passing source run, and at least one executable line. Unknown lines, partial reports and zero executable lines cannot pass, even with a zero-percent minimum. Checking a historical report does not establish freshness against today's sources.

The output contains `threshold.json`, `evaluation.json`, `policy.json` and `graph.json`, including when coverage is below the threshold. Stdout returns the structured result and output artifact manifest. Exit codes follow the result status: 0 for passed, 1 for failed, 3 for incomplete, and 130 for cancelled. Unsupported metrics normally yield incomplete. A failed, incomplete or cancelled source report preserves its status even when the measured ratio passes or the metric is unsupported. Invalid command arguments use exit code 2.

The JSON contracts are `coverageThresholdPolicy`, `coverageThresholdResult`, `coverageThresholdExport` (stdout) and `coverageThresholdGraph` in `schemas/v1/contracts.json`. The result retains the source-report digest, selected policy, measured counts and diagnostics; `outcome` describes the comparison while `status` also preserves the source report’s outcome.

## Aggregate coverage across checks

Combine compatible reports while retaining an explicit list of expected test checks:

```sh
kvik coverage-aggregate \
  --run /path/to/run-a --run /path/to/run-b \
  --report /path/to/report-a/coverage.json --report /path/to/report-b/coverage.json \
  --expect library:jvm:test --expect app:jvm:test \
  --output /path/to/new-aggregate-directory
```

For one retained run, the default expected matrix is its requested test checks. With multiple runs, repeat `--expect module:target:test` to state the complete expected matrix. Available report files never define completeness. Repeat `--module ID` and `--target ID` to filter that matrix; unmatched selectors are rejected. Reports are optional so an explicitly expected check can be recorded as missing. At most one report may contribute to each selected check, including across repeated runs.

The command requires compatible input snapshots, configurations, dependency locks and runtime compositions. Overlapping toolchain identities must agree. Each report must match the exact committed verification from one supplied run, and each selected report’s original artifacts must still match their digests. Aggregation reads retained data without running tests or tools. It does not combine arbitrary CI jobs from different source or build configurations.

Within each JVM target, matching logical source paths are counted once. Their content digests, line counts and executable-line maps must agree. Contributors that share a production module must declare the same source inventory for that module. A line is covered if any contributor observed it covered, but an unknown observation remains unknown even when another contributor observed a hit. A non-executable versus executable mapping conflict is rejected. Sources retain their module/source-set owners and contributing check IDs. Coverage is never combined across targets.

Missing checks remain visible. Unsupported targets remain both missing and unsupported. A target with missing or incomplete measurements has null counters rather than a partial percentage. Data completeness is separate from source-run success: failed, cancelled or incomplete verification cannot become passed through aggregation.

The new output directory contains `aggregate.json`, `report.json` (the exported aggregate), `scope.json` and `graph.json`. Selected reports and their originals are copied under `contributors/<report-hash>/`; supplied run records are copied under `runs/<runId>/` as `verification.json`, `completion.json` and `events.jsonl`. The aggregate embeds each canonical contributor report with its digest. Stdout identifies the aggregate digest, status, completeness and output artifacts. Valid negative results are saved: exit codes are 0 for passed, 1 for failed, 3 for incomplete and 130 for cancelled.

Aggregation currently exports line coverage only. Check an aggregate with an explicit target selection:

```sh
kvik coverage-check --report /path/to/aggregate-directory/aggregate.json \
  --metric line --minimum 80 --all-targets --all-modules \
  --output /path/to/new-aggregate-threshold-directory
```

`report.json` from the aggregate directory is also accepted. Aggregate checks require repeatable `--target ID` or `--all-targets`. A single coverage report keeps its existing behavior; an explicit `--target jvm` or `--all-targets` is optional there. Each check also requires repeatable `--module ID` or `--all-modules`.

For aggregate checks, `--all-modules` selects production roots from contributors together with expected-check modules, separately for each selected target. An explicit module must exist somewhere in the aggregate; absence on a selected target produces an incomplete result. Each target gets its own policy and line ratio. A logical source path counts once within that target, and counters are never pooled across targets. Incomplete target measurements retain null counters; unsupported targets or metrics produce an `unsupported` outcome.

The overall check preserves the whole aggregate's source status, even when selecting fewer targets or modules. To gate an independently scoped subset, first create an aggregate with the desired `coverage-aggregate --module` and `--target` filters, then check that aggregate. Threshold checking verifies retained contributor reports and originals without rerunning tests.

Aggregate checks write the same threshold artifacts as single-report checks. `coverageThresholdExport.result` is either a `coverageThresholdResult` or a `coverageAggregateThresholdResult`; the aggregate form contains a `coverageAggregateThresholdPolicy` and separate `coverageAggregateThresholdTargetResult` entries.

The aggregate contract is `coverageAggregate` in `schemas/v1/contracts.json`, with supporting `coverageAggregationScope`, `coverageContribution`, `coverageMergedSource` and `coverageAggregateTarget` definitions. `coverageAggregateExport` describes stdout and `coverageAggregateGraph` describes `graph.json`.

## Export for SonarQube

Convert retained line coverage to SonarQube's generic coverage format:

```sh
kvik coverage-export --report /path/to/coverage-directory/coverage.json \
  --format sonar-generic --all-modules --output /path/to/new-sonar-directory
```

For `aggregate.json` or its exported `report.json`, supply exactly one `--target ID`. A single coverage report defaults to JVM and permits an explicit `--target jvm`. Repeat `--module ID` instead of `--all-modules` to select modules. Aggregate `--all-modules` includes contributor production roots and expected-check modules for the selected target. An explicit module must exist somewhere in the aggregate; absence on the selected target yields an incomplete export. Targets are never pooled.

When source mappings and measurements are complete, the output contains `report/coverage.xml`, `report/export.json` and `graph.json`. The XML uses [SonarQube's generic coverage version 1 format](https://docs.sonarsource.com/sonarqube-server/analyzing-source-code/test-coverage/generic-test-data). Configure the scanner with:

```properties
sonar.coverageReportPaths=/path/to/new-sonar-directory/report/coverage.xml
```

XML source paths remain relative to the Kvik project root. Configure the scanner's project root accordingly. The XML includes executable source lines with their covered state; optional branch attributes are omitted. Branch or function measurements are not invented. The original JaCoCo XML files remain available separately for consumers that import their richer counters directly.

Sonar's generic coverage format cannot preserve unknown line states. No XML is written for missing sources, unknown or partial measurements, incompatible duplicate source mappings, or unsupported targets. The command still saves `report/export.json` and `graph.json` with the incomplete or unsupported outcome. Known sources with zero executable lines can be exported as valid XML; this differs from a threshold check, which requires a nonzero denominator to pass.

Data export and source-run success are separate. Complete XML can accompany a failed, cancelled or stale source result; the metadata retains that status. For an aggregate, the whole aggregate's source status remains authoritative even when exporting one target or a subset of modules. Keep `export.json` with the XML: provenance, module and target selection, status and conversion losses live in that sidecar and cannot be fully represented in the consumer XML.

The output directory must be new and outside the source report directory. Export verifies retained reports and original artifacts; it does not start a JDK, rerun tests, contact SonarQube or publish results. Exit codes follow status: 0 passed, 1 failed, 3 incomplete, 130 cancelled; invalid arguments use 2. An unsupported export normally has incomplete status, while a source failure or cancellation keeps its original status.

The JSON definitions are `coverageConsumerExportResult` for `report/export.json`, `coverageConsumerExport` for stdout and `coverageConsumerExportGraph` for `graph.json` in `schemas/v1/contracts.json`.

## Export original JaCoCo XML

Copy the retained producer reports for a consumer that accepts JaCoCo XML:

```sh
kvik coverage-export --report /path/to/coverage-directory/coverage.json \
  --format jacoco-xml --all-modules --output /path/to/new-jacoco-directory
```

Target and module selection follow the same rules as Sonar export: an aggregate requires exactly one `--target ID`, and every export requires repeatable `--module ID` or `--all-modules`. Only JVM originals are supported.

Files are written under `report/jacoco/<test-module>/jvm/test/<production-module>.xml`. Each contributing test check retains its own report for each selected production module. If two checks cover the same module, both scoped originals are copied deliberately. The command does not merge XML, re-encode it, or synthesize aggregate counters.

Original bytes and digests are preserved, including JaCoCo's [instruction, branch, complexity, line, method and class counters](https://www.jacoco.org/jacoco/trunk/doc/counters.html). These are full producer reports, rather than the normalized line-only output used by Kvik's thresholds or Sonar conversion. The metadata records `format: "jacoco-xml"` and an empty `conversionLosses` list.

For this format, `complete` means all requested original XML files are available. Unknown or incomplete normalized line coverage does not prevent copying available originals; the source report's incomplete, failed or cancelled status is still preserved. Missing expected contributors on the selected target block export even when selecting fewer modules. A missing selected production root or original XML also yields no XML and an incomplete result; unsupported targets yield no XML and an unsupported result.

All originals are verified against retained digests. Selected XML is bounded to 64 MiB in total. `report/export.json`, `graph.json`, stdout and exit codes follow the same contract as Sonar export. This is a local copy operation with no JDK, network, test execution or service publication.

## Publish a GitHub Check

Publish a retained single report or aggregate as a GitHub Check:

```sh
# Supply a token with repository Checks: write permission through the environment.
kvik coverage-publish --report /path/to/coverage-directory/coverage.json \
  --repository owner/repository --sha 0123456789abcdef0123456789abcdef01234567 \
  --output /path/to/new-publication-attempt
```

An aggregate uses its `aggregate.json` or `report.json` instead. The whole report
scope is published; use `coverage-aggregate` first to choose contributors and an
expected matrix. The summary counts shared sources once per target, retains
missing and unsupported contributors, and leaves incomplete counters unknown.
Targets are never pooled. The check describes measurement completeness and the
retained source-run outcome. It does not evaluate a coverage threshold; use
`coverage-check` separately to enforce one.

The default check name is `Kvik coverage`. `--name`, `--api-url` and `--token-env`
follow the [test publication options](test-reports.md#publish-a-github-check),
including the explicit commit association and single-attempt transport policy.
The publisher uses GitHub's [Create a check run API](https://docs.github.com/en/rest/checks/runs#create-a-check-run).
A passed, failed, incomplete or cancelled report produces a `success`, `failure`,
`action_required` or `cancelled` conclusion respectively. No current project,
compiler or test process is loaded, and publication never establishes that a
historical report describes today's sources or the caller-supplied commit.

Before the HTTP request, the attempt directory retains the original input bytes
as `source/report.json`, every referenced original artifact under `source/`, and
`request.json`, `action.json` and `intent.json`. Aggregate contributors retain
their reports and originals under `source/contributors/<report-hash>/`.
Every original is checked against its recorded digest before sending. Each file
is bounded to 64 MiB and the retained attempt inputs to 256 MiB. The action is
`publish` with cache policy `never`; the token is transport-only.

`outcome/result.json` and stdout use the existing `githubCheckPublication` schema.
Exit 0 means the check was published, independently of the coverage outcome in
`sourceStatus`. Rejection or an unknown remote result returns 3; cancellation
returns 130. Server response bodies and credentials are not retained.

After checking whether an ambiguous attempt already created a remote check,
retry from its self-contained inputs with a fresh attempt directory:

```sh
kvik coverage-publish --report /path/to/previous-attempt/source/report.json \
  --repository owner/repository --sha 0123456789abcdef0123456789abcdef01234567 \
  --output /path/to/another-attempt
```

This works even after the original report directory is removed. There is no
automatic POST retry or deduplication; another attempt can create another check.
The retained reports remain usable regardless of the publication result.
