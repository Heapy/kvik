package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kvik.plugins.kotlin.KotlinBuildPlugin
import kotlin.test.*

private fun pluginDigest(text: String) = Sha256.digest(text.encodeToByteArray())
private val pluginComposition = pluginDigest("source composition")
private val pluginRequest = ProjectGraphRequest(setOf("app"), setOf("jvm"), ProjectMode.BUILD)
private const val pluginCheck = "app:jvm:build"

private fun pluginContext(): ProjectBuildContext {
    val project = parseProjectConfiguration(parseJson("""{
        "schemaVersion":1,"name":"plugins",
        "repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],
        "modules":[{"id":"app","sourceSets":[{"name":"main","directories":["src"],
            "resources":[],"dependsOn":[],"dependencies":[]}],
            "targets":[{"name":"jvm","main":"main","test":null,"compilerOptions":[]}],"compilerPlugins":[]}],
        "scripts":{"task":{"sources":["task.kt"]}}
    }"""))
    val effective = effectiveConfiguration(project)
    return ProjectBuildContext(effective, DependencyLock(effective.digest, emptyList()),
        TreeManifest(listOf("src/Main.kt", "task.kt").map { FileEntry(LogicalPath(it), pluginDigest(it)) }),
        pluginComposition, mapOf("jvm" to listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith(::pluginDigest)))
}

private fun pluginNode(id: String, dependencies: Set<String> = emptySet(), composition: Digest = pluginComposition,
                       policy: CachePolicy = CachePolicy.LOCAL): ActionNode = ActionNode(id, dependencies) {
    ActionSpec("fixture-v1", composition, ActionKind.GENERATE, mapOf("os" to "fixture"), "jvm",
        mapOf("fixture" to pluginDigest("tool")), TreeManifest(emptyList()), listOf(LogicalPath("${id.replace(':', '-')}.txt")), emptyList(),
        emptyMap(), cachePolicy = policy)
}

private fun pluginGraph(nodes: List<ActionNode> = listOf(pluginNode("main")),
                        checks: Set<String> = setOf(pluginCheck),
                        terminals: Map<String, String> = mapOf(pluginCheck to "main")) = ProjectActionGraph(nodes, checks, terminals)

private open class AdditivePlugin(override val id: String, override val apiVersion: Int = CONTRACT_VERSION,
                                 private val make: (JsonValue.Object) -> List<ActionNode> = { emptyList() }) : BuildPlugin {
    override val configurationSchema = obj()
    override fun actions(project: JsonValue.Object) = make(project)
}

private class FixtureProvider(id: String = "provider", private val project: (ProjectBuildContext, ProjectGraphRequest) -> ProjectActionGraph = { _, _ -> pluginGraph() },
                              private val script: (ProjectBuildContext, ScriptGraphRequest) -> ProjectActionGraph = { _, request ->
                                  val id = "script:${request.name}:jvm:run"
                                  pluginGraph(listOf(pluginNode(id)), setOf(id), mapOf(id to id))
                              }) : AdditivePlugin(id), ProjectBuildPlugin {
    override fun projectGraph(context: ProjectBuildContext, request: ProjectGraphRequest) = project(context, request)
    override fun scriptGraph(context: ProjectBuildContext, request: ScriptGraphRequest) = script(context, request)
}

private class Contributor(id: String, private val add: (ProjectBuildContext, ProjectActionGraph) -> List<ActionNode>) :
    AdditivePlugin(id), ProjectGraphContributor {
    override fun contribute(context: ProjectBuildContext, graph: ProjectActionGraph) = add(context, graph)
    override fun actions(project: JsonValue.Object): List<ActionNode> = error("Typed contributors must not use the legacy callback")
}

