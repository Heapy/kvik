@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kotlinx.cinterop.*
import platform.posix.*

internal data class ProjectFile(val root: String, val name: LogicalPath, val bytes: ByteArray, val configuration: ProjectConfiguration) {
    val path: String get() = "$root/${name.value}"
}

internal fun currentDirectory(): String = memScoped {
        val buffer = allocArray<ByteVar>(4096)
        getcwd(buffer, 4096u)?.toKString() ?: error("Cannot read working directory: errno=$errno")
}

internal fun readProject(projectPath: String): ProjectFile {
    val absolute = if (projectPath.startsWith('/')) projectPath else "${currentDirectory()}/$projectPath"
    NativeFiles.validateAbsolute(absolute)
    val parent = absolute.substringBeforeLast('/').ifEmpty { "/" }
    val resolved = realpath(parent, null) ?: error("Cannot resolve project directory: errno=$errno")
    val root = try { resolved.toKString() } finally { free(resolved) }
    require(root != "/") { "Filesystem root cannot be a project" }
    NativeFiles.ensureDirectory(root, create = false)
    val name = LogicalPath(absolute.substringAfterLast('/'))
    val bytes = NativeFiles.read("$root/${name.value}", 4 * 1024 * 1024)
    return ProjectFile(root, name, bytes, parseProjectConfiguration(parseJson(bytes.decodeToString(throwOnInvalidSequence = true))))
}

internal fun projectPath(root: String, value: String): String = (if (value.startsWith('/')) value else "$root/${LogicalPath(value).value}").also {
    NativeFiles.validateAbsolute(it)
}

internal data class ProjectOptions(
    val project: String = "kvik.json",
    val store: String = ".kvik/cache",
    val lock: String = "kvik.lock.json",
    val pins: List<Coordinate> = emptyList(),
    val offline: Boolean = false,
)

internal fun projectOptions(args: List<String>, lockOptions: Boolean): ProjectOptions {
    val values = mutableMapOf<String, String>()
    val pins = mutableListOf<Coordinate>()
    var offline = false
    var index = 0
    val allowed = setOf("--project", "--pin") + if (lockOptions) setOf("--store", "--lock") else emptySet()
    while (index < args.size) {
        val flag = args[index++]
        if (flag == "--offline" && lockOptions) {
            require(!offline) { "Duplicate option: --offline" }
            offline = true
            continue
        }
        require(flag in allowed) { "Unsupported option: $flag" }
        val value = args.getOrNull(index++)
        require(value != null && value.isNotBlank() && !value.startsWith("--")) { "Missing value for $flag" }
        if (flag == "--pin") pins += Coordinate.parse(value)
        else require(values.put(flag, value) == null) { "Duplicate option: $flag" }
    }
    return ProjectOptions(values["--project"] ?: "kvik.json", values["--store"] ?: ".kvik/cache", values["--lock"] ?: "kvik.lock.json", pins,
        offline || getenv("KVIK_BOOTSTRAP_OFFLINE")?.toKString() == "1")
}
