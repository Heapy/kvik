@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.locking.ResolverPolicy
import kvik.locking.effectiveConfiguration
import kotlinx.cinterop.*
import platform.posix.*

private data class Owner(val module: String, val sourceSet: String, val targets: List<String>)

/** Read-only inspection of declared directories, including untracked files. */
fun inspectProject(projectPath: String, policy: ResolverPolicy = ResolverPolicy()): JsonValue {
    val project = readProject(projectPath)
    val root = project.root
    val fileName = project.name
    val configuration = project.configuration
    val owners = linkedMapOf<String, MutableList<Owner>>()
    fun collect(path: String, owner: Owner) {
        val logical = LogicalPath(path)
        val status = NativeFiles.info("$root/${logical.value}") ?: return
        when {
            NativeFiles.directory(status) -> NativeFiles.names("$root/${logical.value}").forEach { collect("$path/$it", owner) }
            NativeFiles.regular(status) -> owners.getOrPut(path) { mutableListOf() }.add(owner)
            else -> error("Unsupported symlink or special project input: $path")
        }
    }
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
                // Reject links in every declared ancestor, including a link whose leaf is absent.
                var current = root
                var missing = false
                for (part in directory.value.split('/')) {
                    current += "/$part"
                    val status = NativeFiles.info(current)
                    if (status == null) { missing = true; break }
                    require(NativeFiles.directory(status)) { "Source/resource root is not a real directory: ${directory.value}" }
                }
                if (!missing) collect(directory.value, owner)
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
