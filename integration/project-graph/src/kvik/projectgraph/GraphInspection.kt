package kvik.projectgraph

import kvik.core.*

/** A declaration-bound output location, relative to the producer's output tree, not the project. */
data class GeneratedSourceRoot(val producer: String, val module: String, val sourceSet: String,
                               val kind: String, val configurationPointer: String, val outputRoot: LogicalPath)

/** Inspect topology without resolving recipes: prerequisite output bytes do not exist yet. */
fun inspectActionGraph(graph: ProjectActionGraph): JsonValue.Object {
    require(graph.nodes.size <= 10_000 && graph.nodes.sumOf { it.dependencies.size.toLong() } <= 100_000) {
        "Graph inspection exceeds 10,000 actions or 100,000 edges"
    }
    val nodes = graph.nodes.associateBy { it.id }
    require(nodes.size == graph.nodes.size && nodes.keys.all { it.isNotBlank() }) { "Invalid action identities" }
    validateGraph(nodes.mapValues { it.value.dependencies })
    require(graph.requestedChecks.isNotEmpty() && graph.terminalActions.keys == graph.requestedChecks &&
        graph.terminalActions.values.all { it in nodes } && graph.contributedActions.all { it in nodes }) { "Invalid check endpoints" }
    require(graph.generatedSources.all { it.producer in nodes }) { "Unknown generated source producer" }
    require(graph.testLaunches.keys.all { it in nodes }) { "Unknown test launch action" }
    val consumers = nodes.keys.associateWith { mutableListOf<String>() }
    for (node in nodes.values) for (producer in node.dependencies) consumers.getValue(producer).add(node.id)
    return obj(
        "actions" to nodes.values.sortedBy { it.id }.map { node -> obj(
            "id" to node.id.json(),
            "dependencies" to node.dependencies.sorted().map(String::json).json(),
            "contributed" to (node.id in graph.contributedActions).json(),
            "testLaunch" to (graph.testLaunches[node.id]?.toJson() ?: JsonValue.Null),
            "output" to obj("contract" to "tree-manifest".json(), "state" to "unresolved".json(),
                "declaredArtifacts" to (node.outputContract?.toJson() ?: JsonValue.Null),
                "resultConsumers" to consumers.getValue(node.id).sorted().map(String::json).json()),
        ) }.json(),
        "checks" to graph.requestedChecks.sorted().map { check -> obj("id" to check.json(),
            "terminalAction" to graph.terminalActions.getValue(check).json(),
            "requiredContributions" to graph.contributedActions.sorted().map(String::json).json()) }.json(),
        "generatedSources" to graph.generatedSources.distinct().sortedWith(compareBy({ it.producer }, { it.module }, { it.sourceSet }, { it.outputRoot.value }, { it.kind }, { it.configurationPointer })).map { source ->
            obj("producer" to source.producer.json(), "module" to source.module.json(), "sourceSet" to source.sourceSet.json(),
                "kind" to source.kind.json(), "configurationPointer" to source.configurationPointer.json(),
                "outputRoot" to source.outputRoot.value.json())
        }.json(),
    )
}
