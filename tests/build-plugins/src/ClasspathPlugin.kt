package fixture.plugins

import kvik.core.*
import kvik.compiler.jvm.jvmClasspathAction
import kvik.projectgraph.*

/** A source plugin consuming a compiler artifact through the public graph. */
class ClasspathPlugin : ProjectGraphContributor {
    override val id = "fixture.classpath"
    override val apiVersion = CONTRACT_VERSION
    override val configurationSchema = obj("type" to "object".json())

    override fun artifacts(context: ProjectBuildContext, graph: ProjectActionGraph): List<ProjectArtifact> =
        listOf("bundle" to "main", "reports" to "test").mapNotNull { (name, compilation) ->
            graph.nodes.singleOrNull { it.id == "$id:$name" }?.let { node ->
                ProjectArtifact("app", "jvm", compilation, node.id, node.outputContract!!.artifacts.single())
            }
        }

    override fun contribute(context: ProjectBuildContext, graph: ProjectActionGraph): List<ActionNode> {
        val artifact = graph.findArtifacts("app", "jvm", "main", "jvm-classes").singleOrNull() ?: return emptyList()
        val compile = artifact.producer
        val bundle = ActionNode("fixture.classpath:bundle", setOf(compile), ActionOutputContract(treeOutput("classpath", "platform-classpath"))) { results ->
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
        val reports = graph.artifacts.filter { it.module == "app" && it.target == "jvm" && it.compilation == "test" &&
            it.output.kind in setOf("test-results", "coverage-results", "coverage-execution", "test-instrumentation") }
        val reportBundle = reports.takeIf { it.isNotEmpty() }?.let {
            ActionNode("fixture.classpath:reports", reports.map { it.producer }.toSet(), ActionOutputContract(treeOutput("classpath", "platform-classpath"))) { results ->
                val inputs = TreeManifest(reports.flatMap { report ->
                    report.manifest(results, LogicalPath("reports/${report.output.path.value}")).files
                })
                jvmClasspathAction(JvmClasspath("app", "test", listOf(LogicalPath("reports"))), inputs,
                    context.composition, context.toolchains.getValue("jvm"))
            }
        }
        return listOfNotNull(bundle, extraCompile, reportBundle)
    }
}
