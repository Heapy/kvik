package fixture.plugins

import kvik.core.*
import kvik.projectgraph.*

class InstrumentationPlugin : ProjectTestInstrumentationContributor {
    override val id = "fixture.instrumentation"
    override val apiVersion = CONTRACT_VERSION
    override val configurationSchema = obj("type" to "object".json())

    override fun instrumentTests(context: ProjectBuildContext, graph: ProjectActionGraph): List<JvmTestInstrumentation> {
        val output = LogicalPath("instrumentation/probe/report.txt")
        val agent = JvmTestAgent("probe", LogicalPath("agents/probe.jar"), listOf(
            JvmAgentOption(JvmAgentOptionKind.INPUT_FILE, "agents/config.txt"),
            JvmAgentOption(JvmAgentOptionKind.LITERAL, "|"),
            JvmAgentOption(JvmAgentOptionKind.OUTPUT_FILE, output.value),
            JvmAgentOption(JvmAgentOptionKind.LITERAL, "|normal")), listOf(output))
        val inputs = TreeManifest(context.snapshot.files.filter { it.path.value in setOf("agents/probe.jar", "agents/config.txt") })
        return graph.testLaunches.filterValues { it is JvmTestLaunch }.keys.map { id ->
            JvmTestInstrumentation(id, listOf(agent)) { inputs }
        }
    }
}
