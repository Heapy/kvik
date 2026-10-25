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
            scriptDigest(node.id + if (node.id == changedOutput) ":changed" else ""))) +
            if (action.implementation == "kvik-kotlin-script-compiler-v1") listOf(FileEntry(LogicalPath("classes/kvik-script.json"), scriptDigest("descriptor")))
            else emptyList()) else TreeManifest(emptyList())
        results[node.id] = ActionResult(node.id, action.key(Sha256), ActionStatus.EXECUTED, outputs, 0)
    }
    return actions
}
private val scriptSpec = ScriptSpec(listOf(LogicalPath("task.kt")))
private const val scriptCompile = "script:task:jvm:compile"
private const val scriptRun = "script:task:jvm:run"

class ScriptGraphTest {
    @Test fun publicScriptInputsResolveModuleClasspathWithoutCompilingTheScript() {
        val spec = scriptSpec.copy(dependencies = listOf(ScriptDependencyReference.Module("helper"),
            ScriptDependencyReference.External(Coordinate.parse("g:external:1"))))
        val graph = scriptPlan(spec, scriptFiles("task.kt", "helper/src/Main.kt"), listOf(scriptModule("helper")))
        val compilation = graph.requireScriptCompilation("task")
        assertEquals(scriptCompile, compilation.actionId)
        assertNull(compilation.entryScript)
        assertEquals(listOf(ScriptSourceRoot(LogicalPath("task.kt"), LogicalPath("src/task.kt"))), compilation.sourceRoots)
        assertEquals(setOf("helper:jvm:main"), compilation.dependencies)
        assertFailsWith<IllegalArgumentException> { compilation.resolve(emptyMap()) }
        val module = ActionResult("helper:jvm:main", scriptDigest("key"), ActionStatus.LOCAL_HIT,
            TreeManifest(listOf(FileEntry(LogicalPath("classes/Helper.class"), scriptDigest("helper bytes")))), 0)
        val inputs = compilation.resolve(mapOf(module.actionId to module))
        assertEquals(listOf(LogicalPath("src/task.kt")), inputs.sources)
        assertEquals(LogicalPath("modules/helper/jvm/main"), inputs.classpath.first())
        assertEquals(setOf(scriptDigest("helper bytes"), scriptDigest("g:external:1")), inputs.classpathFiles().files.map { it.digest }.toSet())
        assertEquals(listOf(scriptDigest("task.kt")), inputs.sourceFiles().files.map { it.digest })
        assertEquals(graph.nodes.single { it.id == scriptCompile }.resolve(mapOf(module.actionId to module)).inputs, inputs.files)
        assertFailsWith<IllegalArgumentException> { compilation.resolve(mapOf(module.actionId to module.copy(status = ActionStatus.FAILED, exitCode = 1))) }
        assertFailsWith<IllegalArgumentException> { compilation.resolve(mapOf(module.actionId to module.copy(actionId = "other"))) }
        assertFailsWith<IllegalArgumentException> { graph.copy(scriptCompilations = listOf(compilation, compilation)) }
        assertFailsWith<IllegalArgumentException> { graph.copy(nodes = graph.nodes.filterNot { it.id == scriptCompile }) }
        assertFailsWith<IllegalArgumentException> { graph.requireScriptCompilation("missing") }
        assertEquals(listOf(compilation.toJson()), inspectActionGraph(graph).values.getValue("scriptCompilations").arrayValue())
    }

    @Test fun standaloneKotlinScriptInputsRetainImportsAndPublishedPlugin() {
        val primary = LogicalPath("scripts/task.kts")
        val plugin = CompilerPluginSpec(null, Coordinate.parse("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20"),
            setOf("jvm"), setOf("script"), emptyList(), emptyList())
        val spec = ScriptSpec(listOf(LogicalPath("scripts")), entryScript = primary, compilerPlugins = listOf(plugin))
        val config = parseStandaloneScriptConfiguration(obj("schemaVersion" to 1.json(), "name" to "task".json(),
            "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
            "script" to spec.toJson()))
        val effective = effectiveConfiguration(config)
        val graph = scriptActionGraph(effective, scriptLock(effective), scriptFiles(primary.value, "scripts/helper.kts"),
            scriptDigest("runtime"), scriptTools, ScriptGraphRequest("task"))
        assertTrue(graph.compilations.isEmpty())
        val compilation = graph.requireScriptCompilation("task")
        assertEquals(primary, compilation.entryScript)
        assertTrue(compilation.dependencies.isEmpty())
        assertEquals(listOf(ScriptSourceRoot(LogicalPath("scripts"), LogicalPath("src/scripts"))), compilation.sourceRoots)
        val inputs = compilation.resolve(emptyMap())
        assertEquals(setOf("src/scripts/task.kts", "src/scripts/helper.kts"), inputs.sources.map { it.value }.toSet())
        assertTrue(inputs.commonSources.isEmpty())
        assertEquals(1, inputs.plugins.size)
        assertTrue(inputs.plugins.none { it in inputs.classpath })
        assertEquals(graph.nodes.single { it.id == scriptCompile }.resolve(emptyMap()).inputs, inputs.files)
    }

