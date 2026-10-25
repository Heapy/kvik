package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kvik.compiler.jvm.*
import kvik.compiler.js.*
import kvik.compiler.native.*
import kotlin.test.*

class ScriptGeneratorGraphTest {
    private fun digest(value: String) = Sha256.digest(value.encodeToByteArray())
    private val tools = listOf("jvm", "js").associateWith { listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith(::digest) } +
        mapOf("macosArm64" to listOf("native", "jdk", "sdk", "native-dependencies").associateWith(::digest))
    private val generatorId = "script:generate:jvm:generate"
    private val scriptCompileId = "script:generate:jvm:compile"
    private val generated = LogicalPath("generated/scripts/generate/Generated.kt")

    private fun module(id: String, targets: Set<String>, generatedOnly: Boolean = true): JsonValue {
        fun source(name: String, parents: List<String> = emptyList()) = obj("name" to name.json(),
            "directories" to (if (generatedOnly) emptyList() else listOf("$id/$name".json())).json(),
            "resources" to emptyList<JsonValue>().json(), "dependsOn" to parents.map { it.json() }.json(), "dependencies" to emptyList<JsonValue>().json())
        return obj("id" to id.json(), "sourceSets" to (listOf(source("commonMain")) + targets.flatMap {
            listOf(source("${it}Main", listOf("commonMain")), source("${it}Test", listOf("${it}Main")))
        }).json(), "targets" to targets.map { obj("name" to it.json(), "main" to "${it}Main".json(), "test" to "${it}Test".json(),
            "compilerOptions" to emptyList<JsonValue>().json()) }.json(), "compilerPlugins" to emptyList<JsonValue>().json())
    }
    private fun spec(kts: Boolean = false, sourceSet: String = "commonMain") = ScriptSpec(
        listOf(LogicalPath(if (kts) "scripts/generate.kts" else "scripts/Generate.kt")),
        entryScript = if (kts) LogicalPath("scripts/generate.kts") else null,
        generator = ScriptGeneratorSpec("library", sourceSet, listOf(LogicalPath("schema")), listOf("literal arg")))
    private fun configuration(targets: Set<String> = setOf("jvm"), script: ScriptSpec = spec(),
                              generatedOnly: Boolean = true, extraModules: List<JsonValue> = emptyList(), extraScripts: Map<String, ScriptSpec> = emptyMap()) =
        parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "fixture".json(),
            "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
            "modules" to (listOf(module("library", targets, generatedOnly)) + extraModules).json(),
            "scripts" to JsonValue.Object((extraScripts + ("generate" to script)).mapValues { it.value.toJson() })))
    private fun files(kts: Boolean = false, data: String = "input", extra: List<String> = emptyList()) = TreeManifest(
        listOf(FileEntry(LogicalPath(if (kts) "scripts/generate.kts" else "scripts/Generate.kt"), digest("script")),
            FileEntry(LogicalPath("schema/api.json"), digest(data))) + extra.map { FileEntry(LogicalPath(it), digest(it)) })
    private fun graph(configuration: ProjectConfiguration = configuration(), files: TreeManifest = files(), targets: Set<String> = setOf("jvm"),
                      mode: ProjectMode = ProjectMode.BUILD, modules: Set<String> = setOf("library")): ProjectActionGraph {
        val effective = effectiveConfiguration(configuration)
        val lock = DependencyLock(effective.digest, effective.requests.map { request ->
            ScopedSelection(request.domain, LockedSelection(request.coordinate, request.variant,
                listOf(LockedArtifact(request.coordinate, "fixture", "central", "https://repo.maven.apache.org/maven2/${request.coordinate.name}.jar", digest(request.coordinate.toString()))),
                listOf(MetadataIdentity("https://repo.maven.apache.org/maven2/${request.coordinate.name}.pom", digest("metadata")))))
        })
        return projectActionGraph(effective, lock, files, digest("runtime"), tools, ProjectGraphRequest(modules, targets, mode))
    }
    private fun resolve(graph: ProjectActionGraph, generatedContents: String = "generated", invalidOutput: Boolean = false): Map<String, ActionSpec> {
        val results = mutableMapOf<String, ActionResult>()
        return graph.nodes.filterNot { it.id.endsWith(":package") }.associate { node ->
            val action = node.resolve(results.filterKeys { it in node.dependencies })
            val output = when {
                node.id.endsWith(":generate") -> listOf(FileEntry(LogicalPath(if (invalidOutput) "generated/unexpected.txt" else "generated/Generated.kt"), digest(generatedContents)))
                action.implementation == "kvik-native-compiler-v1" -> listOf(FileEntry(NativeCompilation.fromJson(action.parameters).output, digest(action.key(Sha256).toString())))
                action.implementation == "kvik-js-compiler-v1" -> listOf(FileEntry(JsCompilation.fromJson(action.parameters).output, digest(action.key(Sha256).toString())))
                action.implementation == "kvik-kotlin-script-compiler-v1" -> listOf(
                    FileEntry(LogicalPath("classes/Generate.class"), digest(action.key(Sha256).toString())), FileEntry(LogicalPath("classes/kvik-script.json"), digest("descriptor")))
                else -> listOf(FileEntry(LogicalPath("${JvmCompilation.fromJson(action.parameters).output.value}/Main.class"), digest(action.key(Sha256).toString())))
            }
            results[node.id] = ActionResult(node.id, action.key(Sha256), ActionStatus.EXECUTED, TreeManifest(output), 0)
            node.id to action
        }
    }

    @Test fun oneFreshGenerationFeedsGeneratedOnlyCommonSourcesAcrossJvmNativeAndJs() {
        val targets = setOf("jvm", "macosArm64", "js")
        val plan = graph(configuration(targets), targets = targets)
        assertEquals(1, plan.nodes.count { it.id == generatorId })
        assertEquals(1, plan.nodes.count { it.id == scriptCompileId })
        val actions = resolve(plan)
        assertEquals(ActionKind.GENERATE, actions.getValue(generatorId).kind)
        assertEquals(CachePolicy.NEVER, actions.getValue(generatorId).cachePolicy)
        for (target in targets) {
            assertContains(plan.nodes.single { it.id == "library:$target:main" }.dependencies, generatorId)
            val action = actions.getValue("library:$target:main")
            assertEquals(listOf(generated), when (target) {
                "jvm" -> JvmCompilation.fromJson(action.parameters).sources
                "macosArm64" -> NativeCompilation.fromJson(action.parameters).sources
                else -> JsCompilation.fromJson(action.parameters).sources
            })
            assertFalse(action.inputs.files.any { it.path.value.startsWith("inputs/") || it.path.value.startsWith("scripts/") })
        }
        val native = NativeCompilation.fromJson(actions.getValue("library:macosArm64:main").parameters)
        assertContains(native.commonSources, generated)
        assertContains(native.sourceFragments.single { it.name == "commonMain" }.sources, generated)
        assertEquals(1, native.sourceFragments.count { generated in it.sources })
        assertContains(JvmCompilation.fromJson(actions.getValue("library:jvm:main").parameters).commonSources, generated)
        assertContains(JsCompilation.fromJson(actions.getValue("library:js:main").parameters).commonSources, generated)
        assertFailsWith<IllegalArgumentException> { resolve(plan, invalidOutput = true) }
    }

    @Test fun dataChangesDoNotRecompileGeneratorAndOnlyOutputChangesInvalidateConsumer() {
        val baseline = resolve(graph())
        val changedInput = resolve(graph(files = files(data = "changed input")))
        assertEquals(baseline.getValue(scriptCompileId).key(Sha256), changedInput.getValue(scriptCompileId).key(Sha256))
        assertNotEquals(baseline.getValue(generatorId).key(Sha256), changedInput.getValue(generatorId).key(Sha256))
        assertEquals(baseline.getValue("library:jvm:main").key(Sha256), changedInput.getValue("library:jvm:main").key(Sha256))
        val changedOutput = resolve(graph(), generatedContents = "changed source")
        assertNotEquals(baseline.getValue("library:jvm:main").key(Sha256), changedOutput.getValue("library:jvm:main").key(Sha256))
        val launch = JvmScriptLaunch.fromJson(baseline.getValue(generatorId).parameters)
        assertEquals(listOf("generated", "inputs", "literal arg"), launch.arguments)
        assertContains(baseline.getValue(generatorId).inputs.files.map { it.path }, LogicalPath("inputs/schema/api.json"))
    }

    @Test fun kotlinScriptGeneratorCanAttachOnlyToJvmTests() {
        val config = configuration(script = spec(kts = true, sourceSet = "jvmTest"), generatedOnly = false)
        val inputs = files(kts = true, extra = listOf("library/jvmMain/Main.kt"))
        val build = graph(config, inputs)
        assertFalse(build.nodes.any { it.id == generatorId })
        val plan = graph(config, inputs, mode = ProjectMode.COMPILE_TESTS)
        assertFalse(generatorId in plan.nodes.single { it.id == "library:jvm:main" }.dependencies)
        assertContains(plan.nodes.single { it.id == "library:jvm:test-compile" }.dependencies, generatorId)
        val actions = resolve(plan)
        assertEquals("kvik-kotlin-script-compiler-v1", actions.getValue(scriptCompileId).implementation)
        assertEquals(listOf("generated", "inputs", "literal arg"), KtsLaunch.fromJson(actions.getValue(generatorId).parameters).arguments)
        assertEquals(listOf(generated), JvmCompilation.fromJson(actions.getValue("library:jvm:test-compile").parameters).sources)
        assertFalse(generated in JvmCompilation.fromJson(actions.getValue("library:jvm:main").parameters).sources)
    }

    @Test fun generatorModulePrerequisiteCanHaveItsOwnGenerator() {
        val script = spec().copy(dependencies = listOf(ScriptDependencyReference.Module("zhelper")))
        val nested = ScriptSpec(listOf(LogicalPath("scripts/Nested.kt")), generator = ScriptGeneratorSpec("zhelper", "commonMain"))
        val config = configuration(script = script, extraModules = listOf(module("zhelper", setOf("jvm"))), extraScripts = mapOf("nested" to nested))
        val plan = graph(config, files(extra = listOf("scripts/Nested.kt")))
        assertContains(plan.nodes.single { it.id == "zhelper:jvm:main" }.dependencies, "script:nested:jvm:generate")
        assertContains(plan.nodes.single { it.id == scriptCompileId }.dependencies, "zhelper:jvm:main")
        val actions = resolve(plan)
        assertContains(JvmCompilation.fromJson(actions.getValue("zhelper:jvm:main").parameters).sources, LogicalPath("generated/scripts/nested/Generated.kt"))
        assertContains(JvmCompilation.fromJson(actions.getValue("library:jvm:main").parameters).sources, generated)
        // library is visited first; its script contributes zhelper before that module's own test request.
        val tests = graph(config, files(extra = listOf("scripts/Nested.kt")), mode = ProjectMode.COMPILE_TESTS,
            modules = setOf("library", "zhelper"))
        assertEquals(setOf("library:jvm:compile-tests", "zhelper:jvm:compile-tests"), tests.requestedChecks)
        assertEquals(1, tests.nodes.count { it.id == "zhelper:jvm:main" })
        resolve(tests)
    }
}
