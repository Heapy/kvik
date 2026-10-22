@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compilertools.tests

import kvik.compilertools.*
import kvik.compiler.jvm.JvmToolchainLayout
import kvik.core.*
import kvik.toolchains.ToolComponent
import kvik.execution.NativeProcessHost
import kvik.cache.NativeFiles
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.time.TimeSource

fun main() {
    var provider: InstalledJvmToolchainProvider? = null
    while (true) {
        val line = readlnOrNull() ?: break
        val start = TimeSource.Monotonic.markNow()
        val response = try {
            val request = parseJson(line).objectValue()
            val result = when (request.getValue("op").stringValue()) {
                "setup" -> {
                    val roots = request.getValue("roots").objectValue()
                    provider = InstalledJvmToolchainProvider(request.getValue("toolLock").canonical(),
                        ToolComponent.entries.associateWith { roots.getValue(it.lockName).stringValue() },
                        request.getValue("bundle").stringValue(), Digest.fromJson(request.getValue("expectedBundle")),
                        request.getValue("artifacts").stringValue())
                    "ready".json()
                }
                "bundle" -> {
                    val bundle = verifyJvmWorkerBundle(request.getValue("bundle").stringValue(), Digest.fromJson(request.getValue("expectedBundle")))
                    obj("worker" to bundle.identity.toJson(), "compiler" to bundle.compiler.toJson(), "junit" to bundle.junitIdentity.toJson())
                }
                "verify", "compile" -> {
                    val layout = checkNotNull(provider).verified(request.getValue("identities").objectValue().mapValues { Digest.fromJson(it.value) })
                    if (request.getValue("op").stringValue() == "compile") compile(layout, request)
                    obj("identities" to JsonValue.Object(layout.identities.mapValues { it.value.toJson() }), "java" to layout.javaExecutable.json(),
                        "classes" to layout.workerClasses.json(), "artifacts" to layout.compilerArtifacts.json(),
                        "jacocoAgent" to checkNotNull(layout.jacocoAgent).json(), "jacocoCli" to checkNotNull(layout.jacocoCli).json(),
                        "jacocoAgentIdentity" to checkNotNull(layout.jacocoAgentIdentity).toJson(), "jacocoCliIdentity" to checkNotNull(layout.jacocoCliIdentity).toJson())
                }
                else -> error("Unknown operation")
            }
            obj("ok" to true.json(), "value" to result)
        } catch (failure: Exception) {
            obj("ok" to false.json(), "error" to (failure.message ?: failure::class.simpleName.orEmpty()).json())
        }
        val rss = memScoped { val usage = alloc<rusage>(); check(getrusage(RUSAGE_SELF, usage.ptr) == 0); usage.ru_maxrss }
        println(JsonValue.Object(response.values + mapOf("elapsedMs" to start.elapsedNow().inWholeMilliseconds.json(), "maxRssBytes" to rss.json())).canonical())
        fflush(stdout)
    }
}

private fun compile(layout: JvmToolchainLayout, request: Map<String, JsonValue>) {
    val workspace = request.getValue("workspace").stringValue()
    val protocol = WorkerRequest("provider-probe", "jvm-compile", layout.identities.getValue("jvm-compiler"),
        listOf("Hello.kt"), listOf("stdlib.jar"), emptyList(), listOf("-jvm-target", "21"), "classes")
    NativeFiles.writeNew("$workspace/request.json", protocol.toJson().canonical().encodeToByteArray())
    val process = NativeProcessHost(request.getValue("guardian").stringValue()).start(ProcessRequest(
        listOf(layout.javaExecutable, "-Djava.io.tmpdir=$workspace/tmp", "-Duser.home=$workspace/home", "-cp",
            listOf(layout.workerClasses, layout.btaApiJar, layout.stdlibJar).joinToString(":"), "kvik.jvm.CompilerWorkerKt",
            workspace, "request.json", "result.json", layout.compilerArtifacts, layout.compilerManifest),
        workspace, mapOf("HOME" to "$workspace/home", "TMPDIR" to "$workspace/tmp", "LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin"),
        "$workspace/stdout", "$workspace/stderr", 30000))
    process.use {
        var exit = process.poll()
        while (exit == null) { usleep(1000u); exit = process.poll() }
        require(exit.exitCode == 0 && !exit.timedOut && !exit.cancelled) { "Owned BTA process failed: $exit" }
    }
    val result = WorkerResult.fromJson(parseJson(NativeFiles.read("$workspace/result.json", 1024 * 1024).decodeToString()))
    require(result.requestId == "provider-probe" && result.status == WorkerStatus.SUCCESS)
    require(NativeFiles.info("$workspace/classes/HelloKt.class")?.let(NativeFiles::regular) == true)
}
