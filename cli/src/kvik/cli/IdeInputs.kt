package kvik.cli

import kvik.cache.NativeFiles
import kvik.cache.NativeOutputTrees
import kvik.core.*
import kvik.projectgraph.IdeInputs

internal fun preparedIdeModel(record: VerificationRecord, action: ActionResult, bytes: ByteArray): IdeInputs {
    require(record.status == VerificationStatus.PASSED && record.freshnessAtCompletion == Freshness.CURRENT) { "IDE preparation is not current and passed" }
    require(action.successful && action.actionId in record.completedChecks && action.actionId.endsWith(":ide-inputs")) { "IDE preparation action is not a completed check" }
    val metadata = action.outputs.files.single { it.path.value == "ide/model.json" }
    require(!metadata.executable && bytes.size <= 4 * 1024 * 1024 && Sha256.digest(bytes) == metadata.digest) { "IDE metadata digest, size or mode differs" }
    val model = IdeInputs.fromJson(parseJson(bytes.decodeToString(throwOnInvalidSequence = true)))
    require(bytes.contentEquals(model.toJson().canonical().encodeToByteArray())) { "IDE metadata is not canonical" }
    require(action.actionId == "${model.module}:${model.target}:ide-inputs") { "IDE metadata owner differs" }
    require(action.outputs.files.all { it.path == metadata.path || it.path.value.startsWith("ide/input/") }) { "Unexpected IDE preparation output" }
    model.requireInputs(TreeManifest(action.outputs.files.filter { it.path != metadata.path }.map {
        it.copy(path = LogicalPath(it.path.value.removePrefix("ide/input/")))
    }))
    return model
}

/** Only current, complete preparation runs expose an immutable generation of ordinary IDE paths. */
internal fun materializeIdeInputs(record: VerificationRecord, content: ContentStore, runDirectory: String,
                                  beforeExpose: () -> Boolean): Boolean {
    require(record.requestedChecks.isNotEmpty() && record.requestedChecks.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*:(jvm|js|wasmJs|macosArm64|linuxX64|iosArm64|iosSimulatorArm64):ide-inputs")) }) { "Expected IDE preparation checks" }
    val files = record.requestedChecks.sorted().flatMap { check ->
        val action = record.actions.single { it.actionId == check }
        val metadata = action.outputs.files.single { it.path.value == "ide/model.json" }
        val model = preparedIdeModel(record, action, content.get(metadata.digest) ?: error("Missing IDE model"))
        action.outputs.files.map { it.copy(path = LogicalPath("${preparedIdeOwnerPath(record, model.module, model.target)}/${it.path.value.removePrefix("ide/")}")) }
    }
    val pending = NativeFiles.tempDirectory(runDirectory)
    try {
        NativeOutputTrees(content).materialize(TreeManifest(files), "$pending/ide")
        if (!beforeExpose()) return false
        NativeFiles.exposeDirectory("$pending/ide", "$runDirectory/ide")
        return true
    } finally { NativeFiles.removeTree(pending) }
}

internal fun readPreparedIdeModel(record: VerificationRecord, action: ActionResult, directory: String): IdeInputs {
    val parts = action.actionId.split(':')
    require(parts.size == 3 && parts[2] == "ide-inputs")
    val root = "$directory/ide/${preparedIdeOwnerPath(record, parts[0], parts[1])}"
    NativeFiles.ensureDirectory(root, create = false)
    val model = preparedIdeModel(record, action, NativeFiles.read("$root/model.json", 4 * 1024 * 1024))
    val expected = action.outputs.files.associateBy { it.path.value.removePrefix("ide/") }
    val seen = mutableSetOf<String>()
    fun verify(path: String, prefix: String) {
        for (name in NativeFiles.names(path)) {
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            val full = "$path/$name"
            val info = NativeFiles.info(full) ?: error("IDE input disappeared")
            if (NativeFiles.directory(info)) {
                require(expected.keys.any { it.startsWith("$relative/") }) { "Unexpected IDE directory: $relative" }
                verify(full, relative)
            } else {
                val entry = expected[relative] ?: error("Unexpected IDE input: $relative")
                require(NativeFiles.regular(info) && (info.mode and 73 != 0) == entry.executable)
                require(entry.digest.sizeBytes <= Int.MAX_VALUE && Sha256.digest(NativeFiles.read(full, entry.digest.sizeBytes.toInt())) == entry.digest)
                seen += relative
            }
        }
    }
    verify(root, "")
    require(seen == expected.keys) { "Missing IDE input files" }
    return model
}

private fun preparedIdeOwnerPath(record: VerificationRecord, module: String, target: String): String =
    if (record.requestedChecks.all { it.endsWith(":jvm:ide-inputs") }) module else "$module/$target"
