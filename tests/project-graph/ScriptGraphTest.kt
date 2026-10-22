package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kvik.compiler.jvm.*
import kotlin.test.*

private fun scriptDigest(text: String) = Sha256.digest(text.encodeToByteArray())
private val scriptTools = listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith(::scriptDigest)
private fun scriptModule(name: String, dependencies: List<JsonValue> = emptyList()): JsonValue = obj("id" to name.json(),
    "sourceSets" to listOf(obj("name" to "main".json(), "directories" to listOf("$name/src".json()).json(),
        "resources" to listOf("$name/resources".json()).json(), "dependsOn" to emptyList<JsonValue>().json(), "dependencies" to dependencies.json())).json(),
    "targets" to listOf(obj("name" to "jvm".json(), "main" to "main".json(), "test" to JsonValue.Null, "compilerOptions" to emptyList<JsonValue>().json())).json(),
    "compilerPlugins" to emptyList<JsonValue>().json())
private fun scriptConfig(spec: ScriptSpec, modules: List<JsonValue> = emptyList()): ProjectConfiguration = parseProjectConfiguration(obj(
    "schemaVersion" to 1.json(), "name" to "fixture".json(), "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
    "modules" to modules.json(), "scripts" to obj("task" to spec.toJson())))
private fun scriptLock(effective: EffectiveConfiguration): DependencyLock = DependencyLock(effective.digest, effective.requests.map { request ->
    ScopedSelection(request.domain, LockedSelection(request.coordinate, request.variant,
        listOf(LockedArtifact(request.coordinate, "fixture", "central", "https://repo.maven.apache.org/maven2/${request.coordinate.name}.jar", scriptDigest(request.coordinate.toString()))),
        listOf(MetadataIdentity("https://repo.maven.apache.org/maven2/${request.coordinate.name}.pom", scriptDigest("metadata")))))
})
private fun scriptFiles(vararg paths: String) = TreeManifest(paths.map { FileEntry(LogicalPath(it), scriptDigest(it)) })
private fun scriptPlan(spec: ScriptSpec, files: TreeManifest = scriptFiles("task.kt"), modules: List<JsonValue> = emptyList(), args: List<String> = emptyList()): ProjectActionGraph {
    val effective = effectiveConfiguration(scriptConfig(spec, modules))
    return scriptActionGraph(effective, scriptLock(effective), files, scriptDigest("composition"), scriptTools, ScriptGraphRequest("task", args))
}
private fun scriptActions(graph: ProjectActionGraph, changedOutput: String? = null): Map<String, ActionSpec> {
    val results = linkedMapOf<String, ActionResult>()
    val actions = linkedMapOf<String, ActionSpec>()
    for (node in graph.nodes) {
        val action = node.resolve(results.filterKeys { it in node.dependencies })
        actions[node.id] = action
        val outputs = if (action.kind == ActionKind.COMPILE) TreeManifest(listOf(FileEntry(LogicalPath("classes/MainKt.class"),
            scriptDigest(node.id + if (node.id == changedOutput) ":changed" else "")))) else TreeManifest(emptyList())
        results[node.id] = ActionResult(node.id, action.key(Sha256), ActionStatus.EXECUTED, outputs, 0)
    }
    return actions
}
private val scriptSpec = ScriptSpec(listOf(LogicalPath("task.kt")))
private const val scriptCompile = "script:task:jvm:compile"
private const val scriptRun = "script:task:jvm:run"

class ScriptGraphTest {
    @Test fun scriptUsesDistinctChecksAndNeverCachesExecution() {
        val graph = scriptPlan(scriptSpec)
        assertEquals(setOf(scriptRun), graph.requestedChecks)
        assertEquals(mapOf(scriptRun to scriptRun), graph.terminalActions)
        assertEquals(listOf(scriptCompile, scriptRun), graph.nodes.map { it.id })
        val actions = scriptActions(graph)
        assertEquals(CachePolicy.LOCAL, actions.getValue(scriptCompile).cachePolicy)
        assertEquals(ActionKind.SCRIPT, actions.getValue(scriptRun).kind)
        assertEquals(CachePolicy.NEVER, actions.getValue(scriptRun).cachePolicy)
        assertEquals(null, JvmScriptLaunch.fromJson(actions.getValue(scriptRun).parameters).mainClass)
        assertFailsWith<IllegalArgumentException> { actions.getValue(scriptRun).copy(cachePolicy = CachePolicy.LOCAL) }
    }

