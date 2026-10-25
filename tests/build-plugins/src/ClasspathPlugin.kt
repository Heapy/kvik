package fixture.plugins

import kvik.core.*
import kvik.compiler.jvm.jvmClasspathAction
import kvik.projectgraph.*

/** A source plugin consuming a compiler artifact through the public graph. */
class ClasspathPlugin : ProjectGraphContributor {
    override val id = "fixture.classpath"
    override val apiVersion = CONTRACT_VERSION
    override val configurationSchema = obj("type" to "object".json())

    override fun contribute(context: ProjectBuildContext, graph: ProjectActionGraph): List<ActionNode> {
        val artifact = graph.findArtifacts("app", "jvm", "main", "jvm-classes").singleOrNull() ?: return emptyList()
        val compile = artifact.producer
        val bundle = ActionNode("fixture.classpath:bundle", setOf(compile)) { results ->
            require(context.snapshot.files.none { it.path.value == "other/resources/fail.txt" }) { "Fixture plugin failure" }
            val extra = context.snapshot.files.singleOrNull { it.path.value == "other/resources/input.txt" }
                ?.copy(path = LogicalPath("classes/plugin-input.txt"))
            val classes = TreeManifest(artifact.manifest(results, LogicalPath("classes")).files + listOfNotNull(extra))
            jvmClasspathAction(JvmClasspath("app", "main", listOf(LogicalPath("classes"))), classes,
                context.composition, context.toolchains.getValue("jvm"))
        }
        val extraCompile = if (context.snapshot.files.any { it.path.value == "other/resources/remote.txt" }) {
            val original = graph.nodes.single { it.id == compile }
            ActionNode("fixture.classpath:compile", original.dependencies, original.resolve)
        } else null
        return listOfNotNull(bundle, extraCompile)
    }
}
