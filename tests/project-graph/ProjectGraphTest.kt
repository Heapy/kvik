@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kvik.compiler.jvm.*
import kvik.compiler.native.*
import kotlin.test.*
import kotlinx.cinterop.*
import platform.posix.*

private fun digest(value: String) = Sha256.digest(value.encodeToByteArray())
private val composition = digest("runtime")
private val tools = mapOf("jvm" to listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith(::digest),
    "macosArm64" to listOf("native", "jdk", "sdk", "native-dependencies").associateWith(::digest))
private fun external(value: String) = Dependency.External(Coordinate.parse(value), exported = true)
private fun source(name: String, directory: String, parents: List<String> = emptyList(), dependencies: List<Dependency> = emptyList(), resource: String? = null) =
    SourceSet(name, listOf(LogicalPath(directory)), listOfNotNull(resource?.let(::LogicalPath)), parents, dependencies)
private fun module(id: String, dependencies: List<Dependency> = emptyList(), plugins: List<CompilerPluginSpec> = emptyList()): ModuleSpec = ModuleSpec(id,
    listOf(source("commonMain", "$id/common", dependencies = dependencies, resource = "$id/resources"),
        source("jvmMain", "$id/jvm", listOf("commonMain")), source("nativeMain", "$id/native", listOf("commonMain")),
        source("commonTest", "$id/commonTest", listOf("commonMain"), listOf(external("g:tests:1")), "$id/test-resources"),
        source("jvmTest", "$id/jvmTest", listOf("commonTest", "jvmMain")), source("nativeTest", "$id/nativeTest", listOf("commonTest", "nativeMain"))),
    listOf(TargetSpec("jvm", "jvmMain", "jvmTest", emptyList()), TargetSpec("macosArm64", "nativeMain", "nativeTest", emptyList())), plugins)
private fun configuration(vararg modules: ModuleSpec): ProjectConfiguration {
    fun paths(values: List<LogicalPath>) = values.map { it.value.json() }.json()
    fun strings(values: Collection<String>) = values.map { it.json() }.json()
    return parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "fixture".json(),
        "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
        "modules" to modules.map { m -> obj("id" to m.id.json(), "sourceSets" to m.sourceSets.map { s -> obj("name" to s.name.json(),
            "directories" to paths(s.directories), "resources" to paths(s.resources), "dependsOn" to strings(s.dependsOn), "dependencies" to s.dependencies.map { d -> when(d) {
                is Dependency.Module -> obj("module" to d.module.json(), "scope" to d.scope.name.lowercase().json(), "exported" to d.exported.json()); is Dependency.External -> obj("coordinate" to d.coordinate.toString().json(), "scope" to d.scope.name.lowercase().json(), "exported" to d.exported.json()) } }.json()) }.json(),
            "targets" to m.targets.map { t -> obj("name" to t.name.json(), "main" to t.main.json(), "test" to (t.test?.json() ?: JsonValue.Null), "compilerOptions" to strings(t.compilerOptions)).let { fields -> if (t.testCompilerOptions == null) fields else JsonValue.Object(fields.objectValue() + ("testCompilerOptions" to strings(t.testCompilerOptions))) } }.json(),
            "compilerPlugins" to m.compilerPlugins.map { p -> JsonValue.Object(mapOf("targets" to strings(p.targets), "scopes" to strings(p.scopes), "options" to strings(p.options), "inputs" to paths(p.inputs)) +
                if (p.coordinate != null) mapOf("coordinate" to p.coordinate.toString().json()) else mapOf("module" to p.module!!.json(), "id" to p.id!!.json(), "compilerVersion" to p.compilerVersion!!.json(), "pathOptions" to p.pathOptions.map { it.toJson() }.json(), "outputs" to paths(p.outputs))) }.json()) }.json()))
}
private val base = module("base", listOf(external("g:stdlib:1")))
private val app = module("app", listOf(Dependency.Module("base"), external("g:library:1")))
private val project get() = configuration(base, app)
private fun snapshot(): TreeManifest = TreeManifest(listOf("base", "app").flatMap { id ->
    listOf("common/Common.kt", "jvm/Actual.kt", "native/Actual.kt", "commonTest/Tests.kt", "jvmTest/JvmTests.kt", "nativeTest/NativeTests.kt", "resources/config.txt", "test-resources/config.txt")
        .map { path -> FileEntry(LogicalPath("$id/$path"), digest("$id/$path")) }
})
private fun artifact(name: String, target: String, usage: String): LockedArtifact {
    val plugin = name.startsWith("org.jetbrains.kotlin:")
    val coordinate = Coordinate.parse(name)
    val extension = if (target == "jvm") "jar" else "klib"
    return LockedArtifact(coordinate, usage, "central", "https://repo.maven.apache.org/maven2/${coordinate.name}.$extension", digest("$name:$target${if(plugin) ":plugin" else ""}"))
}
/** These model a previously verified lock; K09 locking tests independently exercise metadata/CAS proof. */
private fun lock(effective: EffectiveConfiguration) = DependencyLock(effective.digest, effective.requests.map { request ->
    val root = artifact(request.coordinate.toString(), request.variant.platform, request.variant.usage)
    val runtime = if (request.coordinate.name == "library" && request.variant.usage == "java-runtime") listOf(artifact("g:runtime-only:1", "jvm", "runtime")) else emptyList()
    ScopedSelection(request.domain, LockedSelection(request.coordinate, request.variant, listOf(root) + runtime, listOf(MetadataIdentity("https://repo.maven.apache.org/maven2/${request.coordinate.name}.module", digest("metadata")))))
})
private fun request(mode: ProjectMode = ProjectMode.BUILD, target: String = "jvm", classes: List<String> = listOf("fixture.Tests")) =
    ProjectGraphRequest(setOf("app"), setOf(target), mode, if (mode == ProjectMode.TEST && target == "jvm") mapOf("app" to JvmTests(classes)) else emptyMap())
