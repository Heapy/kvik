@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.jvm

import kvik.core.*
import kvik.cache.*
import kvik.execution.*
import platform.posix.usleep
import kotlin.test.*

class KtsExecutorTest {
    @Test fun compileStagesDeclaredSourcesPluginsAndCapturesDescriptor() {
        val f = KtsFixture()
        val primary = LogicalPath("src/task.kts")
        val plugin = JvmPluginApplication("example.plugin", "2.4.20", listOf(LogicalPath("plugin.jar")),
            inputs = listOf(LogicalPath("data.txt")),
            pathOptions = listOf(PluginPathOption("input", PluginPathKind.INPUT_FILE, LogicalPath("data.txt")),
                PluginPathOption("output", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("generated"))), outputs = listOf(LogicalPath("generated")))
        val compilation = KtsCompilation("task", primary, listOf(primary), emptyList(), LogicalPath("classes"),
            pluginApplications = listOf(plugin), declaredDependencies = listOf("g:a:1"), declaredRepositories = listOf("https://repo.example"))
        val action = ktsCompileAction(compilation, f.inputs("src/task.kts", "plugin.jar", "data.txt"), f.identity, f.identities)
        var seen: JsonValue.Object? = null
        val host = f.host { request ->
            assertTrue("kvik.jvm.KotlinScriptCompileWorker" in request.arguments)
            val directory = request.workingDirectory
            val raw = parseJson(readJvmFile("$directory/request.json").decodeToString()).objectValue()
            seen = JsonValue.Object(raw)
            mkdirJvm("$directory/output/classes")
            writeJvmFile("$directory/output/classes/kvik-script.json", "{}".encodeToByteArray())
            writeJvmFile("$directory/output/classes/Task.class", byteArrayOf(1))
            mkdirJvm("$directory/output/generated")
            writeJvmFile("$directory/output/generated/data.txt", byteArrayOf(2))
            writeJvmFile("$directory/result.json", WorkerResult(raw.getValue("requestId").stringValue(), WorkerStatus.SUCCESS, 0, emptyList()).toJson().canonical().encodeToByteArray())
        }
        val executor = JvmActionExecutor(f.root.runId, f.root.path, host, f.store, f.provider)
        val result = executor.start(f.root.runId, "compile", action).use(::awaitKts)
        assertEquals(ActionStatus.EXECUTED, result.status)
        assertEquals(setOf("classes/Task.class", "classes/kvik-script.json", "generated/data.txt"), result.outputs.files.map { it.path.value }.toSet())
        val request = checkNotNull(seen).values
        assertEquals("input/src/task.kts", request.getValue("primary").stringValue())
        val staged = JvmPluginApplication.fromJson(request.getValue("pluginApplications").arrayValue().single())
        assertEquals("input/data.txt", staged.inputs.single().value)
        assertEquals("output/generated", staged.outputs.single().value)
        assertEquals("input/plugin.jar", staged.classpath.single().value)
        assertEquals(1, f.closes)
    }
    @Test fun evaluationUsesVerifiedDistributionUserWorkingDirectoryAndStructuredFailure() {
        val f = KtsFixture()
        val bundle = LogicalPath("scripts/task/classes")
        val launch = KtsLaunch("task", bundle, listOf(bundle), listOf("@literal", "space argument"))
        val action = ktsScriptAction(launch, f.inputs("${bundle.value}/kvik-script.json", "${bundle.value}/Task.class"), f.identity,
            f.identities, mapOf("DECLARED" to "yes"), 1234)
        val diagnostic = Diagnostic("KVIK_SCRIPT_EVALUATION_ERROR", DiagnosticCategory.INFRASTRUCTURE, "boom")
        val host = f.host(exit = 1) { request ->
            assertEquals(f.root.path, request.workingDirectory)
            val index = request.arguments.indexOf("kvik.jvm.KotlinScriptEvaluationWorker")
            assertTrue(index >= 0)
            val directory = request.arguments[index + 1]
            val raw = parseJson(readJvmFile("$directory/evaluation.json").decodeToString()).objectValue()
            assertEquals("input/${bundle.value}", raw.getValue("bundle").stringValue())
            assertEquals(launch.arguments, raw.getValue("arguments").arrayValue().map(JsonValue::stringValue))
            assertEquals("yes", request.environment["DECLARED"])
            writeJvmFile("$directory/result.json", WorkerResult(raw.getValue("requestId").stringValue(), WorkerStatus.INFRASTRUCTURE_ERROR, 1,
                listOf(diagnostic)).toJson().canonical().encodeToByteArray())
        }
        val executor = JvmScriptExecutor(f.root.runId, f.root.path, f.root.path, host, f.store, f.provider)
        val result = executor.start(f.root.runId, "run", action).use(::awaitKts)
        assertEquals(ActionStatus.FAILED, result.status)
        assertEquals(1, result.exitCode)
        assertEquals(diagnostic.code, executor.observations.getValue("run").diagnostics.single().code)
        assertEquals(1, f.closes)
    }
}

private class KtsFixture {
    val root = NativeRunDirectory("/private/tmp")
    val store = NativeContentStore("${root.path}/store")
    val identity = Sha256.digest("{}".encodeToByteArray())
    val identities = jvmIdentities.associateWith { identity }
    var closes = 0
    init { writeJvmFile("${root.path}/compiler.json", "{}".encodeToByteArray()) }
    val provider = object : JvmToolchainProvider {
        override fun verified(identities: Map<String, Digest>) = JvmToolchainLayout(identities, "/usr/bin/true", "/tool/worker", "/tool/api.jar",
            "/tool/stdlib.jar", "/tool/junit.jar", "/tool/compiler", "${root.path}/compiler.json")
        override fun verifiedExecution(identities: Map<String, Digest>, checkpoint: () -> Unit): JvmToolchainLayout = error("Scripts require pinned scripting artifact verification")
    }
    fun inputs(vararg paths: String) = TreeManifest(paths.map { FileEntry(LogicalPath(it), store.put(byteArrayOf(1))) })
    fun host(exit: Int = 0, launch: (ProcessRequest) -> Unit) = object : ProcessHost {
        override fun start(request: ProcessRequest): OwnedProcess {
            launch(request)
            return object : OwnedProcess {
                override fun poll() = ProcessResult(exit)
                override fun cancel() = Unit
                override fun close() { closes++ }
            }
        }
        override fun close() = Unit
    }
}
private fun awaitKts(running: RunningAction): ActionResult {
    repeat(10_000) { running.poll()?.let { return it }; usleep(1000u) }
    error("Kotlin script action did not finish")
}
