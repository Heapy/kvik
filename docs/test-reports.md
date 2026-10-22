# Retained test reports

Export a completed run without loading its project or starting a compiler or test process:

```sh
kvik test-report --run /path/to/.kvik/runs/RUN_ID --output /path/to/new-report-directory
kvik test-report --run /path/to/.kvik/runs/RUN_ID --output /path/to/selected-report --module library --target jvm
```

The default format is `junit-xml`; `--format json` exports the normalized result. Module and target options may be repeated. Selection is limited to the test scopes requested by that run. An absent module or target is an error. The output directory must be new and outside the retained run.

The directory contains `report.json`, `junit.xml` (or `export.json`) and `graph.json`. Aggregation and export are separate graph actions. Their inputs include the committed run records, scope selection and implementation identity; export consumes the normalized report artifact. These transformations currently execute locally on each request. They do not rerun tests or turn historical results into verification of current sources. The command exit status describes report generation; the JSON response separately identifies the source run and report statuses.

Filtering narrows the reported test cases but conservatively retains the source run's failed, incomplete or cancelled overall status. Individual scope outcomes remain available separately. Existing retained records contain only aggregate discovery counts, so per-scope discovery counts are unknown for a run that requested multiple test scopes. JSON schemas are published in `schemas/v1/contracts.json` as `testReport`, `testReportGraph` and `testReportExport`.

Test results retain the final outcome and every observed attempt. Unique test counts count final outcomes once per module/target/test identity; attempt counts include retries separately. Any failed attempt prevents passing verification, even if a later attempt passes. A runner's own retries are recorded when its protocol exposes them; Kvik does not introduce retries. Old records remain readable, with unavailable attempt metadata and durations explicitly unknown.

New runner results include elapsed durations when available, suite and display names, skip reasons, and bounded structured failures. Text fields are limited to 8,192 UTF-16 code units and stack traces to 32,768; at most 1,000 attempts are retained per test. Attempts reference the action's shared stdout/stderr logs, rather than claiming those streams were isolated per test. References contain paths relative to the source run and content digests. Unreadable logs or logs larger than the current 64 MiB reference limit produce a diagnostic; the original action logs remain in the run directory.

JUnit XML uses a separate case for each known attempt so an earlier failure remains visible. Test names include stable identity and scope to avoid collisions. Missing durations are omitted. The normalized report remains authoritative for completeness, provenance and conversion notices that JUnit XML cannot fully represent. Incomplete scopes and runner failures remain visible even when their observed leaf tests passed.

The initial XML consumer contract is [GitLab unit test reports](https://docs.gitlab.com/ci/testing/unit_test_reports/). Publication is separate: this command creates local files and requires no service credentials.

This increment covers test results from one retained run. [JVM line coverage](coverage.md) is captured separately with `test --coverage`. Use `coverage-check` to evaluate a retained coverage report against a line threshold. Use `coverage-aggregate` for compatible coverage reports with an explicit expected-check matrix. Service publication remains separate work in PLAN.md.