private fun graph(configuration: ProjectConfiguration = project, files: TreeManifest = snapshot(), request: ProjectGraphRequest = request()): ProjectActionGraph {
    val effective = effectiveConfiguration(configuration)
    return projectActionGraph(effective, lock(effective), files, composition, tools, request)
}
private fun outputs(action: ActionSpec, salt: String): TreeManifest = TreeManifest(when(action.implementation) {
    "kvik-jvm-compiler-v1" -> listOf(FileEntry(LogicalPath("classes/Example.class"), digest(salt)))
    "kvik-native-compiler-v1" -> if (action.kind == ActionKind.LINK) listOf(FileEntry(LogicalPath("tests.kexe"), digest(salt), true), FileEntry(LogicalPath("tests.kexe.dSYM/Contents/Info.plist"), digest("symbols")))
        else listOf(FileEntry(LogicalPath("module.klib"), digest(salt)))
    else -> listOf(FileEntry(LogicalPath("test-results.json"), digest("report")))
})
private fun resolved(graph: ProjectActionGraph, changed: String? = null): Map<String, ActionSpec> {
    val results = linkedMapOf<String, ActionResult>(); val actions = linkedMapOf<String, ActionSpec>()
    for (node in graph.nodes) {
        val action = node.resolve(results.filterKeys { it in node.dependencies })
        actions[node.id] = action
        results[node.id] = ActionResult(node.id, action.key(Sha256), ActionStatus.EXECUTED, outputs(action, node.id + if(node.id == changed) "changed" else ""), 0)
    }
    return actions
}

class ProjectGraphTest {
    @Test fun workspacePluginHasHostRuntimeClosureAndContentSensitivePrerequisites() {
        val plugin = CompilerPluginSpec("producer", null, setOf("jvm"), setOf("main", "test"), listOf("value=first", "value=second"), listOf(LogicalPath("config.txt")),
            "example.plugin", "2.4.20", listOf(PluginPathOption("report", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("report"))), listOf(LogicalPath("report")))
        val producer = module("producer", listOf(Dependency.Module("base"), Dependency.Module("runtime", DependencyScopeKind.RUNTIME), Dependency.External(Coordinate.parse("g:compiler-api:1"))))
        val runtime = module("runtime", listOf(Dependency.External(Coordinate.parse("g:private-runtime:1"))))
        val project = configuration(base, producer, runtime, app.copy(compilerPlugins = listOf(plugin)))
        val extra = listOf("producer", "runtime").flatMap { id -> listOf("common/Common.kt", "jvm/Actual.kt", "resources/META-INF/services/Registrar").map { FileEntry(LogicalPath("$id/$it"), digest("$id/$it")) } }
        val files = TreeManifest(snapshot().files + extra + FileEntry(LogicalPath("config.txt"), digest("config")))
        val effective = effectiveConfiguration(project, scopes = listOf(DependencyScope("app", "jvm", "test", "java-api"), DependencyScope("app", "jvm", "test", "java-runtime")))
        val host = effective.requests.filter { it.domain.purpose == "compiler-plugin" }
        assertTrue(host.isNotEmpty())
        assertEquals(setOf("25"), host.map { it.variant.attributes.getValue(JVM_TARGET_VERSION_ATTRIBUTE) }.toSet())
        assertTrue(host.any { it.coordinate.name == "private-runtime" })
        assertTrue(effective.requests.any { it.domain.module == "producer" && it.domain.usage == "java-api" })
        val graph = projectActionGraph(effective, lock(effective), files, composition, tools, request(ProjectMode.TEST))
        val before = resolved(graph)
        val changed = resolved(graph, "producer:jvm:main")
        val producerCompile = JvmCompilation.fromJson(before.getValue("producer:jvm:main").parameters)
        assertFalse(producerCompile.classpath.any { "runtime" in it.value })
        for (id in listOf("app:jvm:main", "app:jvm:test-compile")) {
            val action = before.getValue(id)
            val compile = JvmCompilation.fromJson(action.parameters)
            val applied = compile.pluginApplications.single()
            assertEquals(plugin.options, applied.options)
            assertTrue(applied.classpath.any { "runtime/classes" in it.value })
            assertTrue(applied.classpath.none { it in compile.classpath })
            assertTrue(action.inputs.files.any { it.path.value == "plugin-inputs/example.plugin/config.txt" })
            assertEquals(listOf(LogicalPath("classes"), LogicalPath("plugins/example.plugin/report")), action.outputs)
            assertNotEquals(action.key(Sha256), changed.getValue(id).key(Sha256))
            val node = graph.nodes.single { it.id == id }
            assertTrue("producer:jvm:main" in node.dependencies && "runtime:jvm:main" in node.dependencies)
        }
        assertEquals(before.getValue("base:jvm:main"), changed.getValue("base:jvm:main"))
        assertTrue(before.getValue("app:jvm:test").inputs.files.none { it.path.value.startsWith("plugins/") })
        val resourceChange = TreeManifest(files.files.map { if (it.path.value.startsWith("producer/resources/")) it.copy(digest = digest("changed service resource")) else it })
        val changedResources = resolved(projectActionGraph(effective, lock(effective), resourceChange, composition, tools, request(ProjectMode.TEST)))
        assertNotEquals(before.getValue("app:jvm:main").key(Sha256), changedResources.getValue("app:jvm:main").key(Sha256))
        val testOnly = configuration(base, producer, runtime, app.copy(compilerPlugins = listOf(plugin.copy(scopes = setOf("test")))))
        val testPlan = graph(testOnly, files, request(ProjectMode.TEST))
        assertEquals(resolved(testPlan).getValue("app:jvm:main"), resolved(testPlan, "producer:jvm:main").getValue("app:jvm:main"))
        assertNotEquals(resolved(testPlan).getValue("app:jvm:test-compile").key(Sha256), resolved(testPlan, "producer:jvm:main").getValue("app:jvm:test-compile").key(Sha256))
        assertFails { graph.nodes.single { it.id == "app:jvm:main" }.resolve(emptyMap()) }
        assertFails { graph(project, files = TreeManifest(files.files.filter { it.path.value != "config.txt" }), request = request(ProjectMode.TEST)).let(::resolved) }
    }

