@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
package kvik.compiler.jvm

import kvik.core.*
import kvik.cache.*
import kvik.execution.*
import kotlin.test.*
import kotlin.concurrent.atomics.AtomicInt
import platform.posix.usleep

class BridgeUnitTest {
    private val digest = Sha256.digest(byteArrayOf())
    @Test fun schedulingResourcesRoundTripAndBindOnlyTheTestAction() {
        val selection = JvmTestSelection("sample", listOf(LogicalPath("runtime.jar")), emptyList())
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("runtime.jar"), digest)))
        val tools = jvmIdentities.associateWith { digest }
        fun action(value: JvmTestSelection) = jvmTestAction(value, inputs, digest, tools)
        val custom = selection.copy(resources = TestResources(768, linkedSetOf("server", "database")))
        assertEquals(custom, JvmTestSelection.fromJson(custom.toJson()))
        assertFalse("resources" in selection.toJson().values)
        assertNotEquals(action(selection).key(Sha256), action(custom).key(Sha256))
        assertEquals(action(custom).key(Sha256), action(custom.copy(resources = TestResources(768, linkedSetOf("database", "server")))).key(Sha256))
        assertEquals(CachePolicy.NEVER, action(custom).cachePolicy)
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess = error("Resource queries must not launch")
            override fun close() = Unit
        }
        val store = object : ContentStore {
            override fun put(bytes: ByteArray): Digest = error("No writes")
            override fun get(digest: Digest): ByteArray? = error("No reads")
        }
        val executor = JvmActionExecutor("run", "/unused", host, store, JvmToolchainProvider { error("No tools") })
        assertEquals(ResourceRequest(512), executor.resources(action(selection)))
        assertEquals(ResourceRequest(768, setOf("server", "database")), executor.resources(action(custom)))
        val compile = jvmCompileAction(JvmCompilation("sample", listOf(LogicalPath("Main.kt")), emptyList(), LogicalPath("classes")),
            TreeManifest(listOf(FileEntry(LogicalPath("Main.kt"), digest))), digest, tools)
        assertEquals(ResourceRequest(2048), executor.resources(compile))
    }

    @Test fun metadataContractIsSeparateFromJvmAndBindsDeclaredTargetsAndFlags() {
        val source = LogicalPath("Common.kt")
        val library = LogicalPath("common.klib")
        val compilation = JvmMetadataCompilation("example:library", listOf(source), listOf(library), LogicalPath("commonMain"))
        val inputs = TreeManifest(listOf(FileEntry(source, digest), FileEntry(library, digest)))
        val tools = jvmIdentities.associateWith { digest }
        val action = jvmMetadataCompileAction(compilation, inputs, digest, tools)
        assertEquals(compilation, JvmMetadataCompilation.fromJson(compilation.toJson()))
        assertEquals("common", action.target)
        assertEquals(ActionKind.COMPILE, action.kind)
        assertEquals(CachePolicy.LOCAL, action.cachePolicy)
        assertNotEquals(action.key(Sha256), jvmMetadataCompileAction(compilation.copy(explicitApi = true), inputs, digest, tools).key(Sha256))
        assertNotEquals(action.key(Sha256), jvmMetadataCompileAction(compilation.copy(warningsAsErrors = true), inputs, digest, tools).key(Sha256))
        assertFails { compilation.copy(targets = setOf("jvm")) }
        assertFails { jvmMetadataCompileAction(compilation, TreeManifest(listOf(FileEntry(source, digest))), digest, tools) }
        assertFails { jvmCompileAction(JvmCompilation("m", listOf(source), listOf(library), LogicalPath("classes")), inputs, digest, tools) }
        val request = WorkerRequest("metadata", "metadata-compile", digest, listOf("Common.kt"), listOf("common.klib"), emptyList(), compilation.options(), "commonMain")
        assertEquals(request, WorkerRequest.fromJson(request.toJson()))
        assertEquals(request, WorkerRequest.fromJson(JsonValue.Object(request.toJson().objectValue() - "metadataTargets")))
        val withPlugin = request.copy(pluginClasspath = listOf("plugin.jar"))
        assertEquals(withPlugin, WorkerRequest.fromJson(withPlugin.toJson()))
        assertFails { request.copy(metadataTargets = setOf("jvm")) }
        for (targets in listOf(setOf("js", "wasmJs"), setOf("jvm", "js", "wasmJs"))) {
            assertEquals(targets, WorkerRequest.fromJson(request.copy(metadataTargets = targets).toJson()).metadataTargets)
            val web = compilation.copy(targets = targets)
            assertEquals(web, JvmMetadataCompilation.fromJson(web.toJson()))
            assertNotEquals(action.key(Sha256), jvmMetadataCompileAction(web, inputs, digest, tools).key(Sha256))
        }
        val ordinary = WorkerRequest("jvm", "jvm-compile", digest, listOf("A.kt"), emptyList(), emptyList(), emptyList(), "classes")
        assertFalse("metadataTargets" in ordinary.toJson().objectValue())
    }

    @Test fun metadataPreparationBindsTargetsAndLibrariesWithoutCompilingSources() {
        val preparation = JvmMetadataPreparation("sample:commonTest", listOf(LogicalPath("metadata.jar")), LogicalPath("editor"), setOf("jvm", "js"))
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("metadata.jar"), digest)))
        val tools = jvmIdentities.associateWith { digest }
        fun action(value: JvmMetadataPreparation = preparation, files: TreeManifest = inputs) = jvmMetadataPrepareAction(value, files, digest, tools)
        assertEquals(preparation, JvmMetadataPreparation.fromJson(preparation.toJson()))
        assertEquals(ActionKind.GENERATE, action().kind)
        assertEquals(CachePolicy.LOCAL, action().cachePolicy)
        assertNotEquals(action().key(Sha256), action(preparation.copy(targets = setOf("jvm", "wasmJs"))).key(Sha256))
        assertNotEquals(action().key(Sha256), action(files = TreeManifest(listOf(FileEntry(LogicalPath("metadata.jar"), Sha256.digest(byteArrayOf(1)))))).key(Sha256))
        assertFails { preparation.copy(classpath = emptyList()) }
        assertFails { preparation.copy(targets = setOf("jvm")) }
        assertFails { action(files = TreeManifest(emptyList())) }
        val request = WorkerRequest("prepare", "metadata-prepare", digest, emptyList(), listOf("metadata.jar"), emptyList(), emptyList(), "editor", metadataTargets = preparation.targets)
        assertEquals(request, WorkerRequest.fromJson(request.toJson()))
        assertFails { WorkerRequest.fromJson(JsonValue.Object(request.toJson().objectValue() - "metadataTargets")) }
        assertFails { request.copy(sources = listOf("Broken.kt")) }
        assertFails { request.copy(pluginClasspath = listOf("plugin.jar")) }
        assertFails { request.copy(options = listOf("-Werror")) }
        assertFails { request.copy(classpath = listOf("metadata.jar", "metadata.jar")) }
    }

    @Test fun metadataPreparationWorkerRequiresAnExactClasspathInventory() {
        val root = NativeRunDirectory("/private/tmp")
        val store = NativeContentStore("${root.path}/store")
        val manifest = obj("fixture" to true.json())
        writeJvmFile("${root.path}/manifest.json", manifest.canonical().encodeToByteArray())
        val identities = jvmIdentities.associateWith { if (it == "jvm-compiler") Sha256.digest(manifest) else digest }
        val provider = JvmToolchainProvider { requested -> JvmToolchainLayout(requested, "/tool/java", "/tool/classes", "/tool/api.jar", "/tool/std.jar", "/tool/junit.jar", "/tool/compiler", "${root.path}/manifest.json") }
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("metadata.jar"), store.put(byteArrayOf(1)))))
        val preparation = JvmMetadataPreparation("sample", listOf(LogicalPath("metadata.jar")), LogicalPath("editor"), setOf("jvm", "js"))
        val action = jvmMetadataPrepareAction(preparation, inputs, digest, identities)
        for (variant in listOf("success", "missing", "extra", "escape", "empty")) {
            var closed = false
            val host = object : ProcessHost {
                override fun start(request: ProcessRequest): OwnedProcess {
                    val directory = request.workingDirectory
                    val worker = WorkerRequest.fromJson(parseJson(readJvmFile("$directory/request.json").decodeToString()))
                    assertEquals("metadata-prepare", worker.operation)
                    assertEquals(preparation.targets, worker.metadataTargets)
                    assertTrue(worker.sources.isEmpty() && worker.options.isEmpty() && worker.pluginApplications.isEmpty())
                    assertEquals(listOf("input/metadata.jar"), worker.classpath)
                    mkdirJvm("$directory/output/editor")
                    val names = when (variant) { "escape" -> listOf("../0.klib"); "empty" -> emptyList(); else -> listOf("0.klib") }
                    writeJvmFile("$directory/output/editor/classpath.json", obj("schemaVersion" to 1.json(), "classpath" to names.map { it.json() }.json()).canonical().encodeToByteArray())
                    if (variant != "missing") writeJvmFile("$directory/output/editor/0.klib", byteArrayOf(1))
                    if (variant == "extra") writeJvmFile("$directory/output/editor/extra", byteArrayOf(1))
                    writeJvmFile("$directory/result.json", WorkerResult(worker.requestId, WorkerStatus.SUCCESS, 0, emptyList()).toJson().canonical().encodeToByteArray())
                    return object : OwnedProcess {
                        override fun poll() = ProcessResult(0)
                        override fun cancel() = Unit
                        override fun close() { closed = true }
                    }
                }
                override fun close() = Unit
            }
            val executor = JvmActionExecutor(root.runId, root.path, host, store, provider)
            val result = executor.start(root.runId, variant, action).use(::awaitBridgeResult)
            assertTrue(closed)
            assertEquals(if (variant == "success") ActionStatus.EXECUTED else ActionStatus.FAILED, result.status)
            assertEquals(variant == "success", result.outputs.files.isNotEmpty())
        }
    }

    @Test fun metadataPluginInputsOptionsAndOutputsAreBoundToTheAction() {
        val plugin = JvmPluginApplication("example.plugin", "2.4.20", listOf(LogicalPath("plugin.jar")),
            listOf("mode=first"), listOf(LogicalPath("config.txt")),
            listOf(PluginPathOption("report", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("report"))), listOf(LogicalPath("report")))
        val compilation = JvmMetadataCompilation("fixture:common", listOf(LogicalPath("A.kt")),
            listOf(LogicalPath("library.klib")), LogicalPath("metadata"), pluginApplications = listOf(plugin))
        val inputs = TreeManifest(listOf("A.kt", "library.klib", "plugin.jar", "config.txt").map { FileEntry(LogicalPath(it), digest) })
        val tools = jvmIdentities.associateWith { digest }
        fun action(value: JvmMetadataCompilation = compilation, files: TreeManifest = inputs) =
            jvmMetadataCompileAction(value, files, digest, tools)
        assertEquals(compilation, JvmMetadataCompilation.fromJson(compilation.toJson()))
        assertEquals(listOf(LogicalPath("metadata"), LogicalPath("report")), action().outputs)
        val changed = plugin.copy(options = listOf("mode=second"))
        assertNotEquals(action().key(Sha256), action(compilation.copy(pluginApplications = listOf(changed))).key(Sha256))
        for (path in listOf("plugin.jar", "config.txt")) {
            assertNotEquals(action().key(Sha256), action(files = TreeManifest(inputs.files.map {
                if (it.path.value == path) it.copy(digest = Sha256.digest(path.encodeToByteArray())) else it
            })).key(Sha256))
            assertFails { action(files = TreeManifest(inputs.files.filter { it.path.value != path })) }
        }
        // Legacy serialization may coexist with a different plugin ID, but not another serialization registration.
        assertEquals(listOf(LogicalPath("serialization.jar")), compilation.copy(plugins = listOf(LogicalPath("serialization.jar"))).plugins)
        assertFails { compilation.copy(plugins = listOf(LogicalPath("serialization.jar")),
            pluginApplications = listOf(plugin.copy(id = "org.jetbrains.kotlinx.serialization"))) }
        val overlapping = plugin.copy(outputs = listOf(LogicalPath("metadata/report")),
            pathOptions = listOf(PluginPathOption("report", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("metadata/report"))))
        assertFails { compilation.copy(pluginApplications = listOf(overlapping)) }
        assertFails { compilation.copy(pluginApplications = listOf(plugin.copy(classpath = listOf(LogicalPath("library.klib"))))) }
    }

    @Test fun metadataWorkerUsesOwnedCompileBoundaryAndCommonDiagnostics() {
        val root = NativeRunDirectory("/private/tmp")
        val store = NativeContentStore("${root.path}/store")
        val manifest = obj("fixture" to true.json())
        writeJvmFile("${root.path}/manifest.json", manifest.canonical().encodeToByteArray())
        val identities = jvmIdentities.associateWith { if (it == "jvm-compiler") Sha256.digest(manifest) else digest }
        val provider = JvmToolchainProvider { requested -> JvmToolchainLayout(requested, "/tool/java", "/tool/classes", "/tool/api.jar", "/tool/std.jar", "/tool/junit.jar", "/tool/compiler", "${root.path}/manifest.json") }
        val bytes = store.put("expect class A".encodeToByteArray())
        val inputs = TreeManifest(listOf("A.kt", "stdlib-all.jar", "plugin.jar", "config.txt").map { FileEntry(LogicalPath(it), bytes) })
        val plugin = JvmPluginApplication("example.plugin", "2.4.20", listOf(LogicalPath("plugin.jar")), emptyList(),
            listOf(LogicalPath("config.txt")), listOf(PluginPathOption("configuration", PluginPathKind.INPUT_FILE, LogicalPath("config.txt")),
                PluginPathOption("report", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("report"))), listOf(LogicalPath("report")))
        val compilation = JvmMetadataCompilation("fixture:common", listOf(LogicalPath("A.kt")), listOf(LogicalPath("stdlib-all.jar")),
            LogicalPath("commonMain"), pluginApplications = listOf(plugin))
        val action = jvmMetadataCompileAction(compilation, inputs, digest, identities)
        for (variant in listOf("success", "compiler-error", "extra-output", "cancel")) {
            var closed = false
            var launches = 0
            val host = object : ProcessHost {
                override fun start(request: ProcessRequest): OwnedProcess {
                    launches++
                    assertEquals(action.timeoutMillis, request.timeoutMillis)
                    val directory = request.workingDirectory
                    val worker = WorkerRequest.fromJson(parseJson(readJvmFile("$directory/request.json").decodeToString()))
                    assertEquals("metadata-compile", worker.operation)
                    assertEquals(setOf("jvm", "macosArm64"), worker.metadataTargets)
                    assertEquals(listOf("input/stdlib-all.jar"), worker.classpath)
                    assertEquals(listOf("input/A.kt"), worker.sources)
                    val application = worker.pluginApplications.single()
                    assertEquals(listOf(LogicalPath("input/plugin.jar")), application.classpath)
                    assertEquals(listOf(LogicalPath("input/config.txt")), application.inputs)
                    assertEquals(listOf(LogicalPath("output/report")), application.outputs)
                    assertEquals(listOf("input/config.txt", "output/report"), application.pathOptions.map { it.path.value })
                    val failed = variant == "compiler-error"
                    val diagnostics = if (failed) listOf(Diagnostic("KVIK_COMPILATION_ERROR", DiagnosticCategory.COMPILATION, "bad source", path = "input/A.kt")) else emptyList()
                    writeJvmFile("$directory/result.json", WorkerResult(worker.requestId, if (failed) WorkerStatus.COMPILATION_ERROR else WorkerStatus.SUCCESS, if (failed) 1 else 0, diagnostics).toJson().canonical().encodeToByteArray())
                    mkdirJvm("$directory/output/commonMain")
                    writeJvmFile("$directory/output/commonMain/manifest", byteArrayOf(1))
                    mkdirJvm("$directory/output/report")
                    writeJvmFile("$directory/output/report/result.txt", byteArrayOf(2))
                    if (variant == "extra-output") writeJvmFile("$directory/output/extra", byteArrayOf(1))
                    return object : OwnedProcess {
                        override fun poll() = ProcessResult(if (failed) 1 else 0)
                        override fun cancel() = Unit
                        override fun close() { closed = true }
                    }
                }
                override fun close() = Unit
            }
            val executor = JvmActionExecutor(root.runId, root.path, host, store, provider)
            assertFails { executor.start(root.runId, "tampered-$variant", action.copy(arguments = action.arguments + "-d")) }
            val result = executor.start(root.runId, variant, action).use { running ->
                if (variant == "cancel") {
                    repeat(10_000) { if (launches == 0) { assertNull(running.poll()); usleep(1000u) } }
                    assertEquals(1, launches); running.cancel()
                }
                awaitBridgeResult(running)
            }
            assertTrue(closed)
            assertEquals("jvm", executor.toolVerifications.getValue(variant).scope)
            assertTrue(executor.toolVerifications.getValue(variant).durationNanos >= 0)
            assertEquals(if (variant == "success") ActionStatus.EXECUTED else if (variant == "cancel") ActionStatus.CANCELLED else ActionStatus.FAILED, result.status)
            assertEquals(variant == "success", result.outputs.files.isNotEmpty())
            assertTrue(executor.observations.getValue(variant).diagnostics.all { it.target == "common" })
        }
    }

    @Test fun archiveWorkerIdentityHasDistinctPinnedEncoding() {
        val empty = Sha256.digest(byteArrayOf())
        val archive = jvmWorkerArchiveIdentity(empty, empty, empty, empty, empty, empty, JACOCO_VERSION)
        assertEquals(Digest("292eba912d2d1c6db2f909f7f19ddec6f25706d92f04358d7cbb95f97f5c55a3", 694), archive)
        assertNotEquals(jvmWorkerIdentity(empty, empty, empty, empty, empty, empty, JACOCO_VERSION), archive)
        assertNotEquals(archive, jvmWorkerArchiveIdentity(Sha256.digest(byteArrayOf(1)), empty, empty, empty, empty, empty, JACOCO_VERSION))
        assertNotEquals(archive, jvmWorkerArchiveIdentity(empty, empty, empty, empty, empty, empty, JACOCO_VERSION, empty))
        assertFails { jvmWorkerArchiveIdentity(empty, empty, empty, empty, empty, empty, "0.8.13") }
    }

    @Test fun optionalCoverageToolsBindIdentityAndRequireCompleteLayout() {
        val old = Sha256.digest(obj("schemaVersion" to CONTRACT_VERSION.json(), "implementation" to "kvik-jvm-worker-bundle-v1".json(),
            "workerTree" to digest.toJson(), "api" to digest.toJson(), "stdlib" to digest.toJson(), "junit" to digest.toJson()))
        assertEquals(old, jvmWorkerIdentity(digest, digest, digest, digest))
        val covered = jvmWorkerIdentity(digest, digest, digest, digest, digest, digest, JACOCO_VERSION)
        assertNotEquals(old, covered)
        assertNotEquals(covered, jvmWorkerIdentity(digest, digest, digest, digest, Sha256.digest(byteArrayOf(1)), digest, JACOCO_VERSION))
        assertFails { jvmWorkerIdentity(digest, digest, digest, digest, jacocoAgent = digest) }
        assertFails { jvmWorkerIdentity(digest, digest, digest, digest, digest, digest, "0.8.13") }
        val layout = JvmToolchainLayout(jvmIdentities.associateWith { digest }, "/tools/java", "/tools/worker", "/tools/api.jar", "/tools/stdlib.jar", "/tools/junit.jar", "/tools/compiler", "/tools/manifest")
        assertFails { layout.copy(jacocoAgent = "/tools/agent.jar") }
        assertFails { layout.copy(jacocoAgent = "/tools/bad=agent.jar", jacocoCli = "/tools/cli.jar", jacocoAgentIdentity = digest, jacocoCliIdentity = digest) }
        assertEquals(digest, layout.copy(jacocoAgent = "/tools/agent.jar", jacocoCli = "/tools/cli.jar", jacocoAgentIdentity = digest, jacocoCliIdentity = digest).jacocoAgentIdentity)
    }

    @Test fun coverageBindsSourcesAndToolsWithoutChangingOrdinaryTestShape() {
        val source = CoverageSource("m", "main", LogicalPath("src/A.kt"), digest)
        val spec = JvmCoverageSpec(JACOCO_VERSION, digest, digest, listOf(CoverageClassRoot("m", LogicalPath("main"))), listOf(source))
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("main/example/A.class"), digest), FileEntry(source.inputPath, digest),
            FileEntry(LogicalPath("tests/example/Tests.class"), digest)))
        val selection = JvmTestSelection("m", listOf(LogicalPath("tests"), LogicalPath("main")), listOf("example.Tests"))
        val identities = jvmIdentities.associateWith { digest }
        val plain = jvmTestAction(selection, inputs, digest, identities)
        assertFalse("coverage" in selection.toJson().values)
        assertEquals(selection, JvmTestSelection.fromJson(selection.toJson()))
        val covered = jvmTestAction(selection.copy(coverage = spec), inputs, digest, identities)
        assertEquals(CachePolicy.NEVER, covered.cachePolicy)
        assertEquals(selection.copy(coverage = spec), JvmTestSelection.fromJson(covered.parameters))
        assertNotEquals(plain.key(Sha256), covered.key(Sha256))
        assertNotEquals(covered.key(Sha256), jvmTestAction(selection.copy(coverage = spec.copy(excludes = listOf("example.Generated*"))), inputs, digest, identities).key(Sha256))
        assertNotEquals(covered.key(Sha256), jvmTestAction(selection.copy(coverage = spec.copy(reporter = Sha256.digest(byteArrayOf(1)))), inputs, digest, identities).key(Sha256))
        assertFails { jvmTestAction(selection.copy(coverage = spec.copy(sources = listOf(source.copy(digest = Sha256.digest(byteArrayOf(2)))))), inputs, digest, identities) }
        assertFails { jvmTestAction(selection.copy(coverage = spec), TreeManifest(inputs.files + FileEntry(LogicalPath("tests/example/A.class"), digest)), digest, identities) }
        assertEquals(listOf("example.A"), coverageClassNames(spec, inputs))
        assertFails { coverageClassNames(spec, TreeManifest(listOf(FileEntry(LogicalPath("main/example/Bad,Name.class"), digest)))) }
        assertFails { coverageClassNames(spec, TreeManifest(listOf(FileEntry(LogicalPath("main/" + "a".repeat(65_537) + ".class"), digest)))) }
    }

    @Test fun rawCoverageIsRetainedForPassFailureAndCancellationAndMissingDataDoesNotEraseTests() {
        val root = NativeRunDirectory("/private/tmp")
        val store = NativeContentStore("${root.path}/store")
        val manifest = obj("fixture" to true.json())
        writeJvmFile("${root.path}/manifest.json", manifest.canonical().encodeToByteArray())
        val agentBytes = "agent".encodeToByteArray(); val reporterBytes = "reporter".encodeToByteArray()
        writeJvmFile("${root.path}/agent.jar", agentBytes); writeJvmFile("${root.path}/reporter.jar", reporterBytes)
        val identities = jvmIdentities.associateWith { if (it == "jvm-compiler") Sha256.digest(manifest) else digest }
        val provider = JvmToolchainProvider { requested -> JvmToolchainLayout(requested, "/tool/java", "/tool/classes", "/tool/api.jar", "/tool/std.jar", "/tool/junit.jar", "/tool/compiler", "${root.path}/manifest.json",
            "${root.path}/agent.jar", "${root.path}/reporter.jar", Sha256.digest(agentBytes), Sha256.digest(reporterBytes)) }
        val bytes = store.put("class A".encodeToByteArray())
        val source = CoverageSource("m", "main", LogicalPath("src/A.kt"), bytes)
        val spec = JvmCoverageSpec(JACOCO_VERSION, Sha256.digest(agentBytes), Sha256.digest(reporterBytes), listOf(CoverageClassRoot("m", LogicalPath("main"))), listOf(source))
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("main/example/A.class"), bytes), FileEntry(source.inputPath, bytes), FileEntry(LogicalPath("tests/example/Tests.class"), bytes)))
        val action = jvmTestAction(JvmTestSelection("m", listOf(LogicalPath("tests"), LogicalPath("main")), listOf("example.Tests"), coverage = spec), inputs, digest, identities)
        for (variant in listOf("pass", "missing", "empty", "failure", "failure-missing", "cancel")) {
            var launches = 0
            val failed = variant.startsWith("failure")
            val host = object : ProcessHost {
                override fun start(request: ProcessRequest): OwnedProcess {
                    launches++
                    assertEquals(listOf("-javaagent:${root.path}/agent.jar=destfile=output/coverage.exec,append=false,dumponexit=true,output=file,jmx=false,includes=example.A"), request.arguments.filter { it.startsWith("-javaagent:") })
                    val directory = request.workingDirectory
                    assertEquals(listOf("/tool/classes", "/tool/junit.jar", "$directory/input/tests", "$directory/input/main", "/tool/std.jar"),
                        request.arguments[request.arguments.indexOf("-cp") + 1].split(':'))
                    assertEquals(listOf("kvik.jvm.TestWorker", directory, "request.json", "result.json", "/tool/std.jar"), request.arguments.takeLast(5))
                    val requestId = parseJson(readJvmFile("$directory/request.json").decodeToString()).objectValue().getValue("requestId").stringValue()
                    val result = TestResult("concrete-id", "m", "jvm", if (failed) TestStatus.FAILED else TestStatus.PASSED)
                    val report = obj("schemaVersion" to 1.json(), "requestId" to requestId.json(), "exitCode" to (if (failed) 1 else 0).json(),
                        "status" to (if (failed) "failed" else "passed").json(), "tests" to listOf(result.toJson()).json(),
                        "testCounts" to TestCounts(1, 1, if (failed) 1 else 0, 0, true).toJson(), "diagnostics" to emptyList<JsonValue>().json())
                    writeJvmFile("$directory/result.json", report.canonical().encodeToByteArray())
                    if (!variant.endsWith("missing")) writeJvmFile("$directory/output/coverage.exec", if (variant == "empty") byteArrayOf() else byteArrayOf(1, 2, 3))
                    return object : OwnedProcess {
                        override fun poll() = ProcessResult(if (failed) 1 else 0)
                        override fun cancel() = Unit
                        override fun close() = Unit
                    }
                }
                override fun close() = Unit
            }
            val executor = JvmActionExecutor(root.runId, root.path, host, store, provider)
            val result = executor.start(root.runId, variant, action).use { running ->
                if (variant == "cancel") {
                    repeat(10_000) { if (launches == 0) { assertNull(running.poll()); usleep(1000u) } }
                    assertEquals(1, launches); running.cancel()
                }
                awaitBridgeResult(running)
            }
            val observation = executor.observations.getValue(variant)
            val expected = when { variant == "cancel" -> VerificationStatus.CANCELLED; failed -> VerificationStatus.FAILED; variant in setOf("missing", "empty") -> VerificationStatus.INCOMPLETE; else -> VerificationStatus.PASSED }
            assertEquals(expected, observation.tests!!.status, variant)
            if (variant != "cancel") assertEquals(listOf("concrete-id"), observation.tests.tests.map { it.id })
            assertEquals(if (failed) 1 else 0, observation.tests.counts.failed)
            assertEquals(if (variant == "cancel") ActionStatus.CANCELLED else if (variant == "pass") ActionStatus.EXECUTED else ActionStatus.FAILED, result.status)
            val capture = parseJson(store.get(result.outputs.files.single { it.path.value == "coverage-result.json" }.digest)!!.decodeToString()).objectValue()
            assertEquals(if (variant.endsWith("missing") || variant == "empty") "incomplete" else "captured", capture.getValue("status").stringValue())
            assertEquals(!variant.endsWith("missing"), result.outputs.files.any { it.path.value == "coverage.exec" })
            assertEquals(variant.endsWith("missing") || variant == "empty", observation.diagnostics.any { it.code == "KVIK_COVERAGE_CAPTURE_INCOMPLETE" })
        }
    }

    @Test fun remoteCompilationRequiresTheApprovedTypedPluginFreeContract() {
        val source = LogicalPath("A.kt")
        val compilation = JvmCompilation("m", listOf(source), emptyList(), LogicalPath("classes"))
        val inputs = TreeManifest(listOf(FileEntry(source, digest)))
        val identities = jvmIdentities.associateWith { digest }
        val local = jvmCompileAction(compilation, inputs, digest, identities)
        val remote = jvmCompileAction(compilation, inputs, digest, identities, cachePolicy = CachePolicy.REMOTE)
        assertTrue(portableJvmCompilation(local))
        assertTrue(portableJvmCompilation(remote))
        assertEquals(local.copy(cachePolicy = CachePolicy.REMOTE), remote)
        assertFalse(portableJvmCompilation(remote.copy(arguments = remote.arguments + "-Xunknown")))
        assertFalse(portableJvmCompilation(remote.copy(environment = remote.environment + ("UNDECLARED" to "1"))))
        assertFalse(portableJvmCompilation(remote.copy(platform = mapOf("os" to "linux", "arch" to "x64"))))
        assertFalse(portableJvmCompilation(remote.copy(cachePolicy = CachePolicy.NEVER)))
        val plugin = LogicalPath("plugin.jar")
        val pluginInputs = TreeManifest(inputs.files + FileEntry(plugin, digest))
        val withPlugin = compilation.copy(plugins = listOf(plugin))
        assertFalse(portableJvmCompilation(jvmCompileAction(withPlugin, pluginInputs, digest, identities)))
        assertFailsWith<IllegalArgumentException> {
            jvmCompileAction(withPlugin, pluginInputs, digest, identities, cachePolicy = CachePolicy.REMOTE)
        }
    }
    @Test fun releaseIsExplicitTypedAndChangesOnlyOptedInActionIdentity() {
        val compilation = JvmCompilation("m", listOf(LogicalPath("A.kt")), emptyList(), LogicalPath("classes"))
        assertFalse("jdkRelease" in compilation.toJson().objectValue())
        assertEquals(compilation, JvmCompilation.fromJson(compilation.toJson()))
        val release = compilation.copy(jdkRelease = 11)
        assertEquals(release, JvmCompilation.fromJson(release.toJson()))
        assertTrue("-Xjdk-release=11" in release.options())
        assertTrue(compilation.options().none { it.startsWith("-Xjdk-release") })
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("A.kt"), digest)))
        val identities = jvmIdentities.associateWith { digest }
        assertNotEquals(jvmCompileAction(compilation, inputs, digest, identities).key(Sha256), jvmCompileAction(release, inputs, digest, identities).key(Sha256))
        assertFails { compilation.copy(jdkRelease = 21) }
        for (invalid in listOf(JsonValue.Null, "11".json(), 8.json(), 21.json())) {
            assertFails { JvmCompilation.fromJson(JsonValue.Object(compilation.toJson().objectValue() + ("jdkRelease" to invalid))) }
        }
    }
    private fun testJson(id: String = "request", exit: Int = 0, status: String = "passed", executed: Int = 1, tests: List<JsonValue> = listOf(TestResult("concrete-id", "m", "jvm", TestStatus.PASSED).toJson())) = obj(
        "schemaVersion" to 1.json(), "requestId" to id.json(), "exitCode" to exit.json(), "status" to status.json(), "tests" to tests.json(),
        "testCounts" to TestCounts(1, executed, 0, 0, true).toJson(), "diagnostics" to emptyList<JsonValue>().json())

    @Test fun testEvidenceMustMatchScopeCountsIdentityAndProcess() {
        assertEquals(VerificationStatus.PASSED, parseTestResult(testJson(), "request", "m", 0).report.status)
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(id = "other"), "request", "m", 0) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(), "request", "m", 1) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(executed = 0), "request", "m", 0) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(), "request", "other", 0) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(tests = emptyList()), "request", "m", 0) }
        assertFailsWith<IllegalArgumentException> { parseTestResult(testJson(status = "incomplete", exit = 3), "request", "m", 3) }
    }

    @Test fun typedInputsAndBundleIdentityAreOrdered() {
        val d = digest
        assertNotEquals(jvmWorkerIdentity(d, d, d, d), jvmWorkerIdentity(d, Sha256.digest(byteArrayOf(1)), d, d))
        assertFailsWith<IllegalArgumentException> { JvmCompilation("m", listOf(LogicalPath("A.kt")), emptyList(), LogicalPath("classes"), jvmTarget = 8) }
        assertFailsWith<IllegalArgumentException> { JvmTestSelection("m", listOf(LogicalPath("runtime.jar")), emptyList(), listOf("unknown")) }
        assertFailsWith<IllegalArgumentException> { JvmTestSelection("m", emptyList(), listOf("fixture.Test")) }
        val identities = jvmIdentities.associateWith { d }
        assertFailsWith<IllegalArgumentException> { jvmCompileAction(JvmCompilation("m", listOf(LogicalPath("A.kt")), emptyList(), LogicalPath("classes")), TreeManifest(emptyList()), d, identities) }
        val test = jvmTestAction(JvmTestSelection("m", listOf(LogicalPath("runtime.jar")), emptyList()), TreeManifest(listOf(FileEntry(LogicalPath("runtime.jar"), d))), d, identities)
        assertEquals(CachePolicy.NEVER, test.cachePolicy)
    }

    @Test fun completedProcessCancellationAndMutatedActionsCannotPass() {
        val root = NativeRunDirectory("/private/tmp")
        val store = NativeContentStore("${root.path}/store")
        val manifest = obj("fixture" to true.json())
        writeJvmFile("${root.path}/manifest.json", manifest.canonical().encodeToByteArray())
        val identities = jvmIdentities.associateWith { if (it == "jvm-compiler") Sha256.digest(manifest) else digest }
        val provider = JvmToolchainProvider { requested -> JvmToolchainLayout(requested, "/tool/java", "/tool/classes", "/tool/api.jar", "/tool/std.jar", "/tool/junit.jar", "/tool/compiler", "${root.path}/manifest.json") }
        var launches = 0
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess {
                launches++
                val id = parseJson(readJvmFile("${request.workingDirectory}/request.json").decodeToString()).objectValue().getValue("requestId").stringValue()
                writeJvmFile("${request.workingDirectory}/result.json", testJson(id).canonical().encodeToByteArray())
                return object : OwnedProcess {
                    override fun poll() = ProcessResult(0)
                    override fun cancel() = Unit // Process has already completed, as in the K08 regression.
                    override fun close() = Unit
                }
            }
            override fun close() = Unit
        }
        val executor = JvmActionExecutor(root.runId, root.path, host, store, provider)
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("runtime.jar"), store.put(byteArrayOf()))))
        val action = jvmTestAction(JvmTestSelection("m", listOf(LogicalPath("runtime.jar")), listOf("fixture.Test")), inputs, digest, identities)
        val emptyClasspath = JsonValue.Object(action.parameters.values + ("classpath" to emptyList<JsonValue>().json()))
        assertFailsWith<IllegalArgumentException> { executor.start(root.runId, "empty-classpath", action.copy(parameters = emptyClasspath)) }
        assertFailsWith<IllegalArgumentException> { executor.start(root.runId, "unsafe-args", action.copy(arguments = listOf("arbitrary"))) }
        assertFailsWith<IllegalArgumentException> { executor.start(root.runId, "unsafe-env", action.copy(environment = mapOf("JAVA_TOOL_OPTIONS" to "unsafe"))) }
        assertEquals(0, launches)
        executor.start(root.runId, "cancelled", action).use { running ->
            repeat(10_000) { if (launches == 0) { assertNull(running.poll()); usleep(1000u) } }
            assertEquals(1, launches)
            running.cancel(); assertEquals(ActionStatus.CANCELLED, awaitBridgeResult(running).status) }
        assertEquals(1, launches)
        assertEquals(VerificationStatus.CANCELLED, executor.observations.getValue("cancelled").tests!!.status)
        assertEquals(0, executor.observations.getValue("cancelled").tests!!.counts.executed)
    }

    @Test fun verificationIsAsynchronousReservesIdAndCancellationJoinsWithoutLaunching() {
        val root = NativeRunDirectory("/private/tmp")
        val entered = AtomicInt(0); val exited = AtomicInt(0)
        val identities = jvmIdentities.associateWith { digest }
        val provider = object : JvmToolchainProvider {
            override fun verified(identities: Map<String, Digest>): JvmToolchainLayout = error("checkpoint overload required")
            override fun verified(identities: Map<String, Digest>, checkpoint: () -> Unit): JvmToolchainLayout {
                entered.store(1)
                try {
                    repeat(10_000) { checkpoint(); usleep(1000u) }
                    error("verification was not cancelled")
                } finally { exited.store(1) }
            }
        }
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess = error("launched before verification")
            override fun close() = Unit
        }
        val content = object : ContentStore {
            override fun get(digest: Digest): ByteArray? = error("materialized before verification")
            override fun put(bytes: ByteArray): Digest = error("unexpected write")
        }
        val executor = JvmActionExecutor(root.runId, root.path, host, content, provider)
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("runtime.jar"), digest)))
        val action = jvmTestAction(JvmTestSelection("m", listOf(LogicalPath("runtime.jar")), listOf("fixture.Test")), inputs, digest, identities)
        executor.start(root.runId, "run", action).use { running ->
            repeat(10_000) { if (entered.load() == 0) usleep(1000u) }
            assertEquals(1, entered.load()); assertNull(running.poll())
            assertFails { executor.start(root.runId, "run", action) }
            running.cancel()
            assertEquals(1, exited.load())
            assertEquals(ActionStatus.CANCELLED, awaitBridgeResult(running).status)
            assertTrue(executor.observations.isEmpty())
            assertTrue(executor.toolVerifications.isEmpty())
        }
    }

    @Test fun completedVerificationIsPublishedOnlyAfterJoinEvenWhenStagingFails() {
        val root = NativeRunDirectory("/private/tmp")
        val manifest = obj("fixture" to true.json())
        writeJvmFile("${root.path}/manifest.json", manifest.canonical().encodeToByteArray())
        val identities = jvmIdentities.associateWith { if (it == "jvm-compiler") Sha256.digest(manifest) else digest }
        for (mode in listOf("success", "failure", "cancel", "close")) {
            val entered = AtomicInt(0); val release = AtomicInt(0); val exited = AtomicInt(0)
            val provider = object : JvmToolchainProvider {
                override fun verified(identities: Map<String, Digest>): JvmToolchainLayout = error("checkpoint overload required")
                override fun verified(identities: Map<String, Digest>, checkpoint: () -> Unit): JvmToolchainLayout {
                    entered.store(1)
                    try {
                        repeat(10_000) { if (release.load() == 0) { checkpoint(); usleep(1000u) } }
                        check(release.load() == 1) { "verification was not released" }
                        if (mode == "failure") error("verification refused")
                        return JvmToolchainLayout(identities, "/tool/java", "/tool/classes", "/tool/api.jar", "/tool/std.jar",
                            "/tool/junit.jar", "/tool/compiler", "${root.path}/manifest.json")
                    } finally { exited.store(1) }
                }
            }
            val host = object : ProcessHost {
                override fun start(request: ProcessRequest): OwnedProcess = error("must not launch")
                override fun close() = Unit
            }
            val content = object : ContentStore {
                override fun get(digest: Digest): ByteArray? = error("staging refused")
                override fun put(bytes: ByteArray): Digest = error("unexpected write")
            }
            val executor = JvmActionExecutor(root.runId, root.path, host, content, provider)
            val action = jvmCompileAction(JvmCompilation("m", listOf(LogicalPath("A.kt")), emptyList(), LogicalPath("classes")),
                TreeManifest(listOf(FileEntry(LogicalPath("A.kt"), digest))), digest, identities)
            executor.start(root.runId, mode, action).use { running ->
                repeat(10_000) { if (entered.load() == 0) usleep(1000u) }
                assertEquals(1, entered.load())
                assertTrue(executor.toolVerifications.isEmpty())
                if (mode == "cancel") { running.cancel(); assertEquals(ActionStatus.CANCELLED, running.poll()?.status) }
                else if (mode == "close") running.close()
                else {
                    release.store(1)
                    assertTrue(executor.toolVerifications.isEmpty(), "Background preparation cannot publish observations")
                    val failure = assertFailsWith<IllegalStateException> { awaitBridgeResult(running) }
                    assertEquals(if (mode == "success") "staging refused" else "verification refused", failure.message)
                }
                assertEquals(1, exited.load())
                assertEquals(if (mode == "success") setOf(mode) else emptySet(), executor.toolVerifications.keys)
            }
        }
    }

    @Test fun incompleteCannotConcealFailedTestsOrContainerFailures() {
        fun record(status: String, exit: Int, tests: List<TestResult>, counts: TestCounts, diagnostics: List<Diagnostic>) = obj(
            "schemaVersion" to 1.json(), "requestId" to "request".json(), "exitCode" to exit.json(), "status" to status.json(),
            "tests" to tests.map { it.toJson() }.json(), "testCounts" to counts.toJson(), "diagnostics" to diagnostics.map { it.toJson() }.json())
        val failed = listOf(TestResult("failed-id", "m", "jvm", TestStatus.FAILED))
        val counts = TestCounts(1, 1, 1, 0, true)
        assertFailsWith<IllegalArgumentException> { parseTestResult(record("incomplete", 3, failed, counts, emptyList()), "request", "m", 3) }
        assertEquals(VerificationStatus.FAILED, parseTestResult(record("failed", 1, failed, counts, emptyList()), "request", "m", 1).report.status)
        val containerFailure = listOf(Diagnostic("KVIK_TEST_CONTAINER_FAILURE", DiagnosticCategory.TEST, "setup failed"))
        assertFailsWith<IllegalArgumentException> { parseTestResult(record("incomplete", 3, emptyList(), TestCounts(1, 0, 0, 0, false), containerFailure), "request", "m", 3) }
        val incomplete = listOf(Diagnostic("KVIK_TEST_INCOMPLETE", DiagnosticCategory.TEST, "no tests"))
        val zero = TestCounts(0, 0, 0, 0, true)
        assertEquals(VerificationStatus.INCOMPLETE, parseTestResult(record("incomplete", 3, emptyList(), zero, incomplete), "request", "m", 3).report.status)
        assertFailsWith<IllegalArgumentException> { parseTestResult(record("failed", 1, emptyList(), zero, incomplete), "request", "m", 1) }
    }

    @Test fun setupFailuresRetainStageAndErrnoWithoutParsingMissingWorkerFiles() {
        val root = NativeRunDirectory("/private/tmp")
        val store = NativeContentStore("${root.path}/store")
        val manifest = obj("fixture" to true.json())
        writeJvmFile("${root.path}/manifest.json", manifest.canonical().encodeToByteArray())
        val identities = jvmIdentities.associateWith { if (it == "jvm-compiler") Sha256.digest(manifest) else digest }
        val provider = JvmToolchainProvider { requested -> JvmToolchainLayout(requested, "/tool/java", "/tool/classes", "/tool/api.jar", "/tool/std.jar", "/tool/junit.jar", "/tool/compiler", "${root.path}/manifest.json") }
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("A.kt"), store.put("class A".encodeToByteArray())),
            FileEntry(LogicalPath("runtime.jar"), store.put(byteArrayOf()))))
        val actions = listOf(jvmCompileAction(JvmCompilation("m", listOf(LogicalPath("A.kt")), emptyList(), LogicalPath("classes")), inputs, digest, identities),
            jvmTestAction(JvmTestSelection("m", listOf(LogicalPath("runtime.jar")), listOf("fixture.Test")), inputs, digest, identities))
        val setup = ProcessSetupFailure(ProcessSetupStage.PARENT_GROUP, 1)
        val exits = listOf(ProcessResult(125, setupFailure = setup),
            ProcessResult(127, setupFailure = ProcessSetupFailure(ProcessSetupStage.EXEC, 2)),
            ProcessResult(125, setupFailure = ProcessSetupFailure(ProcessSetupStage.WORKING_DIRECTORY, 2)),
            ProcessResult(130, cancelled = true, setupFailure = setup),
            ProcessResult(124, timedOut = true, setupFailure = setup), ProcessResult(125), ProcessResult(127))
        for (action in actions) for ((index, exit) in exits.withIndex()) {
            var closes = 0
            val host = object : ProcessHost {
                override fun start(request: ProcessRequest): OwnedProcess = object : OwnedProcess {
                    override fun poll() = exit
                    override fun cancel() = Unit
                    override fun close() { closes++ }
                }
                override fun close() = Unit
            }
            val executor = JvmActionExecutor(root.runId, root.path, host, store, provider)
            val id = "${action.kind}-$index"
            executor.start(root.runId, id, action).use { awaitBridgeResult(it) }
            val observed = executor.observations.getValue(id)
            assertEquals(exit, observed.processResult); assertEquals(exit.exitCode, observed.result.exitCode)
            assertEquals(1, closes)
            val code = when {
                exit.cancelled -> "KVIK_JVM_CANCELLED"
                exit.timedOut -> "KVIK_JVM_TIMEOUT"
                exit.setupFailure != null -> "KVIK_JVM_LAUNCH_FAILED"
                else -> "KVIK_JVM_RESULT_INVALID"
            }
            assertEquals(listOf(code), observed.diagnostics.map { it.code })
            assertEquals(exit.setupFailure?.toJson() ?: JsonValue.Null,
                observed.toJson().values.getValue("processResult").objectValue().getValue("setupFailure"))
            if (code == "KVIK_JVM_LAUNCH_FAILED") {
                assertTrue(observed.diagnostics.single().message.contains("errno=${exit.setupFailure!!.errno}"))
                assertTrue(observed.diagnostics.single().message.contains(exit.setupFailure.stage.name.lowercase()))
            }
            if (action.kind == ActionKind.TEST) {
                assertEquals(if (exit.cancelled) VerificationStatus.CANCELLED else VerificationStatus.INCOMPLETE, observed.tests!!.status)
                assertEquals(0, observed.tests.counts.executed)
            } else assertTrue(observed.result.outputs.files.isEmpty())
        }
    }

    @Test fun interruptedTestsRecoverOnlyAfterProcessCleanupAndFinalReportsTakePrecedence() {
        for (mode in listOf("missing", "malformed", "timeout", "cancel", "wrong-owner", "corrupt-tail", "output", "final")) {
            val root = NativeRunDirectory("/private/tmp")
            val store = NativeContentStore("${root.path}/store")
            val manifest = obj("fixture" to true.json())
            writeJvmFile("${root.path}/manifest.json", manifest.canonical().encodeToByteArray())
            val identities = jvmIdentities.associateWith { if (it == "jvm-compiler") Sha256.digest(manifest) else digest }
            val provider = JvmToolchainProvider { requested -> JvmToolchainLayout(requested, "/tool/java", "/tool/classes", "/tool/api.jar",
                "/tool/std.jar", "/tool/junit.jar", "/tool/compiler", "${root.path}/manifest.json") }
            val host = object : ProcessHost {
                override fun start(request: ProcessRequest): OwnedProcess = object : OwnedProcess {
                    override fun poll() = ProcessResult(0, timedOut = mode == "timeout", cancelled = mode == "cancel")
                    override fun cancel() = Unit
                    override fun close() {
                        val directory = request.workingDirectory
                        val id = parseJson(readJvmFile("$directory/request.json").decodeToString()).objectValue().getValue("requestId").stringValue()
                        val header = obj("schemaVersion" to 1.json(), "kind" to "jvm-test-progress".json(),
                            "requestId" to (if (mode == "wrong-owner") "other" else id).json(), "module" to "m".json(), "target" to "jvm".json())
                        val attempts = listOf(TestAttempt(1, TestStatus.FAILED, failure = TestFailure(TestFailureKind.ASSERTION, message = "first failure")),
                            TestAttempt(2, TestStatus.PASSED))
                        val records = listOf(header) + attempts.mapIndexed { index, attempt -> obj("sequence" to index.json(), "kind" to "attempt".json(),
                            "id" to "retry".json(), "suite" to JsonValue.Null, "name" to "retry".json(), "message" to JsonValue.Null, "attempt" to attempt.toJson()) }
                        val journal = records.joinToString("\n", postfix = "\n") { it.canonical() } + if (mode == "corrupt-tail") "{}\n" else "{partial"
                        writeJvmFile("$directory/result.json.progress.jsonl", (if (mode in listOf("final", "output")) "{invalid journal" else journal).encodeToByteArray())
                        if (mode == "malformed") writeJvmFile("$directory/result.json", "{truncated".encodeToByteArray())
                        if (mode in listOf("final", "output")) {
                            writeJvmFile("$directory/result.json", testJson(id).canonical().encodeToByteArray())
                        }
                        if (mode == "output") writeJvmFile("$directory/output/unexpected", byteArrayOf(1))
                    }
                }
                override fun close() = Unit
            }
            val executor = JvmActionExecutor(root.runId, root.path, host, store, provider)
            val inputs = TreeManifest(listOf(FileEntry(LogicalPath("runtime.jar"), store.put(byteArrayOf()))))
            val action = jvmTestAction(JvmTestSelection("m", listOf(LogicalPath("runtime.jar")), listOf("fixture.Test")), inputs, digest, identities)
            executor.start(root.runId, mode, action).use { awaitBridgeResult(it) }
            val observed = executor.observations.getValue(mode)
            assertEquals(when (mode) { "final" -> ActionStatus.EXECUTED; "cancel" -> ActionStatus.CANCELLED; else -> ActionStatus.FAILED }, observed.result.status)
            val report = observed.tests!!
            when (mode) {
                "wrong-owner" -> {
                    assertTrue(report.tests.isEmpty())
                    assertEquals("KVIK_JVM_PROGRESS_INVALID", observed.diagnostics.last().code)
                }
                "final", "output" -> {
                    assertEquals("concrete-id", report.tests.single().id)
                    assertEquals(if (mode == "final") VerificationStatus.PASSED else VerificationStatus.INCOMPLETE, report.status)
                    assertEquals(if (mode == "final") emptyList() else listOf("KVIK_JVM_OUTPUT_INVALID"), observed.diagnostics.map { it.code })
                }
                else -> {
                    assertEquals(listOf(TestStatus.FAILED, TestStatus.PASSED), report.tests.single().details!!.attempts.map { it.status })
                    assertEquals(TestCounts(null, 1, 0, 0, false), report.counts)
                    assertEquals(if (mode == "cancel") VerificationStatus.CANCELLED else VerificationStatus.FAILED, report.status)
                    val code = when (mode) { "cancel" -> "KVIK_JVM_CANCELLED"; "timeout" -> "KVIK_JVM_TIMEOUT"; else -> "KVIK_JVM_RESULT_INVALID" }
                    assertEquals(code, observed.diagnostics.first().code)
                    if (mode == "corrupt-tail") assertEquals("KVIK_JVM_PROGRESS_INVALID", observed.diagnostics.last().code)
                    assertTrue(observed.result.outputs.files.any { it.path.value == "test-results.json" })
                }
            }
        }
    }

    @Test fun compilerResultRequiresMatchingRequestExitAndDeclaredOutputs() {
        val root = NativeRunDirectory("/private/tmp")
        val store = NativeContentStore("${root.path}/store")
        val manifest = obj("fixture" to true.json())
        writeJvmFile("${root.path}/manifest.json", manifest.canonical().encodeToByteArray())
        val identities = jvmIdentities.associateWith { if (it == "jvm-compiler") Sha256.digest(manifest) else digest }
        val provider = JvmToolchainProvider { requested -> JvmToolchainLayout(requested, "/tool/java", "/tool/classes", "/tool/api.jar", "/tool/std.jar", "/tool/junit.jar", "/tool/compiler", "${root.path}/manifest.json") }
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("A.kt"), store.put("class A".encodeToByteArray()))))
        val action = jvmCompileAction(JvmCompilation("m", listOf(LogicalPath("A.kt")), emptyList(), LogicalPath("classes")), inputs, digest, identities)
        for (variant in listOf("identity", "exit", "missing-output", "extra-output")) {
            val host = object : ProcessHost {
                override fun start(request: ProcessRequest): OwnedProcess {
                    val workspace = request.workingDirectory
                    val id = parseJson(readJvmFile("$workspace/request.json").decodeToString()).objectValue().getValue("requestId").stringValue()
                    writeJvmFile("$workspace/result.json", WorkerResult(if (variant == "identity") "wrong" else id, WorkerStatus.SUCCESS, 0, emptyList()).toJson().canonical().encodeToByteArray())
                    if (variant == "extra-output") writeJvmFile("$workspace/output/unexpected.class", byteArrayOf(1))
                    return object : OwnedProcess {
                        override fun poll() = ProcessResult(if (variant == "exit") 1 else 0)
                        override fun cancel() = Unit
                        override fun close() = Unit
                    }
                }
                override fun close() = Unit
            }
            val executor = JvmActionExecutor(root.runId, root.path, host, store, provider)
            executor.start(root.runId, variant, action).use { running ->
                val result = awaitBridgeResult(running)
                assertEquals(ActionStatus.FAILED, result.status, variant)
                assertTrue(result.outputs.files.isEmpty(), variant)
                assertEquals("KVIK_JVM_RESULT_INVALID", executor.observations.getValue(variant).diagnostics.last().code)
            }
        }
    }
}

private fun awaitBridgeResult(running: RunningAction): ActionResult {
    repeat(10_000) { running.poll()?.let { return it }; usleep(1000u) }
    error("JVM action did not finish")
}
