package kvik.cli

import kvik.cache.NativeFiles
import kvik.cache.NativeOutputTrees
import kvik.core.*

private const val CLASSPATH_METADATA = "classpath/manifest.json"
private const val CLASSPATH_INPUT = "classpath/input/"

private fun classpathMetadata(record: VerificationRecord, action: ActionResult, bytes: ByteArray): JvmClasspath {
    require(record.status == VerificationStatus.PASSED && record.freshnessAtCompletion == Freshness.CURRENT)
    require(action.successful && action.actionId in record.completedChecks)
    require(action.outputs.files.size in 1..100_000)
    val manifest = action.outputs.files.single { it.path.value == CLASSPATH_METADATA }
    require(!manifest.executable && bytes.size <= 256 * 1024 && Sha256.digest(bytes) == manifest.digest)
    val metadata = JvmClasspath.fromJson(parseJson(bytes.decodeToString(throwOnInvalidSequence = true)))
    require(bytes.contentEquals(metadata.toJson().canonical().encodeToByteArray()))
    require(action.actionId == "${metadata.module}:jvm:classpath-${metadata.compilation}")
    require(action.outputs.files.all { it.path.value == CLASSPATH_METADATA || it.path.value.startsWith(CLASSPATH_INPUT) })
    metadata.requireInputs(TreeManifest(action.outputs.files.filter { it.path.value != CLASSPATH_METADATA }.map {
        it.copy(path = LogicalPath(it.path.value.removePrefix(CLASSPATH_INPUT)))
    }))
    return metadata
}

/** Publish a per-run classpath tree only after all prerequisite outputs and current inputs are verified. */
internal fun materializeJvmClasspaths(record: VerificationRecord, content: ContentStore, runDirectory: String,
                                     beforeExpose: () -> Boolean): Boolean {
    require(record.requestedChecks.isNotEmpty() && record.requestedChecks.all { it.endsWith(":classpath-main") || it.endsWith(":classpath-test") })
    val files = record.requestedChecks.sorted().flatMap { check ->
        val action = record.actions.single { it.actionId == check }
        val manifest = action.outputs.files.single { it.path.value == CLASSPATH_METADATA }
        val metadata = classpathMetadata(record, action, content.get(manifest.digest) ?: error("Classpath manifest unavailable"))
        action.outputs.files.map { it.copy(path = LogicalPath("${metadata.module}/${metadata.compilation}/${it.path.value.removePrefix("classpath/")}")) }
    }
    val pending = NativeFiles.tempDirectory(runDirectory)
    try {
        NativeOutputTrees(content).materialize(TreeManifest(files), "$pending/classpath")
        if (!beforeExpose()) return false
        NativeFiles.exposeDirectory("$pending/classpath", "$runDirectory/classpath")
        return true
    } finally { NativeFiles.removeTree(pending) }
}

/** Validate the committed result and materialized tree before handing ordinary filesystem paths to an IDE. */
internal fun materializedJvmClasspath(record: VerificationRecord, module: String, compilation: String, runDirectory: String): List<String> {
    val action = record.actions.single { it.actionId == "$module:jvm:classpath-$compilation" }
    val directory = "$runDirectory/classpath/$module/$compilation"
    NativeFiles.ensureDirectory(directory, create = false)
    val metadata = classpathMetadata(record, action, NativeFiles.read("$directory/manifest.json", 256 * 1024))
    val expected = action.outputs.files.associateBy { it.path.value.removePrefix("classpath/") }
    val seen = mutableSetOf<String>()
    fun visit(path: String, prefix: String) {
        for (name in NativeFiles.names(path)) {
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            val file = "$path/$name"
            val info = NativeFiles.info(file) ?: error("Classpath file disappeared")
            if (NativeFiles.directory(info)) {
                require(expected.keys.any { it.startsWith("$relative/") }) { "Unexpected classpath directory: $relative" }
                visit(file, relative)
            } else {
                require(NativeFiles.regular(info)) { "Classpath contains a symlink or special file" }
                val entry = expected[relative] ?: error("Unexpected classpath file: $relative")
                require(entry.digest.sizeBytes <= Int.MAX_VALUE && (info.mode and 73 != 0) == entry.executable)
                require(Sha256.digest(NativeFiles.read(file, entry.digest.sizeBytes.toInt())) == entry.digest) { "Classpath file differs: $relative" }
                seen += relative
            }
        }
    }
    visit(directory, "")
    require(seen == expected.keys) { "Classpath files are missing" }
    return metadata.classpath.map { "$directory/input/${it.value}" }
}
