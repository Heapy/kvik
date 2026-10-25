package kvik.projectgraph

import kvik.core.*
import kvik.compiler.jvm.*
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

private class Contributor(id: String, override val graphDependencies: Set<String> = emptySet(), private val add: (ProjectBuildContext, ProjectActionGraph) -> List<ActionNode>) :
    AdditivePlugin(id), ProjectGraphContributor {
    override fun contribute(context: ProjectBuildContext, graph: ProjectActionGraph) = add(context, graph)
    override fun actions(project: JsonValue.Object): List<ActionNode> = error("Typed contributors must not use the legacy callback")
}

class BuildPluginsTest {
    @Test fun pluginsPublishArtifactsForLaterConsumersInProjectAndScriptGraphs() {
        val context = pluginContext()
        val output = fileOutput("assets.bin", "generated-data")
        var publications = 0
        val publisher = object : AdditivePlugin("z.producer"), ProjectGraphContributor {
            override fun contribute(context: ProjectBuildContext, graph: ProjectActionGraph) =
                listOf(ActionNode("producer:assets", emptySet(), ActionOutputContract(output)) {
                    pluginNode("assets").resolve(emptyMap()).copy(outputs = listOf(output.path))
                })
            override fun artifacts(context: ProjectBuildContext, graph: ProjectActionGraph): List<ProjectArtifact> {
                assertContains(graph.nodes.map { it.id }, "producer:assets")
                publications++
                return listOf(ProjectArtifact("app", "jvm", "main", "producer:assets", output))
            }
        }
        val consumer = Contributor("a.consumer", setOf("z.producer")) { _, graph ->
            val artifact = graph.requireArtifact("app", "jvm", "main", "generated-data")
            listOf(ActionNode("consumer:assets", setOf(artifact.producer)) { results ->
                pluginNode("consumer").resolve(emptyMap()).copy(inputs = artifact.manifest(results, LogicalPath("input/data.bin")))
            })
        }
        val plugins = listOf(consumer, FixtureProvider(), publisher)
        val graphs = listOf(composedProjectActionGraph(plugins, context, pluginRequest),
            composedScriptActionGraph(plugins, context, ScriptGraphRequest("task")))
        assertEquals(2, publications)
        for (graph in graphs) {
            val artifact = graph.requireArtifact("app", "jvm", "main", "generated-data")
            assertEquals(artifact.toJson(), inspectActionGraph(graph).values.getValue("artifacts").arrayValue().single())
            assertContains(graph.contributedActions, artifact.producer)
            val consumerNode = graph.nodes.single { it.id == "consumer:assets" }
            val result = ActionResult(artifact.producer, pluginDigest("generator"), ActionStatus.EXECUTED,
                TreeManifest(listOf(FileEntry(output.path, pluginDigest("data")))), 0)
            assertEquals(listOf(FileEntry(LogicalPath("input/data.bin"), pluginDigest("data"))),
                consumerNode.resolve(mapOf(artifact.producer to result)).inputs.files)
            val changed = result.copy(outputs = TreeManifest(listOf(FileEntry(output.path, pluginDigest("changed")))))
            assertNotEquals(consumerNode.resolve(mapOf(artifact.producer to result)).key(Sha256),
                consumerNode.resolve(mapOf(artifact.producer to changed)).key(Sha256))
        }
    }

    @Test fun artifactPublicationsRejectForeignActionsUndeclaredOutputsAndDuplicates() {
        val output = fileOutput("data.bin", "generated-data")
        val other = ActionNode("main", emptySet(), { error("No recipe needed") }, ActionOutputContract(output))
        val provider = FixtureProvider(project = { _, _ -> pluginGraph(listOf(other)) })
        fun publish(make: (ProjectArtifact) -> List<ProjectArtifact>) = object : AdditivePlugin("producer"), ProjectGraphContributor {
            override fun contribute(context: ProjectBuildContext, graph: ProjectActionGraph) = listOf(other.copy(id = "owned"))
            override fun artifacts(context: ProjectBuildContext, graph: ProjectActionGraph) =
                make(ProjectArtifact("app", "jvm", "main", "owned", output))
        }
        for (plugin in listOf(
            publish { listOf(it.copy(producer = "main")) },
            publish { listOf(it.copy(producer = "missing")) },
            publish { listOf(it.copy(output = fileOutput("unknown.bin", "generated-data"))) },
            publish { listOf(it, it) },
        )) assertFails { composedProjectActionGraph(listOf(provider, plugin), pluginContext(), pluginRequest) }
        val foreign = object : AdditivePlugin("z.foreign"), ProjectGraphContributor {
            override fun contribute(context: ProjectBuildContext, graph: ProjectActionGraph) = emptyList<ActionNode>()
            override fun artifacts(context: ProjectBuildContext, graph: ProjectActionGraph) =
                listOf(graph.requireArtifact("app", "jvm", "main", "generated-data").copy(compilation = "test"))
        }
        assertFails { composedProjectActionGraph(listOf(provider, publish { listOf(it) }, foreign), pluginContext(), pluginRequest) }
    }