class BuildPluginsTest {
    @Test fun providerReceivesInputsAndContributorsUseItsGraphInStableOrder() {
        val context = pluginContext()
        val calls = mutableListOf<String>()
        val provider = FixtureProvider(project = { received, request ->
            assertSame(context, received)
            assertEquals(pluginRequest, request)
            calls += "provider"
            pluginGraph()
        })
        val first = Contributor("a") { received, graph ->
            assertSame(context, received)
            assertEquals(listOf("main"), graph.nodes.map { it.id })
            calls += "a"
            listOf(pluginNode("extra-a", setOf("main")))
        }
        val second = Contributor("z") { _, graph ->
            assertEquals(listOf("main", "extra-a"), graph.nodes.map { it.id })
            calls += "z"
            listOf(pluginNode("extra-z", setOf("extra-a")))
        }
        val graph = composedProjectActionGraph(listOf(second, provider, first), context, pluginRequest)
        assertEquals(listOf("provider", "a", "z"), calls)
        assertEquals(listOf("main", "extra-a", "extra-z"), graph.nodes.map { it.id })
        assertEquals(setOf("extra-a"), graph.nodes.last().dependencies)
        assertEquals(listOf(LogicalPath("extra-z.txt")), graph.nodes.last().resolve(emptyMap()).outputs)
        calls.clear()
        assertEquals(graph.nodes.map { it.id }, composedProjectActionGraph(listOf(first, second, provider), context, pluginRequest).nodes.map { it.id })
        assertEquals(listOf("provider", "a", "z"), calls)
    }

    @Test fun legacyActionsReceiveAuthoringProjectAndDefaultActionsRemainEmpty() {
        val legacy = AdditivePlugin("legacy", make = { project ->
            assertEquals("plugins", project.values.getValue("name").stringValue())
            assertFalse("domains" in project.values)
            listOf(pluginNode("legacy", setOf("main")))
        })
        val graph = composedProjectActionGraph(listOf(FixtureProvider(), legacy), pluginContext(), pluginRequest)
        assertEquals(listOf("main", "legacy"), graph.nodes.map { it.id })
        val defaults = object : BuildPlugin {
            override val id = "default"
            override val apiVersion = CONTRACT_VERSION
            override val configurationSchema = obj()
        }
        assertTrue(defaults.actions(obj()).isEmpty())
    }

    @Test fun invalidRegistriesFailBeforeInvokingProvider() {
        var called = false
        val provider = FixtureProvider(project = { _, _ -> called = true; pluginGraph() })
        for (plugins in listOf(emptyList(), listOf(AdditivePlugin("legacy")), listOf(provider, FixtureProvider("other")),
            listOf(provider, AdditivePlugin("provider")), listOf(provider, AdditivePlugin(" ")),
            listOf(provider, AdditivePlugin("wrong-api", CONTRACT_VERSION + 1)))) {
            assertFailsWith<IllegalArgumentException> { composedProjectActionGraph(plugins, pluginContext(), pluginRequest) }
        }
        assertFalse(called)
    }

    @Test fun requestedChecksAndTheirTerminalsMustBeExact() {
        val invalid = listOf(pluginGraph(checks = emptySet()), pluginGraph(checks = setOf(pluginCheck, "unexpected")),
            pluginGraph(terminals = emptyMap()), pluginGraph(terminals = mapOf(pluginCheck to "absent")),
            pluginGraph(terminals = mapOf(pluginCheck to "main", "unexpected" to "main")))
        for (graph in invalid) assertFailsWith<IllegalArgumentException> {
            composedProjectActionGraph(listOf(FixtureProvider(project = { _, _ -> graph })), pluginContext(), pluginRequest)
        }
    }

    @Test fun invalidProviderAndContributionGraphsAreRejected() {
        val badNodes = listOf(listOf(pluginNode("main"), pluginNode("main")), listOf(pluginNode("main", setOf("missing"))),
            listOf(pluginNode("main", setOf("main"))), listOf(pluginNode("main"), pluginNode(" ")))
        for (nodes in badNodes) assertFailsWith<IllegalArgumentException> {
            composedProjectActionGraph(listOf(FixtureProvider(project = { _, _ -> pluginGraph(nodes) })), pluginContext(), pluginRequest)
        }
        val badContributions = listOf(listOf(pluginNode("main")), listOf(pluginNode("extra", setOf("missing"))),
            listOf(pluginNode("one", setOf("two")), pluginNode("two", setOf("one"))))
        for (nodes in badContributions) assertFailsWith<IllegalArgumentException> {
            composedProjectActionGraph(listOf(FixtureProvider(), Contributor("extra") { _, _ -> nodes }), pluginContext(), pluginRequest)
        }
    }

