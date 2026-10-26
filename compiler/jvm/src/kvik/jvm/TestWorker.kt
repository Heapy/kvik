package kvik.jvm

import kvik.core.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.Path
import java.nio.file.Files
import java.util.ServiceLoader
import kotlin.system.exitProcess
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots
import org.junit.platform.launcher.*
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

/** One fresh test process per request. Test execution is never restored from a cache. */
object TestWorker {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 3..4) { "TestWorker <workspace> <request-relative> <result-relative> [fallback-stdlib]" }
        val workspace = Workspace(Path.of(args[0]))
        var requestId = "invalid-request"
        val tests = linkedMapOf<String, TestResult>()
        val diagnostics = mutableListOf<Diagnostic>()
        var progress: TestProgress? = null
        fun diagnostic(value: Diagnostic) {
            diagnostics += value
            progress?.diagnostic(value)
        }
        var counts = TestCounts(null, 0, 0, 0, false)
        var status = "incomplete"
        var exit = 3
        try {
            val request = workspace.readJson(args[1]).fields(setOf("schemaVersion", "requestId", "module", "target", "classpath", "testClasses", "engines"), setOf("parallelism", "maxRetries", "discoveryRoots", "includeTags", "excludeTags"))
            request.requireVersion()
            requestId = request.getValue("requestId").stringValue()
            val module = request.getValue("module").stringValue()
            val target = request.getValue("target").stringValue()
            require(requestId.isNotBlank() && module.isNotBlank() && target == "jvm")
            val classpath = request.getValue("classpath").arrayValue().map { workspace.classpath(it.stringValue()) }
            require(classpath.isNotEmpty() && classpath.distinct().size == classpath.size)
            val classes = request.getValue("testClasses").arrayValue().map { it.stringValue() }
            require(classes.distinct().size == classes.size && classes.all { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_.$]*")) })
            val roots = request["discoveryRoots"]?.arrayValue()?.map { workspace.path(it.stringValue()).toRealPath() }.orEmpty()
            require(roots.distinct().size == roots.size && roots.all { it in classpath && Files.isDirectory(it, NOFOLLOW_LINKS) }) {
                "Test discovery roots must be unique declared classpath directories"
            }
            require(roots.isEmpty() || classes.isEmpty()) { "Test discovery cannot be combined with explicit classes" }
            val engineIds = request.getValue("engines").arrayValue().map { it.stringValue() }.toSet()
            require(engineIds.isNotEmpty() && engineIds.all { it in setOf("junit-jupiter", "kotest") })
            val tags = JvmTestTags.fromFields(request)
            require(!tags.enabled || engineIds == setOf("junit-jupiter")) { "JVM tag filters currently require only the junit-jupiter engine" }
            val parallelism = (request["parallelism"]?.longValue() ?: 1).also {
                require(it in 1L..1024L) { "Invalid JVM test parallelism" }
            }.toInt()
            require(parallelism == 1 || engineIds == setOf("junit-jupiter")) { "Parallel JVM tests require only junit-jupiter" }
            val maxRetries = (request["maxRetries"]?.longValue() ?: 0).also {
                require(it in 0L..9L) { "Invalid JVM test maxRetries" }
            }.toInt()
            if (classes.isEmpty() && roots.isEmpty()) reject("KVIK_TEST_EMPTY_SELECTION", "No test classes or compiled test roots were selected")
            // A test worker is a fresh JVM. Kotlin stdlib and kotlin-reflect must share its loader;
            // loading reflect only in a child loader breaks stdlib's ReflectionFactory discovery.
            fun codeSource(type: Class<*>): Path = Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()
            // Native verifies tool artifacts and passes its stdlib only as a fallback.
            // Consumer dependencies retain their declared order, including their own stdlib.
            val bootstrap = listOf(codeSource(TestWorker::class.java), codeSource(LauncherFactory::class.java))
            val processClasspath = System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Path.of(it).toRealPath() }
            val expectedClasspath = if (args.size == 4) bootstrap + classpath + listOf(Path.of(args[3]).toRealPath())
                else bootstrap + listOf(codeSource(kotlin.Unit::class.java)) + classpath // Legacy direct worker clients.
            if (processClasspath != expectedClasspath) reject("KVIK_TEST_CLASSPATH", "JVM classpath differs from the ordered declared test classpath and verified bootstrap artifacts")
            val journal = TestProgress(workspace.path(args[2] + ".progress.jsonl", false), requestId, module)
            progress = journal
            check(journal.failure == null) { journal.failure!! }
            run {
                val loader = TestWorker::class.java.classLoader
                val thread = Thread.currentThread()
                val previous = thread.contextClassLoader
                thread.contextClassLoader = loader
                try {
                    val engines = ServiceLoader.load(TestEngine::class.java, loader).toList().filter { it.id in engineIds }
                    if (engines.map { it.id }.toSet() != engineIds || engines.size != engineIds.size)
                        reject("KVIK_TEST_ENGINE", "Requested test engine is missing or duplicated")
                    val config = LauncherConfig.builder().enableTestEngineAutoRegistration(false)
                        .enableLauncherSessionListenerAutoRegistration(false).enableLauncherDiscoveryListenerAutoRegistration(false)
                        .enableTestExecutionListenerAutoRegistration(false).enablePostDiscoveryFilterAutoRegistration(false)
                        .addTestEngines(*engines.toTypedArray()).build()
                    val launcher = LauncherFactory.create(config)
                    val discovery = LauncherDiscoveryRequestBuilder.request().enableImplicitConfigurationParameters(false)
                        .selectors(classes.map(::selectClass) + selectClasspathRoots(roots.toSet()))
                        .filters(*listOfNotNull(
                            tags.includeTags.takeIf { it.isNotEmpty() }?.let(TagFilter::includeTags),
                            tags.excludeTags.takeIf { it.isNotEmpty() }?.let(TagFilter::excludeTags)).toTypedArray())
                        .configurationParameter("junit.jupiter.execution.parallel.enabled", (parallelism > 1).toString())
                        .configurationParameter("junit.jupiter.execution.parallel.mode.default", "concurrent")
                        .configurationParameter("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
                        .configurationParameter("junit.jupiter.execution.parallel.config.strategy", "fixed")
                        .configurationParameter("junit.jupiter.execution.parallel.config.fixed.parallelism", parallelism.toString())
                        .configurationParameter("junit.jupiter.execution.parallel.config.fixed.max-pool-size", parallelism.toString())
                        .configurationParameter("junit.jupiter.execution.parallel.config.fixed.saturate", "true")
                        .build()
                    var plan = launcher.discover(discovery)
                    val identifiers = (plan.roots + plan.roots.flatMap { plan.getDescendants(it) }).associateBy { it.uniqueId }.toMutableMap()
                    val discovered = identifiers.filterValues { it.isTest }.toMutableMap()
                    val roundDiscovered = discovered.keys.toMutableSet()
                    val roundReported = mutableSetOf<String>()
                    val started = mutableMapOf<String, Long>()
                    val limited = mutableSetOf<String>()
                    var finished = false
                    val lock = Any()
                    fun record(identifier: TestIdentifier, state: TestStatus, throwable: Throwable? = null, skipReason: String? = null) {
                        roundDiscovered += identifier.uniqueId
                        roundReported += identifier.uniqueId
                        discovered[identifier.uniqueId] = identifier
                        identifiers[identifier.uniqueId] = identifier
                        val prior = tests[identifier.uniqueId]?.details?.attempts.orEmpty()
                        val duration = started.remove(identifier.uniqueId)?.let { (System.nanoTime() - it).coerceAtLeast(0) }
                        if (prior.size >= TEST_ATTEMPT_LIMIT) {
                            if (limited.add(identifier.uniqueId)) diagnostic(Diagnostic("KVIK_TEST_ATTEMPT_LIMIT", DiagnosticCategory.TEST,
                                "Test exceeded the supported $TEST_ATTEMPT_LIMIT attempts: ${reportText(identifier.uniqueId)}", module = module, target = target))
                            return
                        }
                        val failure = if (state == TestStatus.FAILED) reportFailure(throwable) else null
                        val reason = if (state == TestStatus.SKIPPED) reportText(skipReason ?: throwable?.message ?: "Test aborted") else null
                        val attempt = TestAttempt(prior.size + 1, state, duration, reason, failure)
                        val parent = identifier.parentId.orElse(null)?.let(identifiers::get)
                        val suite = parent?.takeUnless { it.uniqueId in plan.roots.map { root -> root.uniqueId } }?.displayName?.let(::reportText)
                        val message = reason ?: throwable?.let { reportText("${it.javaClass.name}: ${it.message}") }
                        tests[identifier.uniqueId] = TestResult(identifier.uniqueId, module, target, state, message,
                            TestDetails(suite, reportText(identifier.displayName), prior + attempt))
                        journal.attempt(tests.getValue(identifier.uniqueId), attempt)
                    }
                    val listener = object : TestExecutionListener {
                        override fun dynamicTestRegistered(identifier: TestIdentifier) = synchronized(lock) {
                            identifiers[identifier.uniqueId] = identifier
                            if (identifier.isTest) {
                                discovered[identifier.uniqueId] = identifier
                                roundDiscovered += identifier.uniqueId
                            }
                        }
                        override fun executionStarted(identifier: TestIdentifier) = synchronized(lock) {
                            identifiers[identifier.uniqueId] = identifier
                            if (identifier.isTest) {
                                if (started.put(identifier.uniqueId, System.nanoTime()) != null) {
                                    diagnostic(Diagnostic("KVIK_TEST_ATTEMPT_OVERLAP", DiagnosticCategory.TEST,
                                        "Test started again before its prior attempt finished: ${reportText(identifier.uniqueId)}", module = module, target = target))
                                }
                            }
                        }
                        override fun executionSkipped(identifier: TestIdentifier, reason: String) = synchronized(lock) {
                            val skipped = if (identifier.isTest) listOf(identifier) else plan.getDescendants(identifier).filter { it.isTest && it.uniqueId !in roundReported }
                            for (test in skipped) record(test, TestStatus.SKIPPED, skipReason = reason)
                        }
                        override fun executionFinished(identifier: TestIdentifier, result: TestExecutionResult) = synchronized(lock) {
                            if (identifier.isTest) {
                                val state = when (result.status) {
                                    TestExecutionResult.Status.SUCCESSFUL -> TestStatus.PASSED
                                    TestExecutionResult.Status.FAILED -> TestStatus.FAILED
                                    TestExecutionResult.Status.ABORTED -> TestStatus.SKIPPED
                                }
                                record(identifier, state, result.throwable.orElse(null))
                            }
                            if (identifier.isContainer && result.status == TestExecutionResult.Status.ABORTED) {
                                diagnostic(Diagnostic("KVIK_TEST_CONTAINER_ABORTED", DiagnosticCategory.TEST,
                                    reportText("${identifier.uniqueId}: ${result.throwable.orElse(null)?.message ?: "Container aborted"}"),
                                    module = module, target = target))
                                for (test in plan.getDescendants(identifier).filter { it.isTest && it.uniqueId !in roundReported }) {
                                    record(test, TestStatus.SKIPPED, skipReason = result.throwable.orElse(null)?.message ?: "Container aborted")
                                }
                            } else if (!identifier.isTest && result.status == TestExecutionResult.Status.FAILED) {
                                diagnostic(Diagnostic("KVIK_TEST_CONTAINER_FAILURE", DiagnosticCategory.TEST,
                                    reportText("${identifier.uniqueId}: ${result.status}: ${result.throwable.orElse(null)?.message ?: ""}"), module = module, target = target))
                            }
                        }
                        override fun testPlanExecutionFinished(testPlan: TestPlan) = synchronized(lock) { finished = true }
                    }
                    var complete = true
                    var previousSelection: Set<String>? = null
                    for (round in 0..maxRetries) {
                        if (round > 0) {
                            // Retry the selected scope in this owned worker, with a new discovery plan.
                            // Successful siblings run again too; unique IDs keep counts separate from attempts.
                            plan = launcher.discover(discovery)
                            val next = (plan.roots + plan.roots.flatMap { plan.getDescendants(it) }).associateBy { it.uniqueId }
                            identifiers.putAll(next)
                            val leaves = next.filterValues { it.isTest }
                            discovered.putAll(leaves)
                            roundDiscovered.clear()
                            roundDiscovered.addAll(leaves.keys)
                            roundReported.clear()
                            finished = false
                        }
                        launcher.execute(plan, listener)
                        val sameSelection = previousSelection == null || previousSelection == roundDiscovered
                        if (!sameSelection) diagnostic(Diagnostic("KVIK_TEST_RETRY_SCOPE_CHANGED", DiagnosticCategory.TEST,
                            "Retry discovered different test identities", module = module, target = target))
                        val roundComplete = finished && started.isEmpty() && roundDiscovered == roundReported &&
                            sameSelection && diagnostics.none { it.code == "KVIK_TEST_CONTAINER_ABORTED" }
                        previousSelection = roundDiscovered.toSet()
                        complete = complete && roundComplete
                        // Do not retry incomplete discovery, container/lifecycle failures or infrastructure errors.
                        if (journal.failure != null || !roundComplete || diagnostics.isNotEmpty() || roundReported.none { tests[it]?.status == TestStatus.FAILED }) break
                    }
                    for (test in tests.values.filter { it.hasFailedAttempt && it.status != TestStatus.FAILED }) {
                        diagnostic(Diagnostic("KVIK_TEST_RETRY_FAILURE", DiagnosticCategory.TEST,
                            "Test had a failed attempt before its final ${test.status.name.lowercase()} result: ${reportText(test.id)}", module = module, target = target))
                    }
                    // An aborted factory can stop before registering all dynamic tests. Even when
                    // every observed leaf passed, the container did not complete its requested scope.
                    val containerAborted = diagnostics.any { it.code == "KVIK_TEST_CONTAINER_ABORTED" }
                    val allDiscoveredReported = complete && !containerAborted && discovered.keys == tests.keys
                    counts = TestCounts(discovered.size, tests.values.count { it.status != TestStatus.SKIPPED },
                        tests.values.count { it.status == TestStatus.FAILED }, tests.values.count { it.status == TestStatus.SKIPPED }, allDiscoveredReported)
                    when {
                        tests.values.any { it.hasFailedAttempt } || counts.failed > 0 ||
                            diagnostics.any { it.code != "KVIK_TEST_CONTAINER_ABORTED" } -> { status = "failed"; exit = 1 }
                        counts.satisfiesExecution -> { status = "passed"; exit = 0 }
                        else -> diagnostic(Diagnostic("KVIK_TEST_INCOMPLETE", DiagnosticCategory.TEST,
                            "Selected scope has no completed passing execution or discovery is incomplete", module = module, target = target))
                    }
                } finally { thread.contextClassLoader = previous }
            }
        } catch (failure: Exception) {
            if ((failure as? AdapterFailure)?.code == "KVIK_TEST_EMPTY_SELECTION") {
                counts = TestCounts(0, 0, 0, 0, true)
                diagnostic(Diagnostic("KVIK_TEST_INCOMPLETE", DiagnosticCategory.TEST, failure.message ?: "No tests selected"))
            } else {
                failure.printStackTrace(System.err)
                status = "failed"; exit = 1
                diagnostic(Diagnostic((failure as? AdapterFailure)?.code ?: "KVIK_TEST_INFRASTRUCTURE", DiagnosticCategory.INFRASTRUCTURE,
                    generateSequence<Throwable>(failure) { it.cause }.take(8).joinToString("; caused by: ") { "${it::class.simpleName}: ${it.message}" }))
                counts = TestCounts(null, tests.values.count { it.status != TestStatus.SKIPPED }, tests.values.count { it.status == TestStatus.FAILED },
                    tests.values.count { it.status == TestStatus.SKIPPED }, false)
            }
        }
        progress?.close()
        progress?.failure?.let { problem ->
            diagnostics += Diagnostic("KVIK_TEST_PROGRESS_WRITE_FAILED", DiagnosticCategory.INFRASTRUCTURE, problem)
            status = "failed"; exit = 1
            counts = counts.copy(discoveryComplete = false)
        }
        workspace.publish(args[2], obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to requestId.json(),
            "status" to status.json(), "exitCode" to exit.json(), "tests" to tests.values.sortedBy { it.id }.map { it.toJson() }.json(),
            "testCounts" to counts.toJson(), "diagnostics" to diagnostics.map { it.toJson() }.json()))
        exitProcess(exit)
    }
}