    @Test fun workspacePluginCyclesAndTestOnlyScopeAreExplicit() {
        val plugin = CompilerPluginSpec("base", null, setOf("jvm"), setOf("test"), emptyList(), emptyList(), "example.plugin", "2.4.20")
        val config = configuration(base, app.copy(compilerPlugins = listOf(plugin)))
        val actions = resolved(graph(config, request = request(ProjectMode.TEST)))
        assertTrue(JvmCompilation.fromJson(actions.getValue("app:jvm:main").parameters).pluginApplications.isEmpty())
        assertEquals(1, JvmCompilation.fromJson(actions.getValue("app:jvm:test-compile").parameters).pluginApplications.size)
        assertFails { configuration(base.copy(compilerPlugins = listOf(plugin.copy(scopes = setOf("main")))), app) }
        val back = base.copy(sourceSets = base.sourceSets.map { if (it.name == "commonMain") it.copy(dependencies = listOf(Dependency.Module("app"))) else it })
        assertFails { configuration(back, app.copy(compilerPlugins = listOf(plugin))) }
    }

    @Test fun explicitJdkReleaseFlowsThroughScopedVariantsInheritanceAndActionKeys() {
        fun configured(main: List<String>, test: List<String>? = null) = configuration(base,
            app.copy(targets = listOf(app.targets.first().copy(compilerOptions = main, testCompilerOptions = test))))
        val defaults = configured(emptyList())
        val released = configured(listOf("-Xjdk-release=11"))
        val effective = effectiveConfiguration(released)
        assertNotEquals(effectiveConfiguration(defaults).digest, effective.digest)
        assertFails { projectActionGraph(effective, lock(effectiveConfiguration(defaults)), snapshot(), composition, tools, request(ProjectMode.TEST)) }
        assertEquals(setOf("11"), effective.requests.filter { it.domain.module == "app" }.map { it.variant.attributes.getValue(JVM_TARGET_VERSION_ATTRIBUTE) }.toSet())
        val before = resolved(graph(defaults, request = request(ProjectMode.TEST)))
        val inherited = resolved(graph(released, request = request(ProjectMode.TEST)))
        for (id in listOf("app:jvm:main", "app:jvm:test-compile")) {
            assertEquals(11, JvmCompilation.fromJson(inherited.getValue(id).parameters).jdkRelease)
            assertNotEquals(before.getValue(id).key(Sha256), inherited.getValue(id).key(Sha256))
        }
        assertEquals(before.getValue("base:jvm:main").key(Sha256), inherited.getValue("base:jvm:main").key(Sha256))
        val testRelease = configured(listOf("-Xjdk-release=11"), listOf("-Xjdk-release", "21"))
        val changed = resolved(graph(testRelease, request = request(ProjectMode.TEST)))
        assertEquals(inherited.getValue("app:jvm:main").key(Sha256), changed.getValue("app:jvm:main").key(Sha256))
        assertNotEquals(inherited.getValue("app:jvm:test-compile").key(Sha256), changed.getValue("app:jvm:test-compile").key(Sha256))
        assertEquals(21, JvmCompilation.fromJson(changed.getValue("app:jvm:test-compile").parameters).jvmTarget)
        assertEquals(setOf("21"), effectiveConfiguration(testRelease).requests.filter { it.domain.module == "app" && it.domain.compilation == "test" }.map { it.variant.attributes.getValue(JVM_TARGET_VERSION_ATTRIBUTE) }.toSet())
        val cleared = resolved(graph(configured(listOf("-Xjdk-release=11"), emptyList()), request = request(ProjectMode.TEST)))
        assertNull(JvmCompilation.fromJson(cleared.getValue("app:jvm:test-compile").parameters).jdkRelease)
        assertFails { effectiveConfiguration(configured(listOf("-Xjdk-release=21"), listOf("-Xjdk-release=11"))) }
        assertFails { effectiveConfiguration(configuration(base.copy(targets = listOf(base.targets.first().copy(compilerOptions = listOf("-Xjdk-release=21")))), app)) }
        for (options in listOf(listOf("-Xjdk-release=8"), listOf("-Xjdk-release=17", "-jvm-target=11"), listOf("-Xjdk-release=11", "-Xjdk-release=11"))) {
            assertFails { graph(configured(options)) }
        }
        val native = app.copy(targets = listOf(app.targets.last().copy(compilerOptions = listOf("-Xjdk-release=11"))))
        assertFails { graph(configuration(base, native), request = request(ProjectMode.BUILD, "macosArm64")) }
    }
    @Test fun privateRuntimeAndExportedEdgesDriveDifferentActionInputsAndPrerequisites() {
        fun dep(name: String, exported: Boolean = false, runtime: Boolean = false) = Dependency.External(Coordinate("g", name, "1"), if(runtime) DependencyScopeKind.RUNTIME else DependencyScopeKind.COMPILE, exported)
        val leaf = module("leaf", listOf(dep("leaf-public", exported = true), dep("leaf-private")))
        val hidden = module("hidden", listOf(dep("hidden-private")))
        val service = module("base", listOf(dep("public", exported = true), dep("private"), dep("runtime", runtime = true),
            Dependency.Module("leaf", exported = true), Dependency.Module("hidden", DependencyScopeKind.RUNTIME)))
        val consumer = module("app", listOf(Dependency.Module("base")))
        val config = configuration(service, consumer, leaf, hidden)
        val files = TreeManifest(snapshot().files + listOf("leaf", "hidden").flatMap { id -> snapshot().files.filter { it.path.value.startsWith("base/") }
            .map { it.copy(path = LogicalPath(it.path.value.replaceFirst("base/", "$id/"))) } })
        val plan = graph(config, files, request(ProjectMode.TEST))
        val actions = resolved(plan)
        fun has(action: String, name: String) = actions.getValue(action).inputs.files.any { it.digest == digest("g:$name:1:jvm") }
        assertTrue(has("base:jvm:main", "private"))
        assertFalse(has("base:jvm:main", "runtime"))
        assertFalse(has("base:jvm:main", "leaf-private"))
        assertTrue(has("app:jvm:main", "public"))
        assertTrue(has("app:jvm:main", "leaf-public"))
        for (name in listOf("private", "runtime", "leaf-private", "hidden-private")) {
            assertFalse(has("app:jvm:test-compile", name), name)
            assertTrue(has("app:jvm:test", name), name)
        }
        assertEquals(setOf("base:jvm:main", "leaf:jvm:main"), plan.nodes.single { it.id == "app:jvm:main" }.dependencies)
        assertTrue("hidden:jvm:main" in plan.nodes.single { it.id == "app:jvm:test" }.dependencies)
        assertFalse("hidden:jvm:main" in plan.nodes.single { it.id == "app:jvm:test-compile" }.dependencies)
        val changed = resolved(plan, "hidden:jvm:main")
        assertEquals(actions.getValue("app:jvm:test-compile").key(Sha256), changed.getValue("app:jvm:test-compile").key(Sha256))
        assertNotEquals(actions.getValue("app:jvm:test").key(Sha256), changed.getValue("app:jvm:test").key(Sha256))
        val failure = assertFails { graph(config, files, request(ProjectMode.TEST, "macosArm64")) }
        assertTrue(failure.message!!.contains("Native hidden link dependencies"))
    }

