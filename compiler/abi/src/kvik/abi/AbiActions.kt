package kvik.abi

import kvik.core.*
import kvik.compiler.jvm.jvmHostPlatform

private val abiTools = setOf("jdk", "jvm-compiler", "jvm-worker", "junit")
internal val abiOutputs = listOf(LogicalPath("abi.json"), LogicalPath("api.txt"))
internal const val ABI_JSON_LIMIT = 64 * 1024 * 1024

fun abiDumpAction(extraction: AbiExtraction, inputs: TreeManifest, composition: Digest,
    toolchains: Map<String, Digest>, timeoutMillis: Long = 120_000): ActionSpec {
    require(toolchains.keys == abiTools) { "ABI extraction requires the pinned JVM worker/compiler tool identities" }
    // The worker and adapter share exactly the same artifact/input contract.
    AbiWorkerRequest("validation", toolchains.getValue("jvm-compiler"), extraction, inputs)
    return ActionSpec("kvik-abi-dump-v1", composition, ActionKind.GENERATE, jvmHostPlatform(), extraction.target,
        toolchains.toMap(), TreeManifest(inputs.files.toList()), abiOutputs, listOf("kvik.jvm.AbiWorker"),
        mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin"), extraction.toJson(), timeoutMillis, CachePolicy.LOCAL)
}

fun abiCheckAction(comparison: AbiComparison, inputs: TreeManifest, composition: Digest,
    timeoutMillis: Long = 120_000): ActionSpec {
    require(inputs.files.map { it.path }.toSet() == setOf(comparison.snapshot, comparison.baseline) &&
        inputs.files.size == 2 && inputs.files.none { it.executable }) { "ABI comparison requires exactly its declared snapshot and baseline" }
    require(inputs.files.single { it.path == comparison.snapshot }.digest.sizeBytes <= ABI_JSON_LIMIT &&
        inputs.files.single { it.path == comparison.baseline }.digest.sizeBytes <= ABI_MAX_BYTES) { "ABI comparison input exceeds its size limit" }
    return ActionSpec("kvik-abi-check-v1", composition, ActionKind.VERIFY, jvmHostPlatform(), comparison.target,
        mapOf("abi-comparator" to composition), TreeManifest(inputs.files.toList()), abiOutputs,
        listOf("kvik.abi.compare"), emptyMap(), comparison.toJson(), timeoutMillis, CachePolicy.LOCAL)
}

/** Preserve every API line; tolerate only transport line endings and one optional final newline. */
internal fun normalizedAbiBaseline(bytes: ByteArray): String {
    require(bytes.size <= ABI_MAX_BYTES) { "ABI baseline exceeds its size limit" }
    val text = bytes.decodeToString(throwOnInvalidSequence = true).replace("\r\n", "\n")
    require('\r' !in text && '\u0000' !in text) { "ABI baseline contains an unsupported control character" }
    return if (text.isEmpty() || text.endsWith('\n')) text else "$text\n"
}
