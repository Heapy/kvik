package kvik.cli

import kvik.bsp.*
import kvik.cache.NativeFiles
import kvik.cache.NativeOutputTrees
import kvik.core.*
import kvik.locking.*
import kvik.projectgraph.*
import kvik.runevidence.explainRetainedAction

/** Reconstruct plugin declarations from committed inputs, without reading the current project or executing actions. */
internal fun materializeBspPluginOutputs(model: BspProjectModel, record: VerificationRecord, events: List<RunEvent>,
    runDirectory: String, childDirectory: String, store: String, policy: ResolverPolicy): BspGeneratedOutputs {
    if (buildPlugins.none { it is ProjectGenerationContributor }) return BspGeneratedOutputs(emptyMap(), emptyMap())
    fun read(name: String) = NativeFiles.read("$runDirectory/$name", 16 * 1024 * 1024)
    val snapshot = TreeManifest.fromJson(parseJson(read("inputs.json").decodeToString(throwOnInvalidSequence = true)))
    val started = events.single { it.type == "run-started" }.payload.values
    require(Digest.fromJson(started.getValue("inputManifest")) == Sha256.digest(snapshot.toJson())) { "Retained plugin input manifest differs" }
    val content = ReadOnlyContentStore(store, 64 * 1024 * 1024)
    val configuration = parseBuildProjectConfiguration(read("configuration.json")).withScriptDeclarations(boundedScriptSourceReader { path ->
        val file = snapshot.files.singleOrNull { it.path == path } ?: error("Missing retained script input: ${path.value}")
        content.get(file.digest) ?: error("Retained script content is unavailable")
    })
    require(configuration.document == model.inspection.project.document) { "Retained plugin configuration differs from import" }
    val effective = effectiveConfiguration(configuration, policy)
    require(effective.digest == record.configuration && effective.digest == model.inspection.configuration)
    val lock = parseDependencyLock(read("dependency-lock.json"))
    require(lock.digest == record.dependencyLock && lock.configuration == effective.digest) { "Retained plugin lock differs" }
    val tools = record.toolchains.entries.groupBy { it.key.substringBefore(':') }.mapValues { (_, entries) ->
        entries.associate { it.key.substringAfter(':') to it.value }
    }
    val declarations = collectGeneratedInputs(buildPlugins, ProjectBuildContext(effective, lock, snapshot, record.composition, tools))
    val roots = declarations.sources.map { BspPluginGeneratedRoot(it.producer, it.module, it.sourceSet, it.outputRoot) } +
        declarations.resources.map { BspPluginGeneratedRoot(it.producer, it.module, it.sourceSet, it.outputRoot, it.files) }
    model.registerPluginGeneratedRoots(roots)
    val results = record.actions.associateBy { it.actionId }
    val sources = linkedMapOf<BspTarget, MutableList<BspGeneratedSources>>()
    val resources = linkedMapOf<BspTarget, MutableList<BspGeneratedResources>>()
    for ((producer, bindings) in roots.groupBy { it.producer }) {
        val result = results[producer]?.takeIf { it.successful } ?: continue
        val node = declarations.nodes.single { it.id == producer }
        val prerequisites = node.dependencies.associateWith { id ->
            requireNotNull(results[id]).also { require(it.successful && it.actionId == id) }
        }
        val expected = node.resolve(prerequisites)
        val name = Sha256.digest(producer.encodeToByteArray()).hash
        val retained = parseJson(read("actions/$name.json").decodeToString(throwOnInvalidSequence = true))
        val action = explainRetainedAction(record, events, producer, retained).values.getValue("action")
        require(expected.toJson() == action && expected.key(Sha256) == result.key) { "Plugin generator action differs from retained inputs" }
        checkNotNull(node.outputContract).requireMatches(expected)
        node.outputContract.requireMatches(result.outputs)
        val selected = bindings.associateWith { binding ->
            val files = result.outputs.files.filter { it.path.value.startsWith(binding.outputRoot.value + "/") }
            require(files.size <= 10_000 && files.all { !it.executable && it.digest.sizeBytes <= 8L * 1024 * 1024 } &&
                files.sumOf { it.digest.sizeBytes } <= 64L * 1024 * 1024) { "Plugin generated files exceed BSP limits" }
            if (binding.resources == null) require(files.all { it.path.value.endsWith(".kt") }) { "Plugin generated sources must be Kotlin" }
            else require(files.map { it.path.value.removePrefix(binding.outputRoot.value + "/") }.toSet() ==
                binding.resources.map { it.value }.toSet()) { "Plugin generated resources differ from declaration" }
            TreeManifest(files.map { it.copy(path = LogicalPath(binding.prefix + it.path.value.removePrefix(binding.outputRoot.value + "/"))) })
        }
        val files = TreeManifest(selected.values.flatMap { it.files })
        require(files.files.sumOf { it.digest.sizeBytes } <= 64L * 1024 * 1024) { "Plugin generated outputs exceed BSP limits" }
        for ((binding, tree) in selected) if (binding.resources == null) for (file in tree.files)
            (content.get(file.digest) ?: error("Generated content is unavailable")).decodeToString(throwOnInvalidSequence = true)
        val directory = "$childDirectory/generated-$name"
        if (files.files.isNotEmpty()) NativeOutputTrees(content).materialize(files, directory)
        val owners = bindings.flatMap(model::pluginGeneratedTargets).distinct()
        for (owner in owners) {
            val owned = selected.filterKeys { owner in model.pluginGeneratedTargets(it) }
            if (owned.keys.any { it.resources == null }) {
                val tree = TreeManifest(owned.filterKeys { it.resources == null }.values.flatMap { it.files })
                val values = sources.getOrPut(owner) { mutableListOf() }
                if (tree.files.isNotEmpty()) values += BspGeneratedSources(directory, producer, checkNotNull(result.key), tree)
            }
            if (owned.keys.any { it.resources != null }) {
                val tree = TreeManifest(owned.filterKeys { it.resources != null }.values.flatMap { it.files })
                resources.getOrPut(owner) { mutableListOf() } += BspGeneratedResources(directory, producer, checkNotNull(result.key), tree)
            }
        }
    }
    return BspGeneratedOutputs(sources, resources)
}
