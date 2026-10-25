package kvik.mcp

private val reportOutput = text("output", "--output", "New output directory; must not exist. Paths resolve against the configured project directory (server working directory in source mode).")
private val reportRun = text("run", "--run", "Retained test run directory")
private val reportInput = text("report", "--report", "Retained coverage.json or aggregate.json file")
private val reportRuns = repeated("runs", "--run", minimum = 1, maximum = 100)
private val reportScope = listOf(repeated("modules", "--module"), repeated("targets", "--target"))
private val expectedChecks = repeated("expect", "--expect", minimum = 1, maximum = 1000)
private val testFormat = text("format", "--format", choices = listOf("json", "junit-xml"))
private val coverageModules = listOf(repeated("modules", "--module", minimum = 1), switch("allModules", "--all-modules"))

/** Local artifact creation is opt-in; these tools neither rerun tests nor publish to services. */
val mcpReportTools: List<McpTool> = listOf(
    McpTool("kvik_test_report", "Generate JSON or JUnit XML from one retained run, without rerunning tests. Creates output and may populate the configured cache. Success means report generation completed; inspect sourceRunStatus and reportStatus for test outcomes.",
        listOf("test-report"), required = setOf("run", "output"),
        arguments = listOf(reportRun, reportOutput, testFormat) + reportScope, project = false, report = true),
    McpTool("kvik_test_aggregate", "Aggregate retained test runs into JSON or JUnit XML. Multiple runs require expect entries in module:target:test form. Creates output; failed or incomplete aggregate outcomes return isError with structuredContent. Does not rerun tests.",
        listOf("test-aggregate"), required = setOf("runs", "output"),
        arguments = listOf(reportRuns, reportOutput, expectedChecks, testFormat) + reportScope, project = false, report = true),
    McpTool("kvik_coverage_aggregate", "Aggregate retained test scopes and coverage reports. Multiple runs require expect entries in module:target:test form. Reports may be omitted to record missing coverage. Preserves per-target coverage and diagnoses incompatible contributors; incomplete or failed outcomes return isError. Creates output without rerunning tests.",
        listOf("coverage-aggregate"), required = setOf("runs", "output"), arguments = listOf(reportRuns, reportOutput,
            repeated("reports", "--report", maximum = 1000), expectedChecks) + reportScope, project = false, report = true),
    McpTool("kvik_coverage_check", "Evaluate a retained coverage report against a percentage threshold. Select modules or allModules=true; aggregates also require targets or allTargets=true. minimum is a decimal string from 0 to 100 with at most two decimal places. Creates output; threshold failures, incomplete and unsupported measurements return isError with structuredContent.",
        listOf("coverage-check"), required = setOf("report", "output", "metric", "minimum"), arguments = listOf(reportInput, reportOutput,
            text("metric", "--metric", choices = listOf("line", "branch", "function")), text("minimum", "--minimum"),
            repeated("targets", "--target", minimum = 1), switch("allTargets", "--all-targets")) + coverageModules,
        project = false, report = true),
    McpTool("kvik_coverage_export", "Export retained coverage locally to Sonar generic coverage or JaCoCo XML. Select modules or allModules=true; aggregates require one target. Creates output and diagnoses conversion losses; failed or incomplete outcomes return isError. Does not contact services or rerun tests.",
        listOf("coverage-export"), required = setOf("report", "output", "format"), arguments = listOf(reportInput, reportOutput,
            text("format", "--format", choices = listOf("sonar-generic", "jacoco-xml")), text("target", "--target")) + coverageModules,
        project = false, report = true),
)

/** Coverage analysis may launch the pinned JDK, owned by this foreground MCP connection. */
val mcpCoverageReportTools: List<McpTool> = listOf(
    McpTool("kvik_coverage_report", "Analyze one retained instrumented JVM test run using configured pinned tools. Select module when needed. Creates normalized coverage and original JaCoCo artifacts; cached analysis can avoid launching the JDK. Never reruns tests. Success means report generation completed; inspect sourceRunStatus and reportStatus.",
        listOf("coverage-report"), required = setOf("run", "output"),
        arguments = listOf(reportRun, reportOutput, text("module", "--module")), project = false, report = true),
)
