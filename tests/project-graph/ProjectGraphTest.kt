@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kvik.compiler.jvm.*
import kvik.compiler.native.*
import kvik.compiler.ksp.*
import kvik.library.*
import kvik.abi.*
import kvik.documentation.*
import kotlin.test.*
import kotlinx.cinterop.*
import platform.posix.*

private fun digest(value: String) = Sha256.digest(value.encodeToByteArray())
private val composition = digest("runtime")
private val tools = mapOf("jvm" to listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith(::digest),
    "macosArm64" to listOf("native", "jdk", "sdk", "native-dependencies").associateWith(::digest),
    "linuxX64" to listOf("native", "jdk", "system", "native-dependencies").associateWith(::digest)) +
    listOf("iosArm64", "iosSimulatorArm64").associateWith { listOf("native", "jdk", "sdk", "native-dependencies").associateWith(::digest) }
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
            "targets" to m.targets.map { t -> obj("name" to t.name.json(), "main" to t.main.json(), "test" to (t.test?.json() ?: JsonValue.Null), "compilerOptions" to strings(t.compilerOptions)).let { fields -> if (t.testCompilerOptions == null) fields else JsonValue.Object(fields.objectValue() + ("testCompilerOptions" to strings(t.testCompilerOptions))) }.let { fields -> if (t.application == null) fields else JsonValue.Object(fields.objectValue() + ("application" to t.application.toJson())) }.let { fields -> if (t.nativeFramework == null) fields else JsonValue.Object(fields.objectValue() + ("nativeFramework" to t.nativeFramework.toJson())) }.let { fields -> if (t.abi == null) fields else JsonValue.Object(fields.values + ("abi" to t.abi.toJson())) }.let { fields -> if (t.nativeTimeouts == null) fields else JsonValue.Object(fields.objectValue() + ("nativeTimeouts" to JsonValue.Object(buildMap {
                t.nativeTimeouts.compileMillis?.let { put("compileMillis", it.json()) }; t.nativeTimeouts.linkMillis?.let { put("linkMillis", it.json()) }
            }))) } }.json(),
            "compilerPlugins" to m.compilerPlugins.map { p -> JsonValue.Object(mapOf("targets" to strings(p.targets), "scopes" to strings(p.scopes), "options" to strings(p.options), "inputs" to paths(p.inputs)) +
                if (p.coordinate != null) mapOf("coordinate" to p.coordinate.toString().json()) else mapOf("module" to p.module!!.json(), "id" to p.id!!.json(), "compilerVersion" to p.compilerVersion!!.json(), "pathOptions" to p.pathOptions.map { it.toJson() }.json(), "outputs" to paths(p.outputs))) }.json()).let { value -> if (m.documentation == null) value else JsonValue.Object(value.values + ("documentation" to m.documentation.toJson())) }.let { value -> if (m.publication == null) value else JsonValue.Object(value.objectValue() + ("publication" to obj("coordinate" to m.publication.coordinate.toString().json()))) } }.json()))
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
    val extension = if (target in setOf("jvm", "common")) "jar" else "klib"
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
    "kvik-ksp-native-v1" -> listOf(FileEntry(kotestGeneratedLauncher, digest(salt)))
    "kvik-documentation-native-stdlib-v1" -> listOf(FileEntry(LogicalPath("stdlib/default/manifest"), digest(salt)))
    "kvik-jvm-metadata-v1" -> listOf(FileEntry(LogicalPath("metadata/default/manifest"), digest(salt)))
    "kvik-jvm-compiler-v1" -> listOf(FileEntry(LogicalPath("classes/Example.class"), digest(salt)))
    "kvik-native-compiler-v1" -> if (action.kind == ActionKind.LINK) NativeCompilation.fromJson(action.parameters).let { compilation ->
        listOf(FileEntry(compilation.output, digest(salt), true)) + if (compilation.target == NativeTarget.MACOS_ARM64)
            listOf(FileEntry(LogicalPath(compilation.output.value + ".dSYM/Contents/Info.plist"), digest("symbols"))) else emptyList() }
        else listOf(FileEntry(NativeCompilation.fromJson(action.parameters).output, digest(salt)))
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

private fun modulePathForTest(module: String, target: String) = LogicalPath("modules/$module/$target/main" + if (target == "jvm") "" else ".klib")
private fun applicationFixture(settings: (String) -> ApplicationSpec = { target -> ApplicationSpec(if (target == "jvm") "fixture.MainKt" else "fixture.main") }): Pair<ProjectConfiguration, TreeManifest> {
    val hidden = module("hidden", listOf(Dependency.External(Coordinate.parse("g:hidden:1"))))
    val library = module("base", listOf(external("g:public:1"), Dependency.External(Coordinate.parse("g:private:1")), Dependency.Module("hidden")))
    val runtime = module("runtime", listOf(Dependency.External(Coordinate.parse("g:runtime-private:1"))))
    val main = module("app", listOf(Dependency.Module("base"), Dependency.Module("runtime", DependencyScopeKind.RUNTIME),
        Dependency.External(Coordinate.parse("g:library:1")), Dependency.External(Coordinate.parse("g:only-runtime:1"), DependencyScopeKind.RUNTIME)))
        .let { it.copy(targets = it.targets.map { target -> target.copy(application = settings(target.name)) }) }
    val additional = listOf("hidden", "runtime").flatMap { id -> listOf("common/Common.kt", "jvm/Actual.kt", "native/Actual.kt", "resources/config.txt")
        .map { FileEntry(LogicalPath("$id/$it"), digest("$id/$it")) } }
    return configuration(library, hidden, runtime, main) to TreeManifest(snapshot().files + additional)
}

private fun published(module: ModuleSpec, resources: Boolean = true) = module.copy(
    publication = LibraryPublicationSpec(Coordinate("fixture", module.id, "1")),
    sourceSets = module.sourceSets.map { if (resources) it else it.copy(resources = emptyList()) })

class ProjectGraphTest {
    @Test fun documentationCombinesSourcesAndUsesOnlyDependencyArtifactsWithoutPublication() {
        val settings = DocumentationSettings(includes = listOf(LogicalPath("Module.md")))
        val config = configuration(base, app.copy(documentation = settings))
        val files = TreeManifest(snapshot().files + FileEntry(LogicalPath("Module.md"), digest("module docs")))
        val request = ProjectGraphRequest(setOf("app"), setOf("jvm", "macosArm64"), ProjectMode.DOCUMENTATION)
        val built = graph(config, files, request)
        assertEquals(setOf("app:common:documentation"), built.requestedChecks)
        assertEquals(built.requestedChecks, projectRequestedChecks(request))
        assertEquals(setOf("jvm", "macosArm64"), projectToolTargets(config.project, request))
        assertFalse(built.nodes.any { it.id.endsWith(":test") || it.id.endsWith(":framework") || it.id.endsWith(":package") })
        val actions = resolved(built)
        val action = actions.getValue("app:common:documentation")
        assertEquals("kvik-dokka-html-v1", action.implementation)
        val spec = DocumentationSpec.fromJson(action.parameters)
        assertEquals(listOf("commonMain", "jvmMain", "nativeMain"), spec.sourceSets.map { it.name })
        assertEquals(listOf("common", "jvm", "native"), spec.sourceSets.map { it.platform })
        assertEquals(listOf("commonMain"), spec.sourceSets.single { it.name == "nativeMain" }.dependsOn)
        assertTrue(action.inputs.files.any { it.path.value == "sources/commonMain/root0/Common.kt" })
        assertTrue(action.inputs.files.any { it.path.value.startsWith("modules/base/") })
        assertFalse(action.inputs.files.any { it.path.value.startsWith("modules/app/") || "/commonTest/" in it.path.value })
        assertContains(built.nodes.single { it.id == "app:common:documentation" }.dependencies, "app:macosArm64:main")
        assertContains(built.nodes.single { it.id == "app:common:documentation" }.dependencies, "base:common:documentation-metadata-jvm-macosArm64")
        val changed = resolved(graph(config, TreeManifest(snapshot().files + FileEntry(LogicalPath("Module.md"), digest("new docs"))), request))
        assertNotEquals(action.key(Sha256), changed.getValue("app:common:documentation").key(Sha256))
        actions.filterKeys { it != "app:common:documentation" }.forEach { (id, value) -> assertEquals(value.key(Sha256), changed.getValue(id).key(Sha256)) }
    }

    @Test fun documentationSingleTargetRetainsCommonRefinementAndOrdinaryJvmWorks() {
        val configured = app.copy(documentation = DocumentationSettings())
        val config = configuration(base, configured)
        val request = request(ProjectMode.DOCUMENTATION)
        val action = resolved(graph(config, snapshot(), request)).getValue("app:common:documentation")
        val spec = DocumentationSpec.fromJson(action.parameters)
        assertEquals(setOf("jvm"), spec.targets)
        assertEquals(listOf("common", "jvm"), spec.sourceSets.map { it.platform })
        assertEquals(setOf("jvm", "macosArm64"), spec.sourceSets.first().supportingTargets)
        assertEquals(listOf("commonMain"), spec.sourceSets.last().dependsOn)
        val single = configured.copy(targets = configured.targets.filter { it.name == "jvm" }, sourceSets = configured.sourceSets.map { it.copy(dependencies = emptyList()) })
        val ordinary = resolved(graph(configuration(single), snapshot(), request)).getValue("app:common:documentation")
        assertTrue(DocumentationSpec.fromJson(ordinary.parameters).sourceSets.all { it.platform == "jvm" })
    }

    @Test fun nativeTestFragmentsPreserveImplicitMainVisibilityInInheritedTests() {
        val standaloneTests = app.copy(sourceSets = app.sourceSets.map { set ->
            when (set.name) {
                "commonTest" -> set.copy(dependsOn = emptyList())
                "nativeTest" -> set.copy(dependsOn = listOf("commonTest"))
                else -> set
            }
        })
        val actions = resolved(graph(configuration(base, standaloneTests), snapshot(), request(ProjectMode.TEST, "macosArm64")))
        val compilation = NativeCompilation.fromJson(actions.getValue("app:macosArm64:test-compile").parameters)
        assertContains(compilation.sourceFragments.single { it.name == "commonTest" }.dependsOn, "nativeMain")
        assertEquals(listOf("commonTest"), compilation.sourceFragments.single { it.name == "nativeTest" }.dependsOn)
        assertEquals(compilation.sources.toSet(), compilation.sourceFragments.flatMap { it.sources }.toSet())
        val link = NativeCompilation.fromJson(actions.getValue("app:macosArm64:test-link").parameters)
        assertTrue(link.sourceFragments.isEmpty())
    }

