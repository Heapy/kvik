@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.native

import kvik.core.*
import kvik.cache.NativeContentStore
import kvik.compiler.jvm.*
import kvik.execution.NativeRunDirectory
import kotlin.test.*

class NativeWorkspacePluginTest {
    private val source = LogicalPath("Main.kt")
    private val plugin = JvmPluginApplication("fixture.rewrite", "2.4.20", listOf(LogicalPath("producer"), LogicalPath("api.jar")),
        listOf("value=first,comma", "value=second=literal"), listOf(LogicalPath("message.txt")),
        listOf(PluginPathOption("messageFile", PluginPathKind.INPUT_FILE, LogicalPath("message.txt")),
            PluginPathOption("reportDirectory", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("plugins/fixture.rewrite/report"))),
        listOf(LogicalPath("plugins/fixture.rewrite/report")))
    private val digest = Sha256.digest(byteArrayOf(1))
    private val baseTools = listOf("native", "jdk", "sdk", "native-dependencies").associateWith { digest }
    private val tools = baseTools + listOf("jvm-worker", "jvm-compiler", "junit").associateWith { digest }
    private val inputs = TreeManifest(listOf(source, LogicalPath("producer/Plugin.class"), LogicalPath("api.jar"), LogicalPath("message.txt")).map { FileEntry(it, digest) })
    private fun compilation() = NativeCompilation("fixture", listOf(source), output = LogicalPath("main.klib"), pluginApplications = listOf(plugin))

    @Test fun exactClasspathOptionsInputsAndToolsAreActionIdentity() {
        val compilation = compilation()
        val action = nativeCompileAction(compilation, inputs, digest, tools)
        assertEquals(compilation, NativeCompilation.fromJson(compilation.toJson()))
        assertEquals(listOf(compilation.output) + plugin.outputs, action.outputs)
        assertFalse(action.arguments.any { it.startsWith("-P") || it.startsWith("-Xplugin") })
        assertFails { nativeCompileAction(compilation, inputs, digest, baseTools) }
        for (changed in listOf(plugin.copy(classpath = plugin.classpath.reversed()), plugin.copy(options = plugin.options.reversed()),
            plugin.copy(options = listOf("value=different")))) {
            assertNotEquals(action.key(Sha256), nativeCompileAction(compilation.copy(pluginApplications = listOf(changed)), inputs, digest, tools).key(Sha256))
        }
        val altered = TreeManifest(inputs.files.map { if (it.path == LogicalPath("message.txt")) it.copy(digest = Sha256.digest(byteArrayOf(2))) else it })
        assertNotEquals(action.key(Sha256), nativeCompileAction(compilation, altered, digest, tools).key(Sha256))
        assertNotEquals(action.key(Sha256), nativeCompileAction(compilation, inputs, digest, tools + ("jvm-worker" to Sha256.digest(byteArrayOf(2)))).key(Sha256))
        assertFails { nativeCompileAction(compilation, TreeManifest(inputs.files.filter { it.path != LogicalPath("message.txt") }), digest, tools) }
        assertFails { nativeCompileAction(compilation, TreeManifest(inputs.files.filter { it.path != LogicalPath("producer/Plugin.class") }), digest, tools) }
    }

    @Test fun legacyActionsRemainUnchangedAndLinkCannotRunPlugins() {
        val legacy = compilation().copy(pluginApplications = emptyList())
        assertFalse("pluginApplications" in legacy.toJson().objectValue())
        assertEquals(legacy, NativeCompilation.fromJson(legacy.toJson()))
        assertEquals(baseTools, nativeCompileAction(legacy, inputs, digest, baseTools).toolchains)
        assertFails { compilation().copy(plugins = listOf(LogicalPath("serialization.jar"))) }
        assertFails { compilation().copy(sources = emptyList(), includedLibrary = LogicalPath("tests.klib"), produce = NativeOutput.TEST, output = LogicalPath("tests.kexe")) }
        assertFails { compilation().copy(output = LogicalPath("plugins/fixture.rewrite/report")) }
        assertFails { compilation().copy(pluginApplications = listOf(plugin.copy(outputs = listOf(LogicalPath("other/report")),
            pathOptions = listOf(PluginPathOption("out", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("other/report")))))) }
    }

