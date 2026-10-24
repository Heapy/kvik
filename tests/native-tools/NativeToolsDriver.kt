@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compilertools.nativetests

import kvik.compilertools.InstalledNativeToolchainProvider
import kvik.compiler.native.*
import kvik.core.*
import kvik.toolchains.toolComponentsFor
import kvik.execution.*
import kvik.cache.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.time.TimeSource

fun main() {
    var provider: InstalledNativeToolchainProvider? = null
    var target = NativeTarget.MACOS_ARM64
    while (true) {
        val line = readlnOrNull() ?: break
        val start = TimeSource.Monotonic.markNow()
        val response = try {
            val request = parseJson(line).objectValue()
            val value = when (request.getValue("op").stringValue()) {
                "setup" -> {
                    val roots = request.getValue("roots").objectValue()
                    target = request["target"]?.stringValue()?.let(NativeTarget::require) ?: NativeTarget.MACOS_ARM64
                    val host = request.getValue("toolLock").objectValue().getValue("host").objectValue()
                    provider = InstalledNativeToolchainProvider(request.getValue("toolLock").canonical(),
                        toolComponentsFor(host.getValue("os").stringValue(), host.getValue("architecture").stringValue()).associateWith { roots.getValue(it.lockName).stringValue() }, request.getValue("data").stringValue(), target)
                    "ready".json()
                }
                "verify" -> {
                    val layout = checkNotNull(provider).verified(request.getValue("identities").objectValue().mapValues { Digest.fromJson(it.value) })
                    obj("compiler" to layout.compiler.json(), "javaHome" to layout.javaHome.json(), "sdk" to layout.sdk.json(),
                        "data" to layout.dependencies.json(), "developer" to layout.developerDirectory.json())
                }
                "compile" -> compile(checkNotNull(provider), request, target)
                else -> error("Unknown operation")
            }
            obj("ok" to true.json(), "value" to value)
        } catch (failure: Exception) {
            obj("ok" to false.json(), "error" to (failure.message ?: failure::class.simpleName.orEmpty()).json())
        }
        val rss = memScoped { val usage = alloc<rusage>(); check(getrusage(RUSAGE_SELF, usage.ptr) == 0); usage.ru_maxrss }
        println(JsonValue.Object(response.values + mapOf("elapsedMs" to start.elapsedNow().inWholeMilliseconds.json(), "maxRssBytes" to rss.json())).canonical())
        fflush(stdout)
    }
}

private fun compile(provider: InstalledNativeToolchainProvider, request: Map<String, JsonValue>, target: NativeTarget): JsonValue {
    val workspace = request.getValue("workspace").stringValue()
    val store = NativeContentStore("$workspace/store")
    val directory = NativeRunDirectory(workspace)
    val source = store.put("fun answer(): Int = 42\n".encodeToByteArray())
    val input = TreeManifest(listOf(FileEntry(LogicalPath("Example.kt"), source)))
    val ids = request.getValue("identities").objectValue().mapValues { Digest.fromJson(it.value) }
    val action = nativeCompileAction(NativeCompilation("provider", listOf(LogicalPath("Example.kt")), output = LogicalPath("provider.klib"), target = target),
        input, Sha256.digest("provider-fixture".encodeToByteArray()), ids)
    NativeProcessHost(request.getValue("guardian").stringValue()).use { host ->
        val executor = NativeCompilerExecutor(directory.runId, directory.path, host, store, provider)
        executor.start(directory.runId, "compile", action).use { running -> while (running.poll() == null) usleep(10000u) }
        val observed = executor.observations.getValue("compile")
        require(observed.result.status == ActionStatus.EXECUTED) { "Owned Native compilation failed: ${observed.stderr}" }
        require(observed.result.outputs.files.single().path == LogicalPath("provider.klib"))
        return obj("action" to action.toJson(), "result" to observed.result.toJson(), "stderr" to observed.stderr.json(), "runDirectory" to directory.path.json())
    }
}