    @Test fun documentationSharedNativeUsesMetadataIntersectionAndExactSupportingTargets() {
        fun ios(module: ModuleSpec) = module.copy(targets = listOf(module.targets.first { it.name == "jvm" },
            TargetSpec("iosArm64", "nativeMain", null, emptyList()), TargetSpec("iosSimulatorArm64", "nativeMain", null, emptyList())))
        val config = configuration(ios(base), ios(app).copy(documentation = DocumentationSettings()))
        val request = ProjectGraphRequest(setOf("app"), setOf("jvm", "iosArm64", "iosSimulatorArm64"), ProjectMode.DOCUMENTATION)
        val built = graph(config, snapshot(), request)
        val actions = resolved(built)
        val spec = DocumentationSpec.fromJson(actions.getValue("app:common:documentation").parameters)
        for (target in listOf("iosArm64", "iosSimulatorArm64")) {
            val compilation = NativeCompilation.fromJson(actions.getValue("app:$target:main").parameters)
            assertEquals(listOf("commonMain"), compilation.sourceFragments.single { it.name == "nativeMain" }.dependsOn)
            val terminal = compilation.sourceFragments.last()
            assertTrue(terminal.sources.isEmpty())
            assertEquals(listOf("nativeMain"), terminal.dependsOn)
            assertEquals(compilation.sources.toSet(), compilation.sourceFragments.flatMap { it.sources }.toSet())
            assertTrue(compilation.arguments().none { it.startsWith("-Xcommon-sources=") })
        }
        val native = spec.sourceSets.single { it.name == "nativeMain" }
        assertEquals("native", native.platform)
        assertEquals(setOf("iosArm64", "iosSimulatorArm64"), native.supportingTargets)
        assertTrue(native.classpath.any { "documentation-metadata-iosArm64-iosSimulatorArm64" in it.value })
        assertTrue(native.classpath.none { it.value.endsWith(".klib") })
        assertTrue(native.classpath.any { it.value.startsWith("native-stdlib/") })
        val metadata = JvmMetadataCompilation.fromJson(actions.getValue("base:common:documentation-metadata-iosArm64-iosSimulatorArm64").parameters)
        assertEquals(native.supportingTargets, metadata.targets)
        assertContains(metadata.classpath, LogicalPath("native-stdlib/shared"))
        assertTrue(actions.containsKey("base:iosArm64:documentation-stdlib"))
        val subset = resolved(graph(config, snapshot(), request.copy(targets = setOf("iosSimulatorArm64"))))
        val subsetMetadata = JvmMetadataCompilation.fromJson(subset.getValue("base:common:documentation-metadata-iosArm64-iosSimulatorArm64").parameters)
        assertContains(subsetMetadata.classpath, LogicalPath("native-stdlib/shared"))
        assertTrue(subset.keys.none { ":iosArm64:documentation-stdlib" in it })
        assertTrue(metadata.sources.any { it.value.contains("/native/") })
        assertTrue(metadata.sources.none { it.value.contains("/jvm/") })
        assertEquals(request.targets, spec.sourceSets.single { it.name == "commonMain" }.supportingTargets)
    }

    @Test fun documentationDependencyMetadataPreservesWorkspacePlugins() {
        val plugin = CompilerPluginSpec("producer", null, setOf("jvm", "macosArm64"), setOf("main"),
            listOf("mode=first"), listOf(LogicalPath("config.txt")), "example.plugin", "2.4.20",
            listOf(PluginPathOption("report", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("report"))), listOf(LogicalPath("report")))
        val producer = module("producer")
        val extra = listOf("common/Common.kt", "jvm/Actual.kt").map { FileEntry(LogicalPath("producer/$it"), digest(it)) }
        val files = TreeManifest(snapshot().files + extra + FileEntry(LogicalPath("config.txt"), digest("config")))
        fun plan(selected: CompilerPluginSpec = plugin, inputs: TreeManifest = files) = resolved(graph(configuration(
            base.copy(compilerPlugins = listOf(selected)), producer, app.copy(documentation = DocumentationSettings())), inputs,
            ProjectGraphRequest(setOf("app"), setOf("jvm", "macosArm64"), ProjectMode.DOCUMENTATION)))
        val actions = plan()
        val id = "base:common:documentation-metadata-jvm-macosArm64"
        val action = actions.getValue(id)
        val compilation = JvmMetadataCompilation.fromJson(action.parameters)
        assertEquals(plugin.options, compilation.pluginApplications.single().options)
        assertTrue(actions.containsKey("producer:jvm:main"))
        assertTrue(action.inputs.files.any { it.path.value == "plugin-inputs/example.plugin/config.txt" })
        assertNotEquals(action.key(Sha256), plan(plugin.copy(options = listOf("mode=second"))).getValue(id).key(Sha256))
        val changed = TreeManifest(files.files.map { if (it.path.value == "config.txt") it.copy(digest = digest("changed")) else it })
        assertNotEquals(action.key(Sha256), plan(inputs = changed).getValue(id).key(Sha256))
        assertTrue(actions.getValue("app:common:documentation").inputs.files.none { it.path.value.startsWith("plugin-inputs/") })
    }

    @Test fun documentationEmptySharedRefinementDoesNotRequireAnEmptyMetadataCompilation() {
        fun emptyShared(module: ModuleSpec) = module.copy(sourceSets = module.sourceSets.map {
            if (it.name == "commonMain") it.copy(directories = emptyList()) else it
        })
        val config = configuration(emptyShared(base), emptyShared(app).copy(documentation = DocumentationSettings()))
        val request = ProjectGraphRequest(setOf("app"), setOf("jvm", "macosArm64"), ProjectMode.DOCUMENTATION)
        assertTrue(effectiveConfiguration(config).requests.any { it.domain.target == "common" })
        val actions = resolved(graph(config, snapshot(), request))
        assertTrue(actions.keys.none { ":documentation-metadata-" in it })
        val action = actions.getValue("app:common:documentation")
        val spec = DocumentationSpec.fromJson(action.parameters)
        assertEquals("common", spec.sourceSets.first().platform)
        assertTrue(action.inputs.files.none { it.path.value.startsWith("sources/commonMain/") })
        assertTrue(spec.sourceSets.first().classpath.isNotEmpty())
    }

    @Test fun documentationOptInChangesOnlyExplicitCommonResolutionAndRequiresIncludes() {
        val plain = configuration(module("app"))
        assertTrue(effectiveConfiguration(plain).requests.none { it.domain.target == "common" })
        assertFails { graph(plain, snapshot(), request(ProjectMode.DOCUMENTATION)) }
        val config = configuration(module("app").copy(documentation = DocumentationSettings(includes = listOf(LogicalPath("missing.md")))))
        assertTrue(effectiveConfiguration(config).requests.any { it.domain.target == "common" && it.coordinate.name == "kotlin-stdlib" })
        assertFails { graph(config, snapshot(), request(ProjectMode.DOCUMENTATION)) }
    }

    @Test fun abiChecksConsumeOnlyOwnMainAndBaselineChangesOnlyComparison() {
        for (target in listOf("jvm", "macosArm64", "iosArm64")) {
            val baseline = LogicalPath("api/$target.api")
            val targetSpec = TargetSpec(target, if (target == "jvm") "jvmMain" else "nativeMain", null, emptyList(),
                abi = AbiSettings(baseline), nativeFramework = if (target == "iosArm64") NativeFrameworkSpec("Sample", false) else null)
            val spec = module("app").copy(targets = listOf(targetSpec))
            val config = configuration(spec)
            val request = ProjectGraphRequest(setOf("app"), setOf(target), ProjectMode.ABI_CHECK)
            assertContains(projectToolTargets(config.project, request), "jvm")
            val sourceFiles = snapshot().files.filter { it.path.value.startsWith("app/") }
            fun make(value: String) = graph(config, TreeManifest(sourceFiles + FileEntry(baseline, digest(value))), request)
            val graph = make("old")
            val main = "app:$target:main"; val dump = "app:$target:abi-dump"; val check = "app:$target:abi-check"
            assertEquals(setOf(main, dump, check), graph.nodes.map { it.id }.toSet())
            assertEquals(mapOf(check to check), graph.terminalActions)
            fun actions(graph: ProjectActionGraph, compiled: String): Triple<ActionSpec, ActionSpec, ActionSpec> {
                val compile = graph.nodes.single { it.id == main }.resolve(emptyMap())
                val mainOutput = TreeManifest(listOf(FileEntry(LogicalPath(if (target == "jvm") "classes/Api.class" else "module.klib"), digest(compiled)),
                    FileEntry(LogicalPath("plugins/report.txt"), digest("ignored"))))
                val extracted = graph.nodes.single { it.id == dump }.resolve(mapOf(main to ActionResult(main, compile.key(Sha256), ActionStatus.EXECUTED, mainOutput, 0)))
                assertEquals(1, extracted.inputs.files.size)
                assertEquals(tools.getValue("jvm"), extracted.toolchains)
                val candidate = TreeManifest(listOf(FileEntry(LogicalPath("abi.json"), digest("snapshot:$compiled")), FileEntry(LogicalPath("api.txt"), digest("api:$compiled"))))
                val compared = graph.nodes.single { it.id == check }.resolve(mapOf(dump to ActionResult(dump, extracted.key(Sha256), ActionStatus.EXECUTED, candidate, 0)))
                return Triple(compile, extracted, compared)
            }
            val first = actions(graph, "first")
            val changedBaseline = actions(make("new"), "first")
            assertEquals(first.first.key(Sha256), changedBaseline.first.key(Sha256))
            assertEquals(first.second.key(Sha256), changedBaseline.second.key(Sha256))
            assertNotEquals(first.third.key(Sha256), changedBaseline.third.key(Sha256))
            val changedArtifact = actions(graph, "second")
            assertNotEquals(first.second.key(Sha256), changedArtifact.second.key(Sha256))
            assertNotEquals(first.third.key(Sha256), changedArtifact.third.key(Sha256))
            assertFails { graph(config, TreeManifest(sourceFiles), request) }
            val dumpOnly = graph(config, TreeManifest(sourceFiles), request.copy(mode = ProjectMode.ABI_DUMP))
            assertEquals(setOf(main, dump), dumpOnly.nodes.map { it.id }.toSet())
            assertEquals(setOf(dump), dumpOnly.requestedChecks)
            assertEquals(setOf(main), dumpOnly.nodes.single { it.id == dump }.dependencies)
        }
    }

    @Test fun abiCheckRequiresSettingsAndRejectsFailedDumpPrerequisite() {
        assertFails { graph(request = request(ProjectMode.ABI_CHECK)) }
        val baseline = LogicalPath("app.api")
        val spec = app.copy(targets = app.targets.map { it.copy(abi = AbiSettings(baseline)) })
        val graph = graph(configuration(base, spec), TreeManifest(snapshot().files + FileEntry(baseline, digest("baseline"))), request(ProjectMode.ABI_CHECK))
        val node = graph.nodes.single { it.id == "app:jvm:abi-check" }
        assertFails { node.resolve(mapOf("app:jvm:abi-dump" to ActionResult("app:jvm:abi-dump", null, ActionStatus.FAILED))) }
    }