    @Test fun sourceDependenciesEnterBothLockDomainsAndCompilationWithoutChangingAuthoring() {
        val spec = ScriptSpec(listOf(LogicalPath("scripts")), entryScript = LogicalPath("scripts/task.kts"),
            dependencies = listOf(ScriptDependencyReference.External(Coordinate.parse("g:explicit:1"))))
        val original = scriptConfig(spec)
        val sources = mapOf("scripts/task.kts" to "@file:Import(\"helper.kts\")\n@file:DependsOn(\"g:explicit:1\")",
            "scripts/helper.kts" to "@file:DependsOn(\"g:automatic:2\")\n@file:Repository(\"https://example.org/maven\")")
        val configuration = original.withScriptDeclarations { sources.getValue(it.value).encodeToByteArray() }
        assertEquals(original.document, configuration.document)
        assertEquals(original.project, configuration.project)
        val effective = effectiveConfiguration(configuration)
        assertEquals(setOf("g:explicit:1", "g:automatic:2"), effective.requests.map { it.coordinate.toString() }.toSet())
        assertEquals(4, effective.requests.size)
        val graph = scriptActionGraph(effective, scriptLock(effective), scriptFiles(*sources.keys.toTypedArray()),
            scriptDigest("composition"), scriptTools, ScriptGraphRequest("task"))
        val compile = KtsCompilation.fromJson(scriptActions(graph).getValue(scriptCompile).parameters)
        assertEquals(setOf("g:explicit:1", "g:automatic:2"), compile.declaredDependencies.toSet())
        assertEquals(listOf("https://repo.maven.apache.org/maven2", "https://example.org/maven"), compile.declaredRepositories)
        assertEquals(1, effective.script("task").dependencies.count { (it.value as? Dependency.External)?.coordinate?.name == "explicit" })
        assertTrue(effective.script("task").dependencies.single { (it.value as? Dependency.External)?.coordinate?.name == "automatic" }
            .origin.declaration.startsWith("/scriptDeclarations/task/files/scripts~1helper.kts/"))
        for (domain in listOf(ResolutionDomain("library", "jvm", "main", "java-api"),
            ResolutionDomain.script("other", "java-api"), ResolutionDomain.script("task", "java-runtime", "compiler-plugin")))
            assertEquals(original.project.repositories, effective.repositoriesFor(domain))
        val bodyEdit = original.withScriptDeclarations { (sources.getValue(it.value) + "\nprintln(42)").encodeToByteArray() }
        assertEquals(effective.digest, effectiveConfiguration(bodyEdit).digest)
        val dependencyEdit = original.withScriptDeclarations { sources.getValue(it.value).replace("automatic:2", "automatic:3").encodeToByteArray() }
        assertNotEquals(effective.digest, effectiveConfiguration(dependencyEdit).digest)
    }

