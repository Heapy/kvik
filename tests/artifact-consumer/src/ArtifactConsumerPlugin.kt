package fixture.plugins

import kvik.core.*
import kvik.compiler.jvm.jvmClasspathAction
import kvik.projectgraph.*

/** Consumes a preceding plugin's public artifacts without knowing its action IDs or paths. */
class ArtifactConsumerPlugin : ProjectGraphContributor {
    override val id = "fixture.artifactConsumer"
    override val apiVersion = CONTRACT_VERSION
    override val graphDependencies = setOf("fixture.classpath")
    override val configurationSchema = obj("type" to "object".json())

    override fun contribute(context: ProjectBuildContext, graph: ProjectActionGraph): List<ActionNode> {
        if (graph.findArtifacts("app", "jvm", "main", "platform-classpath").isEmpty()) return emptyList()
        val main = graph.requireArtifact("app", "jvm", "main", "platform-classpath")
        val inputs = listOf(main) + graph.findArtifacts("app", "jvm", "test", "platform-classpath")
        return listOf(ActionNode("$id:bundle", inputs.map { it.producer }.toSet(),
            ActionOutputContract(treeOutput("classpath", "platform-classpath"))) { results ->
            val roots = inputs.map { LogicalPath("upstream/${it.compilation}") }
            val files = TreeManifest(inputs.zip(roots).flatMap { (artifact, root) -> artifact.manifest(results, root).files })
            jvmClasspathAction(JvmClasspath("app", "main", roots), files, context.composition, context.toolchains.getValue("jvm"))
        })
    }
}