    @Test fun linuxWorkspacePluginUsesJvmProducerRuntimeAndHonorsMainOnlyScope() {
        val plugin = CompilerPluginSpec("producer", null, setOf("linuxX64"), setOf("main"),
            listOf("value=a,b=c"), emptyList(), "example.plugin", "2.4.20",
            listOf(PluginPathOption("report", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("report"))), listOf(LogicalPath("report")))
        val producer = module("producer", listOf(Dependency.Module("runtime", DependencyScopeKind.RUNTIME)))
        val runtime = module("runtime", listOf(external("g:host:1")))
        val original = configuration(base, app, producer, runtime)
        val linux = parseProjectConfiguration(parseJson(original.document.canonical().replace("macosArm64", "linuxX64"))).project.modules
        val config = configuration(*linux.map { if (it.id == "app") it.copy(compilerPlugins = listOf(plugin)) else it }.toTypedArray())
        val extra = listOf("producer", "runtime").flatMap { id -> listOf("common/Common.kt", "jvm/Actual.kt")
            .map { FileEntry(LogicalPath("$id/$it"), digest("$id/$it")) } }
        val selected = request(ProjectMode.TEST, "linuxX64")
        assertEquals(setOf("linuxX64", "jvm"), projectToolTargets(config.project, selected))
        val plan = graph(config, TreeManifest(snapshot().files + extra), selected)
        val actions = resolved(plan)
        assertTrue("producer:jvm:main" in actions && "runtime:jvm:main" in actions)
        val compile = NativeCompilation.fromJson(actions.getValue("app:linuxX64:main").parameters)
        val application = compile.pluginApplications.single()
        assertTrue(application.classpath.any { "runtime/classes" in it.value })
        assertTrue(application.classpath.none { it in compile.libraries })
        assertEquals(plugin.options, application.options)
        assertTrue(NativeCompilation.fromJson(actions.getValue("app:linuxX64:test-compile").parameters).pluginApplications.isEmpty())
        assertTrue(actions.getValue("app:linuxX64:test-link").inputs.files.none { it.path.value.startsWith("plugins/") })
        assertNotEquals(actions.getValue("app:linuxX64:main").key(Sha256),
            resolved(plan, "runtime:jvm:main").getValue("app:linuxX64:main").key(Sha256))
    }


    @Test fun linuxTestsUseSeparateApiLinkClosureAndLinuxTools() {
        val config = parseProjectConfiguration(parseJson(project.document.canonical().replace("macosArm64", "linuxX64")))
        val plan = graph(config, request = request(ProjectMode.TEST, "linuxX64"))
        val actions = resolved(plan)
        assertEquals(setOf("app:linuxX64:test"), plan.requestedChecks)
        assertTrue(actions.keys.none { "macosArm64" in it })
        val compiled = actions.getValue("app:linuxX64:test-compile")
        val linked = actions.getValue("app:linuxX64:test-link")
        val parameters = NativeCompilation.fromJson(linked.parameters)
        assertEquals(NativeTarget.LINUX_X64, parameters.target)
        assertEquals(LogicalPath("modules/app/linuxX64/test.klib"), parameters.includedLibrary)
        assertEquals(mapOf("os" to "linux", "arch" to "x64"), linked.platform)
        assertEquals(tools.getValue("linuxX64"), linked.toolchains)
        assertTrue(linked.arguments.containsAll(listOf("-target", "linux_x64")))
        assertTrue(NativeCompilation.fromJson(compiled.parameters).sources.any { "/common/" in it.value })
        assertEquals(CachePolicy.NEVER, actions.getValue("app:linuxX64:test").cachePolicy)
        assertEquals("linuxX64", actions.getValue("app:linuxX64:test").target)
        val wrongTools = tools + ("linuxX64" to tools.getValue("macosArm64"))
        val effective = effectiveConfiguration(config)
        assertFails { projectActionGraph(effective, lock(effective), snapshot(), composition, wrongTools, request(ProjectMode.TEST, "linuxX64")) }
        val testNode = plan.nodes.single { it.id == "app:linuxX64:test" }
        val testResults = testNode.dependencies.associateWith { id ->
            val action = actions.getValue(id)
            val extra = if (id.endsWith(":test-link")) listOf(FileEntry(LogicalPath("tests.kexe.dSYM/Contents/Info.plist"), digest("unexpected"))) else emptyList()
            ActionResult(id, action.key(Sha256), ActionStatus.EXECUTED, TreeManifest(outputs(action, id).files + extra), 0)
        }
        assertFails { testNode.resolve(testResults) }
        val altered = resolved(plan, "base:linuxX64:main")
        assertNotEquals(linked.key(Sha256), altered.getValue("app:linuxX64:test-link").key(Sha256))
    }

    @Test fun linuxKotestUsesLinuxRuntimeAndJvmHostDomains() {
        val document = project.document.canonical().replace("\"name\":\"macosArm64\"",
            """"name":"linuxX64","nativeTestSettings":{"engine":"kotest","kspVersion":"2.3.12","processor":"io.kotest:kotest-framework-symbol-processor:6.2.5"}""")
            .replace("g:tests:1", "io.kotest:kotest-framework-engine-linuxx64:6.2.5")
        val config = parseProjectConfiguration(parseJson(document))
        val effective = effectiveConfiguration(config)
        val hosts = effective.requests.filter { it.domain.target == "linuxX64" && it.domain.isHostTool }
        assertEquals(setOf("ksp-runtime", "symbol-processor"), hosts.map { it.domain.purpose }.toSet())
        assertTrue(hosts.all { it.variant.platform == "jvm" && it.variant.attributes[JVM_TARGET_VERSION_ATTRIBUTE] == "25" })
        val actions = resolved(graph(config, request = request(ProjectMode.TEST, "linuxX64")))
        val generation = actions.getValue("app:linuxX64:ksp-test")
        assertEquals(NativeTarget.LINUX_X64, KspNativeCompilation.fromJson(generation.parameters).target)
        assertTrue(generation.arguments.contains("-target=linuxX64"))
        assertEquals("kvik-kotest-native-tests-v1", actions.getValue("app:linuxX64:test").implementation)
        assertEquals("linuxX64", actions.getValue("app:linuxX64:test").target)
        val wrong = parseProjectConfiguration(parseJson(document.replace("kotest-framework-engine-linuxx64", "kotest-framework-engine-macosarm64")))
        assertFails { graph(wrong, request = request(ProjectMode.TEST, "linuxX64")) }
    }

    @Test fun linuxApplicationKeepsRuntimeOnlyDependenciesOutOfCompilation() {
        val (original, files) = applicationFixture()
        val config = parseProjectConfiguration(parseJson(original.document.canonical().replace("macosArm64", "linuxX64")))
        val actions = resolved(graph(config, files, request(ProjectMode.APPLICATION, "linuxX64")))
        val compile = NativeCompilation.fromJson(actions.getValue("app:linuxX64:main").parameters)
        val link = NativeCompilation.fromJson(actions.getValue("app:linuxX64:application-link").parameters)
        assertTrue(compile.libraries.none { "/runtime/" in it.value })
        assertTrue(link.libraries.any { it.value == "modules/runtime/linuxX64/main.klib" })
        assertEquals(NativeTarget.LINUX_X64, link.target)
        assertEquals("fixture.main", link.entryPoint)
        val bundleNode = graph(config, files, request(ProjectMode.APPLICATION, "linuxX64")).nodes.single { it.id == "app:linuxX64:application" }
        val linkAction = actions.getValue("app:linuxX64:application-link")
        assertFails { bundleNode.resolve(mapOf("app:linuxX64:application-link" to ActionResult("app:linuxX64:application-link", linkAction.key(Sha256), ActionStatus.EXECUTED,
            TreeManifest(outputs(linkAction, "linked").files + FileEntry(LogicalPath("application.kexe.dSYM/Contents/Info.plist"), digest("unexpected"))), 0))) }
        val bundle = actions.getValue("app:linuxX64:application")
        assertEquals("linuxX64", ApplicationLaunch.fromJson(bundle.parameters).target)
        assertTrue(bundle.inputs.files.any { it.path.value.startsWith("runtime/runtime/main/") })
    }

    @Test fun linuxRootMetadataUsesExactlyDeclaredTargetsIncludingNativeOnlyIntersection() {
        for (selected in listOf(setOf("jvm", "linuxX64"), setOf("macosArm64", "linuxX64"), setOf("jvm", "macosArm64", "linuxX64"))) {
            fun forTargets(value: ModuleSpec): ModuleSpec = published(value, false).copy(
                sourceSets = value.sourceSets.map { it.copy(resources = emptyList()) } + source("linuxMain", "${value.id}/linux", listOf("commonMain")),
                targets = selected.sorted().map { name ->
                    (if (name == "jvm") value.targets.first() else value.targets.last()).let {
                        it.copy(name = name, main = if (name == "linuxX64") "linuxMain" else it.main)
                    }
                })
            val config = configuration(forTargets(base), forTargets(app))
            val request = ProjectGraphRequest(setOf("app"), selected, ProjectMode.PACKAGE)
            val files = TreeManifest(snapshot().files + listOf("base", "app").map { FileEntry(LogicalPath("$it/linux/Actual.kt"), digest("linux $it")) })
            val plan = graph(config, files, request)
            assertEquals(selected.map { "app:$it:package" }.toSet() + "app:common:package", plan.requestedChecks)
            assertEquals(plan.requestedChecks, projectRequestedChecks(request))
            assertTrue("jvm" in projectToolTargets(config.project, request))
            val metadata = plan.nodes.single { it.id == "base:common:metadata" }.resolve(emptyMap())
            assertEquals(selected, JvmMetadataCompilation.fromJson(metadata.parameters).targets)
            assertTrue(metadata.inputs.files.none { "/native/" in it.path.value || "/jvm/" in it.path.value || "/linux/" in it.path.value })
            val packageNode = plan.nodes.single { it.id == "app:common:package" }
            assertTrue(packageNode.dependencies.containsAll(selected.map { "app:$it:package" }))
        }
    }


    @Test fun sharedPublishedPluginUsesLockedJvmHostArtifactForMetadata() {
        val plugin = CompilerPluginSpec(null, Coordinate("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", "2.4.20"),
            setOf("jvm", "macosArm64"), setOf("main"), emptyList(), emptyList())
        val config = configuration(published(base, false).copy(compilerPlugins = listOf(plugin)), published(app, false))
        val plan = graph(config, request = ProjectGraphRequest(setOf("app"), setOf("jvm", "macosArm64"), ProjectMode.PACKAGE))
        val metadata = plan.nodes.single { it.id == "base:common:metadata" }.resolve(emptyMap())
        val compilation = JvmMetadataCompilation.fromJson(metadata.parameters)
        assertEquals(1, compilation.plugins.size)
        assertTrue(compilation.plugins.single() in metadata.inputs.files.map { it.path })
        assertTrue(compilation.plugins.none { it in compilation.classpath })
        val jvm = JvmCompilation.fromJson(plan.nodes.single { it.id == "base:jvm:main" }.resolve(emptyMap()).parameters)
        assertEquals(jvm.plugins, compilation.plugins)
    }