    @Test fun kotlinScriptsCapturePrimaryAndImportsWithSeparateFreshExecution() {
        val primary = LogicalPath("scripts/task.main.kts")
        val spec = ScriptSpec(listOf(LogicalPath("scripts")), entryScript = primary,
            dependencies = listOf(ScriptDependencyReference.External(Coordinate.parse("g:helper:1"))))
        val files = scriptFiles(primary.value, "scripts/helper.kts")
        val actions = scriptActions(scriptPlan(spec, files, args = listOf("", "argument")))
        val compile = actions.getValue(scriptCompile)
        val metadata = KtsCompilation.fromJson(compile.parameters)
        assertEquals(LogicalPath("src/${primary.value}"), metadata.primary)
        assertEquals(setOf("src/scripts/task.main.kts", "src/scripts/helper.kts"), metadata.sources.map { it.value }.toSet())
        assertEquals(listOf("g:helper:1"), metadata.declaredDependencies)
        assertEquals(listOf("https://repo.maven.apache.org/maven2"), metadata.declaredRepositories)
        assertEquals(CachePolicy.LOCAL, compile.cachePolicy)
        val run = actions.getValue(scriptRun)
        assertEquals("kvik-kotlin-script-v1", run.implementation)
        assertEquals(CachePolicy.NEVER, run.cachePolicy)
        val launch = KtsLaunch.fromJson(run.parameters)
        assertEquals(listOf("", "argument"), launch.arguments)
        assertEquals(LogicalPath("scripts/task/classes"), launch.bundle)
        assertTrue(run.inputs.files.any { it.path.value == "scripts/task/classes/kvik-script.json" })
        assertFalse(run.inputs.files.any { it.path.value.startsWith("src/") })
        val changedArgs = scriptActions(scriptPlan(spec.copy(environment = mapOf("MODE" to "next")), files, args = listOf("next")))
        assertEquals(compile.key(Sha256), changedArgs.getValue(scriptCompile).key(Sha256))
        assertNotEquals(run.key(Sha256), changedArgs.getValue(scriptRun).key(Sha256))
        val editedHelper = TreeManifest(files.files.map { if (it.path.value.endsWith("helper.kts")) it.copy(digest = scriptDigest("edited")) else it })
        assertNotEquals(compile.key(Sha256), scriptActions(scriptPlan(spec, editedHelper)).getValue(scriptCompile).key(Sha256))
        assertFails { scriptPlan(spec, scriptFiles("scripts/helper.kts")) }
        assertFails { scriptPlan(spec, scriptFiles(primary.value, "scripts/Helper.kt")) }
    }

