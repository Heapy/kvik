package kvik.cli

import kvik.cache.*
import kvik.core.*

internal data class DocumentationSelection(val module: String, val targets: Set<String>) {
    init {
        require(module.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")))
        require(targets.isNotEmpty() && targets.all { it == "jvm" || NativeTarget.find(it) != null })
    }
    val actionId: String get() = "$module:common:documentation"
    fun event(runDirectory: String): JsonValue.Object {
        val directory = "$runDirectory/documentation/$module/html"
        return obj("module" to module.json(), "targets" to targets.sorted().map { it.json() }.json(),
            "actionId" to actionId.json(), "directory" to directory.json(), "index" to "$directory/index.html".json())
    }
}

internal fun materializeDocumentation(record: VerificationRecord, selections: List<DocumentationSelection>, content: ContentStore,
                                      runDirectory: String, beforeExpose: () -> Boolean): Boolean {
    require(record.status == VerificationStatus.PASSED && selections.isNotEmpty())
    require(selections.map { it.module }.distinct().size == selections.size)
    val files = selections.flatMap { selection ->
        require(selection.actionId in record.completedChecks)
        val result = record.actions.single { it.actionId == selection.actionId }
        require(result.successful && result.outputs.files.size in 1..100_000)
        require(result.outputs.files.any { it.path.value == "html/index.html" && it.digest.sizeBytes > 0 }) { "Documentation has no HTML index" }
        require(result.outputs.files.all { it.path.value.startsWith("html/") && !it.executable && it.digest.sizeBytes <= 16L * 1024 * 1024 } &&
            result.outputs.files.sumOf { it.digest.sizeBytes } <= 256L * 1024 * 1024) { "Invalid documentation outputs" }
        result.outputs.files.map { it.copy(path = LogicalPath("${selection.module}/${it.path.value}")) }
    }
    val pending = NativeFiles.tempDirectory(runDirectory)
    try {
        NativeOutputTrees(content).materialize(TreeManifest(files.sortedBy { it.path.value }), "$pending/documentation")
        if (!beforeExpose()) return false
        NativeFiles.exposeDirectory("$pending/documentation", "$runDirectory/documentation")
        return true
    } finally { NativeFiles.removeTree(pending) }
}
