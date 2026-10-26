package kvik.projectgraph

import kvik.core.*

data class GeneratedResourceRoot(val producer: String, val module: String, val sourceSet: String,
    val configurationPointer: String, val outputRoot: LogicalPath, val files: List<LogicalPath>)

internal class ResourceSelection(private val static: List<FileEntry>, private val generated: List<Pair<ExpandedScript, String>>,
    private val pluginRoots: List<Pair<GeneratedResourceRoot, String>> = emptyList()) {
    val generators get() = generated.map { it.first }.distinctBy { it.name }
    val pluginDependencies get() = pluginRoots.map { it.first.producer }.toSet()
    val dependencies get() = generators.map { "script:${it.name}:jvm:generate" }.toSet() + pluginDependencies
    val paths = static.map { it.path } + generated.flatMap { (script, prefix) ->
        script.declaration.generator!!.resources.map { LogicalPath("$prefix/${it.value}") }
    } + pluginRoots.flatMap { (root, prefix) -> root.files.map { LogicalPath("$prefix/${it.value}") } }
    init {
        val folded = paths.map { it.value.lowercase() }
        require(folded.distinct().size == folded.size) { "Ambiguous resource overlay" }
        TreeManifest(folded.map { FileEntry(LogicalPath(it), Digest("0".repeat(64), 0)) })
    }
    fun isEmpty() = paths.isEmpty()
    fun isNotEmpty() = !isEmpty()
    fun resolveGenerated(results: Map<String, ActionResult>): List<FileEntry> {
        val authored = static.map { it.path }.toSet()
        return resolve(results).filter { it.path !in authored }
    }
    operator fun plus(other: ResourceSelection) = ResourceSelection(static + other.static, generated + other.generated, pluginRoots + other.pluginRoots)
    fun resolve(results: Map<String, ActionResult>): List<FileEntry> = (static + generated.flatMap { (script, prefix) ->
        val id = "script:${script.name}:jvm:generate"
        val result = results.getValue(id)
        require(result.actionId == id && result.successful) { "Unsuccessful resource generator: $id" }
        val expected = script.declaration.generator!!.resources.map { LogicalPath("generated/${it.value}") }.toSet()
        require(result.outputs.files.map { it.path }.toSet() == expected && result.outputs.files.none { it.executable }) {
            "Generated resources differ from declared files: ${script.name}"
        }
        result.outputs.files.map { it.copy(path = LogicalPath("$prefix/" + it.path.value.removePrefix("generated/"))) }
    } + pluginRoots.flatMap { (root, prefix) ->
        val files = generatedFiles(results, root.producer, root.outputRoot)
        require(files.map { it.path.value.removePrefix(root.outputRoot.value + "/") }.toSet() == root.files.map { it.value }.toSet()) {
            "Plugin generated resources differ from declared files: ${root.producer}"
        }
        files.map { it.copy(path = LogicalPath("$prefix/" + it.path.value.removePrefix(root.outputRoot.value + "/"))) }
    }).sortedBy { it.path.value }
}

internal fun Iterable<ResourceSelection>.combinedResources(): ResourceSelection =
    fold(ResourceSelection(emptyList(), emptyList())) { result, value -> result + value }

internal fun moduleResources(project: ProjectSpec, files: List<FileEntry>, name: String, target: String, scope: String, pluginRoots: List<GeneratedResourceRoot> = emptyList()): ResourceSelection {
    val module = project.modules.single { it.id == name }
    val selected = module.targets.single { it.name == target }
    val main = sourceSetClosure(module, listOf(selected.main)).map { it.name }.toSet()
    val sets = sourceSetClosure(module, if (scope == "main") listOf(selected.main) else listOf(checkNotNull(selected.test)))
        .filter { scope == "main" || it.name !in main }
    val prefix = "runtime/$name/$scope"
    val static = sets.flatMap { set -> set.resources.flatMap { root ->
        require(files.none { it.path == root }) { "Resource root is a file: ${root.value}" }
        files.filter { it.path.value.startsWith(root.value + "/") }.map {
            it.copy(path = LogicalPath("$prefix/" + it.path.value.removePrefix(root.value + "/")))
        }
    } }
    val generators = scriptGenerators(project, name, sets.map { it.name }.toSet())
        .filter { it.declaration.generator!!.resources.isNotEmpty() }
    return ResourceSelection(static, generators.map { it to prefix },
        pluginRoots.filter { it.module == name && sets.any { set -> set.name == it.sourceSet } }.map { it to prefix })
}
