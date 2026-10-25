package kvik.projectgraph

import kvik.core.*
import kotlin.test.*

class GraphInspectionTest {
    @Test fun declaredArtifactsAreAvailableWithoutResolvingAndAbsentMetadataStaysUnknown() {
        val compile = ActionNode("compile", emptySet(), ActionOutputContract(treeOutput("classes", "jvm-classes"))) { error("Must not resolve") }
        val run = ActionNode("run", setOf("compile"), ActionOutputContract()) { error("Must not resolve") }
        val unknown = node("contributor")
        val actions = inspectActionGraph(ProjectActionGraph(listOf(compile, run, unknown), setOf("script:run"),
            mapOf("script:run" to "run"), setOf("contributor"))).values.getValue("actions").arrayValue()
            .associate { it.objectValue().let { fields -> fields.getValue("id").stringValue() to fields.getValue("output").objectValue().getValue("declaredArtifacts") } }
        assertEquals(compile.outputContract!!.toJson(), actions.getValue("compile"))
        assertEquals(emptyList<JsonValue>().json(), actions.getValue("run"))
        assertEquals(JsonValue.Null, actions.getValue("contributor"))
    }

    private fun node(id: String, vararg dependencies: String) = ActionNode(id, dependencies.toSet()) {
        error("Inspection must not resolve action recipes")
    }
    private fun graph() = ProjectActionGraph(listOf(node("test", "compile"), node("generate"), node("compile", "generate"), node("extra", "compile")),
        setOf("library:jvm:test"), mapOf("library:jvm:test" to "test"), setOf("extra"), listOf(
            GeneratedSourceRoot("generate", "library", "main", "script", "/scripts/generate/generator", LogicalPath("generated"))))

    @Test fun topologyIncludesResultConsumersEndpointsAndContributionsWithoutResolvingRecipes() {
        val value = inspectActionGraph(graph()).values
        val actions = value.getValue("actions").arrayValue().associate { it.objectValue().let { f -> f.getValue("id").stringValue() to f } }
        assertEquals(listOf("compile", "extra", "generate", "test"), actions.keys.toList())
        val compile = actions.getValue("compile")
        assertEquals(listOf("generate".json()), compile.getValue("dependencies").arrayValue())
        assertEquals(listOf("extra".json(), "test".json()), compile.getValue("output").objectValue().getValue("resultConsumers").arrayValue())
        assertEquals("unresolved".json(), compile.getValue("output").objectValue().getValue("state"))
        assertEquals(true.json(), actions.getValue("extra").getValue("contributed"))
        val check = value.getValue("checks").arrayValue().single().objectValue()
        assertEquals("test".json(), check.getValue("terminalAction"))
        assertEquals(listOf("extra".json()), check.getValue("requiredContributions").arrayValue())
        assertEquals("generated".json(), value.getValue("generatedSources").arrayValue().single().objectValue().getValue("outputRoot"))
        assertEquals(inspectActionGraph(graph()), inspectActionGraph(graph().copy(nodes = graph().nodes.reversed())))
    }

    @Test fun invalidGraphsDoNotProducePartialPlans() {
        val graph = graph()
        for (invalid in listOf(
            graph.copy(nodes = graph.nodes + node("compile")),
            graph.copy(nodes = graph.nodes + node("other", "missing")),
            graph.copy(nodes = graph.nodes + node("cycle", "cycle")),
            graph.copy(terminalActions = mapOf("library:jvm:test" to "absent")),
            graph.copy(contributedActions = setOf("absent")),
            graph.copy(generatedSources = listOf(graph.generatedSources.single().copy(producer = "absent"))),
        )) assertFailsWith<IllegalArgumentException> { inspectActionGraph(invalid) }
    }
}