/** Sanitize engine/throwable text before applying the shared UTF-16 bound. */
private fun reportText(value: String, limit: Int = TEST_TEXT_LIMIT): String {
    val result = StringBuilder(minOf(value.length, limit))
    var index = 0
    while (index < value.length && result.length < limit) {
        val char = value[index++]
        when {
            char.isHighSurrogate() && index < value.length && value[index].isLowSurrogate() -> {
                if (result.length + 2 > limit) break
                result.append(char).append(value[index++])
            }
            char.isSurrogate() -> result.append('\uFFFD')
            else -> result.append(char)
        }
    }
    return result.toString()
}

private fun reportFailure(failure: Throwable?): TestFailure {
    if (failure == null) return TestFailure(TestFailureKind.UNKNOWN)
    val stack = StringBuilder()
    val seen = java.util.IdentityHashMap<Throwable, Boolean>()
    var current: Throwable? = failure
    var causes = 0
    while (current != null && causes < 8 && stack.length < TEST_STACK_TRACE_LIMIT && seen.put(current, true) == null) {
        if (causes++ != 0) stack.append("Caused by: ")
        stack.append(reportText("${current.javaClass.name}: ${current.message}")).append('\n')
        val frames = current.stackTrace
        for (frame in frames.take(64)) {
            if (stack.length >= TEST_STACK_TRACE_LIMIT) break
            stack.append("\tat ").append(reportText(frame.toString())).append('\n')
        }
        if (frames.size > 64) stack.append("\t... remaining frames omitted\n")
        if (current.suppressed.isNotEmpty()) stack.append("\t... suppressed exceptions omitted\n")
        current = current.cause
    }
    if (current != null) stack.append("... remaining causes omitted\n")
    return TestFailure(if (failure is AssertionError) TestFailureKind.ASSERTION else TestFailureKind.EXCEPTION,
        reportText(failure.javaClass.name), failure.message?.let(::reportText), reportText(stack.toString(), TEST_STACK_TRACE_LIMIT))
}

