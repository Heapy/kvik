package kvik.jvm

import kvik.core.*
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.system.exitProcess
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.*
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

/** One fresh test process per request. Test execution is never restored from a cache. */
object TestWorker {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3) { "TestWorker <workspace> <request-relative> <result-relative>" }
        val workspace = Workspace(Path.of(args[0]))
        var requestId = "invalid-request"
        val tests = linkedMapOf<String, TestResult>()
        val diagnostics = mutableListOf<Diagnostic>()
        var counts = TestCounts(null, 0, 0, 0, false)
        var status = "incomplete"
        var exit = 3
        try {
            val request = workspace.readJson(args[1]).fields(setOf("schemaVersion", "requestId", "module", "target", "classpath", "testClasses", "engines"))
            request.requireVersion()
            requestId = request.getValue("requestId").stringValue()
            val module = request.getValue("module").stringValue()
            val target = request.getValue("target").stringValue()
            require(requestId.isNotBlank() && module.isNotBlank() && target == "jvm")
            val classpath = request.getValue("classpath").arrayValue().map { workspace.classpath(it.stringValue()) }
            require(classpath.isNotEmpty() && classpath.distinct().size == classpath.size)
            val classes = request.getValue("testClasses").arrayValue().map { it.stringValue() }
            require(classes.distinct().size == classes.size && classes.all { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_.$]*")) })
            val engineIds = request.getValue("engines").arrayValue().map { it.stringValue() }.toSet()
            require(engineIds.isNotEmpty() && engineIds.all { it in setOf("junit-jupiter", "kotest") })
            if (classes.isEmpty()) reject("KVIK_TEST_EMPTY_SELECTION", "No test classes were selected")
            // A test worker is a fresh JVM. Kotlin stdlib and kotlin-reflect must share its loader;
            // loading reflect only in a child loader breaks stdlib's ReflectionFactory discovery.
            fun codeSource(type: Class<*>): Path = Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()
            // The native composition verifies these three bootstrap artifacts. No other entries
            // are permitted before, among or after the ordered consumer classpath.
            val bootstrap = listOf(codeSource(TestWorker::class.java), codeSource(LauncherFactory::class.java), codeSource(kotlin.Unit::class.java))
            val processClasspath = System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Path.of(it).toRealPath() }
            if (processClasspath != bootstrap + classpath) reject("KVIK_TEST_CLASSPATH", "JVM classpath must be exactly worker, JUnit Platform bundle, stdlib, then the ordered declared test classpath")
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
                        .selectors(classes.map(::selectClass)).configurationParameter("junit.jupiter.execution.parallel.enabled", "false").build()
                    val plan = launcher.discover(discovery)
                    val discovered = plan.roots.flatMap { plan.getDescendants(it) }.filter { it.isTest }.associateBy { it.uniqueId }.toMutableMap()
                    var finished = false
                    val lock = Any()
                    val listener = object : TestExecutionListener {
                        override fun dynamicTestRegistered(identifier: TestIdentifier) = synchronized(lock) {
                            if (identifier.isTest) discovered[identifier.uniqueId] = identifier
                        }
                        override fun executionSkipped(identifier: TestIdentifier, reason: String) = synchronized(lock) {
                            val skipped = if (identifier.isTest) listOf(identifier) else plan.getDescendants(identifier).filter { it.isTest }
                            for (test in skipped) {
                                discovered[test.uniqueId] = test
                                tests[test.uniqueId] = TestResult(test.uniqueId, module, target, TestStatus.SKIPPED, reason)
                            }
                        }
                        override fun executionFinished(identifier: TestIdentifier, result: TestExecutionResult) = synchronized(lock) {
                            if (identifier.isTest) {
                                discovered[identifier.uniqueId] = identifier
                                val state = when (result.status) {
                                    TestExecutionResult.Status.SUCCESSFUL -> TestStatus.PASSED
                                    TestExecutionResult.Status.FAILED -> TestStatus.FAILED
                                    TestExecutionResult.Status.ABORTED -> TestStatus.SKIPPED
                                }
                                tests[identifier.uniqueId] = TestResult(identifier.uniqueId, module, target, state,
                                    result.throwable.orElse(null)?.let { "${it::class.qualifiedName}: ${it.message}" })
                            } else if (result.status == TestExecutionResult.Status.ABORTED) {
                                for (test in plan.getDescendants(identifier).filter { it.isTest }) {
                                    discovered[test.uniqueId] = test
                                    tests.putIfAbsent(test.uniqueId, TestResult(test.uniqueId, module, target, TestStatus.SKIPPED,
                                        result.throwable.orElse(null)?.message ?: "Container aborted"))
                                }
                            } else if (result.status == TestExecutionResult.Status.FAILED) {
                                diagnostics += Diagnostic("KVIK_TEST_CONTAINER_FAILURE", DiagnosticCategory.TEST,
                                    "${identifier.uniqueId}: ${result.status}: ${result.throwable.orElse(null)?.message ?: ""}", module = module, target = target)
                            }
                        }
                        override fun testPlanExecutionFinished(testPlan: TestPlan) { finished = true }
                    }
                    launcher.execute(plan, listener)
                    val allDiscoveredReported = finished && discovered.keys == tests.keys
                    counts = TestCounts(discovered.size, tests.values.count { it.status != TestStatus.SKIPPED },
                        tests.values.count { it.status == TestStatus.FAILED }, tests.values.count { it.status == TestStatus.SKIPPED }, allDiscoveredReported)
                    when {
                        counts.failed > 0 || diagnostics.isNotEmpty() -> { status = "failed"; exit = 1 }
                        counts.satisfiesExecution -> { status = "passed"; exit = 0 }
                        else -> diagnostics += Diagnostic("KVIK_TEST_INCOMPLETE", DiagnosticCategory.TEST,
                            "Selected scope has no completed passing execution or discovery is incomplete", module = module, target = target)
                    }
                } finally { thread.contextClassLoader = previous }
            }
        } catch (failure: Exception) {
            if ((failure as? AdapterFailure)?.code == "KVIK_TEST_EMPTY_SELECTION") {
                counts = TestCounts(0, 0, 0, 0, true)
                diagnostics += Diagnostic("KVIK_TEST_INCOMPLETE", DiagnosticCategory.TEST, failure.message ?: "No tests selected")
            } else {
                failure.printStackTrace(System.err)
                status = "failed"; exit = 1
                diagnostics += Diagnostic((failure as? AdapterFailure)?.code ?: "KVIK_TEST_INFRASTRUCTURE", DiagnosticCategory.INFRASTRUCTURE,
                    generateSequence<Throwable>(failure) { it.cause }.take(8).joinToString("; caused by: ") { "${it::class.simpleName}: ${it.message}" })
                counts = TestCounts(null, tests.values.count { it.status != TestStatus.SKIPPED }, tests.values.count { it.status == TestStatus.FAILED },
                    tests.values.count { it.status == TestStatus.SKIPPED }, false)
            }
        }
        workspace.publish(args[2], obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to requestId.json(),
            "status" to status.json(), "exitCode" to exit.json(), "tests" to tests.values.sortedBy { it.id }.map { it.toJson() }.json(),
            "testCounts" to counts.toJson(), "diagnostics" to diagnostics.map { it.toJson() }.json()))
        exitProcess(exit)
    }
}