    @Test fun commonMetadataUsesSharedPluginHostClosureAndExcludesReportsFromDependencies() {
        val plugin = CompilerPluginSpec("producer", null, setOf("jvm", "macosArm64"), setOf("main"),
            listOf("mode=first"), listOf(LogicalPath("config.txt")), "example.plugin", "2.4.20",
            listOf(PluginPathOption("report", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("report"))), listOf(LogicalPath("report")))
        val producer = module("producer", listOf(Dependency.Module("runtime", DependencyScopeKind.RUNTIME)))
        val runtime = module("runtime", listOf(external("g:host-runtime:1")))
        val extra = listOf("producer", "runtime").flatMap { id -> listOf("common/Common.kt", "jvm/Actual.kt", "resources/META-INF/services/Registrar")
            .map { FileEntry(LogicalPath("$id/$it"), digest("$id/$it")) } }
        val files = TreeManifest(snapshot().files + extra + FileEntry(LogicalPath("config.txt"), digest("config")))
        fun plan(selected: CompilerPluginSpec = plugin, snapshot: TreeManifest = files) = graph(configuration(
            published(base, false), producer, runtime, published(app, false).copy(compilerPlugins = listOf(selected))), snapshot,
            ProjectGraphRequest(setOf("app"), setOf("jvm", "macosArm64"), ProjectMode.PACKAGE))
        fun node(selected: CompilerPluginSpec = plugin, snapshot: TreeManifest = files) = plan(selected, snapshot).nodes.single { it.id == "app:common:metadata" }
        fun results(node: ActionNode) = node.dependencies.associateWith { id -> ActionResult(id, digest(id), ActionStatus.EXECUTED,
            TreeManifest(if (id == "base:common:metadata") listOf(
                FileEntry(LogicalPath("metadata/default/manifest"), digest("common")),
                FileEntry(LogicalPath("plugins/example.plugin/report/generated.txt"), digest("report")))
            else listOf(FileEntry(LogicalPath("classes/Producer.class"), digest(id)))), 0) }
        val common = node()
        assertEquals(setOf("base:common:metadata", "producer:jvm:main", "runtime:jvm:main"), common.dependencies)
        val inputs = results(common)
        val action = common.resolve(inputs)
        val compilation = JvmMetadataCompilation.fromJson(action.parameters)
        val application = compilation.pluginApplications.single()
        assertEquals(plugin.options, application.options)
        assertTrue(application.classpath.any { "runtime/classes" in it.value })
        assertTrue(application.classpath.none { it in compilation.classpath })
        assertTrue(action.inputs.files.any { it.path.value == "plugin-inputs/example.plugin/config.txt" })
        assertFalse(action.inputs.files.any { it.path.value.endsWith("generated.txt") })
        assertEquals(listOf(LogicalPath("metadata"), LogicalPath("plugins/example.plugin/report")), action.outputs)
        for (id in listOf("producer:jvm:main", "runtime:jvm:main")) {
            val changed = inputs.getValue(id).copy(outputs = TreeManifest(listOf(FileEntry(LogicalPath("classes/Producer.class"), digest("changed")))))
            assertNotEquals(action.key(Sha256), common.resolve(inputs + (id to changed)).key(Sha256))
        }
        val changedInput = TreeManifest(files.files.map { if (it.path.value == "config.txt") it.copy(digest = digest("changed")) else it })
        assertNotEquals(action.key(Sha256), node(snapshot = changedInput).resolve(inputs).key(Sha256))
        assertNotEquals(action.key(Sha256), node(plugin.copy(options = listOf("mode=second"))).resolve(inputs).key(Sha256))
        for (selected in listOf(plugin.copy(targets = setOf("jvm")), plugin.copy(targets = setOf("macosArm64")), plugin.copy(scopes = setOf("test")))) {
            val excluded = node(selected)
            assertEquals(setOf("base:common:metadata"), excluded.dependencies)
            assertTrue(JvmMetadataCompilation.fromJson(excluded.resolve(results(excluded)).parameters).pluginApplications.isEmpty())
        }
    }

    @Test fun multiplatformPackagingCompilesSharedMetadataAndGatesTargetChecksOnTheRoot() {
        val config = configuration(published(base, false), published(app, false))
        val plan = graph(config, request = ProjectGraphRequest(setOf("app"), setOf("jvm", "macosArm64"), ProjectMode.PACKAGE))
        assertEquals(setOf("app:jvm:package", "app:macosArm64:package", "app:common:package"), plan.requestedChecks)
        assertEquals(plan.requestedChecks, plan.terminalActions.values.toSet())
        assertEquals(plan.requestedChecks, projectRequestedChecks(ProjectGraphRequest(setOf("app"), setOf("jvm", "macosArm64"), ProjectMode.PACKAGE)))
        val common = plan.nodes.single { it.id == "app:common:metadata" }
        assertEquals(setOf("base:common:metadata"), common.dependencies)
        val baseOutputs = TreeManifest(listOf(FileEntry(LogicalPath("metadata/default/manifest"), digest("metadata"))))
        val prerequisite = ActionResult("base:common:metadata", digest("base metadata action"), ActionStatus.EXECUTED, baseOutputs, 0)
        val action = common.resolve(mapOf(prerequisite.actionId to prerequisite))
        val compilation = JvmMetadataCompilation.fromJson(action.parameters)
        assertEquals("fixture:app", compilation.module)
        assertEquals(listOf(LogicalPath("src/app/common/Common.kt")), compilation.sources)
        assertTrue(LogicalPath("modules/base/metadata") in compilation.classpath)
        assertTrue(action.inputs.files.any { it.path.value == "modules/base/metadata/default/manifest" })
        assertTrue(action.inputs.files.none { "/jvm/" in it.path.value || "/native/" in it.path.value || "/test" in it.path.value })
        assertEquals("common", action.target)
        assertEquals(CachePolicy.LOCAL, action.cachePolicy)
        assertEquals(setOf("app:common:metadata", "app:jvm:package", "app:macosArm64:package", "base:common:package"),
            plan.nodes.single { it.id == "app:common:package" }.dependencies)
        val packageNode = plan.nodes.single { it.id == "app:jvm:package" }
        val packageInputs = packageNode.dependencies.associateWith { id -> ActionResult(id, digest(id), ActionStatus.EXECUTED,
            TreeManifest(listOf(FileEntry(LogicalPath(if (id.endsWith(":main")) "classes/App.class" else "library/base.jar"), digest(id)))), 0) }
        assertTrue(LibraryDependency(Coordinate("fixture", "base", "1"), false, Coordinate("fixture", "base-jvm", "1")) in
            LibraryPackage.fromJson(packageNode.resolve(packageInputs).parameters).dependencies)
        assertNotEquals(action.key(Sha256), common.resolve(mapOf(prerequisite.actionId to prerequisite.copy(outputs =
            TreeManifest(listOf(FileEntry(LogicalPath("metadata/default/manifest"), digest("changed"))))))).key(Sha256))
        assertFailsWith<IllegalArgumentException> { common.resolve(mapOf(prerequisite.actionId to prerequisite.copy(status = ActionStatus.FAILED, exitCode = 1))) }
    }

    @Test fun platformOnlyWorkspaceDependenciesDoNotAdvertiseAnUnpublishedRoot() {
        val base = published(module("base"), false)
        val app = published(module("app"), false).let { spec -> spec.copy(sourceSets = spec.sourceSets.map {
            if (it.name == "jvmMain") it.copy(dependencies = listOf(Dependency.Module("base"))) else it
        }) }
        val plan = graph(configuration(base, app), request = ProjectGraphRequest(setOf("app"), setOf("jvm", "macosArm64"), ProjectMode.PACKAGE))
        assertTrue(plan.nodes.none { it.id == "base:common:package" })
        val node = plan.nodes.single { it.id == "base:jvm:package" }
        val dependency = ActionResult("base:jvm:main", digest("base"), ActionStatus.EXECUTED,
            TreeManifest(listOf(FileEntry(LogicalPath("classes/Base.class"), digest("class")))), 0)
        assertNull(LibraryPackage.fromJson(node.resolve(mapOf(dependency.actionId to dependency)).parameters).rootCoordinate)
    }

    @Test fun libraryPackageHasOnlyOwnClassesResourcesAndDirectDependencyMetadata() {
        val runtime = published(module("runtime"))
        val library = published(module("app", listOf(Dependency.Module("base", exported = true),
            Dependency.Module("runtime", DependencyScopeKind.RUNTIME), Dependency.External(Coordinate.parse("g:library:1")))))
        val config = configuration(published(base), runtime, library)
        val files = TreeManifest(snapshot().files + FileEntry(LogicalPath("runtime/common/Runtime.kt"), digest("runtime")))
        val plan = graph(config, files, request(ProjectMode.PACKAGE))
        val actions = resolved(plan)
        assertEquals(setOf("app:jvm:package"), plan.requestedChecks)
        assertEquals(setOf("app:jvm:main", "base:jvm:package", "runtime:jvm:package"), plan.nodes.single { it.id == "app:jvm:package" }.dependencies)
        val action = actions.getValue("app:jvm:package")
        assertEquals(ActionKind.PACKAGE, action.kind)
        assertEquals(CachePolicy.LOCAL, action.cachePolicy)
        assertEquals("kvik-library-package-v1", action.implementation)
        val selection = LibraryPackage.fromJson(action.parameters)
        assertEquals(Coordinate("fixture", "app-jvm", "1"), selection.coordinate)
        assertEquals(DEFAULT_JVM_TARGET, selection.jvmTarget)
        assertEquals(listOf(LibraryDependency(Coordinate("fixture", "base-jvm", "1"), true),
            LibraryDependency(Coordinate("fixture", "runtime-jvm", "1"), false), LibraryDependency(Coordinate("g", "library", "1"), false)), selection.dependencies)
        assertEquals(setOf("classes/Example.class", "classes/config.txt"), action.inputs.files.map { it.path.value }.toSet())
        assertEquals(digest("app:jvm:main"), action.inputs.files.single { it.path.value.endsWith(".class") }.digest)
        assertEquals(digest("app/resources/config.txt"), action.inputs.files.single { it.path.value.endsWith("config.txt") }.digest)
        assertEquals(action.key(Sha256), resolved(plan, "base:jvm:main").getValue("app:jvm:package").key(Sha256))
        assertNotEquals(action.key(Sha256), resolved(plan, "app:jvm:main").getValue("app:jvm:package").key(Sha256))
    }

    @Test fun nativePackageHasOnlyOwnKlibAndRejectsUnrepresentedResources() {
        val nativeLibrary = module("app", listOf(Dependency.Module("base"),
            Dependency.External(Coordinate.parse("g:library:1")),
            Dependency.External(Coordinate.parse("g:only-runtime:1"), DependencyScopeKind.RUNTIME)))
        val unsupported = assertFailsWith<IllegalArgumentException> {
            graph(configuration(published(base, false), published(nativeLibrary, false)), request = request(ProjectMode.PACKAGE, "macosArm64"))
        }
        assertTrue(unsupported.message!!.contains("cannot represent runtime-only dependencies"))
        val config = configuration(published(base, false), published(app, false))
        val plan = graph(config, request = request(ProjectMode.PACKAGE, "macosArm64"))
        val action = resolved(plan).getValue("app:macosArm64:package")
        assertEquals(listOf(FileEntry(LogicalPath("module.klib"), digest("app:macosArm64:main"))), action.inputs.files)
        val selection = LibraryPackage.fromJson(action.parameters)
        assertNull(selection.jvmTarget)
        assertEquals("app-macosarm64", selection.coordinate.name)
        assertEquals(mapOf("fixture:base-macosarm64:1" to true, "g:library:1" to true),
            selection.dependencies.associate { it.coordinate.toString() to it.api })
        assertFailsWith<IllegalArgumentException> { graph(configuration(published(base), published(app)), request = request(ProjectMode.PACKAGE, "macosArm64")) }
    }