    @Test fun contributorsConsumeSemanticArtifactsAndScriptsRetainThem() {
        val context = pluginContext()
        var calls = 0
        val plugin = Contributor("consumer") { _, graph ->
            val classes = graph.requireArtifact("app", "jvm", "main", "jvm-classes")
            calls++
            listOf(ActionNode("consumer:bundle", setOf(classes.producer)) { results ->
                jvmClasspathAction(JvmClasspath("app", "main", listOf(LogicalPath("input"))),
                    classes.manifest(results, LogicalPath("input")), context.composition, context.toolchains.getValue("jvm"))
            })
        }
        val graph = composedProjectActionGraph(listOf(KotlinBuildPlugin(), plugin), context, pluginRequest)
        val classes = graph.requireArtifact("app", "jvm", "main", "jvm-classes")
        val result = ActionResult(classes.producer, pluginDigest("compile"), ActionStatus.LOCAL_HIT,
            TreeManifest(listOf(FileEntry(LogicalPath("classes/Main.class"), pluginDigest("bytecode")))), 0)
        val bundle = graph.nodes.single { it.id == "consumer:bundle" }.resolve(mapOf(classes.producer to result))
        assertEquals(listOf(LogicalPath("input/Main.class")), bundle.inputs.files.map { it.path })
        assertEquals(1, calls)
        // A named script keeps the semantic artifacts of its workspace prerequisites.
        val document = context.effective.document.objectValue().getValue("project") as JsonValue.Object
        val scriptProject = parseProjectConfiguration(JsonValue.Object(document.values + ("scripts" to obj("task" to obj(
            "sources" to listOf("task.kt".json()).json(), "dependencies" to listOf(obj("module" to "app".json())).json())))))
        val effective = effectiveConfiguration(scriptProject)
        val script = composedScriptActionGraph(listOf(KotlinBuildPlugin(), plugin),
            context.copy(effective = effective, lock = DependencyLock(effective.digest, emptyList())), ScriptGraphRequest("task"))
        assertEquals(classes, script.requireArtifact("app", "jvm", "main", "jvm-classes"))
        assertEquals(2, calls)
    }

    @Test fun artifactsRejectUndeclaredProducersAndAmbiguousQueries() {
        val output = treeOutput("classes", "jvm-classes")
        val artifact = ProjectArtifact("app", "jvm", "main", "compile", output)
        val node = ActionNode("compile", emptySet(), { error("No recipe required for artifact lookup") }, ActionOutputContract(output))
        val graph = pluginGraph(listOf(node), terminals = mapOf(pluginCheck to node.id)).copy(artifacts = listOf(artifact))
        assertFails { graph.copy(artifacts = listOf(artifact, artifact)) }
        assertFails { graph.copy(artifacts = listOf(artifact.copy(producer = "unknown"))) }
        assertFails { graph.copy(artifacts = listOf(artifact.copy(output = treeOutput("other", "jvm-classes")))) }
        val other = node.copy(id = "another")
        val ambiguous = graph.copy(nodes = listOf(node, other), artifacts = listOf(artifact, artifact.copy(producer = other.id)))
        assertEquals(2, ambiguous.findArtifacts("app", "jvm", "main", "jvm-classes").size)
        assertFails { ambiguous.requireArtifact("app", "jvm", "main", "jvm-classes") }
    }