    @Test fun invocationSettingsNeverChangeCompilationIdentity() {
        val baseline = scriptActions(scriptPlan(scriptSpec))
        val spec = scriptSpec.copy(mainClass = "MainKt", environment = mapOf("MESSAGE" to "value"), timeoutMillis = 1234)
        val arguments = listOf("", "--flag", "spaces and $ literal", "кириллица")
        val changed = scriptActions(scriptPlan(spec, args = arguments))
        assertEquals(baseline.getValue(scriptCompile).key(Sha256), changed.getValue(scriptCompile).key(Sha256))
        assertNotEquals(baseline.getValue(scriptRun).key(Sha256), changed.getValue(scriptRun).key(Sha256))
        val action = changed.getValue(scriptRun)
        assertEquals(arguments, action.arguments)
        assertEquals(arguments, JvmScriptLaunch.fromJson(action.parameters).arguments)
        assertEquals(mapOf("MESSAGE" to "value"), action.environment)
        assertEquals(1234L, action.timeoutMillis)
    }

    @Test fun sourceHelperResourceOptionsAndRuntimeCompositionInvalidateCompilation() {
        val spec = scriptSpec.copy(sources = listOf(LogicalPath("task.kt"), LogicalPath("helpers")), resources = listOf(LogicalPath("resources")))
        val files = scriptFiles("task.kt", "helpers/Util.kt", "resources/config.json")
        val baseline = scriptActions(scriptPlan(spec, files)).getValue(scriptCompile).key(Sha256)
        for (path in files.files.map { it.path }) {
            val changed = TreeManifest(files.files.map { if (it.path == path) it.copy(digest = scriptDigest("changed")) else it })
            assertNotEquals(baseline, scriptActions(scriptPlan(spec, changed)).getValue(scriptCompile).key(Sha256), path.value)
        }
        assertNotEquals(baseline, scriptActions(scriptPlan(spec.copy(compilerOptions = listOf("-Werror")), files)).getValue(scriptCompile).key(Sha256))
        val effective = effectiveConfiguration(scriptConfig(spec))
        val composition = scriptActionGraph(effective, scriptLock(effective), files, scriptDigest("changed runtime"), scriptTools, ScriptGraphRequest("task"))
        assertNotEquals(baseline, scriptActions(composition).getValue(scriptCompile).key(Sha256))
    }

    @Test fun modulePrivateAndRuntimeDependenciesStayOutOfScriptCompileClasspath() {
        val modules = listOf(scriptModule("task", listOf(obj("module" to "hidden".json(), "scope" to "compile".json(), "exported" to false.json()),
            obj("coordinate" to "g:private:1".json(), "scope" to "compile".json(), "exported" to false.json()),
            obj("coordinate" to "g:public:1".json(), "scope" to "compile".json(), "exported" to true.json()))),
            scriptModule("hidden"), scriptModule("runtime"), scriptModule("unused"))
        val spec = scriptSpec.copy(dependencies = listOf(ScriptDependencyReference.Module("task"), ScriptDependencyReference.Module("runtime", DependencyScopeKind.RUNTIME)))
        val graph = scriptPlan(spec, scriptFiles("task.kt", "task/src/Task.kt", "hidden/src/Hidden.kt", "runtime/src/Runtime.kt", "task/resources/config.txt"), modules)
        assertFalse(graph.nodes.any { it.id.startsWith("unused:") })
        assertTrue(graph.nodes.any { it.id == "task:jvm:main" })
        val actions = scriptActions(graph)
        val compile = JvmCompilation.fromJson(actions.getValue(scriptCompile).parameters)
        assertTrue(LogicalPath("modules/task/jvm/main") in compile.classpath)
        for (name in listOf("hidden", "runtime")) assertFalse(LogicalPath("modules/$name/jvm/main") in compile.classpath)
        assertTrue(actions.getValue(scriptCompile).inputs.files.none { it.digest == scriptDigest("g:private:1") })
        assertTrue(actions.getValue(scriptCompile).inputs.files.any { it.digest == scriptDigest("g:public:1") })
        val runtime = actions.getValue(scriptRun)
        val launch = JvmScriptLaunch.fromJson(runtime.parameters)
        for (name in listOf("task", "hidden", "runtime")) assertTrue(LogicalPath("modules/$name/jvm/main") in launch.classpath)
        assertTrue(runtime.inputs.files.any { it.digest == scriptDigest("g:private:1") })
        assertTrue(LogicalPath("runtime/task/main") in launch.classpath)
        assertNotEquals(actions.getValue(scriptCompile).key(Sha256), scriptActions(graph, "task:jvm:main").getValue(scriptCompile).key(Sha256))
    }