    @Test fun nativePublicationNamesUseRootCoordinatesInAllModesAndRejectCollidingOverrides() {
        val config = configuration(published(base, false), published(app, false))
        for (mode in listOf(ProjectMode.BUILD, ProjectMode.PACKAGE)) {
            val actions = resolved(graph(config, request = request(mode, "macosArm64")))
            assertEquals("fixture:app", NativeCompilation.fromJson(actions.getValue("app:macosArm64:main").parameters).moduleName)
            assertEquals("fixture:base", NativeCompilation.fromJson(actions.getValue("base:macosArm64:main").parameters).moduleName)
        }
        fun named(module: ModuleSpec, name: String) = published(module, false).let { value ->
            value.copy(targets = value.targets.map { if (it.name == "macosArm64") it.copy(compilerOptions = listOf("-module-name", name)) else it })
        }
        val explicit = resolved(graph(configuration(published(base, false), named(app, "explicit.name")), request = request(ProjectMode.PACKAGE, "macosArm64")))
        assertEquals("explicit.name", NativeCompilation.fromJson(explicit.getValue("app:macosArm64:main").parameters).moduleName)
        assertFailsWith<IllegalArgumentException> { graph(configuration(named(base, "collision"), named(app, "collision")), request = request(ProjectMode.PACKAGE, "macosArm64")) }
    }

    @Test fun libraryPackageRefusesMissingPublicationFailedPrerequisitesAndResourceCollision() {
        val missing = assertFailsWith<IllegalArgumentException> { graph(configuration(base, published(app)), request = request(ProjectMode.PACKAGE)) }
        assertTrue(missing.message!!.contains("requires publication: base"))
        val plan = graph(configuration(published(base), published(app)), request = request(ProjectMode.PACKAGE))
        val node = plan.nodes.single { it.id == "app:jvm:package" }
        val prerequisites = node.dependencies.associateWith { id -> ActionResult(id, digest(id), ActionStatus.EXECUTED,
            TreeManifest(listOf(FileEntry(LogicalPath(if (id.endsWith(":main")) "classes/Example.class" else "library/artifact.jar"), digest(id)))), 0) }
        assertFailsWith<IllegalArgumentException> { node.resolve(prerequisites + ("app:jvm:main" to prerequisites.getValue("app:jvm:main").copy(status = ActionStatus.FAILED, exitCode = 1))) }
        val collision = TreeManifest(snapshot().files + FileEntry(LogicalPath("app/resources/Example.class"), digest("resource")))
        assertFailsWith<IllegalArgumentException> { resolved(graph(configuration(published(base), published(app)), collision, request(ProjectMode.PACKAGE))) }
        val extra = prerequisites.getValue("app:jvm:main").copy(outputs = TreeManifest(prerequisites.getValue("app:jvm:main").outputs.files + FileEntry(LogicalPath("plugins/report.txt"), digest("plugin report"))))
        val action = node.resolve(prerequisites + ("app:jvm:main" to extra))
        assertTrue(action.inputs.files.none { it.path.value.startsWith("plugins/") })
    }

    @Test fun libraryPackageRequiresRuntimeDomainsAndRejectsPolicyOverrides() {
        val config = configuration(published(base), published(app))
        val restricted = effectiveConfiguration(config, scopes = listOf(DependencyScope("app", "jvm", "main", "java-api")))
        assertFailsWith<IllegalArgumentException> { projectActionGraph(restricted, lock(restricted), snapshot(), composition, tools, request(ProjectMode.PACKAGE)) }
        val excluded = effectiveConfiguration(config, ResolverPolicy(metadataExclusions = listOf(ScopedMetadataExclusion(
            ResolutionDomain("app", "jvm", "main", "java-runtime"), MetadataEdgeExclusion(Coordinate.parse("g:library:1"), digest("metadata"),
                "runtime", Coordinate.parse("g:runtime-only:1"), "fixture correction")))))
        assertFailsWith<IllegalArgumentException> { projectActionGraph(excluded, lock(excluded), snapshot(), composition, tools, request(ProjectMode.PACKAGE)) }
        for ((pin, accepted) in listOf("g:library:1" to true, "g:library:2" to false, "g:runtime-only:1" to false)) {
            val effective = effectiveConfiguration(config, ResolverPolicy(pins = listOf(Coordinate.parse(pin))))
            if (accepted) resolved(projectActionGraph(effective, lock(effective), snapshot(), composition, tools, request(ProjectMode.PACKAGE)))
            else assertFailsWith<IllegalArgumentException> { projectActionGraph(effective, lock(effective), snapshot(), composition, tools, request(ProjectMode.PACKAGE)) }
        }
    }

    @Test fun compilerPluginHostModulesNeedNoPublicationAndNeverEnterPackageMetadata() {
        val plugin = CompilerPluginSpec("producer", null, setOf("jvm"), setOf("main"), emptyList(), emptyList(), "example.plugin", "2.4.20")
        val producer = module("producer", listOf(Dependency.External(Coordinate.parse("g:compiler-api:1"))))
        val config = configuration(published(base), producer, published(app.copy(compilerPlugins = listOf(plugin))))
        val files = TreeManifest(snapshot().files + FileEntry(LogicalPath("producer/common/Plugin.kt"), digest("plugin")))
        val actions = resolved(graph(config, files, request(ProjectMode.PACKAGE)))
        assertTrue("producer:jvm:main" in actions)
        assertFalse("producer:jvm:package" in actions)
        val packaged = actions.getValue("app:jvm:package")
        assertFalse(LibraryPackage.fromJson(packaged.parameters).dependencies.any { it.coordinate.name.contains("producer") || it.coordinate.name.contains("compiler") })
        assertTrue(packaged.inputs.files.all { it.path.value.startsWith("classes/") })
    }

    @Test fun applicationRuntimeClosuresPreservePrivateAndRuntimeOnlyBoundaries() {
        val (config, files) = applicationFixture()
        for (target in listOf("jvm", "macosArm64")) {
            val plan = graph(config, files, request(ProjectMode.APPLICATION, target))
            val actions = resolved(plan)
            val terminal = "app:$target:application"
            assertEquals(setOf(terminal), plan.requestedChecks)
            assertEquals(mapOf(terminal to terminal), plan.terminalActions)
            val compile = actions.getValue("app:$target:main")
            val apiPaths = if (target == "jvm") JvmCompilation.fromJson(compile.parameters).classpath else NativeCompilation.fromJson(compile.parameters).libraries
            assertTrue(modulePathForTest("base", target) in apiPaths)
            assertFalse(modulePathForTest("hidden", target) in apiPaths)
            assertFalse(modulePathForTest("runtime", target) in apiPaths)
            for (name in listOf("private", "hidden", "runtime-private", "only-runtime")) {
                val hidden = artifact("g:$name:1", target, if (target == "jvm") "java-runtime" else "kotlin-api").digest
                assertTrue(compile.inputs.files.none { it.digest == hidden })
            }
            val prepared = actions.getValue(terminal)
            assertEquals("kvik-application-bundle-v1", prepared.implementation)
            assertEquals(ActionKind.PACKAGE, prepared.kind)
            assertEquals(CachePolicy.LOCAL, prepared.cachePolicy)
            val launch = ApplicationLaunch.fromJson(prepared.parameters)
            launch.requireInputs(prepared.inputs)
            for (producer in listOf("app", "base", "hidden", "runtime")) assertTrue(prepared.inputs.files.any { it.path.value == "runtime/$producer/main/config.txt" })
            assertTrue(prepared.inputs.files.none { it.path.value.startsWith("src/") || it.path.value.startsWith("plugins/") || "/test/" in it.path.value })
            if (target == "jvm") {
                val expectedPrefixes = listOf("app", "base", "hidden", "runtime").flatMap { listOf(LogicalPath("modules/$it/jvm/main"), LogicalPath("runtime/$it/main")) }
                assertEquals(expectedPrefixes, launch.classpath.take(expectedPrefixes.size))
                assertTrue(launch.classpath.drop(expectedPrefixes.size).all { it.value.startsWith("deps/") && it.value.endsWith(".jar") })
                val expectedArtifacts = listOf("public", "private", "hidden", "runtime-private", "library", "only-runtime", "runtime-only")
                    .map { artifact("g:$it:1", target, "java-runtime").digest }.toSet()
                assertEquals(expectedArtifacts, prepared.inputs.files.filter { it.path.value.startsWith("deps/") }.map { it.digest }.toSet())
                assertEquals(setOf("app:jvm:main", "base:jvm:main", "hidden:jvm:main", "runtime:jvm:main"), plan.nodes.single { it.id == terminal }.dependencies)
            } else {
                val link = actions.getValue("app:$target:application-link")
                val spec = NativeCompilation.fromJson(link.parameters)
                assertEquals(NativeOutput.PROGRAM, spec.produce)
                assertEquals("fixture.main", spec.entryPoint)
                assertEquals(modulePathForTest("app", target), spec.includedLibrary)
                assertEquals(listOf("base", "hidden", "runtime").map { modulePathForTest(it, target) }, spec.libraries.take(3))
                assertTrue(spec.sources.isEmpty() && spec.plugins.isEmpty())
                for (name in listOf("public", "private", "hidden", "runtime-private", "library", "only-runtime")) {
                    assertTrue(link.inputs.files.any { it.digest == artifact("g:$name:1", target, "kotlin-api").digest })
                }
                assertEquals(LogicalPath("modules/app/macosArm64/application.kexe"), launch.executable)
                assertTrue(launch.classpath.isEmpty())
                assertTrue(prepared.inputs.files.none { it.path.value.endsWith(".klib") || it.path.value.endsWith(".jar") })
                assertEquals(setOf("app:$target:application-link"), plan.nodes.single { it.id == terminal }.dependencies)
            }
        }
    }

