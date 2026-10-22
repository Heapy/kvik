@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.native.kotestprobe

import kvik.core.*
import kvik.cache.*
import kvik.execution.*
import kvik.compiler.native.*
import kvik.compiler.ksp.*
import kvik.actioncache.LocalCachingActionExecutor
import platform.posix.usleep

/** Adapter fixture driver. Physical installation identities are supplied by the bounded outer audit. */
fun main(args: Array<String>) {
    val request = parseJson(readNativeFile(args.single()).decodeToString()).objectValue()
    fun string(name: String) = request.getValue(name).stringValue()
    val content = NativeContentStore(string("store"), 128 * 1024 * 1024)
    val run = NativeRunDirectory(string("runs"))
    val inputs = TreeManifest.fromJson(request.getValue("inputs"))
    val paths = request.getValue("files").objectValue()
    inputs.files.forEach { file -> check(content.put(readNativeFile(paths.getValue(file.path.value).stringValue(), 128 * 1024 * 1024)) == file.digest) { "Changed input: ${file.path}" } }
    val identities = request.getValue("toolchains").objectValue().mapValues { Digest.fromJson(it.value) }
    val composition = Digest.fromJson(request.getValue("composition"))
    val parameters = request.getValue("parameters")
    val timeout = request["timeoutMillis"]?.longValue() ?: 180_000L
    val action = when (string("mode")) {
        "ksp" -> kspNativeAction(KspNativeCompilation.fromJson(parameters), inputs, composition, identities, timeout)
        "compile" -> nativeCompileAction(NativeCompilation.fromJson(parameters), inputs, composition, identities, timeout)
        "test" -> parameters.objectValue().let { kotestNativeTestAction(it.getValue("module").stringValue(), LogicalPath(it.getValue("executable").stringValue()), inputs, composition, identities, timeout) }
        else -> error("Unknown fixture mode")
    }
    NativeProcessHost(string("guardian")).use { host ->
        val provider = NativeToolchainProvider { actual ->
            check(actual == identities)
            NativeToolchainLayout(string("compiler"), string("javaHome"), string("sdk"), string("dependencies"), string("developerDirectory"))
        }
        val ksp = KspNativeExecutor(run.runId, run.path, host, content, provider)
        val compiler = NativeCompilerExecutor(run.runId, run.path, host, content, provider)
        val tests = KotestNativeExecutor(run.runId, run.path, host, content, provider)
        val delegate: ActionExecutor = when (string("mode")) { "ksp" -> ksp; "compile" -> compiler; else -> tests }
        val cacheDiagnostics = mutableListOf<String>()
        val executor = LocalCachingActionExecutor(delegate, NativeActionCache(string("cache"), content), content) { cacheDiagnostics += it.code + ": " + it.message }
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        var cancelled = false
        val result = executor.start(run.runId, "fixture", action).use { running ->
            var finished: ActionResult?
            while (true) {
                val cancelAfter = request["cancelAfterMillis"]?.longValue()
                if (!cancelled && cancelAfter != null && started.elapsedNow().inWholeMilliseconds >= cancelAfter) { cancelled = true; running.cancel() }
                finished = running.poll()
                if (finished != null) break
                usleep(10000u)
            }
            checkNotNull(finished)
        }
        val native = tests.observations["fixture"] ?: compiler.observations["fixture"]
        val generated = ksp.observations["fixture"]
        val evidence = obj("action" to action.toJson(), "result" to result.toJson(), "tests" to (native?.tests?.toJson() ?: JsonValue.Null),
            "stdout" to (native?.stdout ?: generated?.stdout ?: "").json(), "stderr" to (native?.stderr ?: generated?.stderr ?: "").json(),
            "directory" to (native?.directory ?: generated?.directory ?: run.path).json(), "cacheDiagnostics" to cacheDiagnostics.map { it.json() }.json(),
            "durationMillis" to started.elapsedNow().inWholeMilliseconds.json())
        writeNativeFile(string("report"), evidence.canonical().encodeToByteArray())
        println("${result.status}: ${result.diagnostic ?: ""}")
    }
}
