@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.native.probe

import kvik.core.*
import kvik.cache.*
import kvik.execution.*
import kvik.compiler.native.*
import platform.posix.usleep

fun main(args: Array<String>) {
    val request = parseJson(readNativeFile(args[0]).decodeToString()).objectValue()
    fun string(name: String) = request.getValue(name).stringValue()
    val store = NativeContentStore(string("store"))
    val directory = NativeRunDirectory(string("runs"))
    val input = TreeManifest.fromJson(request.getValue("inputs"))
    val paths = request.getValue("files").objectValue()
    input.files.forEach { file ->
        val bytes = readNativeFile(paths.getValue(file.path.value).stringValue(), 64 * 1024 * 1024)
        check(store.put(bytes) == file.digest) { "Input changed before execution" }
    }
    val identities = request.getValue("toolchains").objectValue().mapValues { Digest.fromJson(it.value) }
    val composition = Digest.fromJson(request.getValue("composition"))
    val action = if (request["compilation"] != null) nativeCompileAction(NativeCompilation.fromJson(request.getValue("compilation")), input, composition, identities)
                 else nativeTestAction(string("module"), LogicalPath(string("executable")), input, composition, identities, request["filter"]?.stringValue())
    NativeProcessHost(string("guardian")).use { host ->
        val executor = NativeCompilerExecutor(directory.runId, directory.path, host, store, NativeToolchainProvider { actual ->
            check(actual == identities)
            NativeToolchainLayout(string("compiler"), string("javaHome"), string("sdk"), string("dependencies"))
        })
        executor.start(directory.runId, "probe", action).use { running ->
            while (running.poll() == null) usleep(10000u)
        }
        val observed = executor.observations.getValue("probe")
        val result = obj("action" to action.toJson(), "result" to observed.result.toJson(), "diagnostics" to observed.diagnostics.map { it.toJson() }.json(), "tests" to (observed.tests?.toJson() ?: JsonValue.Null), "stdout" to observed.stdout.json(), "stderr" to observed.stderr.json(), "runDirectory" to directory.path.json())
        writeNativeFile(string("report"), result.canonical().encodeToByteArray())
        println("${observed.result.status} ${directory.path}")
    }
}
