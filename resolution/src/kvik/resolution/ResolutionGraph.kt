package kvik.resolution

import kvik.core.*

/** Query evidence from the same traversal that produces the immutable lock selections. */
data class ResolutionNode(val id: String, val coordinate: Coordinate, val platform: Boolean, val expanded: Boolean,
                          val variant: String, val repository: String, val metadata: List<MetadataIdentity>, val artifacts: List<LockedArtifact>)
data class ResolutionEdge(val source: String, val target: String?, val kind: String, val requirement: Coordinate,
                          val semantics: String, val selected: Coordinate?, val transitive: Boolean, val exclusion: MetadataEdgeExclusion? = null) {
    init {
        if (exclusion != null) require(kind == "excluded" && target == null && selected == null && !transitive &&
            semantics == "explicit-exclusion" && requirement == exclusion.dependency)
        else if (semantics == "metadata-exclusion") require(kind == "excluded" && target == null && selected == null && !transitive)
        else require(kind != "excluded" && semantics != "explicit-exclusion")
    }
}
data class ResolutionRoot(val requested: Coordinate, val node: String)
data class ResolutionGraph(val variant: VariantRequest, val roots: List<ResolutionRoot>, val nodes: List<ResolutionNode>,
                           val edges: List<ResolutionEdge>, val selections: List<LockedSelection>) {
    /** Bounded shortest paths; edges retain alternative routes without exponential enumeration. */
    fun toJson(maxPaths: Int = 4096): JsonValue.Object {
        require(maxPaths in 1..4096 && nodes.size <= 1024 && edges.size <= 8192 && roots.size <= 256 &&
            nodes.all { node -> node.coordinate.toString().length <= 1024 && node.variant.length <= 4096 && node.repository.length <= 4096 &&
                node.metadata.size <= 32 && node.artifacts.size <= 32 && node.metadata.all { it.url.length <= 4096 } &&
                node.artifacts.all { it.url.length <= 4096 && it.variant.length <= 4096 && it.repository.length <= 4096 } } &&
            roots.all { it.requested.toString().length <= 1024 } && edges.all { it.requirement.toString().length <= 1024 }) { "Query graph exceeds output bounds" }
        val paths = mutableListOf<JsonValue>()
        var truncated = false
        val adjacency = edges.withIndex().filter { it.value.target != null && it.value.kind != "constraint" }.groupBy { it.value.source }
        for (root in roots) {
            data class Step(val node: String, val nodes: List<String>, val edges: List<Int>)
            val queue = ArrayDeque<Step>(); queue += Step(root.node, listOf(root.node), emptyList())
            val visited = mutableSetOf(root.node)
            while (queue.isNotEmpty()) {
                val step = queue.removeFirst()
                if (paths.size == maxPaths) { truncated = true; break }
                paths += obj("root" to root.requested.toString().json(), "node" to step.node.json(),
                    "nodes" to step.nodes.map(String::json).json(), "edges" to step.edges.map(Int::json).json())
                for ((index, edge) in adjacency[step.node].orEmpty()) {
                    if (visited.add(edge.target!!)) queue += Step(edge.target, step.nodes + edge.target, step.edges + index)
                }
            }
            if (truncated) break
        }
        return obj("variant" to obj("platform" to variant.platform.json(), "usage" to variant.usage.json(),
            "attributes" to JsonValue.Object(variant.attributes.mapValues { it.value.json() })),
            "roots" to roots.map { obj("requested" to it.requested.toString().json(), "node" to it.node.json()) }.json(),
            "nodes" to nodes.map { node -> obj("id" to node.id.json(), "coordinate" to node.coordinate.toString().json(),
                "platform" to node.platform.json(), "expanded" to node.expanded.json(), "variant" to node.variant.json(), "repository" to node.repository.json(),
                "metadata" to node.metadata.map { obj("url" to it.url.json(), "digest" to it.digest.toJson()) }.json(),
                "artifacts" to node.artifacts.map { obj("coordinate" to it.coordinate.toString().json(), "variant" to it.variant.json(),
                    "repository" to it.repository.json(), "url" to it.url.json(), "digest" to it.digest.toJson()) }.json()) }.json(),
            "edges" to edges.map { edge -> obj("source" to edge.source.json(), "target" to (edge.target?.json() ?: JsonValue.Null),
                "kind" to edge.kind.json(), "requirement" to obj("coordinate" to edge.requirement.toString().json(), "semantics" to edge.semantics.json()),
                "selected" to (edge.selected?.toString()?.json() ?: JsonValue.Null), "transitive" to edge.transitive.json()).let { value ->
                    if (edge.exclusion == null) value else JsonValue.Object(value.objectValue() + ("exclusion" to obj("rule" to edge.exclusion.toJson(), "digest" to edge.exclusion.digest.toJson())))
                } }.json(),
            "paths" to paths.json(), "pathPolicy" to "shortest-per-root-node".json(), "truncated" to truncated.json(), "artifactVerification" to "verified".json())
    }
}
