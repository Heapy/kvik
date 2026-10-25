@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.js

import kotlinx.cinterop.toKString
import kvik.cache.*
import kvik.compiler.jvm.JvmToolchainProvider
import kvik.core.*
import kvik.execution.*
import kvik.runevidence.*
import platform.posix.*
import kotlin.test.*
import kotlin.time.TimeSource

class JsTestProgressRealTest {
    @Test fun realNodeRetriesResetMemoryAndRetainOriginalFailuresAndLogs() {
        val node = checkNotNull(getenv("KVIK_JS_TEST_NODE")).toKString()
        val guardian = checkNotNull(getenv("KVIK_JS_TEST_GUARDIAN")).toKString()
        for (target in KotlinJsTarget.entries) {
            val root = NativeRunDirectory("/private/tmp")
            val store = NativeContentStore("${root.path}/store")
            val body = """
                describe('suite',()=>{
                    it('flaky',()=>{
                        if (globalThis.alreadyRan) throw Error('process memory was reused');
                        globalThis.alreadyRan = true;
                        if (existsSync('marker')) { console.log('RECOVERED'); return; }
                        writeFileSync('marker','first failure');
                        console.log('FIRST_FAILURE');
                        throw Error('original failure');
                    });
                    it('sibling',()=>{});
                });
            """.trimIndent()
            val source = "import {existsSync,writeFileSync} from 'node:fs';\n" +
                if (target == KotlinJsTarget.JS) body else "export function startUnitTests(){$body}"
            val digest = store.put(source.encodeToByteArray())
            val selection = JsTestSelection("sample", target, LogicalPath("tests.mjs"), maxRetries = 2)
            val action = jsTestAction(selection, TreeManifest(listOf(FileEntry(selection.entryPoint, digest))), digest, mapOf("node" to digest))
            NativeProcessHost(guardian).use { host ->
                val executor = JsCompilerExecutor(root.runId, root.path, host, store,
                    JvmToolchainProvider { error("No compiler") }, NodeToolchainProvider { _, _ -> node })
                executor.start(root.runId, "tests", action).use { running ->
                    val start = TimeSource.Monotonic.markNow()
                    while (running.poll() == null) { check(start.elapsedNow().inWholeSeconds < 15); usleep(10_000u) }
                    val report = executor.observations.getValue("tests").tests!!
                    assertEquals(VerificationStatus.FAILED, report.status)
                    assertEquals(TestCounts(2, 2, 0, 0, true), report.counts)
                    assertTrue(report.tests.all { it.details!!.attempts.size == 2 })
                    val attempts = report.tests.single { it.id == "suite.flaky" }.details!!.attempts
                    assertEquals(listOf(TestStatus.FAILED, TestStatus.PASSED), attempts.map { it.status })
                    assertEquals("original failure", attempts.first().failure!!.message)
                    for ((index, attempt) in attempts.withIndex()) {
                        val log = attempt.logs.single { it.stream == TestLogStream.STDOUT }
                        val bytes = NativeFiles.read("${root.path}/${log.path.value}", 1024)
                        assertEquals(log.digest, Sha256.digest(bytes))
                        assertContains(bytes.decodeToString(), if (index == 0) "FIRST_FAILURE" else "RECOVERED")
                    }
                }
            }
        }
    }

