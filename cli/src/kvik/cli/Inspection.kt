@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.locking.ResolverPolicy
import kvik.locking.effectiveConfiguration
import kvik.projectsnapshot.ResolvedSourceSnapshot
import kotlinx.cinterop.*
import platform.posix.*

private data class Owner(val module: String, val sourceSet: String, val targets: List<String>)

/** Read-only inspection of declared directories, including untracked files. */
fun inspectProject(projectPath: String, policy: ResolverPolicy = ResolverPolicy()): JsonValue {
    val project = readProject(projectPath)
    val root = project.root
    fun collect(path: String): List<String> {
        val logical = LogicalPath(path)
        val status = NativeFiles.info("$root/${logical.value}") ?: return emptyList()
        return when {
            NativeFiles.directory(status) -> NativeFiles.names("$root/${logical.value}").flatMap { collect("$path/$it") }
            NativeFiles.regular(status) -> listOf(path)
            else -> error("Unsupported symlink or special project input: $path")
        }
    }
    return inspectConfiguration(project.configuration, project.name, policy) { directory ->
        // Reject links in every declared ancestor, including a link whose leaf is absent.
        var current = root
        var missing = false
        for (part in directory.value.split('/')) {
            current += "/$part"
            val status = NativeFiles.info(current)
            if (status == null) { missing = true; break }
            require(NativeFiles.directory(status)) { "Source/resource root is not a real directory: ${directory.value}" }
        }
        if (missing) emptyList() else collect(directory.value)
    }
}

/** Inspection reads only the configuration blob; source ownership comes from the frozen inventory. */
internal fun inspectSourceProject(resolved: ResolvedSourceSnapshot, fileName: LogicalPath,
                                 policy: ResolverPolicy, checkpoint: () -> Unit): JsonValue {
    val manifest = resolved.manifest
    val entry = manifest.files.singleOrNull { it.path == fileName } ?: error("Configuration is absent from revision: ${fileName.value}")
    require(entry.blob.sizeBytes <= 4 * 1024 * 1024) { "Project configuration exceeds 4 MiB" }
    val bytes = resolved.fetch(entry.blob, 4 * 1024 * 1024, checkpoint) ?: error("Configuration blob is unavailable")
    resolved.verify(entry.blob, bytes, checkpoint)
    val configuration = parseProjectConfiguration(parseJson(bytes.decodeToString(throwOnInvalidSequence = true)))
    val root = fileName.value.substringBeforeLast('/', "")
    val prefix = if (root.isEmpty()) "" else "$root/"
    val files = manifest.files.map { it.path.value }.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    val directories = manifest.directories.map { it.value }.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    val inspected = inspectConfiguration(configuration, LogicalPath(fileName.value.substringAfterLast('/')), policy) { directory ->
        checkpoint()
        var parent = ""
        for (part in directory.value.split('/')) {
            parent = if (parent.isEmpty()) part else "$parent/$part"
            require(parent !in files) { "Source/resource root is not a directory: ${directory.value}" }
        }
        if (directory.value !in directories) emptyList() else files.filter { it.startsWith("${directory.value}/") }
    }
    return JsonValue.Object(inspected.values + mapOf("source" to resolved.provenance.toJson(),
        "sourceRoot" to root.json(), "sourceManifest" to manifest.digest.toJson()))
}

private fun inspectConfiguration(configuration: ProjectConfiguration, fileName: LogicalPath, policy: ResolverPolicy,
                                 filesBelow: (LogicalPath) -> List<String>): JsonValue.Object {
    val owners = linkedMapOf<String, MutableList<Owner>>()
    val origins = linkedMapOf<String, JsonValue>()
    fun origin(id: String, pointer: String) {
        origins[id] = obj("file" to fileName.value.json(), "pointer" to pointer.json())
    }
    configuration.project.modules.forEachIndexed { moduleIndex, module ->
        origin("module:${module.id}", "/modules/$moduleIndex")
        module.targets.forEachIndexed { index, target -> origin("target:${module.id}:${target.name}", "/modules/$moduleIndex/targets/$index") }
        module.sourceSets.forEachIndexed { index, sourceSet ->
            origin("sourceSet:${module.id}:${sourceSet.name}", "/modules/$moduleIndex/sourceSets/$index")
            val targets = module.targets.filter { target -> sourceSet in sourceSetClosure(module, listOfNotNull(target.main, target.test)) }.map { it.name }
            val owner = Owner(module.id, sourceSet.name, targets)
            for (directory in (sourceSet.directories + sourceSet.resources).distinct()) {
                filesBelow(directory).forEach { owners.getOrPut(it) { mutableListOf() }.add(owner) }
            }
        }
    }
    return obj(
        "schemaVersion" to CONTRACT_VERSION.json(), "project" to configuration.document,
        "configuration" to effectiveConfiguration(configuration, policy).digest.toJson(), "composition" to Digest(runtimeComposition, runtimeCompositionSize).toJson(),
        "files" to owners.keys.sorted().map { path ->
            val entries = owners.getValue(path)
            obj(
            "path" to path.json(), "modules" to entries.map { it.module }.distinct().sorted().map { it.json() }.json(),
            "sourceSets" to entries.map { it.sourceSet }.distinct().sorted().map { it.json() }.json(),
            "targets" to entries.flatMap { it.targets }.distinct().sorted().map { it.json() }.json(),
        ) }.json(), "origins" to JsonValue.Object(origins),
        "capabilities" to listOf("runtime-info", "project-inspection", "dependency-resolution", "locked-dependencies", "build", "test", "verification").map { it.json() }.json(),
    )
}