    @Test fun applicationArgumentsEnvironmentAndEntrypointsInvalidateOnlyTheirConsumers() {
        val (original, files) = applicationFixture()
        val (changed, _) = applicationFixture { target -> ApplicationSpec(if (target == "jvm") "fixture.MainKt" else "fixture.main", listOf("a", "argument with spaces"), mapOf("SETTING" to "changed")) }
        assertNotEquals(effectiveConfiguration(original).digest, effectiveConfiguration(changed).digest)
        assertEquals(effectiveConfiguration(original).requests, effectiveConfiguration(changed).requests)
        for (target in listOf("jvm", "macosArm64")) {
            val before = resolved(graph(original, files, request(ProjectMode.APPLICATION, target)))
            val after = resolved(graph(changed, files, request(ProjectMode.APPLICATION, target)))
            assertEquals(before.keys, after.keys)
            for ((id, action) in before) {
                if (id.endsWith(":application")) assertNotEquals(action.key(Sha256), after.getValue(id).key(Sha256))
                else assertEquals(action, after.getValue(id), "Run settings changed compiler/link inputs: $id")
            }
            assertEquals(resolved(graph(original, files, request(ProjectMode.BUILD, target))), resolved(graph(changed, files, request(ProjectMode.BUILD, target))))
            assertEquals(resolved(graph(original, files, request(ProjectMode.TEST, target))), resolved(graph(changed, files, request(ProjectMode.TEST, target))))
            val (differentEntry, _) = applicationFixture { platform -> ApplicationSpec(if (platform == "jvm") "fixture.OtherKt" else "fixture.other") }
            val entryActions = resolved(graph(differentEntry, files, request(ProjectMode.APPLICATION, target)))
            assertEquals(before.getValue("app:$target:main"), entryActions.getValue("app:$target:main"))
            if (target == "macosArm64") assertNotEquals(before.getValue("app:$target:application-link").key(Sha256), entryActions.getValue("app:$target:application-link").key(Sha256))
        }
    }

    @Test fun applicationBindingsFollowProducerBytesAndRejectWrongPrerequisites() {
        val (config, files) = applicationFixture()
        for (target in listOf("jvm", "macosArm64")) {
            val plan = graph(config, files, request(ProjectMode.APPLICATION, target))
            val before = resolved(plan)
            val changed = resolved(plan, "runtime:$target:main")
            assertEquals(before.getValue("app:$target:main"), changed.getValue("app:$target:main"))
            val consumer = if (target == "jvm") "app:$target:application" else "app:$target:application-link"
            assertNotEquals(before.getValue(consumer).key(Sha256), changed.getValue(consumer).key(Sha256))
            val terminal = plan.nodes.single { it.id == "app:$target:application" }
            assertFails { terminal.resolve(emptyMap()) }
            val results = terminal.dependencies.associateWith { id -> ActionResult(id, digest("key"), ActionStatus.EXECUTED, outputs(before.getValue(id), id), 0) }
            assertFails { terminal.resolve(results.mapValues { (_, result) -> result.copy(status = ActionStatus.FAILED) }) }
            assertFails { terminal.resolve(results + ("unrequested" to results.values.first())) }
            if (target == "jvm") {
                val withPluginOutput = results.mapValues { (_, result) -> result.copy(outputs = TreeManifest(result.outputs.files +
                    FileEntry(LogicalPath("plugins/example/report.txt"), digest("plugin-only output")))) }
                val bundle = terminal.resolve(withPluginOutput)
                assertTrue(bundle.inputs.files.none { "plugins/" in it.path.value })
                assertEquals(before.getValue(terminal.id), bundle)
            }
            if (target == "macosArm64") {
                for (bad in listOf(TreeManifest(emptyList()), TreeManifest(listOf(FileEntry(LogicalPath("application.kexe"), digest("program"), false))),
                    TreeManifest(listOf(FileEntry(LogicalPath("application.kexe"), digest("program"), true), FileEntry(LogicalPath("extra.txt"), digest("extra")))))) {
                    assertFails { terminal.resolve(results.mapValues { (_, result) -> result.copy(outputs = bad) }) }
                }
                val linkChanged = resolved(plan, "app:$target:application-link")
                assertNotEquals(before.getValue(terminal.id).key(Sha256), linkChanged.getValue(terminal.id).key(Sha256))
            }
        }
    }

    @Test fun applicationUsesExactRuntimeDomainAndDeclaredScopeAndNativeBudget() {
        val (config, files) = applicationFixture()
        for (target in listOf("jvm", "macosArm64")) {
            val usage = if (target == "jvm") "java-runtime" else "kotlin-runtime"
            val effective = effectiveConfiguration(config)
            val original = lock(effective)
            val replacement = artifact("g:library:2", target, usage)
            val changed = original.copy(selections = original.selections.map { selected ->
                if (selected.domain == ResolutionDomain("app", target, "main", usage) && selected.selection.requested.name == "library")
                    selected.copy(selection = selected.selection.copy(artifacts = listOf(replacement))) else selected
            })
            val before = resolved(projectActionGraph(effective, original, files, composition, tools, request(ProjectMode.APPLICATION, target)))
            val after = resolved(projectActionGraph(effective, changed, files, composition, tools, request(ProjectMode.APPLICATION, target)))
            assertEquals(before.getValue("app:$target:main"), after.getValue("app:$target:main"))
            val consumer = after.getValue(if (target == "jvm") "app:$target:application" else "app:$target:application-link")
            assertTrue(consumer.inputs.files.any { it.digest == replacement.digest })
            assertNotEquals(before.getValue(if (target == "jvm") "app:$target:application" else "app:$target:application-link").key(Sha256), consumer.key(Sha256))
            val api = if (target == "jvm") "java-api" else "kotlin-api"
            val apiOnly = effectiveConfiguration(config, scopes = listOf(DependencyScope("app", target, "main", api)))
            assertFails { projectActionGraph(apiOnly, lock(apiOnly), files, composition, tools, request(ProjectMode.APPLICATION, target)) }
        }
        val timed = parseProjectConfiguration(parseJson(config.document.canonical().replace("\"name\":\"macosArm64\"", "\"name\":\"macosArm64\",\"nativeTimeouts\":{\"linkMillis\":600000}")))
        val actions = resolved(graph(timed, files, request(ProjectMode.APPLICATION, "macosArm64")))
        assertEquals(600_000L, actions.getValue("app:macosArm64:application-link").timeoutMillis)
        assertEquals(180_000L, actions.getValue("app:macosArm64:main").timeoutMillis)
        assertFails { graph(request = request(ProjectMode.APPLICATION)) }
    }


    @Test fun nativeTimeoutOverridesBindOnlyTheirCompilerPhaseAndKeepDefaultIdentities() {
        for (kotest in listOf(false, true)) {
            val document = if (kotest) project.document.canonical().replace("\"name\":\"macosArm64\"",
                """"name":"macosArm64","nativeTestSettings":{"engine":"kotest","kspVersion":"2.3.12","processor":"io.kotest:kotest-framework-symbol-processor:6.2.5"}""")
                .replace("g:tests:1", "io.kotest:kotest-framework-engine-macosarm64:6.2.5") else project.document.canonical()
            fun configured(timeouts: String? = null) = parseProjectConfiguration(parseJson(if (timeouts == null) document else
                document.replace("\"name\":\"macosArm64\"", "\"name\":\"macosArm64\",\"nativeTimeouts\":$timeouts")))
            fun actions(timeouts: String? = null) = resolved(graph(configured(timeouts), request = request(ProjectMode.TEST, "macosArm64")))
            val defaults = actions()
            val explicitDefaults = actions("""{"compileMillis":180000,"linkMillis":180000}""")
            assertEquals(defaults, explicitDefaults)
            val linkOnly = actions("""{"linkMillis":600000}""")
            val compileOnly = actions("""{"compileMillis":240000}""")
            val both = actions("""{"compileMillis":240000,"linkMillis":600000}""")
            for ((id, action) in defaults) {
                val phase = id.substringAfterLast(':')
                val compilerPhase = phase in setOf("main", "test-compile")
                val expectedDefault = when (phase) { "test" -> 60_000L; "ksp-test" -> 120_000L; else -> 180_000L }
                assertEquals(expectedDefault, action.timeoutMillis)
                assertEquals(if (phase == "test-link") 600_000L else expectedDefault, linkOnly.getValue(id).timeoutMillis)
                assertEquals(if (compilerPhase) 240_000L else expectedDefault, compileOnly.getValue(id).timeoutMillis)
                assertEquals(if (compilerPhase) 240_000L else if (phase == "test-link") 600_000L else expectedDefault, both.getValue(id).timeoutMillis)
                if (phase == "test-link") assertNotEquals(action.key(Sha256), linkOnly.getValue(id).key(Sha256))
                else assertEquals(action, linkOnly.getValue(id))
                if (compilerPhase) assertNotEquals(action.key(Sha256), compileOnly.getValue(id).key(Sha256))
                else assertEquals(action, compileOnly.getValue(id))
                // Result content is held fixed here: downstream identity changes only when producer bytes change.
                if (action.implementation == "kvik-native-compiler-v1") assertEquals(action,
                    nativeCompileAction(NativeCompilation.fromJson(action.parameters), action.inputs, composition, tools.getValue("macosArm64")))
            }
            assertNotEquals(effectiveConfiguration(configured()).digest, effectiveConfiguration(configured("""{"linkMillis":600000}""")).digest)
            assertEquals(effectiveConfiguration(configured()).requests, effectiveConfiguration(configured("""{"linkMillis":600000}""")).requests)
            val build = resolved(graph(configured("""{"compileMillis":240000}"""), request = request(ProjectMode.BUILD, "macosArm64")))
            assertTrue(build.values.all { it.timeoutMillis == 240_000L })
            assertEquals(resolved(graph(configured())), resolved(graph(configured("""{"linkMillis":600000}"""))))
        }
    }

