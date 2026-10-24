package kvik.cli

import kvik.cache.*
import kvik.core.*

internal data class FrameworkSelection(val module: String, val target: String, val specification: NativeFrameworkSpec) {
    val actionId: String get() = "$module:$target:framework"
    val check: String get() = "$module:$target:build"
    fun event(runDirectory: String): JsonValue.Object {
        val directory = "$runDirectory/frameworks/$module/$target"
        val framework = "$directory/${specification.baseName}.framework"
        return obj("module" to module.json(), "target" to target.json(), "actionId" to actionId.json(),
            "directory" to directory.json(), "framework" to framework.json(),
            "dSYM" to (if (specification.isStatic) JsonValue.Null else "$framework.dSYM".json()),
            "isStatic" to specification.isStatic.json())
    }
}

/** Expose all selected framework bundles together after rechecking CAS bytes and source freshness. */
internal fun materializeFrameworks(record: VerificationRecord, selections: List<FrameworkSelection>, content: ContentStore,
                                   runDirectory: String, beforeExpose: () -> Boolean): Boolean {
    require(record.status == VerificationStatus.PASSED && selections.isNotEmpty())
    require(selections.map { it.actionId }.distinct().size == selections.size)
    val outputs = selections.flatMap { selection ->
        require(selection.check in record.completedChecks && NativeTarget.require(selection.target).isIos)
        val result = record.actions.single { it.actionId == selection.actionId }
        require(result.successful && result.outputs.files.isNotEmpty()) { "Missing successful framework output" }
        val name = selection.specification.baseName
        val framework = "$name.framework"
        val roots = listOf(framework) + if (selection.specification.isStatic) emptyList() else listOf("$framework.dSYM")
        require(result.outputs.files.all { file -> roots.any { file.path.value.startsWith("$it/") } }) { "Unexpected framework output path" }
        val files = result.outputs.files.associateBy { it.path.value }
        require(files["$framework/$name"]?.executable == !selection.specification.isStatic) { "Missing framework binary or invalid executable mode" }
        for (relative in listOf("Headers/$name.h", "Modules/module.modulemap", "Info.plist")) {
            require(files["$framework/$relative"]?.executable == false) { "Missing framework metadata: $relative" }
        }
        if (!selection.specification.isStatic) require(files.containsKey("$framework.dSYM/Contents/Resources/DWARF/$name")) { "Missing framework debug symbols" }
        result.outputs.files.map { it.copy(path = LogicalPath("${selection.module}/${selection.target}/${it.path.value}")) }
    }
    val pending = NativeFiles.tempDirectory(runDirectory)
    try {
        NativeOutputTrees(content).materialize(TreeManifest(outputs.sortedBy { it.path.value }), "$pending/frameworks")
        if (!beforeExpose()) return false
        NativeFiles.exposeDirectory("$pending/frameworks", "$runDirectory/frameworks")
        return true
    } finally { NativeFiles.removeTree(pending) }
}