    @Test fun scriptUsesDistinctChecksAndNeverCachesExecution() {
        val graph = scriptPlan(scriptSpec)
        assertEquals(setOf(scriptRun), graph.requestedChecks)
        assertEquals(mapOf(scriptRun to scriptRun), graph.terminalActions)
        assertEquals(listOf(scriptCompile, scriptRun), graph.nodes.map { it.id })
        val actions = scriptActions(graph)
        assertEquals(CachePolicy.LOCAL, actions.getValue(scriptCompile).cachePolicy)
        val compilation = JvmCompilation.fromJson(actions.getValue(scriptCompile).parameters)
        assertTrue(compilation.includeStandardLibrary)
        assertEquals(compilation, JvmCompilation.fromJson(compilation.toJson()))
        assertFalse("includeStandardLibrary" in compilation.copy(includeStandardLibrary = false).toJson().values)
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

    @Test fun compilerIdentityInvalidatesScriptWithFixedCompositionAndInputs() {
        val effective = effectiveConfiguration(scriptConfig(scriptSpec))
        val lock = scriptLock(effective)
        val files = scriptFiles("task.kt")
        val composition = scriptDigest("composition")
        fun actions(tools: Map<String, Digest>) = scriptActions(scriptActionGraph(
            effective, lock, files, composition, tools, ScriptGraphRequest("task")))
        val original = actions(scriptTools)
        val changed = actions(scriptTools + ("jvm-compiler" to scriptDigest("different compiler distribution")))
        for (id in listOf(scriptCompile, scriptRun)) {
            val before = original.getValue(id)
            val after = changed.getValue(id)
            assertNotEquals(before.key(Sha256), after.key(Sha256), id)
            assertEquals(before, after.copy(toolchains = before.toolchains), id)
        }
        assertEquals(CachePolicy.NEVER, changed.getValue(scriptRun).cachePolicy)
    }

    @Test fun publishedPluginDiagnosticsDistinguishCoordinateVersionOptionsAndInputs() {
        val coordinate = Coordinate("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", "2.4.20")
        val plugin = CompilerPluginSpec(null, coordinate, setOf("jvm"), setOf("script"), emptyList(), emptyList())
        fun compile(value: CompilerPluginSpec) = scriptActions(scriptPlan(
            scriptSpec.copy(compilerPlugins = listOf(value)), scriptFiles("task.kt", "plugin-input.txt"))).getValue(scriptCompile)
        val action = compile(plugin)
        assertEquals(listOf(LogicalPath("plugins/${scriptDigest(coordinate.toString()).hash}-${scriptDigest(coordinate.toString()).sizeBytes}.jar")),
            JvmCompilation.fromJson(action.parameters).plugins)
        val other = coordinate.copy(name = "other-plugin")
        val incompatible = coordinate.copy(version = "2.3.20")
        for ((invalid, diagnostic) in listOf(
            plugin.copy(coordinate = other) to "Unsupported published script compiler plugin $other; supported coordinate: $coordinate",
            plugin.copy(coordinate = incompatible) to "Script compiler plugin $incompatible is incompatible with pinned Kotlin 2.4.20; use $coordinate",
            plugin.copy(options = listOf("generateSchema=true")) to "Published script compiler plugin $coordinate does not support options; remove options or use a workspace compiler plugin",
            plugin.copy(inputs = listOf(LogicalPath("plugin-input.txt"))) to "Published script compiler plugin $coordinate does not support file inputs; remove inputs or use a workspace compiler plugin",
        )) {
            assertEquals(diagnostic, assertFailsWith<IllegalArgumentException> { compile(invalid) }.message)
        }
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

    @Test fun workspaceAndPublishedPluginsComposeForBothScriptAdapters() {
        val workspace = CompilerPluginSpec(module = "plugin", coordinate = null, targets = setOf("jvm"), scopes = setOf("script"),
            options = listOf("text=original"), inputs = listOf(LogicalPath("plugin-input.txt")), id = "fixture.plugin", compilerVersion = "2.4.20")
        val coordinate = Coordinate.parse("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20")
        val serialization = CompilerPluginSpec(null, coordinate, setOf("jvm"), setOf("script"), emptyList(), emptyList())
        val modules = listOf(scriptModule("plugin", listOf(obj("coordinate" to "g:plugin-runtime:1".json()))))
        for (kts in listOf(false, true)) for (plugins in listOf(listOf(workspace, serialization), listOf(serialization, workspace))) {
            val primary = LogicalPath(if (kts) "task.kts" else "task.kt")
            val spec = ScriptSpec(listOf(primary), entryScript = primary.takeIf { kts }, compilerPlugins = plugins)
            val files = scriptFiles(primary.value, "plugin/src/Plugin.kt", "plugin/resources/META-INF/services/Registrar", "plugin-input.txt")
            val graph = scriptPlan(spec, files, modules)
            assertEquals(setOf("plugin:jvm:main"), graph.nodes.single { it.id == scriptCompile }.dependencies)
            val actions = scriptActions(graph)
            val compilation = actions.getValue(scriptCompile)
            val published = if (kts) KtsCompilation.fromJson(compilation.parameters).plugins else JvmCompilation.fromJson(compilation.parameters).plugins
            val applications = if (kts) KtsCompilation.fromJson(compilation.parameters).pluginApplications else JvmCompilation.fromJson(compilation.parameters).pluginApplications
            assertEquals(1, published.size)
            assertEquals("fixture.plugin", applications.single().id)
            assertTrue(compilation.inputs.files.any { it.path == published.single() && it.digest == scriptDigest(coordinate.toString()) })
            assertTrue(compilation.inputs.files.any { it.digest == scriptDigest("g:plugin-runtime:1") })
            assertFalse(actions.getValue(scriptRun).inputs.files.any { it.path.value.startsWith("plugins/") || it.path.value.startsWith("plugin-inputs/") })
            assertEquals(CachePolicy.NEVER, actions.getValue(scriptRun).cachePolicy)
            assertNotEquals(compilation.key(Sha256), scriptActions(graph, "plugin:jvm:main").getValue(scriptCompile).key(Sha256))
            val changed = spec.copy(compilerPlugins = plugins.map { if (it.module != null) it.copy(options = listOf("text=changed")) else it })
            assertNotEquals(compilation.key(Sha256), scriptActions(scriptPlan(changed, files, modules)).getValue(scriptCompile).key(Sha256))
            assertFailsWith<IllegalArgumentException> {
                scriptPlan(spec.copy(compilerPlugins = listOf(workspace.copy(id = "org.jetbrains.kotlinx.serialization"), serialization)), files, modules)
            }
        }
    }

    @Test fun multipleWorkspacePluginsKeepTheirOwnClosuresAndOrderedIdentities() {
        val first = CompilerPluginSpec(module = "first", coordinate = null, targets = setOf("jvm"), scopes = setOf("script"),
            options = listOf("text=first"), inputs = emptyList(), id = "fixture.first", compilerVersion = "2.4.20")
        val second = first.copy(module = "second", id = "fixture.second", options = listOf("text=second"))
        val shared = listOf(obj("module" to "shared".json()))
        val modules = listOf(scriptModule("first", shared), scriptModule("second", shared), scriptModule("shared"))
        for (kts in listOf(false, true)) {
            val primary = LogicalPath(if (kts) "task.kts" else "task.kt")
            val spec = ScriptSpec(listOf(primary), entryScript = primary.takeIf { kts }, compilerPlugins = listOf(first, second))
            val files = scriptFiles(primary.value, "first/src/Plugin.kt", "second/src/Plugin.kt", "shared/src/Helper.kt")
            val graph = scriptPlan(spec, files, modules)
            assertEquals(setOf("first:jvm:main", "second:jvm:main", "shared:jvm:main"), graph.nodes.single { it.id == scriptCompile }.dependencies)
            assertEquals(1, graph.nodes.count { it.id == "shared:jvm:main" })
            val actions = scriptActions(graph)
            val compile = actions.getValue(scriptCompile)
            val applications = if (kts) KtsCompilation.fromJson(compile.parameters).pluginApplications else JvmCompilation.fromJson(compile.parameters).pluginApplications
            assertEquals(listOf(first.id, second.id), applications.map { it.id })
            for ((application, own, other) in listOf(Triple(applications[0], "first", "second"), Triple(applications[1], "second", "first"))) {
                assertTrue(application.classpath.any { "/modules/$own/" in it.value })
                assertTrue(application.classpath.any { "/modules/shared/" in it.value })
                assertFalse(application.classpath.any { "/modules/$other/" in it.value })
            }
            for (module in listOf("first", "second", "shared"))
                assertNotEquals(compile.key(Sha256), scriptActions(graph, "$module:jvm:main").getValue(scriptCompile).key(Sha256))
            assertNotEquals(compile.key(Sha256), scriptActions(scriptPlan(spec.copy(compilerPlugins = listOf(second, first)), files, modules)).getValue(scriptCompile).key(Sha256))
            assertFalse(actions.getValue(scriptRun).inputs.files.any { it.path.value.startsWith("plugins/") })
            assertFails { scriptPlan(spec.copy(compilerPlugins = listOf(first, second.copy(id = first.id))), files, modules) }
        }
    }

    @Test fun publishedScriptPluginsKeepDescriptorsAndSeparateLockedInputs() {
        val first = CompilerPluginSpec(null, Coordinate.parse("g:first:1"), setOf("jvm"), setOf("script"),
            listOf("text=first"), listOf(LogicalPath("config.txt")), "fixture.first", "2.4.20",
            listOf(PluginPathOption("config", PluginPathKind.INPUT_FILE, LogicalPath("config.txt"))))
        val second = first.copy(coordinate = Coordinate.parse("g:second:1"), id = "fixture.second")
        for (kts in listOf(false, true)) {
            val primary = LogicalPath(if (kts) "task.kts" else "task.kt")
            val spec = ScriptSpec(listOf(primary), entryScript = primary.takeIf { kts }, compilerPlugins = listOf(first, second))
            val config = parseStandaloneScriptConfiguration(obj("schemaVersion" to 1.json(), "name" to "task".json(),
                "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(), "script" to spec.toJson()))
            val effective = effectiveConfiguration(config)
            val files = scriptFiles(primary.value, "config.txt")
            val graph = scriptActionGraph(effective, scriptLock(effective), files, scriptDigest("runtime"), scriptTools, ScriptGraphRequest("task"))
            val actions = scriptActions(graph)
            val compile = actions.getValue(scriptCompile)
            val applications = compile.parameters.objectValue().getValue("pluginApplications").arrayValue().map(JvmPluginApplication::fromJson)
            assertEquals(listOf(first.id, second.id), applications.map { it.id })
            assertTrue(graph.nodes.single { it.id == scriptCompile }.dependencies.isEmpty())
            applications.forEach { plugin ->
                assertTrue(plugin.classpath.all { it.value.startsWith("plugins/${plugin.id}/deps/") })
                assertTrue(plugin.classpath.all { path -> compile.inputs.files.any { it.path == path } })
                assertEquals(listOf("text=first"), plugin.options)
                assertEquals(listOf(LogicalPath("plugin-inputs/${plugin.id}/config.txt")), plugin.inputs)
            }
            assertFalse(actions.getValue(scriptRun).inputs.files.any { it.path.value.startsWith("plugins/") })
            assertFails { scriptPlan(spec.copy(compilerPlugins = listOf(first, second.copy(id = first.id))), files) }
        }
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
        for (name in listOf(".", "..", "1task")) assertFailsWith<IllegalArgumentException> { launch.copy(name = name) }
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
