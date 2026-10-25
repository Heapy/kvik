@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.jvm.agents

import kvik.core.*
import kvik.cache.*
import kvik.execution.*
import kvik.compiler.jvm.*
import platform.posix.*

fun main(args: Array<String>) {
    val request = parseJson(readJvmFile(args.single()).decodeToString()).objectValue()
    fun string(name: String) = request.getValue(name).stringValue()
    val store = NativeContentStore(string("store"))
    val run = NativeRunDirectory(string("runs"))
    val files = request.getValue("files").objectValue().map { (logical, physical) ->
        FileEntry(LogicalPath(logical), store.put(readJvmFile(physical.stringValue(), 64 * 1024 * 1024)))
    }
    val inputs = TreeManifest(files)
    val identities = request.getValue("toolchains").objectValue().mapValues { Digest.fromJson(it.value) }
    val layout = request.getValue("layout").objectValue()
    fun tool(name: String) = layout.getValue(name).stringValue()
    val provider = JvmToolchainProvider { actual ->
        check(actual == identities)
        JvmToolchainLayout(actual, tool("javaExecutable"), tool("workerClasses"), tool("btaApiJar"), tool("stdlibJar"),
            tool("junitJar"), tool("compilerArtifacts"), tool("compilerManifest"))
    }
    NativeProcessHost(string("guardian")).use { host ->
        var directory: String? = null
        val tracking = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess {
                directory = request.workingDirectory
                return host.start(request)
            }
            override fun close() = Unit
        }
        val executor = JvmActionExecutor(run.runId, run.path, tracking, store, provider)
        for (value in request.getValue("cases").arrayValue()) {
            val case = value.objectValue()
            val id = case.getValue("id").stringValue()
            val selection = JvmTestSelection.fromJson(case.getValue("selection"))
            val action = jvmTestAction(selection, inputs, Digest.fromJson(request.getValue("composition")), identities)
            directory = null
            executor.start(run.runId, id, action).use { running ->
                var cancelled = false
                var attempts = 0
                while (running.poll() == null) {
                    check(attempts++ < 3000) { "Test agent action did not finish" }
                    if (case.getValue("cancel").booleanValue() && !cancelled &&
                        directory?.let { access("$it/test-started", F_OK) == 0 } == true) {
                        running.cancel(); cancelled = true
                    }
                    usleep(10000u)
                }
                if (case.getValue("cancel").booleanValue()) check(cancelled)
            }
        }
        writeJvmFile(string("report"), JsonValue.Object(executor.observations.mapValues { it.value.toJson() }).canonical().encodeToByteArray())
    }
}