    @Test fun testCompilerOptionsAndDomainMutationsCannotLeakIntoProduction() {
        val target = app.targets.first().copy(compilerOptions = listOf("-jvm-target=17", "-Xexplicit-api=strict"), testCompilerOptions = listOf("-jvm-target=21"))
        val config = configuration(base, app.copy(targets = listOf(target)))
        val effective = effectiveConfiguration(config)
        val lock = lock(effective)
        val plan = projectActionGraph(effective, lock, snapshot(), composition, tools, request(ProjectMode.TEST))
        val original = resolved(plan)
        assertTrue(JvmCompilation.fromJson(original.getValue("app:jvm:main").parameters).explicitApi)
        val test = JvmCompilation.fromJson(original.getValue("app:jvm:test-compile").parameters)
        assertFalse(test.explicitApi)
        assertEquals(21, test.jvmTarget)
        val changed = lock.copy(selections = lock.selections.map { scoped ->
            if (scoped.domain == ResolutionDomain("app", "jvm", "test", "java-api")) scoped.copy(selection = scoped.selection.copy(
                artifacts = scoped.artifacts.map { it.copy(digest = digest("changed ${it.coordinate}")) })) else scoped
        })
        val after = resolved(projectActionGraph(effective, changed, snapshot(), composition, tools, request(ProjectMode.TEST)))
        assertEquals(original.getValue("app:jvm:main").key(Sha256), after.getValue("app:jvm:main").key(Sha256))
        assertEquals(original.getValue("base:jvm:main").key(Sha256), after.getValue("base:jvm:main").key(Sha256))
        assertNotEquals(original.getValue("app:jvm:test-compile").key(Sha256), after.getValue("app:jvm:test-compile").key(Sha256))
        assertFails { effectiveConfiguration(configuration(base, app.copy(targets = listOf(target.copy(testCompilerOptions = listOf("-jvm-target=11")))))) }
        val native = app.copy(targets = listOf(app.targets.last().copy(compilerOptions = listOf("-opt-in=example.MainOptIn"), testCompilerOptions = emptyList())))
        assertTrue(assertFails { graph(configuration(base, native), request = request(ProjectMode.TEST, "macosArm64")) }.message!!.contains("test opt-ins"))
        val nativeEffective = effectiveConfiguration(project)
        val differentLink = lock(nativeEffective).let { locked -> locked.copy(selections = locked.selections.map { entry ->
            if (entry.domain == ResolutionDomain("app", "macosArm64", "test", "kotlin-runtime")) entry.copy(selection = entry.selection.copy(
                artifacts = entry.artifacts.map { it.copy(digest = digest("runtime ${it.coordinate}")) })) else entry
        }) }
        assertTrue(assertFails { projectActionGraph(nativeEffective, differentLink, snapshot(), composition, tools, request(ProjectMode.TEST, "macosArm64")) }
            .message!!.contains("distinct compile/link"))
    }
    @Test fun jvmTargetVariantsFollowConsumerAndModuleCompilationSeparately() {
        fun at(module: ModuleSpec, version: Int) = module.copy(targets = module.targets.map { if (it.name == "jvm") it.copy(compilerOptions = listOf("-jvm-target=$version")) else it })
        val plugin = CompilerPluginSpec(null, Coordinate.parse("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20"), setOf("jvm"), setOf("main"), emptyList(), emptyList())
        val project = configuration(at(base, 17), at(app.copy(compilerPlugins = listOf(plugin)), 21))
        val effective = effectiveConfiguration(project, scopes = listOf(DependencyScope("app", "jvm", "main", "java-api")))
        val stdlib = effective.requests.filter { it.coordinate.name == "stdlib" }
        assertEquals(setOf("17", "21"), stdlib.map { it.variant.attributes.getValue(JVM_TARGET_VERSION_ATTRIBUTE) }.toSet())
        assertEquals("25", effective.requests.single { it.coordinate == plugin.coordinate }.variant.attributes.getValue(JVM_TARGET_VERSION_ATTRIBUTE))
        val locked = lock(effective).let { value -> value.copy(selections = value.selections.map { s -> s.copy(selection = s.selection.copy(artifacts = s.artifacts.map {
            it.copy(url = it.url.removeSuffix(".jar") + "-${s.variant.attributes[JVM_TARGET_VERSION_ATTRIBUTE]}.jar",
                digest = digest(it.coordinate.toString() + s.variant.attributes[JVM_TARGET_VERSION_ATTRIBUTE]))
        })) }) }
        val actions = resolved(projectActionGraph(effective, locked, snapshot(), composition, tools, request()))
        assertEquals(17, JvmCompilation.fromJson(actions.getValue("base:jvm:main").parameters).jvmTarget)
        assertEquals(21, JvmCompilation.fromJson(actions.getValue("app:jvm:main").parameters).jvmTarget)
        assertTrue(actions.getValue("base:jvm:main").inputs.files.any { it.digest == digest("g:stdlib:117") })
        assertTrue(actions.getValue("app:jvm:main").inputs.files.any { it.digest == digest("g:stdlib:121") })
        assertFalse(actions.getValue("app:jvm:main").inputs.files.any { it.digest == digest("g:stdlib:117") })
        assertFails { effectiveConfiguration(configuration(at(base, 21), at(app, 17))) }
    }
    @Test fun transitiveModulesAndResourcesEnterConsumerInputsWithoutTargetLeakage() {
        val leaf = module("leaf", listOf(external("g:leaf-api:1")))
        val intermediate = base.copy(sourceSets = base.sourceSets.map { if(it.name == "commonMain") it.copy(dependencies = it.dependencies + Dependency.Module("leaf", exported = true)) else it })
        val files = TreeManifest(snapshot().files + snapshot().files.filter { it.path.value.startsWith("base/") }
            .map { it.copy(path = LogicalPath(it.path.value.replaceFirst("base/", "leaf/"))) })
        val plan = graph(configuration(leaf, intermediate, app), files, request(ProjectMode.TEST))
        assertEquals(setOf("base:jvm:main", "leaf:jvm:main"), plan.nodes.single { it.id == "app:jvm:main" }.dependencies)
        val actions = resolved(plan)
        val compile = JvmCompilation.fromJson(actions.getValue("app:jvm:main").parameters)
        assertEquals(listOf("modules/base/jvm/main", "modules/leaf/jvm/main"), compile.classpath.filter { it.value.startsWith("modules/") }.map { it.value })
        assertTrue(actions.getValue("app:jvm:test").inputs.files.any { it.path.value == "runtime/leaf/main/config.txt" })
        assertTrue(plan.nodes.none { "macosArm64" in it.id || it.id == "leaf:jvm:test" })
        val changed = TreeManifest(files.files.map { if(it.path.value == "leaf/resources/config.txt") it.copy(digest = digest("changed resource")) else it })
        val after = resolved(graph(configuration(leaf, intermediate, app), changed, request(ProjectMode.TEST)))
        assertNotEquals(actions.getValue("app:jvm:test").key(Sha256), after.getValue("app:jvm:test").key(Sha256))
        assertEquals(actions.getValue("app:jvm:main").key(Sha256), after.getValue("app:jvm:main").key(Sha256))
    }

