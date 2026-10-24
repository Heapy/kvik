package kvik.runevidence

import kvik.core.*

/** Adapter-validated test report for one terminal action; counts describe these exact test IDs. */
data class TestObservation(val tests: List<TestResult>, val counts: TestCounts, val status: VerificationStatus)
data class RunEvidence(val record: VerificationRecord, val exitStatus: ExitStatus)

internal fun validCheck(check: String): Boolean = check.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*:(jvm|macosArm64|linuxX64):(build|test|application|package)")) ||
    check.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*:(iosArm64|iosSimulatorArm64):(build|package)")) ||
    check.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*:common:package")) ||
    check.matches(Regex("script:[A-Za-z][A-Za-z0-9_.-]{0,127}:jvm:(prepare|run)"))
internal fun scriptExecutionCheck(check: String): Boolean = check.startsWith("script:") && check.endsWith(":run") && validCheck(check)

/**
 * Aggregate one planned run without filesystem, process or journal effects. Missing evidence is
 * incomplete; contradictory evidence throws IllegalArgumentException rather than inventing results.
 * Application checks prove bundle preparation only, including cached preparation; they do not
 * observe application execution and require no test report.
 * Action keys and input identities are supplied by the coordinator that owns execution and CAS.
 */
fun aggregateRunEvidence(
    runId: String,
    requestedChecks: Set<String>,
    terminalActions: Map<String, String>,
    actionDependencies: Map<String, Set<String>>,
    actions: List<ActionResult>,
    testObservations: Map<String, TestObservation>,
    inputSnapshot: Digest,
    configuration: Digest,
    dependencyLock: Digest,
    composition: Digest,
    toolchains: Map<String, Digest>,
    reproduction: List<String>,
    freshnessAtCompletion: Freshness,
    cancelled: Boolean = false,
): RunEvidence {
    val checks = requestedChecks.sorted().toSet()
    require(checks.isNotEmpty() && checks.all(::validCheck)) {
        "Requested checks must identify module:target:build|test|application|package or script:name:jvm:prepare|run"
    }
    val terminals = terminalActions.toMap()
    val graph = actionDependencies.mapValues { it.value.toSet() }
    require(graph.keys.all { it.isNotBlank() }) { "Planned action IDs must be nonempty" }
    validateGraph(graph)
    require(terminals.keys.all { it in checks } && terminals.values.all { it in graph }) { "Terminal mapping contains an unknown check/action" }
    require(terminals.values.distinct().size == terminals.size) { "A terminal action cannot satisfy multiple checks" }
    val results = actions.map { it.copy(outputs = TreeManifest(it.outputs.files.toList())) }.sortedBy { it.actionId }
    require(results.map { it.actionId }.distinct().size == results.size) { "Duplicate scheduler action result" }
    require(results.all { it.actionId in graph }) { "Scheduler result is outside the planned graph" }
    val byId = results.associateBy { it.actionId }
    for (check in checks.filter(::scriptExecutionCheck)) {
        require(byId[check]?.status !in setOf(ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)) { "Script execution cannot be a cache hit: $check" }
        terminals[check]?.let { terminal ->
            require(terminal == check) { "Script execution requires its distinct run terminal: $check" }
        }
    }
    val testChecks = checks.filter { it.endsWith(":test") }
    val testTerminals = testChecks.mapNotNull { check -> terminals[check]?.let { it to check } }.toMap()
    val observations = testObservations.mapValues { (_, report) -> report.copy(tests = report.tests.toList()) }
    require(observations.keys.all { it in testTerminals }) { "Test observation is not a requested test terminal" }
    for ((action, check) in testTerminals) {
        require(byId[action]?.status !in setOf(ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)) { "Test action $action cannot be a cache hit" }
        val report = observations[action] ?: continue
        validateReport(action, check, report)
        byId[action]?.let { result ->
            val expected = when (report.status) {
                VerificationStatus.PASSED -> ActionStatus.EXECUTED
                VerificationStatus.FAILED, VerificationStatus.INCOMPLETE -> ActionStatus.FAILED
                VerificationStatus.CANCELLED -> ActionStatus.CANCELLED
            }
            require(result.status == expected) { "Test observation/result status disagree for $action: ${report.status}/${result.status}" }
        }
    }
    val tests = observations.values.flatMap { it.tests }.sortedWith(compareBy({ it.module }, { it.target }, { it.id }))
    require(tests.map { Triple(it.module, it.target, it.id) }.distinct().size == tests.size) { "Duplicate test identity across action observations" }
    fun sumCount(select: (TestCounts) -> Int): Int {
        val count = observations.values.sumOf { select(it.counts).toLong() }
        require(count <= Int.MAX_VALUE) { "Aggregated test counts exceed the v1 integer range" }
        return count.toInt()
    }
    val reportsPresent = testChecks.all { terminals[it] in observations }
    val discoveryKnown = reportsPresent && observations.values.all { it.counts.discovered != null }
    val counts = TestCounts(if (discoveryKnown) sumCount { it.discovered!! } else null,
        sumCount { it.executed }, sumCount { it.failed }, sumCount { it.skipped },
        reportsPresent && observations.values.all { it.counts.discoveryComplete })
    fun successfulClosure(action: String): Boolean {
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean = if (!visited.add(id)) true else
            byId[id]?.successful == true && graph.getValue(id).all(::visit)
        return visit(action)
    }
    val completed = checks.filter { check ->
        val terminal = terminals[check]
        terminal != null && successfulClosure(terminal) &&
            (!check.endsWith(":test") || observations[terminal]?.status == VerificationStatus.PASSED)
    }.toSet()
    val status = when {
        cancelled || results.any { it.status == ActionStatus.CANCELLED } || observations.values.any { it.status == VerificationStatus.CANCELLED } -> VerificationStatus.CANCELLED
        observations.values.any { it.status == VerificationStatus.FAILED } || results.any {
            it.status == ActionStatus.FAILED && observations[it.actionId]?.status != VerificationStatus.INCOMPLETE
        } -> VerificationStatus.FAILED
        freshnessAtCompletion != Freshness.CURRENT || completed != checks || byId.keys != graph.keys || results.any { !it.successful } -> VerificationStatus.INCOMPLETE
        else -> VerificationStatus.PASSED
    }
    val record = VerificationRecord(runId, checks, completed, inputSnapshot, configuration, dependencyLock, composition,
        toolchains.toMap(), results, tests, counts, status, freshnessAtCompletion, reproduction.toList())
    return RunEvidence(record, when (status) {
        VerificationStatus.PASSED -> ExitStatus.SUCCESS
        VerificationStatus.FAILED -> ExitStatus.FAILED
        VerificationStatus.INCOMPLETE -> ExitStatus.INCOMPLETE
        VerificationStatus.CANCELLED -> ExitStatus.CANCELLED
    })
}

