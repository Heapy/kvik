# Retained test reports

Export a completed run without loading its project or starting a compiler or test process:

```sh
kvik test-report --run /path/to/.kvik/runs/RUN_ID --output /path/to/new-report-directory
kvik test-report --run /path/to/.kvik/runs/RUN_ID --output /path/to/selected-report --module library --target jvm
```

The default format is `junit-xml`; `--format json` exports the normalized result. Module and target options may be repeated. Selection is limited to the test scopes requested by that run. An absent module or target is an error. The output directory must be new and outside the retained run.

The directory contains `report.json`, `junit.xml` (or `export.json`) and `graph.json`. It also retains the original `verification.json`, `completion.json`, `events.jsonl` and referenced action logs under `runs/<runId>/`. Aggregation and export are separate graph actions. Their inputs include the committed run records, log identities, scope selection and implementation identity; export consumes the normalized report artifact. These transformations use the local action cache, with `executed` or `local_hit` outcomes in `graph.json`. They do not rerun tests or turn historical results into verification of current sources. The command exit status describes report generation; the JSON response separately identifies the source run and report statuses.

Resolve a log reference as `runs/<runId>/<log.path>`. Shared action logs are copied once even when several tests or attempts reference them. The bundle retains the complete supplied run, including when filters narrow the displayed report, so it can be regrouped later. To re-export after moving the bundle or deleting the original run, use `--run /path/to/bundle/runs/<runId>`. Missing, changed, conflicting or symlinked logs reject export before creating the output directory, including on cache hits. Each log is limited to 64 MiB and the complete output bundle to 256 MiB.

`test-report` accepts `--store PATH` and defaults to `.kvik/cache` under the current
working directory. The store must be outside the retained run and output
directories. Each call validates the retained run before consulting the cache.
Changing only the export format reuses the normalized report. Negative source
and report statuses remain negative on cache hits; cache corruption or an
unavailable store falls back to local transformation.

Filtering narrows the reported test cases but conservatively retains the source run's failed, incomplete or cancelled overall status. Individual scope outcomes remain available separately. New records retain each observed test scope’s status and discovery counts, including when another scope fails or execution stops early. Older records contain only aggregate discovery counts, so their per-scope discovery counts remain unknown when a run requested multiple test scopes. JSON schemas are published in `schemas/v1/contracts.json` as `testReport`, `testReportGraph` and `testReportExport`.

Test results retain the final outcome and every observed attempt. Unique test counts count final outcomes once per module/target/test identity; attempt counts include retries separately. Any failed attempt prevents passing verification, even if a later attempt passes. JVM/JUnit supports opt-in `testSettings.maxRetries` (0–9, default 0): a retry reruns the selected test scope and retains every attempt, including passing siblings. A runner's own retries are recorded when its protocol exposes them. Old records remain readable, with unavailable attempt metadata and durations explicitly unknown.

New runner results include elapsed durations when available, suite and display names, skip reasons, and bounded structured failures. Text fields are limited to 8,192 UTF-16 code units and stack traces to 32,768; at most 1,000 attempts are retained per test. Attempts reference the action's shared stdout/stderr logs, rather than claiming those streams were isolated per test. References contain paths relative to the source run and content digests. Unreadable logs or logs larger than the current 64 MiB reference limit produce a diagnostic; the original action logs remain in the run directory.

JUnit XML uses a separate case for each known attempt so an earlier failure remains visible. Test names include stable identity and scope to avoid collisions. Missing durations are omitted. The normalized report remains authoritative for completeness, provenance and conversion notices that JUnit XML cannot fully represent. Incomplete scopes and runner failures remain visible even when their observed leaf tests passed.

On JVM, an aborted JUnit container (including setup, teardown or dynamic-test discovery) makes verification incomplete, even if its observed tests passed. The `KVIK_TEST_CONTAINER_ABORTED` diagnostic retains the container identity and reason; observed tests and skip reasons are preserved. Discovery remains incomplete because the container may have stopped before registering all dynamic tests. A failed test or container still takes precedence and yields a failed result. An individual test's assumption abort remains a skipped test.

JS/Wasm reports preserve completed tests when Node exits early, times out or is cancelled. The interrupted test has no invented outcome, and recovered discovery remains incomplete. Earlier failures stay visible; cancellation retains its own status. A process exit code of zero without a valid final report does not establish success. JSON and JUnit exports retain these partial results without rerunning tests.