    @Test fun moduleClosureSourcesAndOutputNamespacesAreDeterministic() {
        val graph = graph()
        assertEquals(listOf("base:jvm:main", "app:jvm:main"), graph.nodes.map { it.id })
        assertEquals(setOf("app:jvm:build"), graph.requestedChecks)
        assertEquals(mapOf("app:jvm:build" to "app:jvm:main"), graph.terminalActions)
        assertEquals(setOf("base:jvm:main"), graph.nodes.last().dependencies)
        val actions = resolved(graph)
        val app = JvmCompilation.fromJson(actions.getValue("app:jvm:main").parameters)
        assertEquals(listOf(LogicalPath("src/app/common/Common.kt")), app.commonSources)
        assertEquals(setOf("src/app/common/Common.kt", "src/app/jvm/Actual.kt"), app.sources.map { it.value }.toSet())
        assertTrue(LogicalPath("modules/base/jvm/main") in app.classpath)
        assertTrue(actions.getValue("app:jvm:main").inputs.files.any { it.path.value == "modules/base/jvm/main/Example.class" })
        assertNotEquals(actions.getValue("app:jvm:main").key(Sha256), resolved(graph, "base:jvm:main").getValue("app:jvm:main").key(Sha256))
        val reordered = graph(files = TreeManifest(snapshot().files.reversed()))
        assertEquals(actions.mapValues { it.value.key(Sha256) }, resolved(reordered).mapValues { it.value.key(Sha256) })
        val unrelated = graph(files = TreeManifest(snapshot().files + FileEntry(LogicalPath("unselected/Other.kt"), digest("other"))))
        assertEquals(actions.mapValues { it.value.key(Sha256) }, resolved(unrelated).mapValues { it.value.key(Sha256) })
    }

