package kvik.projectgraph

import kvik.core.*

/** A semantic reference to a declared output of an action already present in this graph. */
data class ProjectArtifact(
    val module: String,
    val target: String,
    val compilation: String,
    val producer: String,
    val output: OutputArtifact,
) {
    init {
        require(listOf(module, target, compilation, producer).all { it.isNotBlank() })
    }

    /** Add [producer] to the consumer's dependencies before resolving this reference. */
    fun manifest(results: Map<String, ActionResult>, destination: LogicalPath = output.path): TreeManifest {
        val result = requireNotNull(results[producer]) { "Missing artifact producer: $producer" }
        require(result.actionId == producer && result.successful) { "Unsuccessful or mismatched artifact producer: $producer" }
        require(result.outputs.files.none {
            (it.path == output.path || it.path.value.startsWith(output.path.value + "/")) && !output.contains(it.path)
        }) { "Artifact output shape differs: $producer:${output.path.value}" }
        val files = result.outputs.files.filter { output.contains(it.path) }
        require(output.shape == ArtifactShape.TREE || files.isNotEmpty()) { "Missing artifact: $producer:${output.path.value}" }
        return TreeManifest(files.map { file ->
            file.copy(path = LogicalPath(destination.value + file.path.value.removePrefix(output.path.value)))
        })
    }

    fun toJson(): JsonValue.Object = obj("module" to module.json(), "target" to target.json(),
        "compilation" to compilation.json(), "producer" to producer.json(), "output" to output.toJson())
}

/** Only returns artifacts in the selected graph; does not expand the build or resolve dependencies. */
fun ProjectActionGraph.findArtifacts(module: String, target: String, compilation: String, kind: String): List<ProjectArtifact> =
    artifacts.filter { it.module == module && it.target == target && it.compilation == compilation && it.output.kind == kind }

fun ProjectActionGraph.requireArtifact(module: String, target: String, compilation: String, kind: String): ProjectArtifact {
    val matches = findArtifacts(module, target, compilation, kind)
    require(matches.size == 1) { "Expected one artifact for $module:$target:$compilation:$kind, found ${matches.size}" }
    return matches.single()
}

internal fun validateProjectArtifacts(nodes: List<ActionNode>, artifacts: List<ProjectArtifact>) {
    val producers = nodes.associateBy { it.id }
    require(artifacts.distinct().size == artifacts.size) { "Duplicate project artifact" }
    for (artifact in artifacts) {
        require(producers[artifact.producer]?.outputContract?.artifacts?.contains(artifact.output) == true) {
            "Project artifact must reference a declared producer output: ${artifact.producer}:${artifact.output.path.value}"
        }
    }
}
