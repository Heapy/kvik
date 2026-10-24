@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.projectsnapshot.*
import kotlinx.cinterop.*
import platform.posix.*
import kvik.files.platform.kvik_files_potential_component_alias

/** Caller binds capture to project.bytes; only trusted stable ancestors are supported. */
internal fun requireDisjointStore(project: ProjectFile, store: String, lock: String, tools: String, selection: SnapshotSelection = SnapshotSelection.Project) =
    requireDisjointStore(project.root, project.path, projectInputPlan(project.configuration, selection), store, lock, tools)

internal fun requireDisjointStore(root: String, configurationPath: String, plan: ProjectInputPlan, store: String, lock: String, tools: String) {
    requireDisjointInputPath(root, configurationPath, plan, store, listOf(lock, tools))
}

internal fun requireDisjointInputPath(root: String, configurationPath: String, plan: ProjectInputPlan, output: String, additional: List<String> = emptyList()) {
    val watched = plan.paths.map { projectPath(root, it.value) } + configurationPath + additional
    val storeAncestry = physicalAncestry(output)
    for (input in watched) {
        val ancestry = physicalAncestry(input)
        require(storeAncestry.none { (identity, tail) -> ancestry[identity]?.let { other ->
            tail.take(minOf(tail.size, other.size)).zip(other).all { (a, b) ->
                potentialComponentAlias(a, b)
            }
        } == true }) { "Output path overlaps watched input (including potential case/Unicode aliases): $input; choose a disjoint output directory with a distinct ASCII ancestor" }
    }
}

/** Includes existing filesystem aliases and conservative case/Unicode comparisons for missing leaves. */
internal fun pathsPhysicallyOverlap(first: String, second: String): Boolean {
    val ancestry = physicalAncestry(first)
    return physicalAncestry(second).any { (identity, tail) -> ancestry[identity]?.let { other ->
        tail.take(minOf(tail.size, other.size)).zip(other).all { (a, b) ->
            potentialComponentAlias(a, b)
        }
    } == true }
}

private fun potentialComponentAlias(first: String, second: String): Boolean {
    val result = kvik_files_potential_component_alias(first, second)
    check(result >= 0) { "Cannot compare input/output path components" }
    return result != 0
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
