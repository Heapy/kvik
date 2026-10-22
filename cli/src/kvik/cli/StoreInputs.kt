@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kotlinx.cinterop.*
import platform.posix.*
import platform.Foundation.*

/** Caller binds capture to project.bytes; only trusted stable ancestors are supported. */
internal fun requireDisjointStore(project: ProjectFile, store: String, lock: String, tools: String) {
    val watched = project.configuration.project.modules.flatMap { module ->
        module.sourceSets.flatMap { it.directories + it.resources } + module.compilerPlugins.flatMap { it.inputs }
    }.map { projectPath(project.root, it.value) } + listOf(project.path, lock, tools)
    val storeAncestry = physicalAncestry(store)
    for (input in watched) {
        val ancestry = physicalAncestry(input)
        require(storeAncestry.none { (identity, tail) -> ancestry[identity]?.let { other ->
            tail.take(minOf(tail.size, other.size)).zip(other).all { (a, b) ->
                NSString.create(string = a).compare(b, NSCaseInsensitiveSearch).toLong() == 0L
            }
        } == true }) { "Content store overlaps watched input: $input" }
    }
}

/** Resolve existing ancestors without creating a store; catches aliases even for missing leaves. */
private fun potentialPhysicalPath(path: String): String {
    var ancestor = path
    val missing = mutableListOf<String>()
    while (NativeFiles.info(ancestor) == null) {
        missing.add(ancestor.substringAfterLast('/'))
        ancestor = ancestor.substringBeforeLast('/').ifEmpty { "/" }
    }
    val pointer = realpath(ancestor, null) ?: error("Cannot resolve input/cache ancestor")
    val physical = try { pointer.toKString() } finally { free(pointer) }
    return (physical.trimEnd('/') + missing.asReversed().joinToString("/", prefix = if (missing.isEmpty()) "" else "/")).ifEmpty { "/" }
}

/** Shared existing inode + relative suffix handles firmlinks and missing case/Unicode aliases.
 * Case-insensitive canonical string comparison is deliberately conservative on case-sensitive volumes.
 */
private fun physicalAncestry(path: String): Map<Pair<ULong, ULong>, List<String>> {
    var current = potentialPhysicalPath(path)
    val suffix = mutableListOf<String>()
    val result = mutableMapOf<Pair<ULong, ULong>, List<String>>()
    while (true) {
        memScoped {
            val status = alloc<stat>()
            if (lstat(current, status.ptr) == 0) result[status.st_dev.toULong() to status.st_ino] = suffix.toList()
            else require(errno == ENOENT) { "Cannot inspect input/cache ancestor" }
        }
        if (current == "/") break
        suffix.add(0, current.substringAfterLast('/'))
        current = current.substringBeforeLast('/').ifEmpty { "/" }
    }
    return result
}