    @Test fun nativeRuntimeSelectionChangesOnlyLinkInputsAndRejectsMalformedCompiledOutput() {
        val effective = effectiveConfiguration(project, policy = ResolverPolicy(scopedPins = listOf(
            ScopedPin(ResolutionDomain("app", "macosArm64", "test", "kotlin-runtime"), Coordinate.parse("g:library:2")))))
        val original = lock(effective)
        val scoped = original.copy(selections = original.selections.map { selected ->
            if (selected.domain == ResolutionDomain("app", "macosArm64", "test", "kotlin-runtime") && selected.selection.requested.name == "library")
                selected.copy(selection = selected.selection.copy(artifacts = listOf(artifact("g:library:2", "macosArm64", "kotlin-api")))) else selected
        })
        val plan = projectActionGraph(effective, scoped, snapshot(), composition, tools, request(ProjectMode.TEST, "macosArm64"))
        val actions = resolved(plan)
        val api = artifact("g:library:1", "macosArm64", "kotlin-api").digest
        val runtime = artifact("g:library:2", "macosArm64", "kotlin-api").digest
        assertTrue(actions.getValue("app:macosArm64:test-compile").inputs.files.any { it.digest == api })
        assertFalse(actions.getValue("app:macosArm64:test-compile").inputs.files.any { it.digest == runtime })
        assertFalse(actions.getValue("app:macosArm64:test-link").inputs.files.any { it.digest == api })
        assertTrue(actions.getValue("app:macosArm64:test-link").inputs.files.any { it.digest == runtime })
        val link = plan.nodes.single { it.id == "app:macosArm64:test-link" }
        val results = link.dependencies.associateWith { id -> ActionResult(id, digest("key"), ActionStatus.EXECUTED,
            if (id.endsWith("test-compile")) TreeManifest(listOf(FileEntry(LogicalPath("wrong.klib"), digest("wrong")))) else outputs(actions.getValue(id), id), 0) }
        assertFails { link.resolve(results) }
        assertFails { link.resolve(results.mapValues { (_, result) -> result.copy(status = ActionStatus.FAILED) }) }
    }
    @Test fun nativeKotestGenerationPrecedesLinkAndSingleExecutionWithContentSensitiveEdges() {
        val configured = parseProjectConfiguration(parseJson(project.document.canonical().replace("\"name\":\"macosArm64\"",
            """"name":"macosArm64","nativeTestSettings":{"engine":"kotest","kspVersion":"2.3.12","processor":"io.kotest:kotest-framework-symbol-processor:6.2.5"}""")
            .replace("g:tests:1", "io.kotest:kotest-framework-engine-macosarm64:6.2.5")))
        val planned = graph(configured, request = request(ProjectMode.TEST, "macosArm64"))
        val actions = resolved(planned)
        val generate = actions.getValue("app:macosArm64:ksp-test")
        assertEquals(CachePolicy.LOCAL, generate.cachePolicy)
        val ksp = KspNativeCompilation.fromJson(generate.parameters)
        assertTrue(ksp.sources.all { "Test/" in it.value })
        assertTrue(ksp.runtimeClasspath.all { it.value.startsWith("ksp/ksp-runtime/") })
        assertTrue(ksp.processorClasspath.all { it.value.startsWith("ksp/symbol-processor/") })
        assertTrue(ksp.libraries.contains(LogicalPath("modules/app/macosArm64/main.klib")))
        val link = actions.getValue("app:macosArm64:test-compile")
        val compilation = NativeCompilation.fromJson(link.parameters)
        assertEquals(NativeOutput.LIBRARY, compilation.produce)
        assertEquals(kotestGeneratedLauncher, compilation.kotestLauncher)
        assertFalse("-generate-test-runner" in link.arguments)
        assertTrue(link.arguments.containsAll(listOf("prepared/kotest.kt", "prepared/KvikKotestRuntime.kt")))
        val executable = NativeCompilation.fromJson(actions.getValue("app:macosArm64:test-link").parameters)
        assertTrue(executable.sources.isEmpty() && executable.plugins.isEmpty())
        assertEquals(NativeOutput.KOTEST_TEST, executable.produce)
        assertEquals(LogicalPath("modules/app/macosArm64/test.klib"), executable.includedLibrary)
        assertTrue(actions.getValue("app:macosArm64:test-link").arguments.containsAll(listOf("-entry", "kvik.kotest.runtime.main")))
        val test = actions.getValue("app:macosArm64:test")
        assertEquals("kvik-kotest-native-tests-v1", test.implementation)
        assertEquals(CachePolicy.NEVER, test.cachePolicy)
        assertTrue(test.arguments.isEmpty())
        val changed = resolved(planned, "app:macosArm64:ksp-test")
        assertEquals(generate.key(Sha256), changed.getValue("app:macosArm64:ksp-test").key(Sha256))
        assertNotEquals(link.key(Sha256), changed.getValue("app:macosArm64:test-compile").key(Sha256))
        assertNotEquals(generate.key(Sha256), resolved(planned, "app:macosArm64:main").getValue("app:macosArm64:ksp-test").key(Sha256))
        assertFalse(resolved(graph(configured, request = request(ProjectMode.BUILD, "macosArm64"))).values.any { it.kind == ActionKind.GENERATE })
    }
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

