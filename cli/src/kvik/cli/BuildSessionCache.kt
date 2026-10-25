package kvik.cli

import kvik.core.*
import kvik.locking.*
import kvik.projectgraph.*

/** Retained models/graphs only. Each operation has fresh inputs, lock replay and validated desired tool identities. */
internal class BuildSessionCache {
    private val models = linkedMapOf<Digest, ProjectConfiguration>()
    private val graphs = linkedMapOf<Digest, ProjectActionGraph>()
    private var previousModels: Set<Digest> = emptySet()
    var modelReused = false; private set
    var graphReused = false; private set
    fun begin() { previousModels = models.keys.toSet(); modelReused = false; graphReused = false }

    fun configuration(bytes: ByteArray): ProjectConfiguration {
        val key = Sha256.digest(bytes)
        models[key]?.let { if (key in previousModels) modelReused = true; return it }
        val model = parseBuildProjectConfiguration(bytes)
        if (models.size == 2) models.remove(models.keys.first())
        models[key] = model
        return model
    }

    fun graph(effective: EffectiveConfiguration, lock: DependencyLock, snapshot: TreeManifest,
              snapshotIdentity: Digest, lockBytes: ByteArray, composition: Digest,
              tools: Map<String, Map<String, Digest>>, toolsDocument: Digest, request: ProjectGraphRequest,
              plugins: List<BuildPlugin>): ProjectActionGraph {
        val key = Sha256.digest(obj("configuration" to effective.digest.toJson(), "lock" to Sha256.digest(lockBytes).toJson(),
            "snapshot" to snapshotIdentity.toJson(), "toolsDocument" to toolsDocument.toJson(), "composition" to composition.toJson(),
            "plugins" to plugins.sortedBy { it.id }.map { it.descriptor() }.json(),
            "tools" to JsonValue.Object(tools.mapValues { (_, values) -> JsonValue.Object(values.mapValues { it.value.toJson() }) }),
            "modules" to request.modules.sorted().map { it.json() }.json(), "targets" to request.targets.sorted().map { it.json() }.json(),
            "mode" to request.mode.name.json(), "tests" to JsonValue.Object(request.jvmTests.mapValues { (_, tests) ->
                obj("classes" to tests.classes.map { it.json() }.json(), "engines" to tests.engines.map { it.json() }.json(),
                    "coverage" to (tests.coverage?.toJson() ?: JsonValue.Null), "parallelism" to tests.parallelism.json(), "maxRetries" to tests.maxRetries.json()) })))
        graphs[key]?.let { graphReused = true; return it }
        val graph = composedProjectActionGraph(plugins, ProjectBuildContext(effective, lock, snapshot, composition, tools), request)
        if (graphs.size == 3) graphs.remove(graphs.keys.first())
        graphs[key] = graph
        return graph
    }
}
