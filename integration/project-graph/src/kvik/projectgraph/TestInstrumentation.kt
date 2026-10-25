package kvik.projectgraph

import kvik.core.*

/** Add agents to an existing ordinary test action, using snapshot or prerequisite artifacts.
 * [inputs] receives only the explicitly added dependencies. It describes immutable inputs;
 * agent execution remains owned by the JVM adapter in the original test process.
 */
class JvmTestInstrumentation(
    val actionId: String,
    agents: List<JvmTestAgent>,
    dependencies: Set<String> = emptySet(),
    val inputs: (Map<String, ActionResult>) -> TreeManifest,
) {
    val agents = agents.map { JvmTestAgent.fromJson(it.toJson()) }
    val dependencies = dependencies.toSet()
    init { require(actionId.isNotBlank() && this.agents.isNotEmpty()) }
}

/** Source plugins may also contribute prerequisite actions via [ProjectGraphContributor]. */
interface ProjectTestInstrumentationContributor : BuildPlugin {
    fun instrumentTests(context: ProjectBuildContext, graph: ProjectActionGraph): List<JvmTestInstrumentation>
}

internal fun instrumentJvmTest(node: ActionNode, launch: TestLaunch, instrumentation: JvmTestInstrumentation): Pair<ActionNode, JvmTestLaunch> {
    require(node.id == instrumentation.actionId && launch is JvmTestLaunch) { "Instrumentation requires an existing JVM test launch" }
    val instrumented = JvmTestLaunch(launch.selection.copy(agents = launch.selection.agents + instrumentation.agents), launch.timeoutMillis)
    val replacement = ActionNode(node.id, node.dependencies + instrumentation.dependencies, instrumented.outputContract) { results ->
        val original = node.resolve(results)
        launch.requireMatches(original)
        val prerequisites = instrumentation.dependencies.associateWith { id ->
            results.getValue(id).also { require(it.actionId == id && it.successful) { "Unsuccessful instrumentation prerequisite: $id" } }
        }
        val added = instrumentation.inputs(prerequisites)
        // Identical shared inputs are allowed; conflicting contents at the same path are not.
        val inputs = TreeManifest((original.inputs.files + added.files).distinct())
        instrumented.action(inputs, original.composition, original.toolchains)
    }
    return replacement to instrumented
}
