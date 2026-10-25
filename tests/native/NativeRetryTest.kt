@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.native

import kvik.cache.NativeContentStore
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kotlin.test.*

class NativeRetryTest {
    @Test fun changedRetryDiscoveryCannotShrinkTheReportedScopeOrEraseEarlierFailures() {
        fun report(id: String, status: TestStatus) = NativeTestReport(listOf(TestResult(id, "sample", "macosArm64", status,
            details = TestDetails("suite", id, listOf(TestAttempt(1, status, failure =
                if (status == TestStatus.FAILED) TestFailure(TestFailureKind.ASSERTION, message = "original") else null))))),
            TestCounts(1, 1, if (status == TestStatus.FAILED) 1 else 0, 0, true),
            if (status == TestStatus.FAILED) VerificationStatus.FAILED else VerificationStatus.PASSED, retryable = status == TestStatus.FAILED)
        val combined = mergeNativeTestAttempts(report("original", TestStatus.FAILED), report("different", TestStatus.PASSED))
        assertEquals(setOf("original", "different"), combined.tests.map { it.id }.toSet())
        assertEquals(VerificationStatus.FAILED, combined.status)
        assertFalse(combined.counts.discoveryComplete)
        assertNull(combined.counts.discovered)
        assertFalse(combined.retryable)
    }

    @Test fun bothEnginesPreserveFailuresAndPassingSiblingsAcrossFreshProcesses() {
        for (kotest in listOf(false, true)) {
            exercise(kotest, "pass", expectedRounds = 1)
            exercise(kotest, "recover", expectedRounds = 2)
            exercise(kotest, "fail", expectedRounds = 3)
        }
    }

    @Test fun incompleteCancelledAndTimedOutRetriesNeverErasePriorFailuresOrLaunchAgain() {
        for (kotest in listOf(false, true)) for (scenario in listOf("partial", "cancel", "timeout", "start-failure", "missing-log"))
            exercise(kotest, scenario, expectedRounds = 2)
    }

    @Test fun failedProcessesWithoutLeafFailuresAreNotRetried() {
        for (kotest in listOf(false, true)) exercise(kotest, "runner-failure", expectedRounds = 1)
    }

