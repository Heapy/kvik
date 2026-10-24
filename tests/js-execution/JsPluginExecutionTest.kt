@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.js

import kvik.cache.NativeContentStore
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.compiler.jvm.JvmToolchainLayout
import kvik.compiler.jvm.JvmToolchainProvider
import kvik.execution.NativeRunDirectory
import kotlin.test.*
import platform.posix.usleep

class JsPluginExecutionTest {
    @Test fun workspaceWorkerStagesTypedPathsAndRequiresBoundCompleteResults() {
        for (target in KotlinJsTarget.entries) for (variant in listOf("success", "request", "exit", "error", "missing-output", "extra-output", "cancel")) {
            val root = NativeRunDirectory("/private/tmp")
            val store = NativeContentStore("${root.path}/store")
            val digest = store.put(byteArrayOf(1))
            val manifest = obj("schemaVersion" to 1.json(), "version" to "2.4.20".json(),
                "artifacts" to listOf(obj("path" to "compiler.jar".json(), "digest" to digest.toJson())).json(), "serializationPlugin" to JsonValue.Null)
            NativeFiles.writeNew("${root.path}/manifest.json", manifest.canonical().encodeToByteArray())
            val identities = listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith { if (it == "jvm-compiler") Sha256.digest(manifest) else digest }
            val layout = JvmToolchainLayout(identities, "/tool/java", "/tool/worker", "/tool/api.jar", "/tool/stdlib.jar", "/tool/junit.jar", "/tool/compiler", "${root.path}/manifest.json")
            val plugin = JvmPluginApplication("example", "2.4.20", listOf(LogicalPath("plugin/classes")), listOf("message=hello"), listOf(LogicalPath("config.txt")),
                listOf(PluginPathOption("config", PluginPathKind.INPUT_FILE, LogicalPath("config.txt")), PluginPathOption("out", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("plugins/example/report"))), listOf(LogicalPath("plugins/example/report")))
            val compile = JsCompilation("sample", target, listOf(LogicalPath("Main.kt")), listOf(LogicalPath("lib.klib")), LogicalPath("main.klib"), pluginApplications = listOf(plugin))
            val inputs = TreeManifest(listOf("Main.kt", "lib.klib", "plugin/classes/Plugin.class", "config.txt").map { FileEntry(LogicalPath(it), digest) })
            val action = jsCompileAction(compile, inputs, digest, identities)
            var launches = 0
            var closes = 0
            val host = object : ProcessHost {
                override fun start(request: ProcessRequest): OwnedProcess {
                    launches++
                    val directory = request.workingDirectory
                    assertContains(request.arguments, "kvik.jvm.JsPluginWorker")
                    assertEquals(listOf(directory, "request.json", "result.json", layout.compilerArtifacts, layout.compilerManifest), request.arguments.takeLast(5))
                    assertEquals(listOf(layout.workerClasses, layout.btaApiJar, layout.stdlibJar).joinToString(":"), request.arguments[request.arguments.indexOf("-cp") + 1])
                    val fields = parseJson(NativeFiles.read("$directory/request.json", 1024 * 1024).decodeToString()).objectValue()
                    assertEquals(target.projectName, fields.getValue("target").stringValue())
                    assertEquals(action.arguments, fields.getValue("arguments").arrayValue().map(JsonValue::stringValue))
                    assertEquals(listOf("input/lib.klib"), fields.getValue("libraries").arrayValue().map(JsonValue::stringValue))
                    val staged = JvmPluginApplication.fromJson(fields.getValue("pluginApplications").arrayValue().single())
                    assertEquals(listOf(LogicalPath("input/plugin/classes")), staged.classpath)
                    assertEquals(listOf(LogicalPath("input/config.txt")), staged.inputs)
                    assertEquals(listOf(LogicalPath("output/plugins/example/report")), staged.outputs)
                    assertEquals(listOf("input/config.txt", "output/plugins/example/report"), staged.pathOptions.map { it.path.value })
                    assertEquals(plugin.options, staged.options)
                    NativeFiles.writeNew("$directory/output/main.klib", byteArrayOf(7))
                    if (variant != "missing-output") {
                        NativeFiles.ensureDirectory("$directory/output/plugins/example/report")
                        NativeFiles.writeNew("$directory/output/plugins/example/report/message.txt", "hello".encodeToByteArray())
                    }
                    if (variant == "extra-output") NativeFiles.writeNew("$directory/output/undeclared.txt", byteArrayOf(8))
                    val id = fields.getValue("requestId").stringValue()
                    val diagnostics = if (variant == "error") listOf(Diagnostic("KVIK_JS_ERROR", DiagnosticCategory.COMPILATION, "compiler error")) else emptyList()
                    NativeFiles.writeNew("$directory/result.json", WorkerResult(if (variant == "request") "wrong-request" else id, WorkerStatus.SUCCESS, 0, diagnostics).toJson().canonical().encodeToByteArray())
                    return object : OwnedProcess {
                        override fun poll() = ProcessResult(if (variant == "exit") 1 else 0)
                        override fun cancel() = Unit
                        override fun close() { closes++ }
                    }
                }
                override fun close() = Unit
            }
            val executor = JsCompilerExecutor(root.runId, root.path, host, store, JvmToolchainProvider { layout }, NodeToolchainProvider { _, _ -> error("No Node during compilation") })
            assertFails { executor.start(root.runId, "mutated", action.copy(arguments = action.arguments + "-unsafe")) }
            executor.start(root.runId, "compile", action).use { running ->
                repeat(10_000) { if (launches == 0) { assertNull(running.poll()); usleep(1000u) } }
                assertEquals(1, launches)
                if (variant == "cancel") running.cancel()
                val result = assertNotNull(running.poll())
                assertEquals(when (variant) { "success" -> ActionStatus.EXECUTED; "cancel" -> ActionStatus.CANCELLED; else -> ActionStatus.FAILED }, result.status, "$target/$variant")
                if (variant == "success") assertEquals(setOf("main.klib", "plugins/example/report/message.txt"), result.outputs.files.map { it.path.value }.toSet())
                else assertTrue(result.outputs.files.isEmpty())
            }
            assertEquals(1, closes)
        }
    }
}
