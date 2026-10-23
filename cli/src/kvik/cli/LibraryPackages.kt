package kvik.cli

import kvik.cache.*
import kvik.core.*
import kvik.projectgraph.ProjectMode

internal fun libraryPackageCommand(args: List<String>): ExitStatus = executeBuild(args, ProjectMode.PACKAGE).status

/** Publish a complete local Maven layout atomically; cached outputs are reverified by CAS reads. */
internal fun materializeLibraryPackages(record: VerificationRecord, content: ContentStore, runDirectory: String,
                                       beforeExpose: () -> Boolean): Boolean {
    require(record.status == VerificationStatus.PASSED && record.requestedChecks.all { it.endsWith(":package") })
    val results = record.actions.filter { it.actionId.endsWith(":package") }
    require(results.isNotEmpty() && results.all { it.successful && it.outputs.files.isNotEmpty() }) { "Missing successful library package outputs" }
    val outputs = linkedMapOf<LogicalPath, FileEntry>()
    for (result in results) for (file in result.outputs.files) {
        require(file.path.value.startsWith("library/") && !file.executable) { "Unexpected library package output: ${file.path.value}" }
        val entry = file.copy(path = LogicalPath(file.path.value.removePrefix("library/")))
        require(outputs[entry.path] == null || outputs[entry.path] == entry) { "Conflicting library artifacts: ${entry.path.value}" }
        outputs[entry.path] = entry
    }
    val pending = NativeFiles.tempDirectory(runDirectory)
    try {
        NativeOutputTrees(content).materialize(TreeManifest(outputs.values.sortedBy { it.path.value }), "$pending/library")
        if (!beforeExpose()) return false
        NativeFiles.exposeDirectory("$pending/library", "$runDirectory/library")
        return true
    } finally { NativeFiles.removeTree(pending) }
}