    @Test fun nativeWorkspacePluginBuildsJvmProducersAndKeepsReportsOutOfLinks() {
        val plugin = CompilerPluginSpec("producer", null, setOf("macosArm64"), setOf("main", "test"),
            listOf("value=with,comma=andEquals"), listOf(LogicalPath("config.txt")), "example.plugin", "2.4.20",
            listOf(PluginPathOption("report", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("report"))), listOf(LogicalPath("report")))
        val producer = module("producer", listOf(Dependency.Module("base"), Dependency.Module("runtime", DependencyScopeKind.RUNTIME),
            external("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20")))
        val runtime = module("runtime", listOf(external("g:private-runtime:1")))
        val extra = listOf("producer", "runtime").flatMap { id ->
            listOf("common/Common.kt", "jvm/Actual.kt", "resources/META-INF/services/Registrar").map {
                FileEntry(LogicalPath("$id/$it"), digest("$id/$it"))
            }
        }
        val files = TreeManifest(snapshot().files + extra + FileEntry(LogicalPath("config.txt"), digest("config")))
        fun plan(scopes: Set<String> = plugin.scopes): ProjectActionGraph = graph(
            configuration(base, producer, runtime, app.copy(compilerPlugins = listOf(plugin.copy(scopes = scopes)))),
            files, request(ProjectMode.TEST, "macosArm64"))
        val nativeRequest = request(ProjectMode.TEST, "macosArm64")
        val configured = configuration(base, producer, runtime, app.copy(compilerPlugins = listOf(plugin)))
        assertEquals(setOf("jvm", "macosArm64"), projectToolTargets(configured.project, nativeRequest))
        assertEquals(setOf("macosArm64"), projectToolTargets(project.project, nativeRequest))
        val testOnly = configuration(base, producer, runtime, app.copy(compilerPlugins = listOf(plugin.copy(scopes = setOf("test")))))
        assertEquals(setOf("macosArm64"), projectToolTargets(testOnly.project, request(ProjectMode.BUILD, "macosArm64")))
        val planned = plan()
        val before = resolved(planned)
        val changed = resolved(planned, "producer:jvm:main")
        assertTrue("producer:jvm:main" in before && "runtime:jvm:main" in before)
        assertFalse("producer:macosArm64:main" in before || "runtime:macosArm64:main" in before)
        assertFalse(JvmCompilation.fromJson(before.getValue("producer:jvm:main").parameters).classpath.any { "runtime" in it.value })
        for (id in listOf("app:macosArm64:main", "app:macosArm64:test-compile")) {
            val action = before.getValue(id)
            val compile = NativeCompilation.fromJson(action.parameters)
            assertEquals(tools.getValue("macosArm64") + tools.getValue("jvm"), action.toolchains)
            val applied = compile.pluginApplications.single()
            assertEquals(plugin.options, applied.options)
            assertTrue(applied.classpath.any { "runtime/classes" in it.value })
            assertTrue(applied.classpath.none { it in compile.libraries })
            assertTrue(action.inputs.files.any { it.path.value == "plugin-inputs/example.plugin/config.txt" })
            assertTrue(LogicalPath("plugins/example.plugin/report") in action.outputs)
            assertNotEquals(action.key(Sha256), changed.getValue(id).key(Sha256))
        }
        assertEquals(before.getValue("base:macosArm64:main"), changed.getValue("base:macosArm64:main"))
        // Plugin-generated reports are retained by compilation but cannot become link libraries.
        val results = linkedMapOf<String, ActionResult>()
        for (node in planned.nodes) {
            val action = node.resolve(results.filterKeys { it in node.dependencies })
            val generated = if (node.id in setOf("app:macosArm64:main", "app:macosArm64:test-compile"))
                listOf(FileEntry(LogicalPath("plugins/example.plugin/report/output.txt"), digest("report"))) else emptyList()
            if (node.id == "app:macosArm64:test-link") {
                assertTrue(NativeCompilation.fromJson(action.parameters).pluginApplications.isEmpty())
                assertEquals(tools.getValue("macosArm64"), action.toolchains)
                assertFalse(action.inputs.files.any { it.path.value.startsWith("plugins/") })
            }
            results[node.id] = ActionResult(node.id, action.key(Sha256), ActionStatus.EXECUTED,
                TreeManifest(outputs(action, node.id).files + generated), 0)
        }
        for ((scopes, excluded, included) in listOf(
            Triple(setOf("main"), "app:macosArm64:test-compile", "app:macosArm64:main"),
            Triple(setOf("test"), "app:macosArm64:main", "app:macosArm64:test-compile"),
        )) {
            val scoped = resolved(plan(scopes))
            assertTrue(NativeCompilation.fromJson(scoped.getValue(excluded).parameters).pluginApplications.isEmpty())
            assertEquals(1, NativeCompilation.fromJson(scoped.getValue(included).parameters).pluginApplications.size)
        }
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
        val nativePlan = graph(config, files, request(ProjectMode.TEST, "macosArm64"))
        val native = resolved(nativePlan)
        for (name in listOf("private", "runtime", "leaf-private", "hidden-private")) {
            val bytes = digest("g:$name:1:macosArm64")
            assertFalse(native.getValue("app:macosArm64:test-compile").inputs.files.any { it.digest == bytes }, name)
            assertTrue(native.getValue("app:macosArm64:test-link").inputs.files.any { it.digest == bytes }, name)
        }
        assertFalse("hidden:macosArm64:main" in nativePlan.nodes.single { it.id == "app:macosArm64:test-compile" }.dependencies)
        assertTrue("hidden:macosArm64:main" in nativePlan.nodes.single { it.id == "app:macosArm64:test-link" }.dependencies)
        val changedNative = resolved(nativePlan, "hidden:macosArm64:main")
        assertEquals(native.getValue("app:macosArm64:test-compile").key(Sha256), changedNative.getValue("app:macosArm64:test-compile").key(Sha256))
        assertNotEquals(native.getValue("app:macosArm64:test-link").key(Sha256), changedNative.getValue("app:macosArm64:test-link").key(Sha256))
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
        val nativeBefore = resolved(projectActionGraph(nativeEffective, lock(nativeEffective), snapshot(), composition, tools, request(ProjectMode.TEST, "macosArm64")))
        val nativeAfter = resolved(projectActionGraph(nativeEffective, differentLink, snapshot(), composition, tools, request(ProjectMode.TEST, "macosArm64")))
        for (id in listOf("base:macosArm64:main", "app:macosArm64:main", "app:macosArm64:test-compile"))
            assertEquals(nativeBefore.getValue(id).key(Sha256), nativeAfter.getValue(id).key(Sha256))
        assertNotEquals(nativeBefore.getValue("app:macosArm64:test-link").key(Sha256), nativeAfter.getValue("app:macosArm64:test-link").key(Sha256))
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

    @Test fun coverageSelectsProductionRuntimeClosureAndLeavesCompilationActionsUnchanged() {
        val (configuration, files) = applicationFixture()
        val coverageTools = JvmCoverageTools(JACOCO_VERSION, digest("agent"), digest("reporter"))
        val plainRequest = request(ProjectMode.TEST)
        val coveredRequest = plainRequest.copy(jvmTests = mapOf("app" to JvmTests(listOf("fixture.Tests"), coverage = coverageTools)))
        fun actions(request: ProjectGraphRequest, snapshot: TreeManifest = files): Map<String, ActionSpec> {
            val plan = graph(configuration, snapshot, request)
            val results = linkedMapOf<String, ActionResult>()
            return plan.nodes.associate { node ->
                val action = node.resolve(results.filterKeys { it in node.dependencies })
                // Distinct module class names, as in actual production output (rather than the
                // shared Example.class shorthand used by older graph-only fixtures).
                val output = if (action.kind == ActionKind.COMPILE) TreeManifest(listOf(FileEntry(
                    LogicalPath("classes/${node.id.replace(':', '/')}/Example.class"), digest(node.id)))) else outputs(action, node.id)
                results[node.id] = ActionResult(node.id, action.key(Sha256), ActionStatus.EXECUTED, output, 0)
                node.id to action
            }
        }
        val plain = actions(plainRequest)
        val covered = actions(coveredRequest)
        assertEquals(plain.keys, covered.keys)
        for (id in plain.keys - "app:jvm:test") assertEquals(plain.getValue(id), covered.getValue(id), id)
        val action = covered.getValue("app:jvm:test")
        val specification = JvmTestSelection.fromJson(action.parameters).coverage!!
        assertEquals(setOf("app", "base", "hidden", "runtime"), specification.classDirectories.map { it.module }.toSet())
        assertTrue(specification.classDirectories.all { it.path == LogicalPath("modules/${it.module}/jvm/main") })
        assertEquals(8, specification.sources.size)
        assertEquals(setOf("commonMain", "jvmMain"), specification.sources.map { it.sourceSet }.toSet())
        for (source in specification.sources) {
            assertFalse("Test" in source.path.value || "resources" in source.path.value)
            assertEquals(files.files.single { it.path == source.path }.digest, source.digest)
            assertEquals(source.digest, action.inputs.files.single { it.path == source.inputPath }.digest)
        }
        assertTrue(plain.getValue("app:jvm:test").inputs.files.none { it.path.value.startsWith("coverage-sources/") })
        assertNotEquals(plain.getValue("app:jvm:test").key(Sha256), action.key(Sha256))
        assertEquals(CachePolicy.NEVER, action.cachePolicy)
        val changed = TreeManifest(files.files.map { if (it.path == LogicalPath("hidden/common/Common.kt")) it.copy(digest = digest("comment change")) else it })
        assertNotEquals(action.key(Sha256), actions(coveredRequest, changed).getValue("app:jvm:test").key(Sha256))
        val excluded = coveredRequest.copy(jvmTests = mapOf("app" to JvmTests(listOf("fixture.Tests"), coverage = coverageTools.copy(excludes = listOf("generated.*")))))
        val changedPolicy = actions(excluded)
        assertNotEquals(action.key(Sha256), changedPolicy.getValue("app:jvm:test").key(Sha256))
        for (id in plain.keys - "app:jvm:test") assertEquals(covered.getValue(id), changedPolicy.getValue(id))
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
        assertEquals(NativeOutput.LIBRARY, link.produce)
        assertTrue(link.sources.any { it.value == "src/app/common/Common.kt" } && link.sources.any { it.value == "src/app/commonTest/Tests.kt" })
        assertEquals(listOf(LogicalPath("modules/base/macosArm64/main.klib")), link.libraries.filter { it.value.startsWith("modules/") })
        assertTrue(link.plugins.single().value.startsWith("plugins/"))
        assertTrue(link.plugins.none { it in link.libraries })
        assertFalse("-generate-test-runner" in actions.getValue("app:macosArm64:test-compile").arguments)
        assertTrue("-generate-test-runner" in actions.getValue("app:macosArm64:test-link").arguments)
        assertTrue(NativeCompilation.fromJson(actions.getValue("app:macosArm64:test-link").parameters).plugins.isEmpty())
        val run = actions.getValue("app:macosArm64:test")
        assertTrue(run.inputs.files.any { it.path.value == "modules/app/macosArm64/test/tests.kexe" && it.executable })
        assertTrue(run.inputs.files.any { it.path.value == "runtime/base/main/config.txt" })
        assertEquals(CachePolicy.NEVER, run.cachePolicy)
        val zero = graph(files = TreeManifest(snapshot().files.filterNot { it.path.value.contains("Test/") }), request = request(ProjectMode.TEST, "macosArm64"))
        assertTrue(zero.nodes.any { it.id == "app:macosArm64:test-compile" })
        val zeroActions = resolved(zero)
        assertEquals(NativeOutput.LIBRARY, NativeCompilation.fromJson(zeroActions.getValue("app:macosArm64:test-compile").parameters).produce)
        assertEquals(NativeOutput.TEST, NativeCompilation.fromJson(zeroActions.getValue("app:macosArm64:test-link").parameters).produce)
        assertEquals(CachePolicy.NEVER, zeroActions.getValue("app:macosArm64:test").cachePolicy)
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

class IosFrameworkGraphTest {
    private fun fixture(target: String, framework: NativeFrameworkSpec? = NativeFrameworkSpec("Sample"),
                        linkMillis: Long? = null, resources: Boolean = false): Pair<ProjectConfiguration, TreeManifest> {
        val runtime = module("runtime", listOf(Dependency.External(Coordinate.parse("g:runtime-private:1"))))
        val consumer = app.copy(sourceSets = app.sourceSets.map { set -> if (set.name != "commonMain") set else set.copy(
            dependencies = set.dependencies + Dependency.Module("runtime", DependencyScopeKind.RUNTIME) +
                Dependency.External(Coordinate.parse("g:only-runtime:1"), DependencyScopeKind.RUNTIME)) })
        val values = listOf(base, consumer, runtime).map { value -> value.copy(
            sourceSets = value.sourceSets.map { it.copy(resources = if (resources && value.id == "runtime") it.resources else emptyList()) },
            targets = listOf(TargetSpec(target, "nativeMain", null, emptyList(),
                nativeTimeouts = linkMillis?.takeIf { value.id == "app" }?.let { NativeTimeouts(linkMillis = it) },
                nativeFramework = framework.takeIf { value.id == "app" }))) }
        val files = TreeManifest(snapshot().files + listOf("common/Common.kt", "native/Actual.kt", "resources/data.txt")
            .map { FileEntry(LogicalPath("runtime/$it"), digest(it)) })
        return configuration(*values.toTypedArray()) to files
    }

    @Test fun iosFrameworkLinksRuntimeClosureOnMacHostWithoutExposingItToCompilation() {
        for (target in listOf("iosArm64", "iosSimulatorArm64")) {
            val (config, files) = fixture(target)
            val selected = request(target = target)
            val plan = graph(config, files, selected)
            val actions = resolved(plan)
            assertEquals("app:$target:framework", plan.terminalActions.getValue("app:$target:build"))
            assertEquals(setOf("app:$target:main", "base:$target:main", "runtime:$target:main"),
                plan.nodes.single { it.id == "app:$target:framework" }.dependencies)
            val main = NativeCompilation.fromJson(actions.getValue("app:$target:main").parameters)
            val linked = actions.getValue("app:$target:framework")
            val parameters = NativeCompilation.fromJson(linked.parameters)
            assertEquals(NativeOutput.FRAMEWORK, parameters.produce)
            assertEquals(NativeTarget.require(target), parameters.target)
            assertEquals(NativeTarget.MACOS_ARM64.platform, linked.platform)
            assertEquals(target, linked.target)
            assertEquals(LogicalPath("Sample.framework"), parameters.output)
            assertEquals(LogicalPath("modules/app/$target/main.klib"), parameters.includedLibrary)
            assertTrue(parameters.sources.isEmpty() && parameters.pluginApplications.isEmpty() && parameters.plugins.isEmpty())
            assertTrue(parameters.libraries.contains(LogicalPath("modules/runtime/$target/main.klib")))
            assertTrue(main.libraries.none { "runtime" in it.value })
            assertTrue(linked.inputs.files.any { it.digest == artifact("g:only-runtime:1", target, "kotlin-api").digest })
            assertTrue(actions.getValue("app:$target:main").inputs.files.none { it.digest == artifact("g:only-runtime:1", target, "kotlin-api").digest })
            assertEquals(CachePolicy.LOCAL, linked.cachePolicy)
            assertEquals(setOf(target), projectToolTargets(config.project, selected))
            assertNotEquals(linked.key(Sha256), resolved(plan, "runtime:$target:main").getValue("app:$target:framework").key(Sha256))
        }
    }

    @Test fun frameworkSettingsChangeOnlyLinkIdentityAndPlainBuildStaysKlib() {
        val target = "iosSimulatorArm64"
        fun actions(framework: NativeFrameworkSpec?, timeout: Long? = null): Map<String, ActionSpec> {
            val (config, files) = fixture(target, framework, timeout)
            return resolved(graph(config, files, request(target = target)))
        }
        val original = actions(NativeFrameworkSpec("Sample"))
        for (changed in listOf(actions(NativeFrameworkSpec("Other")), actions(NativeFrameworkSpec("Sample", true)),
            actions(NativeFrameworkSpec("Sample"), 600_000))) {
            assertEquals(original.getValue("app:$target:main").key(Sha256), changed.getValue("app:$target:main").key(Sha256))
            assertNotEquals(original.getValue("app:$target:framework").key(Sha256), changed.getValue("app:$target:framework").key(Sha256))
        }
        val plain = actions(null)
        assertTrue(plain.keys.none { it.endsWith(":framework") || it.startsWith("runtime:") })
        assertEquals(original.getValue("app:$target:main").key(Sha256), plain.getValue("app:$target:main").key(Sha256))
    }

    @Test fun frameworkRuntimeOnlyProducerPluginsRequireJvmHostTools() {
        val target = "iosArm64"
        val (config, files) = fixture(target)
        val plugin = CompilerPluginSpec("producer", null, setOf(target), setOf("main"),
            emptyList(), emptyList(), "example.plugin", "2.4.20", emptyList(), emptyList())
        val changed = configuration(*(config.project.modules.map { if (it.id == "runtime") it.copy(compilerPlugins = listOf(plugin)) else it } +
            module("producer")).toTypedArray())
        val selected = request(target = target)
        assertEquals(setOf(target, "jvm"), projectToolTargets(changed.project, selected))
        val extra = listOf("common/Common.kt", "jvm/Actual.kt").map { FileEntry(LogicalPath("producer/$it"), digest(it)) }
        val actions = resolved(graph(changed, TreeManifest(files.files + extra), selected))
        assertTrue("producer:jvm:main" in actions)
        assertTrue(NativeCompilation.fromJson(actions.getValue("runtime:$target:main").parameters).pluginApplications.isNotEmpty())
        val linked = NativeCompilation.fromJson(actions.getValue("app:$target:framework").parameters)
        assertTrue(linked.pluginApplications.isEmpty())
        assertTrue(actions.getValue("app:$target:framework").inputs.files.none { it.path.value.startsWith("plugins/") })
    }

    @Test fun frameworkRequiresRuntimeAuthorizationAndRejectsRuntimeResources() {
        val target = "iosArm64"
        val (config, files) = fixture(target)
        val effective = effectiveConfiguration(config, scopes = listOf(DependencyScope("app", target, "main", "kotlin-api")))
        assertContains(assertFails { projectActionGraph(effective, lock(effective), files, composition, tools, request(target = target)) }.message.orEmpty(), "kotlin-runtime")
        val (withResources, resourceFiles) = fixture(target, resources = true)
        assertContains(assertFails { graph(withResources, resourceFiles, request(target = target)) }.message.orEmpty(), "framework resources")
        assertFails { graph(config, files, request(ProjectMode.TEST, target)) }
        assertFails { graph(config, files, request(ProjectMode.APPLICATION, target)) }
    }
}