    @Test fun workerRequestIsTypedAndResultValidationPrecedesOutputPublication() {
        for (scenario in listOf("success", "wrong-id", "wrong-exit", "missing-result", "cancelled", "timeout")) {
            val root = NativeRunDirectory("/private/tmp").path
            val content = NativeContentStore("$root/cas")
            val bytes = content.put("class Example".encodeToByteArray())
            val manifest = obj("schemaVersion" to 1.json())
            writeNativeFile("$root/compiler.json", manifest.canonical().encodeToByteArray())
            val identities = tools + ("jvm-compiler" to Sha256.digest(manifest))
            val actualInputs = TreeManifest(inputs.files.map { it.copy(digest = bytes) })
            val action = nativeCompileAction(compilation(), actualInputs, digest, identities)
            var started = 0
            var closed = false
            val host = object : ProcessHost {
                override fun start(request: ProcessRequest): OwnedProcess {
                    started++
                    assertEquals("/verified/jdk/bin/java", request.arguments.first())
                    assertTrue("kvik.jvm.NativePluginWorker" in request.arguments)
                    assertTrue("-Dkonan.home=/verified/native" in request.arguments)
                    val directory = request.workingDirectory
                    val fields = parseJson(readNativeFile("$directory/request.json").decodeToString()).objectValue()
                    val staged = JvmPluginApplication.fromJson(fields.getValue("pluginApplications").arrayValue().single())
                    assertEquals(plugin.options, staged.options)
                    assertEquals(plugin.classpath.map { LogicalPath("input/${it.value}") }, staged.classpath)
                    assertEquals(plugin.outputs.map { LogicalPath("output/${it.value}") }, staged.outputs)
                    writeNativeFile(request.stdoutFile, byteArrayOf()); writeNativeFile(request.stderrFile, byteArrayOf())
                    writeNativeFile("$directory/output/main.klib", byteArrayOf(1))
                    mkdirNew("$directory/output/plugins"); mkdirNew("$directory/output/plugins/fixture.rewrite"); mkdirNew("$directory/output/plugins/fixture.rewrite/report")
                    writeNativeFile("$directory/output/plugins/fixture.rewrite/report/value", byteArrayOf(2))
                    if (scenario != "missing-result") writeNativeFile("$directory/result.json", WorkerResult(
                        if (scenario == "wrong-id") "unrelated" else fields.getValue("requestId").stringValue(),
                        WorkerStatus.SUCCESS, 0, emptyList()).toJson().canonical().encodeToByteArray())
                    return object : OwnedProcess {
                        override fun poll() = ProcessResult(if (scenario == "wrong-exit") 1 else if (scenario == "timeout") 124 else 0, timedOut = scenario == "timeout")
                        override fun cancel() = Unit
                        override fun close() { closed = true }
                    }
                }
                override fun close() = Unit
            }
            val executor = NativeCompilerExecutor("run", root, host, content, NativeToolchainProvider {
                assertEquals(baseTools, it)
                NativeToolchainLayout("/verified/native/bin/konanc", "/verified/jdk", "/sdk", "/data", "/developer", "/verified/native")
            }, JvmToolchainProvider {
                assertEquals(identities.filterKeys { key -> key in setOf("jdk", "jvm-compiler", "jvm-worker", "junit") }, it)
                JvmToolchainLayout(it, "/verified/jdk/bin/java", "/worker", "/api.jar", "/stdlib.jar", "/junit.jar", "/artifacts", "$root/compiler.json")
            })
            assertFails { executor.start("run", "altered", action.copy(arguments = action.arguments + "-P")) }
            assertEquals(0, started)
            val running = executor.start("run", "valid", action)
            if (scenario == "cancelled") running.cancel()
            val result = running.poll()!!
            running.close(); assertTrue(closed)
            assertEquals(if (scenario == "success") ActionStatus.EXECUTED else if (scenario == "cancelled") ActionStatus.CANCELLED else ActionStatus.FAILED, result.status)
            assertEquals(if (scenario == "success") 2 else 0, result.outputs.files.size)
            if (scenario in setOf("wrong-id", "wrong-exit", "missing-result")) assertTrue(executor.observations.getValue("valid").diagnostics.any { it.code == "KVIK_NATIVE_RESULT_INVALID" })
        }
    }
}
