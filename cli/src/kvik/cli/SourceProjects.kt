@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kotlinx.cinterop.*
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.platform.*
import kvik.kodesource.*
import kvik.projectsnapshot.*
import platform.posix.*

internal data class KodeProjectOptions(val executable: String, val home: String, val project: String, val revision: String)

internal fun KodeProjectOptions.arguments() = listOf("--kode", executable, "--kode-home", home,
    "--source-project", project, "--revision", revision)

/** These options are explicitly enabled per command; other commands keep rejecting them. */
internal fun sourceProjectOptions(args: List<String>): Pair<KodeProjectOptions?, List<String>> {
    val flags = setOf("--kode", "--kode-home", "--source-project", "--revision")
    val values = mutableMapOf<String, String>()
    val remaining = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        if (flag !in flags) { remaining += flag; continue }
        val value = args.getOrNull(index++)
        require(value != null && value.isNotBlank() && !value.startsWith("--")) { "Missing value for $flag" }
        require(values.put(flag, value) == null) { "Duplicate option: $flag" }
    }
    if (values.isEmpty()) return null to remaining
    require(values.keys == flags) { "Select a source with --kode PATH --kode-home PATH --source-project NAME --revision REV_OR_REF" }
    val root = currentDirectory()
    return KodeProjectOptions(projectPath(root, values.getValue("--kode")), projectPath(root, values.getValue("--kode-home")),
        values.getValue("--source-project"), values.getValue("--revision")) to remaining
}

internal fun inspectCommand(args: List<String>): JsonValue {
    val fileIndex = args.indexOf("--file")
    require(args.count { it == "--file" } <= 1) { "Duplicate --file" }
    val file = if (fileIndex < 0) null else LogicalPath(args.getOrNull(fileIndex + 1)
        ?.takeIf { !it.startsWith("--") } ?: error("--file requires a configuration-relative logical path"))
    val (source, remaining) = sourceProjectOptions(if (file == null) args else args.filterIndexed { index, _ -> index != fileIndex && index != fileIndex + 1 })
    val options = projectOptions(remaining, lockOptions = source != null)
    if (source == null) return inspectProject(options.project, options.resolverPolicy(), file)
    check(kvik_interrupt_install() == 0) { "Cannot install source inspection interruption handlers" }
    try {
        return withPreparedSourceProject(source, options, validateConfiguration = file == null) { prepared ->
            prepared.inspect(options.resolverPolicy(), file).also { sourceCheckpoint() }
        }
    } finally { kvik_interrupt_restore() }
}
