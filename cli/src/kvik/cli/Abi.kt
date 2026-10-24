package kvik.cli

import kvik.cache.*
import kvik.core.*

internal data class AbiSelection(val module: String, val target: String) {
    init {
        require(module.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")))
        require(target == "jvm" || NativeTarget.find(target) != null)
    }
    val actionId: String get() = "$module:$target:abi-dump"
    fun event(runDirectory: String): JsonValue.Object {
        val directory = "$runDirectory/abi/$module/$target"
        return obj("module" to module.json(), "target" to target.json(), "actionId" to actionId.json(),
            "directory" to directory.json(), "snapshot" to "$directory/abi.json".json(), "api" to "$directory/api.txt".json())
    }
}

/** Failed comparisons still expose current candidates for review, without modifying baselines. */
internal fun materializeAbiCandidates(record: VerificationRecord, selections: List<AbiSelection>, content: ContentStore,
                                     runDirectory: String, beforeExpose: () -> Boolean): Boolean {
    require(selections.isNotEmpty() && selections.map { it.actionId }.distinct().size == selections.size)
    require(record.status in setOf(VerificationStatus.PASSED, VerificationStatus.FAILED))
    val files = selections.flatMap { selection ->
        val result = record.actions.single { it.actionId == selection.actionId }
        require(result.successful && result.outputs.files.map { it.path.value }.toSet() == setOf("abi.json", "api.txt")) {
            "Missing successful ABI candidate for ${selection.module}:${selection.target}"
        }
        val entries = result.outputs.files.associateBy { it.path.value }
        fun bytes(name: String, limit: Long): ByteArray {
            val entry = entries.getValue(name)
            require(!entry.executable && entry.digest.sizeBytes <= limit) { "Invalid ABI candidate file" }
            val bytes = requireNotNull(content.get(entry.digest)) { "Missing ABI candidate bytes" }
            require(Sha256.digest(bytes) == entry.digest) { "Corrupt ABI candidate bytes" }
            return bytes
        }
        val snapshot = AbiSnapshot.fromJson(parseJson(bytes("abi.json", ABI_MAX_BYTES * 8L).decodeToString(throwOnInvalidSequence = true)))
        require(snapshot.module == selection.module && snapshot.target == selection.target) { "ABI candidate scope mismatch" }
        require(bytes("api.txt", ABI_MAX_BYTES.toLong()).contentEquals(snapshot.api.encodeToByteArray())) { "ABI candidate text differs from snapshot" }
        result.outputs.files.map { it.copy(path = LogicalPath("${selection.module}/${selection.target}/${it.path.value}")) }
    }
    val pending = NativeFiles.tempDirectory(runDirectory)
    try {
        NativeOutputTrees(content).materialize(TreeManifest(files.sortedBy { it.path.value }), "$pending/abi")
        if (!beforeExpose()) return false
        NativeFiles.exposeDirectory("$pending/abi", "$runDirectory/abi")
        return true
    } finally { NativeFiles.removeTree(pending) }
}