    @Test fun realOwnedNodeRetainsCompletedTestsThroughInterruptionAndExport() {
        val node = checkNotNull(getenv("KVIK_JS_TEST_NODE")) { "Run tools/test_js_execution.py" }.toKString()
        val guardian = checkNotNull(getenv("KVIK_JS_TEST_GUARDIAN")).toKString()
        for (target in KotlinJsTarget.entries) for (variant in listOf("passed", "timeout", "timeout-failed", "cancel-failed", "exit-zero")) {
            val root = NativeRunDirectory("/private/tmp")
            val store = NativeContentStore("${root.path}/store")
            val failed = variant.endsWith("failed")
            val cancel = variant.startsWith("cancel")
            val body = """
                describe('partial',()=>{
                    it('passed',()=>{});
                    xit('skipped',()=>{throw Error('must not execute')});
                    ${if (failed) "it('failed',()=>{throw Error('retained failure')});" else ""}
                    it('last',()=>{
                        writeFileSync('ready','ready');
                        ${when (variant) {
                            "passed" -> "return;"
                            "exit-zero" -> "process.exit(0);"
                            else -> "return new Promise(()=>setInterval(()=>{},1000));"
                        }}
                    });
                });
            """.trimIndent()
            val source = "import {writeFileSync} from 'node:fs';\n" +
                if (target == KotlinJsTarget.JS) body else "export function startUnitTests(){$body}"
            val digest = store.put(source.encodeToByteArray())
            val selection = JsTestSelection("sample", target, LogicalPath("tests.mjs"))
            val action = jsTestAction(selection, TreeManifest(listOf(FileEntry(selection.entryPoint, digest))), digest, mapOf("node" to digest),
                if (variant.startsWith("timeout")) 1500 else 10_000)
            val check = "sample:${target.projectName}:test"
            NativeProcessHost(guardian).use { native ->
                var directory: String? = null
                val host = object : ProcessHost {
                    override fun start(request: ProcessRequest): OwnedProcess {
                        directory = request.workingDirectory
                        return native.start(request)
                    }
                    override fun close() = Unit
                }
                // This fixture exercises the real process boundary; installation inventory
                // validation is covered by the production Node provider's own tests.
                val executor = JsCompilerExecutor(root.runId, root.path, host, store,
                    JvmToolchainProvider { error("No compiler during Node tests") }, NodeToolchainProvider { identity, checkpoint ->
                        checkpoint(); require(identity == digest); node
                    })
                executor.start(root.runId, check, action).use { running ->
                    val start = TimeSource.Monotonic.markNow()
                    if (cancel) {
                        while (directory?.let { NativeFiles.info("$it/ready") } == null) {
                            assertNull(running.poll(), "$target/$variant exited before cancellation")
                            check(start.elapsedNow().inWholeSeconds < 10) { "Node did not start" }
                            usleep(10_000u)
                        }
                        running.cancel()
                    }
                    while (running.poll() == null) {
                        check(start.elapsedNow().inWholeSeconds < 15) { "Node did not terminate" }
                        usleep(10_000u)
                    }
                    val observed = executor.observations.getValue(check)
                    val report = assertNotNull(observed.tests)
                    val expected = when {
                        cancel -> VerificationStatus.CANCELLED
                        failed -> VerificationStatus.FAILED
                        variant == "passed" -> VerificationStatus.PASSED
                        else -> VerificationStatus.INCOMPLETE
                    }
                    assertEquals(expected, report.status, "$target/$variant: ${observed.result.diagnostic}")
                    assertEquals(if (variant == "passed") 3 else if (failed) 3 else 2, report.tests.size)
                    assertEquals(if (failed) 4 else 3, report.counts.discovered)
                    assertEquals(variant == "passed", report.counts.discoveryComplete)
                    assertEquals(if (failed) 1 else 0, report.counts.failed)
                    assertEquals(1, report.counts.skipped)
                    if (variant != "passed") assertTrue(report.tests.none { it.id == "partial.last" })
                    val evidence = aggregateRunEvidence(root.runId, setOf(check), mapOf(check to check), mapOf(check to emptySet()),
                        listOf(observed.result), mapOf(check to TestObservation(report.tests, report.counts, report.status)),
                        digest, digest, digest, digest, mapOf("node" to digest), listOf("kvik", "test"), Freshness.CURRENT)
                    assertEquals(expected, evidence.record.status)
                    assertEquals(report.tests.sortedBy { it.id }, evidence.record.tests)
                    val events = observed.diagnostics.mapIndexed { index, diagnostic -> RunEvent(root.runId, index + 1L, "diagnostic", diagnostic.toJson() as JsonValue.Object) }
                    val exported = testReport(evidence.record, events)
                    assertEquals(expected, exported.status)
                    assertEquals(expected, exported.scopes.single().status)
                    assertEquals(report.counts.executed, exported.uniqueCounts.executed)
                    if (expected == VerificationStatus.INCOMPLETE || expected == VerificationStatus.CANCELLED) assertContains(junitXml(exported), "IncompleteVerification")
                    if (failed) assertContains(junitXml(exported), "retained failure")
                }
            }
        }
    }
}