    @Test fun allResolversRequireCompositionAndContributionsCannotOptIntoRemoteCache() {
        val wrong = pluginDigest("different runtime")
        val wrongProvider = composedProjectActionGraph(listOf(FixtureProvider(project = { _, _ ->
            pluginGraph(listOf(pluginNode("main", composition = wrong))) })), pluginContext(), pluginRequest)
        assertFailsWith<IllegalArgumentException> { wrongProvider.nodes.single().resolve(emptyMap()) }
        for (node in listOf(pluginNode("extra", composition = wrong), pluginNode("extra", policy = CachePolicy.REMOTE))) {
            val graph = composedProjectActionGraph(listOf(FixtureProvider(), Contributor("extra") { _, _ -> listOf(node) }), pluginContext(), pluginRequest)
            assertFailsWith<IllegalArgumentException> { graph.nodes.last().resolve(emptyMap()) }
        }
        val providerRemote = composedProjectActionGraph(listOf(FixtureProvider(project = { _, _ ->
            pluginGraph(listOf(pluginNode("main", policy = CachePolicy.REMOTE))) })), pluginContext(), pluginRequest)
        assertEquals(CachePolicy.REMOTE, providerRemote.nodes.single().resolve(emptyMap()).cachePolicy)
    }

    @Test fun nodeDependenciesAreDetachedFromPluginOwnedCollections() {
        val edges = mutableSetOf("main")
        val nodes = mutableListOf(pluginNode("extra", edges))
        val graph = composedProjectActionGraph(listOf(FixtureProvider(), Contributor("extra") { _, _ -> nodes }), pluginContext(), pluginRequest)
        edges += "missing"
        nodes.clear()
        assertEquals(listOf("main", "extra"), graph.nodes.map { it.id })
        assertEquals(setOf("main"), graph.nodes.last().dependencies)
    }

    @Test fun scriptProviderGetsInvocationAndRequiresItsRunCheck() {
        val request = ScriptGraphRequest("task", listOf("--value", "two words"))
        var seen: ScriptGraphRequest? = null
        val provider = FixtureProvider(script = { _, received ->
            seen = received
            val id = "script:task:jvm:run"
            pluginGraph(listOf(pluginNode(id)), setOf(id), mapOf(id to id))
        })
        val graph = composedScriptActionGraph(listOf(provider), pluginContext(), request)
        assertEquals(request, seen)
        assertEquals(setOf("script:task:jvm:run"), graph.requestedChecks)
        assertFailsWith<IllegalArgumentException> {
            composedScriptActionGraph(listOf(FixtureProvider(script = { _, _ -> pluginGraph() })), pluginContext(), request)
        }
    }

    @Test fun kotlinProviderBuildsRealCompilerAndScriptActions() {
        val context = pluginContext()
        val plugin = KotlinBuildPlugin()
        val project = composedProjectActionGraph(listOf(plugin), context, pluginRequest)
        assertEquals(mapOf(pluginCheck to "app:jvm:main"), project.terminalActions)
        val compile = project.nodes.single().resolve(emptyMap())
        assertEquals("kvik-jvm-compiler-v1", compile.implementation)
        assertEquals(ActionKind.COMPILE, compile.kind)
        assertTrue(compile.inputs.files.any { it.path.value.endsWith("Main.kt") })
        val script = composedScriptActionGraph(listOf(plugin), context, ScriptGraphRequest("task"))
        assertEquals(listOf("script:task:jvm:compile", "script:task:jvm:run"), script.nodes.map { it.id })
        assertEquals(ActionKind.COMPILE, script.nodes.first().resolve(emptyMap()).kind)
        assertEquals(setOf("script:task:jvm:compile"), script.nodes.last().dependencies)
    }

    @Test fun standaloneScriptsAlsoExposeTheirRealAuthoringDocumentToLegacyPlugins() {
        val document = parseJson("""{"schemaVersion":1,"name":"task",
            "repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],
            "script":{"sources":["task.kt"]}}""")
        val effective = effectiveConfiguration(parseStandaloneScriptConfiguration(document))
        val context = pluginContext().copy(effective = effective, lock = DependencyLock(effective.digest, emptyList()))
        var called = false
        val legacy = AdditivePlugin("legacy", make = { authoring ->
            called = true
            assertEquals(document, authoring)
            listOf(pluginNode("after-script", setOf("script:task:jvm:run")))
        })
        val graph = composedScriptActionGraph(listOf(legacy, KotlinBuildPlugin()), context, ScriptGraphRequest("task"))
        assertTrue(called)
        assertEquals(listOf("script:task:jvm:compile", "script:task:jvm:run", "after-script"), graph.nodes.map { it.id })
        assertEquals(setOf("script:task:jvm:run"), graph.requestedChecks)
    }
}