    @Test fun jvmTestsUseApiForCompileAndRuntimeForExecutionWithResources() {
        val plan = graph(request = request(ProjectMode.TEST))
        val actions = resolved(plan)
        val compileAction = actions.getValue("app:jvm:test-compile")
        val compile = JvmCompilation.fromJson(compileAction.parameters)
        assertTrue(compile.sources.none { it.value.contains("/common/") || it.value.contains("/jvm/") })
        assertEquals(listOf(LogicalPath("src/app/commonTest/Tests.kt")), compile.commonSources)
        assertEquals(listOf(LogicalPath("modules/app/jvm/main")), compile.friendPaths)
        val run = actions.getValue("app:jvm:test")
        val selection = JvmTestSelection.fromJson(run.parameters)
        val runtimeDigest = artifact("g:runtime-only:1", "jvm", "runtime").digest
        assertFalse(compileAction.inputs.files.any { it.digest == runtimeDigest })
        assertTrue(run.inputs.files.any { it.digest == runtimeDigest })
        assertTrue(listOf("runtime/app/test", "runtime/app/main", "runtime/base/main").all { LogicalPath(it) in selection.classpath })
        assertTrue(selection.classpath.indexOf(LogicalPath("runtime/app/test")) < selection.classpath.indexOf(LogicalPath("runtime/app/main")))
        assertTrue(run.inputs.files.any { it.path.value == "runtime/app/test/config.txt" })
        assertEquals(CachePolicy.NEVER, run.cachePolicy)
        assertEquals(mapOf("app:jvm:test" to "app:jvm:test"), plan.terminalActions)
        val noTests = graph(files = TreeManifest(snapshot().files.filterNot { it.path.value.contains("Test/") }), request = request(ProjectMode.TEST, classes = emptyList()))
        assertFalse(noTests.nodes.any { it.id == "app:jvm:test-compile" })
        val emptySelection = JvmTestSelection.fromJson(resolved(noTests).getValue("app:jvm:test").parameters)
        assertTrue(emptySelection.classes.isEmpty() && emptySelection.classpath.isNotEmpty())
        assertEquals(CachePolicy.NEVER, resolved(noTests).getValue("app:jvm:test").cachePolicy)
    }