    private fun exercise(kotest: Boolean, scenario: String, expectedRounds: Int) {
        val root = NativeRunDirectory("/private/tmp").path
        val content = NativeContentStore("$root/store")
        val digest = content.put(byteArrayOf(1))
        val input = TreeManifest(listOf(FileEntry(LogicalPath("test.kexe"), digest, true)))
        val tools = NativeTarget.MACOS_ARM64.toolIdentityNames.associateWith { digest }
        val resources = TestResources(768, setOf("shared-database"), 2)
        val action = if (kotest) kotestNativeTestAction("sample", LogicalPath("test.kexe"), input, digest, tools, resources = resources, maxRetries = 2)
            else nativeTestAction("sample", LogicalPath("test.kexe"), input, digest, tools, resources = resources, maxRetries = 2)
        var rounds = 0
        var closes = 0
        var launches = 0
        val requests = mutableListOf<ProcessRequest>()
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess {
                val discovery = "--ktest_list_tests" in request.arguments
                if (!discovery) rounds++
                if (rounds == 2 && scenario == "start-failure") error("expected retry launch failure")
                launches++
                requests += request
                val failed = !discovery && scenario != "pass" && scenario != "runner-failure" && (rounds == 1 || scenario == "fail")
                var stdout = "round=$rounds\n"
                if (discovery) stdout = "sample.Suite.\n  test\n  sibling\n"
                else if (kotest) {
                    var journal = kotestJournal(request.environment.getValue("KVIK_KOTEST_REQUEST"), failed)
                    if (rounds == 2 && scenario == "partial") journal = journal.substringBeforeLast("\n", "").substringBeforeLast("\n", "") + "\n"
                    writeNativeFile(request.environment.getValue("KVIK_KOTEST_REPORT"), journal.encodeToByteArray())
                } else {
                    stdout += "##teamcity[testSuiteStarted name='sample.Suite']\n" +
                        "##teamcity[testStarted name='test']\n" +
                        (if (failed) "##teamcity[testFailed name='test' message='first failure' details='stack']\n" else "") +
                        "##teamcity[testFinished name='test' duration='1']\n" +
                        "##teamcity[testStarted name='sibling']\n" +
                        "##teamcity[testFinished name='sibling']\n" +
                        "##teamcity[testSuiteFinished name='sample.Suite']\n"
                    if (rounds == 2 && scenario == "partial") stdout = stdout.substringBefore("##teamcity[testFinished name='sibling']")
                }
                writeNativeFile(request.stdoutFile, stdout.encodeToByteArray())
                if (!(rounds == 2 && scenario == "missing-log")) writeNativeFile(request.stderrFile, "stderr round=$rounds".encodeToByteArray())
                val exit = ProcessResult(if (!discovery && scenario == "runner-failure") 7 else if (failed && !kotest) 1 else 0,
                    timedOut = rounds == 2 && scenario == "timeout")
                return object : OwnedProcess {
                    override fun poll(): ProcessResult {
                        if (!discovery && failed) platform.posix.usleep(20_000u)
                        return exit
                    }
                    override fun cancel() = Unit
                    override fun close() { closes++ }
                }
            }
            override fun close() = Unit
        }
        val native = NativeCompilerExecutor("run", root, host, content, NativeToolchainProvider { error("No compiler needed") })
        val engine = KotestNativeExecutor("run", root, host, content)
        val executor: ActionExecutor = if (kotest) engine else native
        assertEquals(resources.resourceRequest(), executor.resources(action))
        executor.start("run", "tests", action).use { running ->
            var result: ActionResult? = null
            repeat(10) {
                if (result == null) {
                    if (rounds == 2 && scenario == "cancel") running.cancel()
                    result = running.poll()
                }
            }
            assertNotNull(result)
            assertEquals(when (scenario) { "pass" -> ActionStatus.EXECUTED; "cancel" -> ActionStatus.CANCELLED; else -> ActionStatus.FAILED }, result.status)
        }
        assertEquals(expectedRounds, rounds)
        assertEquals(launches, closes)
        assertTrue(requests.zipWithNext().all { (first, next) -> next.timeoutMillis <= first.timeoutMillis })
        val testRequests = requests.filter { "--ktest_list_tests" !in it.arguments }
        if (testRequests.size > 1) assertTrue(testRequests[1].timeoutMillis <= testRequests[0].timeoutMillis - 10)
        assertEquals(requests.size, requests.map { it.stdoutFile }.distinct().size)
        val report = (if (kotest) engine.observations else native.observations).getValue("tests").tests!!
        if (scenario == "recover") {
            assertEquals(VerificationStatus.FAILED, report.status)
            assertEquals(TestCounts(2, 2, 0, 0, true), report.counts)
            assertTrue(report.tests.all { it.details!!.attempts.size == 2 })
            val test = report.tests.single { it.id == "sample.Suite.test" }
            assertEquals(listOf(TestStatus.FAILED, TestStatus.PASSED), test.details!!.attempts.map { it.status })
            assertEquals("first failure", test.details.attempts.first().failure!!.message)
            assertEquals(2, test.details.attempts.map { it.logs.first().path }.distinct().size)
        }
        if (scenario in listOf("partial", "start-failure", "missing-log")) assertFalse(report.counts.discoveryComplete)
        if (scenario !in listOf("pass", "runner-failure")) assertTrue(report.tests.any { it.hasFailedAttempt })
        for (test in report.tests) for (attempt in test.details!!.attempts) for (log in attempt.logs) {
            assertEquals(log.digest, Sha256.digest(NativeFiles.read("$root/${log.path.value}", 16 * 1024 * 1024)))
        }
    }
}

private fun kotestJournal(token: String, failed: Boolean): String {
    val owner = "sample.Suite"
    fun event(name: String, vararg fields: Pair<String, JsonValue>) = obj("event" to name.json(), *fields)
    fun test(name: String, id: String, vararg fields: Pair<String, JsonValue>) = event(name,
        "spec" to owner.json(), "id" to "$owner.$id".json(), "type" to "leaf".json(), *fields)
    val events = listOf(event("inventory", "specs" to listOf(owner.json()).json()), event("engine-start"), event("engine-initialized"),
        event("spec-start", "spec" to owner.json()), test("test-start", "test"),
        test("test-finish", "test", "status" to (if (failed) "Failure" else "Success").json(), "message" to (if (failed) "first failure" else "").json()),
        test("test-start", "sibling"), test("test-finish", "sibling", "status" to "Success".json(), "message" to "".json()),
        event("spec-finish", "spec" to owner.json(), "status" to "Success".json(), "message" to "".json()),
        event("engine-finish", "errors" to 0.json(), "message" to "".json()), event("engine-return", "errors" to 0.json()), event("process-main"))
    return events.mapIndexed { index, value -> JsonValue.Object(value.values + mapOf("version" to 1.json(), "request" to token.json(), "sequence" to index.json())).canonical() }
        .joinToString("\n", postfix = "\n")
}