/** Append only completed observations. An interrupted write leaves an ignorable final fragment. */
private class TestProgress(path: Path, requestId: String, module: String) : AutoCloseable {
    private val channel = FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
    private var sequence = 0
    private var bytes = 0L
    var failure: String? = null
        private set

    init {
        append(obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "jvm-test-progress".json(),
            "requestId" to requestId.json(), "module" to module.json(), "target" to "jvm".json()))
    }

    fun attempt(test: TestResult, attempt: TestAttempt) = append(obj(
        "sequence" to (sequence++).json(), "kind" to "attempt".json(), "id" to test.id.json(),
        "suite" to (test.details!!.suite?.json() ?: JsonValue.Null),
        "name" to (test.details.name?.json() ?: JsonValue.Null),
        "message" to (test.message?.json() ?: JsonValue.Null), "attempt" to attempt.toJson()))

    fun diagnostic(value: Diagnostic) = append(obj("sequence" to (sequence++).json(), "kind" to "diagnostic".json(),
        "diagnostic" to value.toJson()))

    private fun append(value: JsonValue) {
        if (failure != null) return
        try {
            val data = (value.canonical() + "\n").encodeToByteArray()
            check(sequence <= 1_000_000 && bytes + data.size <= 64L * 1024 * 1024) { "Test progress journal exceeds its limit" }
            val buffer = ByteBuffer.wrap(data)
            while (buffer.hasRemaining()) channel.write(buffer)
            bytes += data.size
        } catch (problem: Exception) {
            failure = reportText("Cannot write test progress: ${problem.message}")
        }
    }

    override fun close() {
        try { channel.close() } catch (problem: Exception) {
            if (failure == null) failure = reportText("Cannot close test progress: ${problem.message}")
        }
    }
}