    @Test fun workspaceCompilerPluginIsBuiltAndKeptOffApplicationClasspath() {
        val plugin = CompilerPluginSpec(module = "plugin", coordinate = null, targets = setOf("jvm"), scopes = setOf("script"),
            options = emptyList(), inputs = listOf(LogicalPath("plugin-input.txt")), id = "fixture.plugin", compilerVersion = "2.4.20")
        val spec = scriptSpec.copy(compilerPlugins = listOf(plugin))
        val modules = listOf(scriptModule("plugin", listOf(obj("coordinate" to "g:plugin-runtime:1".json(), "scope" to "runtime".json(), "exported" to false.json()))))
        val graph = scriptPlan(spec, scriptFiles("task.kt", "plugin/src/Plugin.kt", "plugin/resources/META-INF/services/Registrar", "plugin-input.txt"), modules)
        val actions = scriptActions(graph)
        val compile = actions.getValue(scriptCompile)
        val application = JvmCompilation.fromJson(compile.parameters).pluginApplications.single()
        assertEquals("fixture.plugin", application.id)
        assertTrue(application.classpath.any { it.value.startsWith("plugins/fixture.plugin/modules/plugin/") })
        assertTrue(compile.inputs.files.any { it.path.value.endsWith("META-INF/services/Registrar") })
        assertTrue(compile.inputs.files.any { it.digest == scriptDigest("g:plugin-runtime:1") })
        val runtime = actions.getValue(scriptRun)
        assertFalse(runtime.inputs.files.any { it.path.value.startsWith("plugins/") || it.path.value.startsWith("plugin-inputs/") })
        assertFalse(runtime.inputs.files.any { it.digest == scriptDigest("g:plugin-runtime:1") })
        assertNotEquals(compile.key(Sha256), scriptActions(graph, "plugin:jvm:main").getValue(scriptCompile).key(Sha256))
    }

    @Test fun standaloneCompanionHasNoImplicitWorkspaceOrSyntheticModule() {
        val configuration = parseStandaloneScriptConfiguration(obj("schemaVersion" to 1.json(), "name" to "task".json(),
            "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(), "script" to scriptSpec.toJson()))
        val effective = effectiveConfiguration(configuration)
        val graph = scriptActionGraph(effective, scriptLock(effective), scriptFiles("task.kt"), scriptDigest("runtime"), scriptTools, ScriptGraphRequest("task"))
        assertEquals(listOf(scriptCompile, scriptRun), graph.nodes.map { it.id })
        assertEquals(ActionKind.SCRIPT, scriptActions(graph).getValue(scriptRun).kind)
        assertFailsWith<IllegalArgumentException> { scriptActionGraph(effective, scriptLock(effective), scriptFiles("task.kt"), scriptDigest("runtime"), scriptTools, ScriptGraphRequest("other")) }
    }

    @Test fun invalidSourceAndResourceOverlaysFailBeforeExecution() {
        assertFailsWith<IllegalArgumentException> { scriptPlan(scriptSpec, scriptFiles("other.kt")) }
        assertFailsWith<IllegalArgumentException> { scriptPlan(scriptSpec.copy(sources = listOf(LogicalPath("src"))), scriptFiles("src/Main.kt", "src/data.json")) }
        assertFailsWith<IllegalArgumentException> { scriptPlan(scriptSpec.copy(resources = listOf(LogicalPath("a"), LogicalPath("b"))), scriptFiles("task.kt", "a/config.txt", "b/config.txt")) }
        assertFailsWith<IllegalArgumentException> { scriptPlan(scriptSpec.copy(compilerOptions = listOf("-Xmulti-platform"))) }
    }

    @Test fun typedActionRejectsInjectedEnvironmentAndMissingOrMalformedClasspaths() {
        val launch = JvmScriptLaunch("task", LogicalPath("classes"), listOf(LogicalPath("classes")))
        val files = scriptFiles("classes/Main.class")
        assertEquals(launch, JvmScriptLaunch.fromJson(launch.toJson()))
        assertFailsWith<IllegalArgumentException> { jvmScriptAction(launch, files, scriptDigest("runtime"), scriptTools, mapOf("JAVA_TOOL_OPTIONS" to "-agentlib:evil"), 1000) }
        assertFailsWith<IllegalArgumentException> { jvmScriptAction(launch, TreeManifest(emptyList()), scriptDigest("runtime"), scriptTools, emptyMap(), 1000) }
        assertFailsWith<IllegalArgumentException> { JvmScriptLaunch("task", LogicalPath("classes"), listOf(LogicalPath("classes"), LogicalPath("bad:entry.jar"))) }
        assertFailsWith<IllegalArgumentException> { JvmScriptLaunch("task", LogicalPath("classes"), listOf(LogicalPath("classes")), arguments = listOf("bad\u0000argument")) }
    }

    @Test fun graphRequiresExactSuccessfulPredecessorsAndCurrentLock() {
        val graph = scriptPlan(scriptSpec)
        val run = graph.nodes.last()
        assertFailsWith<IllegalArgumentException> { run.resolve(emptyMap()) }
        val failed = ActionResult(scriptCompile, null, ActionStatus.FAILED)
        assertFailsWith<IllegalArgumentException> { run.resolve(mapOf(scriptCompile to failed)) }
        val config = effectiveConfiguration(scriptConfig(scriptSpec))
        val lock = scriptLock(config)
        val changed = effectiveConfiguration(scriptConfig(scriptSpec.copy(timeoutMillis = 500)))
        assertFailsWith<IllegalArgumentException> { scriptActionGraph(changed, lock, scriptFiles("task.kt"), scriptDigest("runtime"), scriptTools, ScriptGraphRequest("task")) }
    }
}
