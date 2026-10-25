package kvik.projectgraph

import kvik.core.*

/** Pre-provider declarations; action dependencies form a closed graph of generator/helper actions. */
data class ProjectGeneratedInputs(
    val nodes: List<ActionNode> = emptyList(),
    val sources: List<GeneratedSourceRoot> = emptyList(),
    val resources: List<GeneratedResourceRoot> = emptyList(),
)

/** Pure declarations; execution uses the normal scheduler, output contracts and native executors. */
interface ProjectGenerationContributor : BuildPlugin {
    fun generatedInputs(context: ProjectBuildContext): ProjectGeneratedInputs
}

internal fun collectGeneratedInputs(plugins: List<BuildPlugin>, context: ProjectBuildContext): ProjectGeneratedInputs {
    val parts = plugins.filterIsInstance<ProjectGenerationContributor>().map { plugin ->
        val part = plugin.generatedInputs(context)
        require(part.nodes.all { it.id.startsWith("plugin:${plugin.id}:generate:") && it.id.length <= 256 }) {
            "Generated action IDs must use plugin:${plugin.id}:generate:NAME"
        }
        val ids = part.nodes.map { it.id }.toSet()
        require((part.sources.map { it.producer } + part.resources.map { it.producer }).all { it in ids }) {
            "Plugin generated roots must reference that plugin's declared actions: ${plugin.id}"
        }
        ProjectGeneratedInputs(part.nodes.map { it.copy(dependencies = it.dependencies.toSet()) }, part.sources.toList(),
            part.resources.map { it.copy(files = it.files.toList()) })
    }
    val result = ProjectGeneratedInputs(parts.flatMap { it.nodes }, parts.flatMap { it.sources }, parts.flatMap { it.resources })
    val project = context.effective.document.objectValue()["project"]?.let(::parseProjectConfiguration)?.project
    require(project != null || result == ProjectGeneratedInputs()) { "Standalone scripts cannot declare project generated inputs" }
    if (project != null) validateGeneratedInputs(project, result)
    return result
}

internal fun validateGeneratedInputs(project: ProjectSpec, inputs: ProjectGeneratedInputs) {
    require(inputs.nodes.size <= 1024 && inputs.sources.size + inputs.resources.size <= 1024) { "Too many plugin generated inputs" }
    val nodes = inputs.nodes.associateBy { it.id }
    require(nodes.size == inputs.nodes.size && nodes.keys.all { it.isNotBlank() }) { "Duplicate/blank generated action ID" }
    validateGraph(nodes.mapValues { it.value.dependencies })
    val roots = inputs.sources.map { Triple(it.producer, it.outputRoot, it.module to it.sourceSet) } +
        inputs.resources.map { Triple(it.producer, it.outputRoot, it.module to it.sourceSet) }
    require(roots.distinct().size == roots.size) { "Duplicate plugin generated root" }
    for ((producer, root, consumer) in roots) {
        val node = requireNotNull(nodes[producer]) { "Unknown plugin generated producer: $producer" }
        require(node.outputContract?.artifacts?.any { it.path == root && it.shape == ArtifactShape.TREE } == true) {
            "Plugin generated root must have a declared tree output: $producer:${root.value}"
        }
        val module = project.modules.singleOrNull { it.id == consumer.first }
        require(module?.sourceSets?.any { it.name == consumer.second } == true) { "Unknown plugin generated source set: $consumer" }
    }
    for ((producer, values) in roots.groupBy { it.first }) {
        val paths = values.map { it.second.value.lowercase() }
        require(paths.indices.all { i -> paths.indices.all { j -> i == j ||
            paths[i] != paths[j] && !paths[i].startsWith(paths[j] + "/") } }) { "Overlapping plugin generated roots: $producer" }
    }
    require(inputs.sources.all { it.kind.isNotBlank() && it.configurationPointer.startsWith('/') }) { "Invalid plugin generated source origin" }
    for (root in inputs.resources) {
        require(root.configurationPointer.startsWith('/') && root.files.isNotEmpty() && root.files.size <= 4096) { "Invalid plugin generated resource declaration" }
        val folded = root.files.map { LogicalPath(it.value.lowercase()) }
        require(folded.distinct().size == folded.size) { "Duplicate plugin generated resource file" }
        TreeManifest(folded.map { FileEntry(it, Digest("0".repeat(64), 0)) })
    }
}

internal fun generatedFiles(results: Map<String, ActionResult>, producer: String, root: LogicalPath): List<FileEntry> {
    val result = results.getValue(producer)
    require(result.actionId == producer && result.successful) { "Unsuccessful plugin generator: $producer" }
    return result.outputs.files.filter { it.path.value.startsWith(root.value + "/") }.also { entries ->
        require(entries.none { it.executable }) { "Executable file in generated sources/resources: $producer" }
    }
}

internal fun pluginSourcePrefix(root: GeneratedSourceRoot): String =
    "generated/plugins/${Sha256.digest(root.producer.encodeToByteArray()).hash}/${root.outputRoot.value}/"

/** Uniform source-set binding used by scripts and source build plugins. */
internal data class SourceGenerator(val producer: String, val sourceSet: String, val root: LogicalPath, val prefix: String, val exclusiveRoot: Boolean = false)