private fun validateReport(action: String, check: String, report: TestObservation) {
    val counts = report.counts
    require(report.tests.all { "${it.module}:${it.target}:test" == check }) { "Test identity belongs to another check in $action" }
    require(report.tests.map { it.id }.distinct().size == report.tests.size) { "Duplicate test identity in $action" }
    require(counts.executed == report.tests.count { it.status != TestStatus.SKIPPED } &&
        counts.failed == report.tests.count { it.status == TestStatus.FAILED } &&
        counts.skipped == report.tests.count { it.status == TestStatus.SKIPPED }) { "Test counts do not match recorded identities in $action" }
    require(counts.discovered == null || counts.discovered.toLong() >= counts.executed.toLong() + counts.skipped) { "Discovered test count is smaller than recorded identities in $action" }
    when (report.status) {
        VerificationStatus.PASSED -> require(counts.satisfiesExecution && report.tests.none { it.hasFailedAttempt }) { "Passing test observation lacks complete successful execution in $action" }
        VerificationStatus.INCOMPLETE -> require(report.tests.none { it.hasFailedAttempt }) { "Incomplete observation contains failed tests in $action" }
        VerificationStatus.FAILED, VerificationStatus.CANCELLED -> Unit // Container failure/cancellation can have no failed leaf test.
    }
}
