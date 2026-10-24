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

/** Read-only inspection of declared directories and manually tagged files, including untracked files. */
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
    fun existingFile(path: LogicalPath): Boolean {
        var current = root
        val parts = path.value.split('/')
        for ((index, part) in parts.withIndex()) {
            current += "/$part"
            val status = NativeFiles.info(current) ?: return false
            require(NativeFiles.directory(status) || NativeFiles.regular(status)) {
                "Unsupported symlink or special tagged file: ${path.value}"
            }
            if (index == parts.lastIndex) return NativeFiles.regular(status)
            if (!NativeFiles.directory(status)) return false
        }
        return false
    }
    return inspectConfiguration(project.configuration, project.name, policy, ::existingFile) { directory ->
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
    }.toJson()
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
    val inspected = inspectConfiguration(configuration, LogicalPath(fileName.value.substringAfterLast('/')), policy,
        existingFile = { it.value in files }, checkpoint = checkpoint) { directory ->
        checkpoint()
        var parent = ""
        for (part in directory.value.split('/')) {
            parent = if (parent.isEmpty()) part else "$parent/$part"
            require(parent !in files) { "Source/resource root is not a directory: ${directory.value}" }
        }
        if (directory.value !in directories) emptyList() else files.filter { it.startsWith("${directory.value}/") }
    }
    return inspected.copy(source = SourceReference(resolved.provenance, root, manifest.digest)).toJson()
}

private fun inspectConfiguration(configuration: ProjectConfiguration, fileName: LogicalPath, policy: ResolverPolicy,
                                 existingFile: (LogicalPath) -> Boolean, checkpoint: () -> Unit = {},
                                 filesBelow: (LogicalPath) -> List<String>): ProjectInspection {
    val owners = linkedMapOf<String, MutableList<Owner>>()
    val origins = linkedMapOf<String, ConfigurationOrigin>()
    fun origin(id: String, pointer: String) {
        origins[id] = ConfigurationOrigin(fileName, pointer)
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
    for (path in configuration.project.fileTags.keys) {
        checkpoint()
        if (existingFile(path)) owners.getOrPut(path.value) { mutableListOf() }
    }
    val tags = tagProjectFiles(configuration, fileName, null, owners.keys.map(::LogicalPath), checkpoint).associateBy { it.path.value }
    return ProjectInspection(
        project = configuration,
        configuration = effectiveConfiguration(configuration, policy).digest, composition = Digest(runtimeComposition, runtimeCompositionSize),
        files = owners.keys.sorted().map { path ->
            val entries = owners.getValue(path)
            FileInspection(LogicalPath(path), entries.map { it.module }.distinct().sorted(),
                entries.map { it.sourceSet }.distinct().sorted(), entries.flatMap { it.targets }.distinct().sorted(),
                tags.getValue(path).tags.sorted())
        }, origins = origins,
        capabilities = listOf("runtime-info", "project-inspection", "file-tags", "manual-file-tags", "source-views", "dependency-resolution", "locked-dependencies", "build", "test", "verification"),
    )
}
