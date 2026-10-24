@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
package kvik.compiler.native

import kvik.core.*
import kotlin.test.*
import platform.posix.usleep
import kotlin.time.TimeSource
import kvik.cache.NativeContentStore
import kvik.execution.NativeRunDirectory
import kvik.execution.SchedulerRun
import kvik.execution.EventJournal
import kotlin.concurrent.atomics.AtomicInt

class NativeUnitTest {
    @Test fun linuxCompilationUsesDistinctIdentityTargetAndOutputContract() {
        val digest = Sha256.digest(byteArrayOf(1))
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("Main.kt"), digest)))
        val mac = NativeCompilation("sample", listOf(LogicalPath("Main.kt")), output = LogicalPath("app.kexe"), produce = NativeOutput.PROGRAM)
        assertFalse("target" in mac.toJson().values)
        assertEquals(mac, NativeCompilation.fromJson(mac.toJson()))
        val linux = mac.copy(target = NativeTarget.LINUX_X64)
        assertEquals(linux, NativeCompilation.fromJson(linux.toJson()))
        val action = nativeCompileAction(linux, inputs, digest, NativeTarget.LINUX_X64.toolIdentityNames.associateWith { digest })
        assertEquals("linuxX64", action.target)
        assertEquals(NativeTarget.LINUX_X64.platform, action.platform)
        assertEquals("linux_x64", action.arguments[action.arguments.indexOf("-target") + 1])
        assertEquals(listOf(LogicalPath("app.kexe")), action.outputs)
        assertEquals(CachePolicy.LOCAL, action.cachePolicy)
        assertFails { nativeCompileAction(linux, inputs, digest, NativeTarget.MACOS_ARM64.toolIdentityNames.associateWith { digest }) }
        val macAction = nativeCompileAction(mac, inputs, digest, NativeTarget.MACOS_ARM64.toolIdentityNames.associateWith { digest })
        assertContains(macAction.outputs, LogicalPath("app.kexe.dSYM"))
        assertNotEquals(macAction.key(Sha256), action.key(Sha256))
        assertNotEquals(nativeDependenciesIdentity(digest, digest, digest), linuxNativeDependenciesIdentity(digest, digest, digest))
    }

    @Test fun linuxTestRecipesAndReportsRetainTargetAndNeverCache() {
        val digest = Sha256.digest(byteArrayOf(1)); val executable = LogicalPath("test.kexe")
        val inputs = TreeManifest(listOf(FileEntry(executable, digest, true)))
        val tools = NativeTarget.LINUX_X64.toolIdentityNames.associateWith { digest }
        for (action in listOf(nativeTestAction("sample", executable, inputs, digest, tools, target = NativeTarget.LINUX_X64),
            kotestNativeTestAction("sample", executable, inputs, digest, tools, target = NativeTarget.LINUX_X64))) {
            assertEquals("linuxX64", action.target); assertEquals(CachePolicy.NEVER, action.cachePolicy)
            assertEquals(NativeTarget.LINUX_X64.platform, action.platform)
        }
        val report = nativeTestReport("sample", listOf("Suite.test"),
            "##teamcity[testSuiteStarted name='Suite']\n##teamcity[testStarted name='test']\n##teamcity[testFinished name='test']\n##teamcity[testSuiteFinished name='Suite']\n",
            ProcessResult(0), NativeTarget.LINUX_X64)
        assertEquals(VerificationStatus.PASSED, report.status)
        assertEquals("linuxX64", report.tests.single().target)
        assertEquals("linuxX64", nativeCompilerDiagnostics("error: broken", "sample", "id", NativeTarget.LINUX_X64).single().target)
    }

    @Test fun linuxLayoutOmitsDarwinEnvironmentAndMismatchedProviderCannotStart() {
        val linux = NativeToolchainLayout("/native/bin/konanc", "/jdk", "", "/dependencies", "", target = NativeTarget.LINUX_X64)
        assertEquals(mapOf("JAVA_HOME" to "/jdk", "KONAN_DATA_DIR" to "/dependencies"), linux.environment())
        assertFails { linux.copy(sdk = "/sdk") }
        assertFails { linux.copy(developerDirectory = "/developer") }
        val digest = Sha256.digest(byteArrayOf(1))
        val action = nativeCompileAction(NativeCompilation("sample", listOf(LogicalPath("Main.kt")), output = LogicalPath("module.klib"), target = NativeTarget.LINUX_X64),
            TreeManifest(listOf(FileEntry(LogicalPath("Main.kt"), digest))), digest, NativeTarget.LINUX_X64.toolIdentityNames.associateWith { digest })
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess = error("Mismatched provider must never launch")
            override fun close() = Unit
        }
        val content = object : ContentStore {
            override fun put(bytes: ByteArray): Digest = error("No CAS publication")
            override fun get(digest: Digest): ByteArray? = error("No materialization")
        }
        val executor = NativeCompilerExecutor("linux", "/uncreated", host, content,
            NativeToolchainProvider { NativeToolchainLayout("/compiler", "/jdk", "/sdk", "/dependencies", "/developer") })
        executor.start("linux", "compile", action).use { running ->
            val started = TimeSource.Monotonic.markNow()
            var result = running.poll()
            while (result == null && started.elapsedNow().inWholeSeconds < 3) { usleep(1_000u); result = running.poll() }
            assertEquals(ActionStatus.FAILED, assertNotNull(result).status)
        }
    }

    @Test fun slowNeighborVerificationDoesNotConsumeTestDiscoveryDeadline() {
        val root = NativeRunDirectory("/private/tmp").path
        val store = NativeContentStore("$root/store")
        val digest = store.put(byteArrayOf(1))
        val tools = listOf("native", "jdk", "sdk", "native-dependencies").associateWith { digest }
        val slowNative = Sha256.digest(byteArrayOf(2))
        val test = nativeTestAction("sample", LogicalPath("tests.kexe"),
            TreeManifest(listOf(FileEntry(LogicalPath("tests.kexe"), digest, true))), digest, tools, timeoutMillis = 1_000)
        val compile = nativeCompileAction(NativeCompilation("slow", listOf(LogicalPath("Main.kt")), output = LogicalPath("main.klib")),
            TreeManifest(listOf(FileEntry(LogicalPath("Main.kt"), digest))), digest, tools + ("native" to slowNative))
        val slowEntered = AtomicInt(0)
        val releaseSlow = AtomicInt(0)
        val slowExited = AtomicInt(0)
        val provider = object : NativeToolchainProvider {
            override fun verified(identities: Map<String, Digest>) = verified(identities) {}
            override fun verified(identities: Map<String, Digest>, checkpoint: () -> Unit): NativeToolchainLayout {
                if (identities.getValue("native") == slowNative) {
                    slowEntered.store(1)
                    val started = TimeSource.Monotonic.markNow()
                    try {
                        while (releaseSlow.load() == 0 && started.elapsedNow().inWholeMilliseconds < 3_000) {
                            checkpoint()
                            usleep(1_000u)
                        }
                    } finally { slowExited.store(1) }
                }
                return NativeToolchainLayout("/compiler", "/jdk", "/sdk", "/dependencies", "/developer")
            }
        }
        var launches = 0
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess {
                launches++
                assertTrue(request.arguments.first().endsWith("/input/tests.kexe"))
                val output = if ("--ktest_list_tests" in request.arguments) "sample.Suite.\n  test\n" else
                    "##teamcity[testSuiteStarted name='sample.Suite']\n##teamcity[testStarted name='test']\n" +
                    "##teamcity[testFinished name='test']\n##teamcity[testSuiteFinished name='sample.Suite']\n"
                writeNativeFile(request.stdoutFile, output.encodeToByteArray())
                writeNativeFile(request.stderrFile, byteArrayOf())
                return object : OwnedProcess {
                    override fun poll() = ProcessResult(0)
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
            override fun close() = Unit
        }
        val executor = NativeCompilerExecutor("parallel", root, host, store, provider)
        try {
            SchedulerRun("parallel", listOf(ActionNode("a-test", emptySet()) { test }, ActionNode("b-compile", emptySet()) { compile }),
                executor, ResourceBudget(2, 4096), EventJournal {}).use { run ->
                val started = TimeSource.Monotonic.markNow()
                while ("a-test" !in run.results || slowEntered.load() == 0) {
                    check(started.elapsedNow().inWholeMilliseconds < 2_000) { "Tool verification blocked test progress" }
                    run.tick()
                    usleep(1_000u)
                }
                assertEquals(ActionStatus.EXECUTED, run.results.getValue("a-test").status)
                assertEquals(VerificationStatus.PASSED, executor.observations.getValue("a-test").tests?.status)
                assertEquals(0, slowExited.load(), "Test must finish while neighbor verification is still pending")
                assertEquals(2, launches)
                run.cancel()
                assertTrue(run.tick())
                assertEquals(ActionStatus.CANCELLED, run.results.getValue("b-compile").status)
            }
        } finally { releaseSlow.store(1) }
        assertEquals(1, slowExited.load(), "Cancellation must join the preparation thread")
        assertEquals(2, launches, "Cancelled preparation must not launch a compiler")
    }

    @Test fun sourceFreeLinkIncludesDeclaredKlibWithoutExposingRuntimeLibrariesToCompilation() {
        val included = LogicalPath("modules/test.klib")
        val runtime = LogicalPath("runtime/private.klib")
        val bytes = Sha256.digest(byteArrayOf(1))
        val inputs = TreeManifest(listOf(FileEntry(included, bytes), FileEntry(runtime, bytes)))
        val tools = listOf("native", "jdk", "sdk", "native-dependencies").associateWith { bytes }
        val link = NativeCompilation("module", emptyList(), libraries = listOf(runtime), output = LogicalPath("tests.kexe"), produce = NativeOutput.TEST, includedLibrary = included)
        assertEquals(link, NativeCompilation.fromJson(link.toJson()))
        val action = nativeCompileAction(link, inputs, bytes, tools)
        assertTrue("-Xinclude=input/modules/test.klib" in action.arguments)
        assertTrue("-Xpartial-linkage-loglevel=error" in action.arguments)
        assertTrue("-generate-test-runner" in action.arguments)
        assertEquals(ActionKind.LINK, action.kind)
        assertTrue("-Werror" in link.copy(warningsAsErrors = true).arguments())
        assertFails { link.copy(sources = listOf(LogicalPath("Main.kt"))) }
        assertFails { link.copy(libraries = listOf(included)) }
        assertFails { link.copy(plugins = listOf(LogicalPath("plugin.jar"))) }
        assertFails { link.copy(explicitApi = true) }
        assertFails { link.copy(produce = NativeOutput.LIBRARY, output = LogicalPath("output.klib")) }
        assertFails { nativeCompileAction(link, TreeManifest(listOf(FileEntry(runtime, bytes))), bytes, tools) }
        val changed = TreeManifest(inputs.files.map { if (it.path == runtime) it.copy(digest = Sha256.digest(byteArrayOf(2))) else it })
        assertNotEquals(action.key(Sha256), nativeCompileAction(link, changed, bytes, tools).key(Sha256))
        val old = NativeCompilation("module", listOf(LogicalPath("Main.kt")), output = LogicalPath("main.klib"))
        assertFalse("includedLibrary" in old.toJson().objectValue())
        assertFalse(old.arguments().any { it.startsWith("-Xpartial-linkage") })
        assertEquals(old, NativeCompilation.fromJson(old.toJson()))
    }
    @Test fun verifiedToolEnvironmentAndOfflinePolicyCannotBeOverridden() {
        val root = NativeRunDirectory("/private/tmp").path
        val store = NativeContentStore("$root/store")
        val digest = store.put("class Example".encodeToByteArray())
        val input = TreeManifest(listOf(FileEntry(LogicalPath("Example.kt"), digest)))
        val identities = listOf("native", "jdk", "sdk", "native-dependencies").associateWith { digest }
        val action = nativeCompileAction(NativeCompilation("example", listOf(LogicalPath("Example.kt")), output = LogicalPath("example.klib")), input, digest, identities)
        var verifications = 0
        val requests = mutableListOf<ProcessRequest>()
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess {
                requests += request
                writeNativeFile(request.stdoutFile, byteArrayOf())
                writeNativeFile(request.stderrFile, "error: deliberate fixture failure".encodeToByteArray())
                return object : OwnedProcess {
                    override fun poll() = ProcessResult(1)
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
            override fun close() = Unit
        }
        val executor = NativeCompilerExecutor("tools", root, host, store, NativeToolchainProvider { requested ->
            verifications++
            assertEquals(identities, requested)
            NativeToolchainLayout("/verified/konanc", "/verified/jdk", "/verified/sdk", "/verified/konan", "/verified/Xcode/Contents/Developer")
        })
        val offline = "-Xoverride-konan-properties=airplaneMode=true"
        for ((id, modified) in listOf(
            "removed" to action.copy(arguments = action.arguments - offline),
            "overridden" to action.copy(arguments = action.arguments + "-Xoverride-konan-properties=airplaneMode=false"),
            "internal-server" to action.copy(environment = action.environment + ("KONAN_USE_INTERNAL_SERVER" to "1")),
            "developer" to action.copy(environment = action.environment + ("DEVELOPER_DIR" to "/unverified")),
            "sdk" to action.copy(environment = action.environment + ("SDKROOT" to "/unverified")))) {
            assertFailsWith<IllegalArgumentException> { executor.start("tools", id, modified) }
        }
        assertEquals(0, verifications)
        assertTrue(requests.isEmpty())
        executor.start("tools", "valid", action).use { assertEquals(ActionStatus.FAILED, awaitResult(it).status) }
        val process = requests.single()
        assertEquals(1, process.arguments.count { it == offline })
        assertEquals(mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin", "KONAN_USE_INTERNAL_SERVER" to "0", "JAVA_HOME" to "/verified/jdk", "SDKROOT" to "/verified/sdk",
            "KONAN_DATA_DIR" to "/verified/konan", "DEVELOPER_DIR" to "/verified/Xcode/Contents/Developer", "HOME" to "${process.workingDirectory}/home"), process.environment)
        val included = LogicalPath("compiled.klib")
        val link = nativeCompileAction(NativeCompilation("example", emptyList(), output = LogicalPath("tests.kexe"), produce = NativeOutput.TEST, includedLibrary = included),
            TreeManifest(listOf(FileEntry(included, digest))), digest, identities)
        executor.start("tools", "link", link).use { assertEquals(ActionStatus.FAILED, awaitResult(it).status) }
        val bound = requests.last()
        assertTrue("-Xinclude=${bound.workingDirectory}/input/compiled.klib" in bound.arguments)
        assertTrue("-Xinclude=input/compiled.klib" in link.arguments)
        val other = Sha256.digest(byteArrayOf(2))
        val original = nativeDependenciesIdentity(digest, digest, digest)
        for (changed in listOf(nativeDependenciesIdentity(other, digest, digest), nativeDependenciesIdentity(digest, other, digest), nativeDependenciesIdentity(digest, digest, other))) assertNotEquals(original, changed)
        assertFailsWith<IllegalArgumentException> { NativeToolchainLayout("/compiler", "/jdk", "/sdk", "/dependencies", "relative/developer") }
    }

    @Test fun compilerTimeoutsDistinguishExit124AndCancellationAndDiscardPartialOutputs() {
        val root = NativeRunDirectory("/private/tmp").path
        val store = NativeContentStore("$root/store")
        val digest = store.put("class Example".encodeToByteArray())
        val identities = listOf("native", "jdk", "sdk", "native-dependencies").associateWith { digest }
        val source = LogicalPath("Example.kt")
        val included = LogicalPath("compiled.klib")
        for (link in listOf(false, true)) {
            val compilation = if (link) NativeCompilation("example", emptyList(), output = LogicalPath("tests.kexe"),
                produce = NativeOutput.TEST, includedLibrary = included)
                else NativeCompilation("example", listOf(source), output = LogicalPath("example.klib"))
            val input = TreeManifest(listOf(FileEntry(if (link) included else source, digest)))
            val action = nativeCompileAction(compilation, input, digest, identities, timeoutMillis = 12_345)
            for (scenario in listOf("timeout", "timeout-with-error", "exit124", "host-cancelled", "late-cancelled")) {
                val phase = if (link) "link" else "compile"
                val id = "$phase-$scenario"
                val partial = "partial output from $id".encodeToByteArray()
                var cancelled = false
                var launched = false
                var closed = false
                val host = object : ProcessHost {
                    override fun start(request: ProcessRequest): OwnedProcess {
                        launched = true
                        assertTrue(request.timeoutMillis in 1..action.timeoutMillis)
                        writeNativeFile(request.stdoutFile, byteArrayOf())
                        writeNativeFile(request.stderrFile,
                            (if (scenario == "timeout-with-error") "error: compiler emitted a prior error" else "").encodeToByteArray())
                        writeNativeFile("${request.workingDirectory}/output/${compilation.output.value}", partial)
                        return object : OwnedProcess {
                            override fun poll() = ProcessResult(124, timedOut = scenario != "exit124", cancelled = scenario == "host-cancelled")
                            override fun cancel() { cancelled = true }
                            override fun close() { closed = true }
                        }
                    }
                    override fun close() = Unit
                }
                val executor = NativeCompilerExecutor("timeouts", root, host, store,
                    NativeToolchainProvider { NativeToolchainLayout("/compiler", "/jdk", "/sdk", "/dependencies", "/developer") })
                executor.start("timeouts", id, action).use { running ->
                    if (scenario == "late-cancelled") {
                        awaitLaunch(running) { launched }
                        running.cancel()
                    }
                    val result = awaitResult(running)
                    assertEquals(result, running.poll())
                    assertTrue(result.outputs.files.isEmpty())
                    assertNull(store.get(Sha256.digest(partial)), "Failed/cancelled partial output must not enter CAS")
                    val observation = executor.observations.getValue(id)
                    assertEquals(result, observation.result)
                    assertNull(observation.tests)
                    when (scenario) {
                        "timeout", "timeout-with-error" -> {
                            assertEquals(ActionStatus.FAILED, result.status)
                            assertEquals(124, result.exitCode)
                            val timeout = observation.diagnostics.single { it.code == "KVIK_NATIVE_TIMEOUT" }
                            assertEquals("Native $phase timed out after 12345 ms", timeout.message)
                            assertEquals(timeout.message, result.diagnostic)
                            assertEquals(DiagnosticCategory.INFRASTRUCTURE, timeout.category)
                            assertEquals(id, timeout.actionId)
                            assertEquals("example", timeout.module)
                            assertEquals("macosArm64", timeout.target)
                            assertFalse(observation.diagnostics.any { it.code == "KVIK_NATIVE_PROCESS_FAILED" })
                            assertEquals(scenario == "timeout-with-error", observation.diagnostics.any { it.code == "KVIK_NATIVE_ERROR" })
                        }
                        "exit124" -> {
                            assertEquals(ActionStatus.FAILED, result.status)
                            assertEquals(124, result.exitCode)
                            val failure = observation.diagnostics.single()
                            assertEquals("KVIK_NATIVE_PROCESS_FAILED", failure.code)
                            assertEquals("Native process exited 124", failure.message)
                            assertNull(result.diagnostic)
                        }
                        else -> {
                            assertEquals(ActionStatus.CANCELLED, result.status)
                            assertNull(result.diagnostic)
                            assertTrue(observation.diagnostics.isEmpty())
                            if (scenario == "late-cancelled") assertEquals(130, result.exitCode)
                        }
                    }
                }
                assertTrue(closed)
                assertEquals(scenario == "late-cancelled", cancelled)
            }
        }
    }

    @Test fun malformedSuiteFinishProducesIncompleteReport() {
        for (events in listOf("##teamcity[testSuiteFinished]", "##teamcity[testSuiteFinished name='absent']",
            "##teamcity[testSuiteStarted name='suite']\n##teamcity[testSuiteFinished]")) {
            val report = nativeTestReport("sample", listOf("suite.test"), events, ProcessResult(0))
            assertEquals(VerificationStatus.INCOMPLETE, report.status)
            assertEquals(TestCounts(1, 0, 0, 0, false), report.counts)
            assertNotNull(report.diagnostic)
        }
    }

    @Test fun cancellationAfterDiscoveryExitPreventsTestLaunch() {
        val root = NativeRunDirectory("/private/tmp").path
        val store = NativeContentStore("$root/store")
        val digest = store.put(byteArrayOf(1))
        val input = TreeManifest(listOf(FileEntry(LogicalPath("tests.kexe"), digest, true)))
        val identities = listOf("native", "jdk", "sdk", "native-dependencies").associateWith { digest }
        val action = nativeTestAction("sample", LogicalPath("tests.kexe"), input, digest, identities)
        var starts = 0
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess {
                starts++
                writeNativeFile(request.stdoutFile, "sample.Suite.\n  test\n".encodeToByteArray())
                writeNativeFile(request.stderrFile, byteArrayOf())
                return object : OwnedProcess {
                    override fun poll() = ProcessResult(0) // Already exited successfully before cancellation.
                    override fun cancel() = Unit
                    override fun close() = Unit
                }
            }
            override fun close() = Unit
        }
        val executor = NativeCompilerExecutor("review", root, host, store,
            NativeToolchainProvider { NativeToolchainLayout("/compiler", "/jdk", "/sdk", "/dependencies", "/developer") })
        executor.start("review", "tests", action).use { running ->
            awaitLaunch(running) { starts == 1 }
            running.cancel()
            val result = awaitResult(running)
            assertEquals(ActionStatus.CANCELLED, result.status)
            assertEquals(130, result.exitCode)
            assertEquals(result, running.poll())
        }
        assertEquals(1, starts)
        assertEquals(VerificationStatus.CANCELLED, executor.observations.getValue("tests").tests?.status)
    }
    @Test fun diagnosticsPreserveSourceLocation() {
        val d = nativeCompilerDiagnostics("input/src/Bad.kt:2:4: error: Unresolved reference 'absent'.", "sample", "compile").single()
        assertEquals("src/Bad.kt", d.path); assertEquals(2, d.line); assertEquals(4, d.column); assertEquals(DiagnosticCategory.COMPILATION, d.category)
    }
    @Test fun typedArgumentsRejectUndeclaredInputsAndOptions() {
        val d = Sha256.digest(byteArrayOf())
        val c = NativeCompilation("m", listOf(LogicalPath("src/A.kt")), output = LogicalPath("m.klib"))
        assertFailsWith<IllegalArgumentException> { nativeCompileAction(c, TreeManifest(emptyList()), d, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { c.copy(moduleName="unsafe value") }
        assertFailsWith<IllegalArgumentException> { c.copy(optIns=listOf("x,-Xplugin=evil")) }
        assertFailsWith<IllegalArgumentException> { c.copy(commonSources=listOf(LogicalPath("missing.kt"))) }
    }
    @Test fun resultsRequireDiscoveryAndBalancedEvents() {
        val ids = nativeTestDiscovery("sample.Suite.\n  passes\n  ignored\n")
        val events = """##teamcity[testSuiteStarted name='sample.Suite']
##teamcity[testStarted name='passes']
##teamcity[testFinished name='passes']
##teamcity[testIgnored name='ignored' message='skip|nreason']
##teamcity[testSuiteFinished name='sample.Suite']"""
        val report = nativeTestReport("sample", ids, events, ProcessResult(0))
        assertEquals(VerificationStatus.PASSED, report.status)
        assertEquals(TestCounts(2,1,0,1,true), report.counts)
        assertEquals("skip\nreason", report.tests.last().message)
        assertEquals(VerificationStatus.INCOMPLETE, nativeTestReport("sample", ids, events.substringBefore("##teamcity[testFinished"), ProcessResult(0)).status)
        assertEquals(VerificationStatus.INCOMPLETE, nativeTestReport("sample", emptyList(), "", ProcessResult(0)).status)
        assertEquals(VerificationStatus.FAILED, nativeTestReport("sample", ids, events, ProcessResult(42)).status)
        assertEquals(VerificationStatus.CANCELLED, nativeTestReport("sample", ids, events, ProcessResult(130,cancelled=true)).status)
        assertEquals(VerificationStatus.INCOMPLETE, nativeTestReport("sample", ids, events+"\n##teamcity[testIgnored name='ignored']", ProcessResult(0)).status)
    }
    @Test fun testsNeverCacheAndMutatedCompilerArgumentsFailIdentity() {
        val d = Sha256.digest(byteArrayOf())
        val action = nativeTestAction("m", LogicalPath("test.kexe"), TreeManifest(listOf(FileEntry(LogicalPath("test.kexe"), d, true))), d, mapOf("native" to d, "jdk" to d, "sdk" to d, "native-dependencies" to d))
        assertEquals(CachePolicy.NEVER, action.cachePolicy)
        assertNotEquals(action.key(Sha256), action.copy(arguments=action.arguments+"--ktest_filter=missing").key(Sha256))
    }
}

private fun awaitResult(running: RunningAction): ActionResult {
    val deadline = TimeSource.Monotonic.markNow()
    while (deadline.elapsedNow().inWholeMilliseconds < 5_000) {
        running.poll()?.let { return it }
        usleep(1_000u)
    }
    error("Action did not finish")
}

private fun awaitLaunch(running: RunningAction, launched: () -> Boolean) {
    val deadline = TimeSource.Monotonic.markNow()
    while (!launched()) {
        check(deadline.elapsedNow().inWholeMilliseconds < 5_000) { "Action did not launch" }
        check(running.poll() == null) { "Action completed before launch handoff" }
        usleep(1_000u)
    }
}
