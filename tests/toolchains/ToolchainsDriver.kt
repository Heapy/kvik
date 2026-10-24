@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.toolchains.tests

import kvik.core.*
import kvik.toolchains.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.time.TimeSource

fun main() {
    val verifier = InstalledToolchainVerifier()
    while (true) {
        val line = readlnOrNull() ?: break
        val start = TimeSource.Monotonic.markNow()
        val result = try {
            val request = parseJson(line).objectValue()
            val response = when (request.getValue("op").stringValue()) {
                "inventory" -> {
                    val limits = request["limits"]?.objectValue()
                    summary(inventory(request.getValue("root").stringValue(), InventoryLimits(
                        maxEntries = limits?.get("entries")?.longValue()?.toInt() ?: 2_000_000,
                        maxDirectoryEntries = limits?.get("directory")?.longValue()?.toInt() ?: 200_000,
                        maxDepth = limits?.get("depth")?.longValue()?.toInt() ?: 128)))
                }
                "verify" -> {
                    val roots = request.getValue("roots").objectValue()
                    val supplied = ToolComponent.entries.filter { it.lockName in roots }.associateWith { roots.getValue(it.lockName).stringValue() }
                    val lock = request.getValue("lock")
                    val verified = verifier.verify(if (lock is JsonValue.StringValue) lock.value else lock.canonical(), supplied)
                    obj("components" to JsonValue.Object(supplied.keys.associate { component ->
                        component.lockName to obj("root" to verified[component].root.json(), "inventory" to summary(verified[component].inventory))
                    }), "sdk" to verified.host.sdk.json())
                }
                "verify-jdk" -> {
                    val lock = request.getValue("lock")
                    val verified = verifier.verifyJdk(if (lock is JsonValue.StringValue) lock.value else lock.canonical(), request.getValue("root").stringValue())
                    obj("root" to verified.root.json(), "inventory" to summary(verified.inventory))
                }
                else -> error("Unknown driver operation")
            }
            obj("ok" to true.json(), "value" to response)
        } catch (failure: Exception) {
            obj("ok" to false.json(), "error" to (failure.message ?: failure::class.simpleName.orEmpty()).json())
        }
        val rss = memScoped { val usage = alloc<rusage>(); check(getrusage(RUSAGE_SELF, usage.ptr) == 0); usage.ru_maxrss }
        println(JsonValue.Object(result.values + mapOf("elapsedMs" to start.elapsedNow().inWholeMilliseconds.json(), "maxRssBytes" to rss.json())).canonical())
        fflush(stdout)
    }
}

private fun summary(value: InventorySummary): JsonValue = obj("digest" to value.digest.toJson(),
    "regularFiles" to value.regularFiles.json(), "symlinks" to value.symlinks.json(), "fileBytes" to value.fileBytes.json())