    @Test fun nativeRunnerRecompilesMainAndIncludesMainOnlyPluginAndResources() {
        val plugin = CompilerPluginSpec(null, Coordinate.parse("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20"), setOf("macosArm64"), setOf("main"), emptyList(), emptyList())
        val plan = graph(configuration(base, app.copy(compilerPlugins = listOf(plugin))), request = request(ProjectMode.TEST, "macosArm64"))
        val actions = resolved(plan)
        val link = NativeCompilation.fromJson(actions.getValue("app:macosArm64:test-compile").parameters)
        assertEquals(NativeOutput.TEST, link.produce)
        assertTrue(link.sources.any { it.value == "src/app/common/Common.kt" } && link.sources.any { it.value == "src/app/commonTest/Tests.kt" })
        assertEquals(listOf(LogicalPath("modules/base/macosArm64/main.klib")), link.libraries.filter { it.value.startsWith("modules/") })
        assertTrue(link.plugins.single().value.startsWith("plugins/"))
        assertTrue(link.plugins.none { it in link.libraries })
        assertTrue("-generate-test-runner" in actions.getValue("app:macosArm64:test-compile").arguments)
        val run = actions.getValue("app:macosArm64:test")
        assertTrue(run.inputs.files.any { it.path.value == "modules/app/macosArm64/test/tests.kexe" && it.executable })
        assertTrue(run.inputs.files.any { it.path.value == "runtime/base/main/config.txt" })
        assertEquals(CachePolicy.NEVER, run.cachePolicy)
        val zero = graph(files = TreeManifest(snapshot().files.filterNot { it.path.value.contains("Test/") }), request = request(ProjectMode.TEST, "macosArm64"))
        assertTrue(zero.nodes.any { it.id == "app:macosArm64:test-compile" })
        assertEquals(NativeOutput.TEST, NativeCompilation.fromJson(resolved(zero).getValue("app:macosArm64:test-compile").parameters).produce)
    }

    @Test fun compilerOptionsAndSingleTargetCommonConventionAreExplicit() {
        val arguments = listOf("-jvm-target=21", "-opt-in", "kotlin.ExperimentalStdlibApi", "-Xexplicit-api=strict", "-Werror", "-language-version", "2.4", "-api-version=2.4", "-Xmulti-platform")
        val singleBase = base.copy(targets = base.targets.filter { it.name == "jvm" })
        val singleApp = app.copy(targets = listOf(app.targets.first().copy(compilerOptions = arguments)))
        val compile = JvmCompilation.fromJson(resolved(graph(configuration(singleBase, singleApp))).getValue("app:jvm:main").parameters)
        assertEquals(21, compile.jvmTarget); assertTrue(compile.explicitApi && compile.warningsAsErrors)
        assertEquals(listOf("kotlin.ExperimentalStdlibApi"), compile.optIns)
        assertEquals(listOf(LogicalPath("src/app/common/Common.kt")), compile.commonSources)
        val intermediate = app.copy(sourceSets = app.sourceSets.map { if(it.name == "jvmMain") it.copy(dependsOn = listOf("jvmIntermediate")) else it } +
            source("jvmIntermediate", "app/intermediate", listOf("commonMain")))
        val withIntermediate = graph(configuration(base, intermediate), TreeManifest(snapshot().files + FileEntry(LogicalPath("app/intermediate/Actual.kt"), digest("platform actual"))))
        val platform = JvmCompilation.fromJson(resolved(withIntermediate).getValue("app:jvm:main").parameters)
        assertTrue(LogicalPath("src/app/intermediate/Actual.kt") in platform.sources)
        assertFalse(LogicalPath("src/app/intermediate/Actual.kt") in platform.commonSources)
        val native = app.copy(targets = app.targets.map { if(it.name == "macosArm64") it.copy(compilerOptions = listOf("-module-name", "io.example:app")) else it })
        assertEquals("io.example:app", NativeCompilation.fromJson(resolved(graph(configuration(base, native), request = request(target = "macosArm64"))).getValue("app:macosArm64:main").parameters).moduleName)
        for (options in listOf(listOf("-classpath", "evil"), listOf("-Xplugin=evil"), listOf("-jvm-target=8"), listOf("-Werror", "-Werror"), listOf("-module-name", "changed"))) {
            assertFails { graph(configuration(base, app.copy(targets = app.targets.map { if(it.name == "jvm") it.copy(compilerOptions = options) else it }))) }
        }
    }