The initial XML consumer contract is [GitLab unit test reports](https://docs.gitlab.com/ci/testing/unit_test_reports/). Publication is separate: this command creates local files and requires no service credentials.

[JVM line coverage](coverage.md) is captured separately with `test --coverage`. Use `coverage-check` to evaluate a retained coverage report against a line threshold. Use `coverage-aggregate` for compatible coverage reports with an explicit expected-check matrix. GitHub Checks publication below sends test summaries; it does not publish coverage.

## Aggregate retained test runs

Combine compatible runs against an explicit expected-check matrix:

```sh
kvik test-aggregate --run /path/to/run-a --run /path/to/run-b \
  --expect library:jvm:test --expect library:macosArm64:test \
  --output /path/to/new-test-aggregate
```

A single run defaults to its requested test checks. Multiple runs require repeatable `--expect MODULE:TARGET:test`; available results never define the expected matrix. Up to 100 runs and 1,000 expected checks are supported; expected targets are `jvm`, `macosArm64`, `linuxX64`, `js` and `wasmJs`. Repeat `--module ID` and `--target ID` to filter the matrix. Unmatched selectors are errors.

All contributors must agree on the input snapshot, configuration, dependency lock and runtime composition. Toolchain identities shared by multiple runs must agree. Each selected check can have only one contributor; duplicate checks or contributors are rejected. Repeated runs are not interpreted as retries. A contributor's failed, incomplete or cancelled status is retained even when selection narrows its tests.

The aggregate contains one entry for each expected check. A missing check has no source run or report, no observed tests, and an incomplete scope. Unique tests are counted by module, target and test ID; observed attempts are counted separately. `complete` means every selected check completed, while discovery counts and verification status remain separate. Unknown discovery stays explicit: full-run selections may use the run's known totals, while partial selections use per-scope counts only when available.

The default format is `junit-xml`; use `--format json` for a JSON export. The output directory must be new and outside every source run. It contains `aggregate.json`, `junit.xml` or `export.json`, `scope.json`, and `graph.json`. Supplied run records are retained under `runs/<runId>/` as `verification.json`, `completion.json` and `events.jsonl`, alongside their verified referenced action logs. The aggregate embeds each contributor's normalized report and digest. Resolve a check's log reference as `runs/<check.runId>/<log.path>`. All supplied runs and their referenced logs are retained even when report selection excludes a scope. They can be used as `--run` inputs for later aggregation or individual export without the original directories. The same log validation and size limits as `test-report` apply.

JUnit XML preserves observed attempts and includes infrastructure errors for missing checks or negative verification, so a set of passing leaf cases cannot hide an incomplete aggregate. Keep `aggregate.json` for provenance, discovery completeness, unique/attempt counts and conversion notices that XML cannot fully represent.

Aggregation reads retained run data locally without loading projects, starting a JDK or rerunning tests. It does not verify today's source state or publish results. Valid negative results are saved. Exit codes follow aggregate status: 0 passed, 1 failed, 3 incomplete and 130 cancelled; invalid command arguments use 2. Invalid or incompatible retained inputs return 3 without publishing an output directory.

`test-aggregate` caches aggregation and export independently in the local action
cache. The default store is `.kvik/cache` under the current working directory;
use `--store PATH` to share another store. It must be outside all source run and
output directories. Keys include the retained records, expected matrix, scope,
format and runtime composition. Switching between JSON and JUnit can reuse the
aggregate. `graph.json` records cache decisions and `local_hit` results.

Retained inputs are validated on every invocation, including cache hits. Cached
negative outcomes preserve their failure or incompleteness. Corrupt cache entries
are recomputed, and an unavailable cache falls back to uncached transformations.
Output directories must still be new. This reuses reporting artifacts; it neither
reuses test execution for a new test request nor caches service publication.

JSON definitions in `schemas/v1/contracts.json` are `testAggregationScope`, `testReportContribution`, `testAggregate`, `testAggregateExport` (stdout), and `testAggregateGraph` (`graph.json`).

## Publish a GitHub Check

Publish retained test results without rerunning tests:

```sh
# Supply GITHUB_TOKEN through your CI secret environment.
kvik test-publish --run /path/to/retained-run \
  --repository owner/repository --sha 0123456789abcdef0123456789abcdef01234567 \
  --output /path/to/new-publication-attempt
```

The default check name is `Kvik tests`; change it with `--name`. A single run defaults to its requested test checks. For multiple `--run` paths, provide the expected matrix with repeatable `--expect MODULE:TARGET:test`, using the same compatibility and missing-check rules as `test-aggregate`. The supplied full 40-character SHA is the destination commit. The caller must establish that this commit corresponds to the retained input snapshot; Kvik does not infer that relationship from Git.

The command calls GitHub's [Create a check run API](https://docs.github.com/en/rest/checks/runs#create-a-check-run), using API version `2026-03-10`. Supply an accepted token with repository **Checks: write** permission in `GITHUB_TOKEN`, or select another environment variable with `--token-env`. The token is transport-only and is not saved in the request or action metadata. The default API is `https://api.github.com`; `--api-url` accepts an HTTPS origin with an optional `/api/v3` suffix for GitHub Enterprise. Loopback HTTP at `http://127.0.0.1:PORT` is available for local testing. A custom API receives the token, so select that endpoint deliberately.

The check is completed with conclusion `success`, `failure`, `action_required` or `cancelled` for passed, failed, incomplete or cancelled source results respectively. Its summary includes unique test counts, attempts, missing checks and provenance. This first publication format sends test summaries, not individual code annotations or coverage data.

Publication uses a `publish` action with cache policy `never`. Before sending HTTP, Kvik saves `aggregate.json`, `request.json`, `action.json`, `intent.json` and copied run records under `runs/<runId>/`. Referenced stdout/stderr files are copied into those same run namespaces after verifying their content digests. Shared action logs are stored once even when many tests or attempts reference them. Resolve a log reference from an aggregate check as `runs/<check.runId>/<log.path>`. These are complete action logs, shared by its tests and retries; they do not attribute individual lines to one test. Missing, changed, conflicting or symlinked logs stop publication before HTTP. Each log is limited to 64 MiB and the complete retained input bundle to 256 MiB. The copied runs and logs can be used for retry after the original run directory is removed. Log contents stay in the local bundle; the GitHub request contains the summary.

The final `outcome/result.json` and stdout use the `githubCheckPublication` schema in `schemas/v1/contracts.json`; both include the output directory, source status, publication outcome, HTTP status when known, remote check ID when confirmed, and request/report digests.

Exit 0 means the check was **published**, even when the tests failed. Exit 3 means `rejected` or `unknown`; cancellation uses 130. The check's conclusion and `sourceStatus` carry the test outcome. A successful HTTP response is accepted only when its check identity and fields match the request.

There is no automatic POST retry. A transport failure, ambiguous response, or attempt directory containing `intent.json` without a final outcome may mean GitHub already created the check. Inspect the remote checks before retrying. Failed uploads preserve the aggregate and copied runs; use those retained run directories with a new `--output` directory for another attempt. This performs another publication without executing tests, and may create another check.
