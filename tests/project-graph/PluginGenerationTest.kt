package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kvik.compiler.jvm.*
import kvik.compiler.native.*
import kvik.compiler.js.*
import kvik.plugins.kotlin.KotlinBuildPlugin
import kotlin.test.*

class PluginGenerationTest {
    private fun digest(value: String) = Sha256.digest(value.encodeToByteArray())
    private val composition = digest("composition")
    private val producer = "plugin:example.codegen:generate:sources"
    private val tools = listOf("jvm", "js").associateWith {
        listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith(::digest)
    } + mapOf("macosArm64" to listOf("native", "jdk", "sdk", "native-dependencies").associateWith(::digest))
    private fun config(): ProjectConfiguration {
        val targets = listOf("jvm", "js", "macosArm64")
        fun source(name: String, parents: List<String>) = obj("name" to name.json(),
            "directories" to emptyList<JsonValue>().json(), "resources" to emptyList<JsonValue>().json(),
            "dependsOn" to parents.map { it.json() }.json(), "dependencies" to emptyList<JsonValue>().json())
        return parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "generated".json(),
            "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.example.test/maven".json())).json(),
            "modules" to listOf(obj("id" to "app".json(), "sourceSets" to (listOf(source("commonMain", emptyList())) +
                targets.flatMap { listOf(source("${it}Main", listOf("commonMain")), source("${it}Test", listOf("${it}Main"))) }).json(),
                "targets" to targets.map { obj("name" to it.json(), "main" to "${it}Main".json(), "test" to "${it}Test".json(),
                    "compilerOptions" to emptyList<JsonValue>().json()) }.json(), "compilerPlugins" to emptyList<JsonValue>().json())).json()))
    }
    private fun declarations(sourceSet: String = "commonMain"): ProjectGeneratedInputs {
        val node = ActionNode(producer, emptySet(), ActionOutputContract(treeOutput("kotlin", "kotlin-sources"), treeOutput("assets", "resources"))) {
            ActionSpec("example.codegen:generate-v1", composition, ActionKind.GENERATE, mapOf("os" to "fixture"), "jvm", emptyMap(),
                TreeManifest(emptyList()), listOf(LogicalPath("kotlin"), LogicalPath("assets")), emptyList(), emptyMap())
        }
        return ProjectGeneratedInputs(listOf(node),
            listOf(GeneratedSourceRoot(producer, "app", sourceSet, "build-plugin", "/pluginSettings/example.codegen", LogicalPath("kotlin"))),
            listOf(GeneratedResourceRoot(producer, "app", sourceSet, "/pluginSettings/example.codegen", LogicalPath("assets"), listOf(LogicalPath("settings.json")))))
    }
    private fun graph(inputs: ProjectGeneratedInputs = declarations(), mode: ProjectMode = ProjectMode.BUILD,
                      targets: Set<String> = setOf("jvm", "js", "macosArm64")): ProjectActionGraph {
        val effective = effectiveConfiguration(config())
        val plugin = object : ProjectGenerationContributor {
            override val id = "example.codegen"
            override val apiVersion = CONTRACT_VERSION
            override val configurationSchema = obj()
            override fun generatedInputs(context: ProjectBuildContext) = inputs
        }
        return composedProjectActionGraph(listOf(KotlinBuildPlugin(), plugin),
            ProjectBuildContext(effective, DependencyLock(effective.digest, emptyList()), TreeManifest(emptyList()), composition, tools),
            ProjectGraphRequest(setOf("app"), targets, mode))
    }
    private fun result(files: List<FileEntry>) = mapOf(producer to ActionResult(producer, digest("key"), ActionStatus.EXECUTED, TreeManifest(files), 0))
    private fun output(source: String = "source") = listOf(FileEntry(LogicalPath("kotlin/Generated.kt"), digest(source)),
        FileEntry(LogicalPath("assets/settings.json"), digest("resource")))

    @Test fun nativeGeneratorFeedsCommonSourcesAndResourcesAcrossAllCompilerAdapters() {
        val graph = graph()
        val root = graph.generatedSources.single()
        val generated = LogicalPath(pluginSourcePrefix(root) + "Generated.kt")
        assertEquals(setOf(producer), graph.contributedActions)
        assertEquals(1, graph.nodes.count { it.id == producer })
        assertEquals(1, inspectActionGraph(graph).values.getValue("generatedResources").arrayValue().size)
        for (target in listOf("jvm", "js", "macosArm64")) {
            val node = graph.nodes.single { it.id == "app:$target:main" }
            assertEquals(setOf(producer), node.dependencies)
            val action = node.resolve(result(output()))
            assertContains(action.inputs.files.map { it.path }, generated)
            assertContains(action.inputs.files.map { it.path }, LogicalPath("runtime/app/main/settings.json"))
            val common = when (target) {
                "jvm" -> JvmCompilation.fromJson(action.parameters).commonSources
                "js" -> JsCompilation.fromJson(action.parameters).commonSources
                else -> NativeCompilation.fromJson(action.parameters).let {
                    assertContains(it.sourceFragments.single { it.name == "commonMain" }.sources, generated)
                    it.commonSources
                }
            }
            assertContains(common, generated)
            assertNotEquals(action.key(Sha256), node.resolve(result(output("edited source"))).key(Sha256))
            assertFailsWith<IllegalArgumentException> { node.resolve(result(output().dropLast(1))) }
            assertFailsWith<IllegalArgumentException> { node.resolve(result(output().map { it.copy(executable = true) })) }
            assertFailsWith<IllegalArgumentException> { node.resolve(result(output() + FileEntry(LogicalPath("kotlin/Bad.java"), digest("java")))) }
        }
    }

    @Test fun testOnlyGenerationIsNotScheduledDuringProductionBuilds() {
        val base = declarations()
        val testId = "plugin:example.codegen:generate:tests"
        val test = declarations("jvmTest").let { inputs -> ProjectGeneratedInputs(
            inputs.nodes.map { it.copy(id = testId) }, inputs.sources.map { it.copy(producer = testId) },
            inputs.resources.map { it.copy(producer = testId) }) }
        val combined = ProjectGeneratedInputs(base.nodes + test.nodes, base.sources + test.sources, base.resources + test.resources)
        val main = graph(combined, targets = setOf("jvm"))
        assertFalse(main.nodes.any { it.id == testId })
        val tests = graph(combined, ProjectMode.COMPILE_TESTS, setOf("jvm"))
        assertContains(tests.nodes.single { it.id == "app:jvm:test-compile" }.dependencies, testId)
        assertFalse(testId in tests.nodes.single { it.id == "app:jvm:main" }.dependencies)
    }

    @Test fun invalidProducerGraphsAndDeclarationsFailBeforeExecution() {
        val inputs = declarations()
        val node = inputs.nodes.single()
        for (invalid in listOf(
            inputs.copy(nodes = listOf(node.copy(id = "app:jvm:main"))),
            inputs.copy(nodes = listOf(node, node)),
            inputs.copy(nodes = listOf(node.copy(dependencies = setOf(producer)))),
            inputs.copy(nodes = listOf(node.copy(dependencies = setOf("missing")))),
            inputs.copy(nodes = listOf(node.copy(outputContract = null))),
            inputs.copy(sources = inputs.sources.map { it.copy(sourceSet = "missing") }),
            inputs.copy(resources = inputs.resources.map { it.copy(outputRoot = LogicalPath("kotlin")) }),
            inputs.copy(resources = inputs.resources.map { it.copy(files = listOf(LogicalPath("a"), LogicalPath("A"))) }),
        )) assertFailsWith<IllegalArgumentException> { graph(invalid) }
    }

    @Test fun generatedResourcesRejectStaticAndGeneratedOverlays() {
        val configuration = config()
        val roots = declarations().resources
        assertFailsWith<IllegalArgumentException> {
            moduleResources(configuration.project, emptyList(), "app", "jvm", "main", roots + roots)
        }
        val selected = moduleResources(configuration.project, emptyList(), "app", "jvm", "main", roots)
        assertFailsWith<IllegalArgumentException> { selected.resolve(result(output() + FileEntry(LogicalPath("assets/extra"), digest("extra")))) }
    }
}
