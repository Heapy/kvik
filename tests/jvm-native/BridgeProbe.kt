@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.jvm.probe

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
    val inputs = TreeManifest.fromJson(request.getValue("inputs"))
    val physical = request.getValue("files").objectValue()
    for (file in inputs.files) check(store.put(readJvmFile(physical.getValue(file.path.value).stringValue(), 64 * 1024 * 1024)) == file.digest)
    val identities = request.getValue("toolchains").objectValue().mapValues { Digest.fromJson(it.value) }
    val layout = request.getValue("layout").objectValue()
    fun tool(name: String) = layout.getValue(name).stringValue()
    val provider = JvmToolchainProvider { actual ->
        check(actual == identities)
        JvmToolchainLayout(actual, tool("javaExecutable"), tool("workerClasses"), tool("btaApiJar"), tool("stdlibJar"), tool("junitJar"), tool("compilerArtifacts"), tool("compilerManifest"))
    }
    val composition = Digest.fromJson(request.getValue("composition"))
    val specifications = request.getValue("nodes").arrayValue().map(JsonValue::objectValue)
    val nodes = specifications.map { node ->
        val id = node.getValue("id").stringValue()
        val dependencies = node.getValue("dependencies").arrayValue().map(JsonValue::stringValue).toSet()
        val paths = node.getValue("inputPaths").arrayValue().map(JsonValue::stringValue).toSet()
        ActionNode(id, dependencies) { results ->
            val files = inputs.files.filter { it.path.value in paths } + results.values.flatMap { it.outputs.files }
            val manifest = TreeManifest(files.distinct())
            if (node["compilation"] != null) jvmCompileAction(JvmCompilation.fromJson(node.getValue("compilation")), manifest, composition, identities)
            else jvmTestAction(JvmTestSelection.fromJson(node.getValue("selection")), manifest, composition, identities)
        }
    }
    NativeProcessHost(string("guardian")).use { host ->
        val pool = if (request["reuseCompiler"]?.booleanValue() == true) JvmCompilerSessionPool(host, run.path, run.path) else null
        val executor = JvmActionExecutor(run.runId, run.path, host, store, provider, pool)
        val events = mutableListOf<RunEvent>()
        try {
        NativeEventJournal("${run.path}/events.jsonl").use { journal ->
            SchedulerRun(run.runId, nodes, executor, ResourceBudget(2, 4096), EventJournal { event -> journal.append(event); events += event },
                admission = pool?.let { ActionAdmission(it::reserve) }).use { scheduler ->
                while (!scheduler.tick()) usleep(10000u)
            }
        }
        } finally { pool?.close() }
        val testNode = specifications.first { it["selection"] != null }
        val dependencies = testNode.getValue("dependencies").arrayValue().map(JsonValue::stringValue)
        val paths = testNode.getValue("inputPaths").arrayValue().map(JsonValue::stringValue).toSet()
        val testInputs = TreeManifest((inputs.files.filter { it.path.value in paths } + dependencies.flatMap { executor.observations.getValue(it).result.outputs.files }).distinct())
        val selected = JvmTestSelection.fromJson(testNode.getValue("selection")).copy(classes = listOf("fixture.SlowTests"), maxRetries = 1)
        val tracking = object : ProcessHost {
            var directory: String? = null
            override fun start(request: ProcessRequest): OwnedProcess { directory = request.workingDirectory; return host.start(request) }
            override fun close() = Unit
        }
        val cancellation = JvmActionExecutor(run.runId, run.path, tracking, store, provider)
        cancellation.start(run.runId, "cancel", jvmTestAction(selected, testInputs, composition, identities)).use { running ->
            var attempts = 0
            while ((tracking.directory == null || access("${tracking.directory}/started.txt", F_OK) != 0) && attempts++ < 2000) { check(running.poll() == null); usleep(10000u) }
            check(attempts < 2000) { "Slow test did not start" }
            running.cancel()
            while (running.poll() == null) usleep(10000u)
            check(access("${tracking.directory}/finished.txt", F_OK) != 0)
        }
        cancellation.start(run.runId, "timeout", jvmTestAction(selected, testInputs, composition, identities, 5_000)).use { running ->
            while (running.poll() == null) usleep(10000u)
        }
        val observations = executor.observations + cancellation.observations
        val report = obj("observations" to JsonValue.Object(observations.mapValues { it.value.toJson() }), "events" to events.map { it.toJson() }.json())
        writeJvmFile(string("report"), report.canonical().encodeToByteArray())
        println("Native JVM graph observations: ${observations.mapValues { it.value.result.status }}")
    }
}