    @Test fun pluginPathsAreSeparatedAndUnsupportedCombinationsRefused() {
        val plugin = CompilerPluginSpec(null, Coordinate.parse("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20"), setOf("jvm"), setOf("main", "test"), emptyList(), emptyList())
        val actions = resolved(graph(configuration(base, app.copy(compilerPlugins = listOf(plugin))), request = request(ProjectMode.TEST)))
        for (id in listOf("app:jvm:main", "app:jvm:test-compile")) {
            val compile = JvmCompilation.fromJson(actions.getValue(id).parameters)
            assertTrue(compile.plugins.single().value.startsWith("plugins/") && compile.plugins.none { it in compile.classpath })
        }
        assertTrue(actions.getValue("app:jvm:test").inputs.files.none { it.path.value.startsWith("plugins/") })
        for (bad in listOf(plugin.copy(options = listOf("unsupported=true")), plugin.copy(inputs = listOf(LogicalPath("config.json"))),
            plugin.copy(coordinate = Coordinate.parse("other:plugin:1")))) {
            assertFails { graph(configuration(base, app.copy(compilerPlugins = listOf(bad)))) }
        }
    }

    @Test fun lockScopesPrerequisitesAndUnsupportedSourceLayoutsFail() {
        val config = project; val effective = effectiveConfiguration(config, scopes = listOf(DependencyScope("app", "jvm", "main", "java-api")))
        val pinned = lock(effective)
        assertFails { projectActionGraph(effective, pinned.copy(configuration = digest("stale")), snapshot(), composition, tools, request()) }
        assertFails { projectActionGraph(effective, pinned, snapshot(), composition, tools, request(ProjectMode.TEST)) }
        assertFails { projectActionGraph(effective, pinned.copy(selections = emptyList()), snapshot(), composition, tools, request()) }
        val plan = graph(); val appNode = plan.nodes.last()
        assertFails { appNode.resolve(emptyMap()) }
        assertFails { appNode.resolve(mapOf("base:jvm:main" to ActionResult("base:jvm:main", null, ActionStatus.FAILED))) }
        assertFails { appNode.resolve(mapOf("base:jvm:main" to ActionResult("base:jvm:main", digest("key"), ActionStatus.EXECUTED, TreeManifest(listOf(FileEntry(LogicalPath("wrong.class"), digest("bytes")))), 0))) }
        assertFails { graph(files = TreeManifest(snapshot().files + FileEntry(LogicalPath("app/jvm/Hidden.java"), digest("java")))) }
        assertFails { graph(files = TreeManifest(snapshot().files.filterNot { it.path.value.startsWith("app/common/") || it.path.value.startsWith("app/jvm/") })) }
        val overlap = app.copy(sourceSets = app.sourceSets.map { if(it.name == "jvmMain") it.copy(directories = listOf(LogicalPath("app/common"))) else it })
        assertFails { graph(configuration(base, overlap)) }
        val resourceOverlay = app.copy(sourceSets = app.sourceSets.map { if(it.name == "jvmMain") it.copy(resources = listOf(LogicalPath("app/test-resources"))) else it })
        assertFails { graph(configuration(base, resourceOverlay)) }
        assertFails { graph(request = request().copy(targets = setOf("unknown"))) }
        assertFails { graph(request = request(ProjectMode.TEST).copy(jvmTests = emptyMap())) }
    }

    @Test fun existingCommonJvmFixtureSnapshotPlansWithoutImplicitDependencies() {
        val path = getenv("KVIK_GRAPH_FIXTURE")!!.toKString()
        val handle = fopen(path, "rb")!!
        val bytes = try {
            check(fseek(handle, 0, SEEK_END) == 0); val length = ftell(handle).toInt(); rewind(handle)
            ByteArray(length).also { data -> data.usePinned { check(fread(it.addressOf(0), 1u, length.toULong(), handle) == length.toULong()) } }
        } finally { fclose(handle) }
        val fixture = parseJson(bytes.decodeToString()).objectValue()
        val configuration = parseProjectConfiguration(fixture.getValue("project"))
        val snapshot = TreeManifest.fromJson(fixture.getValue("snapshot"))
        val effective = effectiveConfiguration(configuration)
        val plan = projectActionGraph(effective, lock(effective), snapshot, composition, tools, ProjectGraphRequest(setOf("library"), setOf("jvm"), ProjectMode.BUILD))
        val action = plan.nodes.single().resolve(emptyMap())
        val compilation = JvmCompilation.fromJson(action.parameters)
        assertEquals(listOf(LogicalPath("src/fixture/common/Common.kt")), compilation.commonSources)
        assertEquals(2, compilation.sources.size)
        assertTrue(compilation.classpath.isEmpty()) // Root integration must explicitly declare stdlib; graph injects nothing.
        assertEquals(snapshot.files.map { it.digest }.toSet(), action.inputs.files.map { it.digest }.toSet())
    }
}