    @Test fun artifactManifestsSelectAndRemapOnlySuccessfulDeclaredOutputs() {
        val artifact = ProjectArtifact("app", "jvm", "main", "compile", treeOutput("classes", "jvm-classes"))
        val file = FileEntry(LogicalPath("classes/pkg/Main.class"), pluginDigest("bytecode"), executable = true)
        fun result(files: List<FileEntry> = listOf(file), status: ActionStatus = ActionStatus.EXECUTED, id: String = "compile") =
            mapOf("compile" to ActionResult(id, pluginDigest("action"), status, TreeManifest(files), if (status == ActionStatus.FAILED) 1 else 0))
        val inputs = artifact.manifest(result(listOf(file, file.copy(path = LogicalPath("classes-other/Leak.class")),
            file.copy(path = LogicalPath("plugins/schema.json")))), LogicalPath("dependencies/app"))
        assertEquals(listOf(file.copy(path = LogicalPath("dependencies/app/pkg/Main.class"))), inputs.files)
        assertTrue(artifact.manifest(result(emptyList())).files.isEmpty())
        assertFails { artifact.manifest(emptyMap()) }
        assertFails { artifact.manifest(result(status = ActionStatus.FAILED)) }
        assertFails { artifact.manifest(result(id = "other")) }
        assertFails { artifact.manifest(result(listOf(file.copy(path = LogicalPath("classes"))))) }
        val library = artifact.copy(output = fileOutput("module.klib", "klib"))
        assertFails { library.manifest(result(emptyList())) }
        assertFails { library.manifest(result(listOf(file.copy(path = LogicalPath("module.klib/invalid"))))) }
        val klib = file.copy(path = LogicalPath("module.klib"))
        for (status in listOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT))
            assertEquals(listOf(klib.copy(path = LogicalPath("deps/app.klib"))),
                library.manifest(result(listOf(klib), status), LogicalPath("deps/app.klib")).files)
    }

    @Test fun providerTestLaunchesExposeArtifactsWithLegacyNodeConstructor() {
        val context = pluginContext()
        val launch = JvmTestLaunch(JvmTestSelection("app", listOf(LogicalPath("classes")), emptyList()))
        val provider = FixtureProvider(project = { _, _ ->
            pluginGraph(listOf(ActionNode("main", emptySet()) {
                launch.action(TreeManifest(emptyList()), pluginComposition, context.toolchains.getValue("jvm"))
            })).copy(testLaunches = mapOf("main" to launch))
        })
        val graph = composedProjectActionGraph(listOf(provider), context, pluginRequest)
        val artifact = graph.requireArtifact("app", "jvm", "test", "test-results")
        assertEquals(launch.outputContract.artifacts.single(), artifact.output)
        assertEquals(listOf(artifact.output), graph.nodes.single().outputContract!!.artifacts)
        val report = FileEntry(artifact.output.path, pluginDigest("test results"))
        val result = ActionResult(artifact.producer, pluginDigest("tests"), ActionStatus.EXECUTED, TreeManifest(listOf(report)), 0)
        assertEquals(listOf(report), artifact.manifest(mapOf(artifact.producer to result)).files)
        assertFails { artifact.manifest(mapOf(artifact.producer to result.copy(status = ActionStatus.FAILED, exitCode = 1))) }
    }

    @Test fun instrumentationContributorsEnrichOrdinaryTestsWithoutReplacingTheirChecks() {
        val context = pluginContext()
        val launch = JvmTestLaunch(JvmTestSelection("app", listOf(LogicalPath("classes")), listOf("fixture.Tests")))
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("classes/Tests.class"), pluginDigest("class"))))
        val provider = FixtureProvider(project = { _, _ ->
            pluginGraph(listOf(ActionNode("main", emptySet(), launch.outputContract) {
                launch.action(inputs, pluginComposition, context.toolchains.getValue("jvm"))
            })).copy(testLaunches = mapOf("main" to launch))
        })
        val agentFile = FileEntry(LogicalPath("agent.jar"), pluginDigest("agent"))
        fun contributor(name: String, actionId: String = "main", dependencies: Set<String> = setOf("agent-build")) =
            object : AdditivePlugin(name), ProjectTestInstrumentationContributor, ProjectGraphContributor {
                override fun contribute(context: ProjectBuildContext, graph: ProjectActionGraph): List<ActionNode> =
                    if (name == "a") listOf(pluginNode("agent-build")) else emptyList()
                override fun instrumentTests(context: ProjectBuildContext, graph: ProjectActionGraph): List<JvmTestInstrumentation> {
                    if (name == "z") {
                        assertEquals(listOf("a"), (graph.testLaunches.getValue("main") as JvmTestLaunch).selection.agents.map { it.id })
                        assertEquals(LogicalPath("instrumentation/a/report.bin"),
                            graph.requireArtifact("app", "jvm", "test", "test-instrumentation").output.path)
                    }
                    assertEquals("main", graph.requireArtifact("app", "jvm", "test", "test-results").producer)
                    return listOf(JvmTestInstrumentation(actionId, listOf(JvmTestAgent(name, agentFile.path,
                        outputs = listOf(LogicalPath("instrumentation/$name/report.bin")))), dependencies) { results ->
                        assertEquals(dependencies, results.keys)
                        TreeManifest(listOf(agentFile))
                    })
                }
            }
        val graph = composedProjectActionGraph(listOf(contributor("z"), provider, contributor("a")), context, pluginRequest)
        assertEquals(setOf(pluginCheck), graph.requestedChecks)
        assertEquals(mapOf(pluginCheck to "main"), graph.terminalActions)
        assertEquals(setOf("agent-build"), graph.contributedActions)
        val node = graph.nodes.single { it.id == "main" }
        assertEquals(setOf("agent-build"), node.dependencies)
        val prerequisite = ActionResult("agent-build", pluginDigest("build"), ActionStatus.EXECUTED, TreeManifest(listOf(agentFile)), 0)
        val action = node.resolve(mapOf("agent-build" to prerequisite))
        val instrumented = graph.testLaunches.getValue("main") as JvmTestLaunch
        assertEquals(listOf("a", "z"), instrumented.selection.agents.map { it.id })
        assertEquals(setOf("instrumentation/a/report.bin", "instrumentation/z/report.bin"),
            graph.findArtifacts("app", "jvm", "test", "test-instrumentation").map { it.output.path.value }.toSet())
        assertEquals(3, inspectActionGraph(graph).values.getValue("artifacts").arrayValue().size)
        assertEquals(launch.selection.classes, instrumented.selection.classes)
        assertEquals(launch.selection.classpath, instrumented.selection.classpath)
        instrumented.requireMatches(action)
        checkNotNull(node.outputContract).requireMatches(action)
        assertEquals(CachePolicy.NEVER, action.cachePolicy)
        assertEquals(listOf("classes/Tests.class", "agent.jar"), action.inputs.files.map { it.path.value })
        assertFails { node.resolve(emptyMap()) }
        assertFails { node.resolve(mapOf("agent-build" to prerequisite.copy(status = ActionStatus.FAILED))) }
        assertFails { composedProjectActionGraph(listOf(provider, contributor("a", "missing")), context, pluginRequest) }
        assertFails { composedProjectActionGraph(listOf(provider, contributor("a", dependencies = setOf("main"))), context, pluginRequest) }
        assertFalse(supportsCoreChangeSelection(listOf(provider, contributor("a"))))
        val inspected = inspectActionGraph(graph).values.getValue("actions").arrayValue()
            .single { it.objectValue().getValue("id").stringValue() == "main" }.objectValue()
        assertEquals(instrumented.toJson(), inspected.getValue("testLaunch"))
    }

    @Test fun instrumentationRejectsWrongLaunchesConflictingInputsAndHiddenRecipeChanges() {
        val context = pluginContext()
        val launch = JvmTestLaunch(JvmTestSelection("app", listOf(LogicalPath("classes")), listOf("fixture.Tests")))
        val file = FileEntry(LogicalPath("classes/Tests.class"), pluginDigest("class"))
        val inputs = TreeManifest(listOf(file, FileEntry(LogicalPath("agent.jar"), pluginDigest("agent"))))
        val node = ActionNode("main", emptySet(), launch.outputContract) { launch.action(inputs, pluginComposition, context.toolchains.getValue("jvm")) }
        val instrumentation = JvmTestInstrumentation("main", listOf(JvmTestAgent("agent", LogicalPath("agent.jar")))) {
            TreeManifest(listOf(file.copy(digest = pluginDigest("conflict"))))
        }
        assertFails { instrumentJvmTest(node, launch, instrumentation).first.resolve(emptyMap()) }
        val native = NativeTestLaunch("app", NativeTarget.MACOS_ARM64, LogicalPath("tests.kexe"))
        assertFails { instrumentJvmTest(node, native, instrumentation) }
        val drift = node.copy(resolve = { launch.action(inputs, pluginComposition, context.toolchains.getValue("jvm")).copy(timeoutMillis = 1) })
        assertFails { instrumentJvmTest(drift, launch, instrumentation).first.resolve(emptyMap()) }
    }
    @Test fun providerLaunchesReachContributorsAndRejectConflictingRecipes() {
        val launch = JvmTestLaunch(JvmTestSelection("app", listOf(LogicalPath("classes")), listOf("fixture.Tests")))
        val context = pluginContext()
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("classes/Tests.class"), pluginDigest("class"))))
        fun provider(drift: Boolean = false) = FixtureProvider(project = { _, _ ->
            pluginGraph(listOf(ActionNode("main", emptySet()) {
                val action = launch.action(inputs, pluginComposition, context.toolchains.getValue("jvm"))
                if (drift) action.copy(timeoutMillis = 1) else action
            })).copy(testLaunches = mapOf("main" to launch))
        })
        val contributor = Contributor("observer") { _, graph ->
            assertSame(launch, graph.testLaunches.getValue("main"))
            emptyList()
        }
        val graph = composedProjectActionGraph(listOf(provider(), contributor), context, pluginRequest)
        assertSame(launch, graph.testLaunches.getValue("main"))
        launch.requireMatches(graph.nodes.single().resolve(emptyMap()))
        val invalid = composedProjectActionGraph(listOf(provider(true)), context, pluginRequest)
        assertFailsWith<IllegalArgumentException> { invalid.nodes.single().resolve(emptyMap()) }
    }
    @Test fun compositionPreservesGeneratedSourceOriginsAlongsideContributedConsumers() {
        val origin = GeneratedSourceRoot("main", "app", "main", "custom", "/modules/0", LogicalPath("generated"))
        val provider = FixtureProvider(project = { _, _ -> pluginGraph().copy(generatedSources = listOf(origin)) })
        val contributor = Contributor("extra") { _, graph ->
            assertEquals(listOf(origin), graph.generatedSources)
            listOf(pluginNode("consumer", setOf("main")))
        }
        val graph = composedProjectActionGraph(listOf(provider, contributor), pluginContext(), pluginRequest)
        assertEquals(listOf(origin), graph.generatedSources)
        val inspected = inspectActionGraph(graph).values.getValue("actions").arrayValue()
        assertEquals(listOf("consumer".json()), inspected.single { it.objectValue().getValue("id") == "main".json() }
            .objectValue().getValue("output").objectValue().getValue("resultConsumers").arrayValue())
    }

    @Test fun impactSelectionRequiresAnExplicitProviderContractAndNoContributors() {
        assertTrue(supportsCoreChangeSelection(listOf(KotlinBuildPlugin())))
        assertFalse(supportsCoreChangeSelection(emptyList()))
        assertFalse(supportsCoreChangeSelection(listOf(FixtureProvider())))
        assertFalse(supportsCoreChangeSelection(listOf(KotlinBuildPlugin(), AdditivePlugin("extra"))))
    }

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
        assertEquals(setOf("extra-a", "extra-z"), graph.contributedActions)
        assertEquals(setOf("extra-a"), graph.nodes.last().dependencies)
        assertEquals(listOf(LogicalPath("extra-z.txt")), graph.nodes.last().resolve(emptyMap()).outputs)
        calls.clear()
        assertEquals(graph.nodes.map { it.id }, composedProjectActionGraph(listOf(first, second, provider), context, pluginRequest).nodes.map { it.id })
        assertEquals(listOf("provider", "a", "z"), calls)
    }

    @Test fun graphDependenciesOrderTransitiveAndLegacyCallbacksWithStableReadyIds() {
        val calls = mutableListOf<String>()
        val provider = FixtureProvider(project = { _, _ -> calls += "provider"; pluginGraph() })
        fun contributor(id: String, dependencies: Set<String> = emptySet()) = object : AdditivePlugin(id), ProjectGraphContributor {
            override val graphDependencies = dependencies
            override fun contribute(context: ProjectBuildContext, graph: ProjectActionGraph): List<ActionNode> {
                calls += id
                return emptyList()
            }
            override fun artifacts(context: ProjectBuildContext, graph: ProjectActionGraph): List<ProjectArtifact> {
                calls += "$id:artifacts"
                return emptyList()
            }
        }
        val a = contributor("a", setOf("z", "provider"))
        val b = contributor("b")
        val y = contributor("y")
        val z = object : AdditivePlugin("z") {
            override val graphDependencies = setOf("y")
            override fun actions(project: JsonValue.Object): List<ActionNode> {
                calls += "z"
                return emptyList()
            }
        }
        for (registry in listOf(listOf(a, b, y, z, provider), listOf(provider, z, y, b, a))) {
            calls.clear()
            composedProjectActionGraph(registry, pluginContext(), pluginRequest)
            assertEquals(listOf("provider", "b", "b:artifacts", "y", "y:artifacts", "z", "a", "a:artifacts"), calls)
        }
        assertEquals(listOf("provider", "z"), a.descriptor().values.getValue("graphDependencies").arrayValue().map { it.stringValue() })
        assertEquals(emptyList(), b.descriptor().values.getValue("graphDependencies").arrayValue())
    }

    @Test fun invalidPluginDependenciesFailBeforeGraphAndGenerationCallbacks() {
        var called = false
        val provider = FixtureProvider(project = { _, _ -> called = true; pluginGraph() })
        fun plugin(id: String, dependencies: Set<String>) = object : AdditivePlugin(id), ProjectGenerationContributor, ConfigurableBuildPlugin {
            override val graphDependencies = dependencies
            override val settingsSchema = obj()
            override fun validateSettings(settings: JsonValue.Object) { called = true }
            override fun generatedInputs(context: ProjectBuildContext): ProjectGeneratedInputs {
                called = true
                return ProjectGeneratedInputs()
            }
        }
        val cases = listOf(
            listOf(provider, plugin("a", setOf("missing"))) to "Unknown graph dependency for build plugin a: missing",
            listOf(provider, plugin("a", setOf("a"))) to "a -> a",
            listOf(provider, plugin("a", setOf("b")), plugin("b", setOf("c")), plugin("c", setOf("a"))) to "a -> b -> c -> a",
        )
        for ((plugins, message) in cases) {
            assertContains(assertFailsWith<IllegalArgumentException> {
                composedProjectActionGraph(plugins, pluginContext(), pluginRequest)
            }.message.orEmpty(), message)
            assertFalse(called)
            assertFails { composedScriptActionGraph(plugins, pluginContext(), ScriptGraphRequest("task")) }
            assertFalse(called)
        }
        val dependentProvider = object : AdditivePlugin("provider"), ProjectBuildPlugin {
            override val graphDependencies = setOf("a")
            override fun projectGraph(context: ProjectBuildContext, request: ProjectGraphRequest): ProjectActionGraph {
                called = true
                return pluginGraph()
            }
            override fun scriptGraph(context: ProjectBuildContext, request: ScriptGraphRequest): ProjectActionGraph = error("Not called")
        }
        assertContains(assertFails { composedProjectActionGraph(listOf(dependentProvider, plugin("a", emptySet())),
            pluginContext(), pluginRequest) }.message.orEmpty(), "provider runs first")
        assertFalse(called)
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

    @Test fun differentChecksCannotShareOneTerminal() {
        val request = pluginRequest.copy(modules = setOf("app", "other"))
        val checks = projectRequestedChecks(request)
        val provider = FixtureProvider(project = { _, _ -> pluginGraph(checks = checks,
            terminals = checks.associateWith { "main" }) })
        assertFailsWith<IllegalArgumentException> {
            composedProjectActionGraph(listOf(provider), pluginContext(), request)
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

    @Test fun contributorsCannotExecuteTestsOrScriptsWithoutDeclaringChecks() {
        for (kind in listOf(ActionKind.TEST, ActionKind.SCRIPT)) {
            val node = ActionNode("extra", emptySet()) {
                pluginNode("extra", policy = CachePolicy.NEVER).resolve(emptyMap()).copy(kind = kind)
            }
            val graph = composedProjectActionGraph(listOf(FixtureProvider(), Contributor("extra") { _, _ -> listOf(node) }),
                pluginContext(), pluginRequest)
            assertFailsWith<IllegalArgumentException> { graph.nodes.last().resolve(emptyMap()) }
        }
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
