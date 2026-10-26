@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.scriptresolution

import kvik.core.*
import kvik.locking.*
import kvik.packages.*
import kvik.resolution.*
import kvik.projectsnapshot.*
import kvik.cache.NativeFiles
import kvik.cli.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.*

private val repository = Repository("fixture", "https://fixture.invalid/maven")
private val noPom = PomParser { error("Unexpected POM fallback in pinned metadata fixture") }
private class MemoryContent : ContentStore {
    val blobs = mutableMapOf<Digest, ByteArray>()
    var puts = 0
    var onPut: ((ByteArray) -> Unit)? = null
    override fun put(bytes: ByteArray): Digest = Sha256.digest(bytes).also { puts++; onPut?.invoke(bytes); blobs[it] = bytes.copyOf() }
    override fun get(digest: Digest): ByteArray? = blobs[digest]?.copyOf()
}
private class Transport(val entries: Map<String, ByteArray>) : ArtifactTransport {
    val calls = mutableListOf<String>()
    override fun fetch(url: String): ByteArray { calls += url; return entries[url] ?: throw MissingResource(url) }
}
private fun component(name: String, dependencies: List<String> = emptyList()): Map<String, ByteArray> {
    val c = Coordinate.parse(name)
    val base = "${repository.url}/${c.group.replace('.', '/')}/${c.name}/${c.version}/${c.name}-${c.version}"
    val bytes = name.encodeToByteArray(); val digest = Sha256.digest(bytes)
    val variants = listOf("java-api", "java-runtime").map { usage -> obj("name" to usage.json(),
        "attributes" to obj("org.gradle.category" to "library".json(), "org.gradle.usage" to usage.json(), "org.jetbrains.kotlin.platform.type" to "jvm".json()),
        "files" to listOf(obj("name" to "${c.name}-${c.version}.jar".json(), "url" to "${c.name}-${c.version}.jar".json(), "size" to digest.sizeBytes.json(), "sha256" to digest.hash.json())).json(),
        "dependencies" to dependencies.map { Coordinate.parse(it).let { d -> obj("group" to d.group.json(), "module" to d.name.json(), "version" to obj("requires" to d.version.json())) } }.json()) }
    return mapOf("$base.module" to obj("formatVersion" to "1.1".json(), "component" to obj("group" to c.group.json(), "module" to c.name.json(), "version" to c.version.json()), "variants" to variants.json()).canonical().encodeToByteArray(), "$base.jar" to bytes)
}
private fun dependency(name: String, module: Boolean = false, exported: Boolean = false, runtime: Boolean = false) = obj(
    (if (module) "module" else "coordinate") to name.json(), "scope" to (if (runtime) "runtime" else "compile").json(), "exported" to exported.json())
private fun module(name: String, dependencies: List<JsonValue> = emptyList(), plugins: List<JsonValue> = emptyList()): JsonValue = obj(
    "id" to name.json(), "sourceSets" to listOf("main", "test").map { scope -> obj("name" to scope.json(),
        "directories" to listOf("$name/$scope".json()).json(), "resources" to emptyList<JsonValue>().json(), "dependsOn" to emptyList<JsonValue>().json(),
        "dependencies" to (if (scope == "main") dependencies else emptyList()).json()) }.json(),
    "targets" to listOf(obj("name" to "jvm".json(), "main" to "main".json(), "test" to "test".json(), "compilerOptions" to emptyList<JsonValue>().json())).json(),
    "compilerPlugins" to plugins.json())
private fun plugin(module: String? = null, scopes: String = "script", input: String = "plugin/options.json") = JsonValue.Object(obj(
    (if (module == null) "coordinate" else "module") to (module ?: "example:plugin:1").json(),
    "targets" to listOf("jvm".json()).json(), "scopes" to listOf(scopes.json()).json(),
    "inputs" to listOf(input.json()).json(), "options" to emptyList<JsonValue>().json()).values +
        if (module == null) emptyMap() else mapOf("id" to "example.plugin".json(), "compilerVersion" to "2.4.20".json()))
private fun script(dependencies: List<JsonValue> = emptyList(), profiles: List<String> = emptyList(), plugins: List<JsonValue> = emptyList()) = obj(
    "sources" to listOf("scripts/Run.kt".json(), "scripts/more".json()).json(), "resources" to listOf("assets".json()).json(),
    "dependencies" to dependencies.json(), "profiles" to profiles.map(String::json).json(), "compilerPlugins" to plugins.json())
private fun project(modules: List<JsonValue>, scripts: Map<String, JsonValue> = emptyMap(), profiles: Map<String, JsonValue> = emptyMap()): ProjectConfiguration {
    val base = obj("schemaVersion" to 1.json(), "name" to "fixture".json(), "repositories" to listOf(obj("id" to repository.id.json(), "url" to repository.url.json())).json(), "modules" to modules.json())
    return parseProjectConfiguration(JsonValue.Object(base.values +
        (if (scripts.isEmpty()) emptyMap() else mapOf("scripts" to JsonValue.Object(scripts))) +
        (if (profiles.isEmpty()) emptyMap() else mapOf("dependencyProfiles" to JsonValue.Object(profiles)))))
}
private fun save(name: String, value: JsonValue) {
    val directory = getenv("KVIK_SCRIPT_TEST_WORK")!!.toKString()
    NativeFiles.writeNew("$directory/$name", value.canonical().encodeToByteArray())
}

class ScriptResolutionTest {
    @Test fun pluginClosuresResolveIndependentlyForModulesScriptsAndScopedPins() {
        fun published(name: String, scope: String): JsonValue = obj("coordinate" to "example:$name:1".json(),
            "id" to "example.$name".json(), "compilerVersion" to "2.4.20".json(), "targets" to listOf("jvm".json()).json(),
            "scopes" to listOf(scope.json()).json(), "options" to emptyList<JsonValue>().json(), "inputs" to emptyList<JsonValue>().json())
        fun plugins(scope: String) = listOf(published("first", scope), published("second", scope), plugin("producer", scope))
        val p = project(listOf(module("consumer", listOf(dependency("example:helper:4")), plugins("main")),
            module("producer", listOf(dependency("example:helper:3")))),
            mapOf("run" to script(listOf(obj("coordinate" to "example:helper:4".json())), plugins = plugins("script"))))
        val entries = component("example:first:1", listOf("example:helper:1")) +
            component("example:second:1", listOf("example:helper:2")) +
            (1..5).flatMap { component("example:helper:$it").entries }.associate { it.toPair() }
        val configuration = effectiveConfiguration(p)
        val content = MemoryContent(); val service = DependencyLocks(content, noPom)
        val network = Transport(entries)
        val lock = service.resolve(configuration, network)
        val expectedVersions = mapOf("example.first" to "1", "example.second" to "2", "example.plugin" to "3")
        val host = lock.selections.filter { it.domain.isHostTool }
        assertEquals(6, host.size)
        for (selection in lock.selections) {
            val expected = expectedVersions[selection.domain.pluginId]
                ?: if (selection.domain.consumer == ResolutionConsumer.Module("producer")) "3" else "4"
            assertEquals(listOf(expected), selection.artifacts.filter { it.coordinate.name == "helper" }.map { it.coordinate.version })
        }
        val calls = network.calls.toList(); val puts = content.puts
        assertEquals(lock, service.loadLocked(lock.bytes(), configuration))
        assertEquals(calls, network.calls); assertEquals(puts, content.puts)
        val forged = lock.copy(selections = lock.selections.map { if (it.domain.isHostTool) it.copy(domain = it.domain.copy(pluginId = null)) else it })
        assertFails { service.loadLocked(forged.bytes(), configuration) }
        val selected = ResolutionDomain("consumer", "jvm", "main", "java-runtime", "compiler-plugin", "example.first")
        val policy = ResolverPolicy(scopedPins = listOf(ScopedPin(selected, Coordinate.parse("example:helper:5"))))
        val changed = effectiveConfiguration(p, policy)
        val pinned = service.resolve(changed, Transport(entries))
        assertEquals(listOf("5"), pinned.selections.single { it.domain == selected }.artifacts.filter { it.coordinate.name == "helper" }.map { it.coordinate.version })
        assertEquals(lock.selections.filter { it.domain != selected }, pinned.selections.filter { it.domain != selected })
        assertFails { effectiveConfiguration(p, ResolverPolicy(scopedPins = listOf(policy.scopedPins.single().copy(domain = selected.copy(pluginId = "missing"))))) }
        val query = queryProjectDependencies("kvik.json", "lock.json", "cache", p, configuration, { lock.bytes() }, content, noPom, 1000)
        assertEquals("complete", query.values.getValue("status").stringValue())
        val reported = query.values.getValue("domains").arrayValue().map { ResolutionDomain.fromJson(it.objectValue().getValue("domain")) }
        assertEquals(lock.selections.map { it.domain }.toSet(), reported.toSet())
        val declarations = query.values.getValue("declarations").arrayValue().map(JsonValue::objectValue)
        val declaredPlugins = declarations.filter { it.getValue("kind").stringValue() == "compiler-plugin" }
        assertEquals(6, declaredPlugins.size)
        for (domain in host.map { it.domain }) {
            val owner = if (domain.consumer is ResolutionConsumer.Script) "script" else "module"
            val declaration = declaredPlugins.single { it[owner]?.stringValue() == domain.consumer.name &&
                it.getValue("pluginId").stringValue() == domain.pluginId }
            assertEquals("2.4.20", declaration.getValue("compilerVersion").stringValue())
            if (domain.pluginId == "example.plugin") assertEquals("producer", declaration.getValue("dependencyModule").stringValue())
            else assertEquals("example:${domain.pluginId!!.substringAfter('.')}:1", declaration.getValue("coordinate").stringValue())
        }
        assertTrue(declarations.filter { it.getValue("kind").stringValue() != "compiler-plugin" }.all { "pluginId" !in it && "compilerVersion" !in it })
        save("plugin-lock.json", lock.toJson()); save("plugin-query.json", query)
    }

    @Test fun legacyModuleDomainBytesAndEmptyScriptScopesRemainUnchanged() {
        val domain = ResolutionDomain("same", "jvm", "main", "java-api")
        assertEquals("""{"compilation":"main","module":"same","purpose":"dependencies","target":"jvm","usage":"java-api"}""", domain.toJson().canonical())
        val p = project(listOf(module("same", listOf(dependency("example:lib:1")))))
        val effective = effectiveConfiguration(p)
        assertEquals(setOf("schemaVersion", "project", "resolver", "scopes", "domains"), effective.document.objectValue().keys)
        // Fixed pre-script document encoding: no new empty collections or consumer tag.
        assertEquals(Digest("3c8fbf17aca94e929e0ebde4f4470b8bf82800e5381e9d2c57b114b20f233389", 1569), effective.digest)
        assertEquals(effective.digest, effectiveConfiguration(p, scriptScopes = emptyList()).digest)
        val content = MemoryContent()
        val service = DependencyLocks(content, noPom)
        val lock = service.resolve(effective, Transport(component("example:lib:1")))
        assertEquals(lock, service.loadLocked(lock.bytes(), effective))
        save("legacy-lock.json", lock.toJson()); save("legacy-effective.json", effective.document)
    }

    @Test fun equallyNamedModuleAndScriptHaveIndependentPinsAndExactOfflineReplay() {
        val p = project(listOf(module("same", listOf(dependency("example:lib:1")))), mapOf("same" to script(listOf(obj("coordinate" to "example:lib:1".json())))))
        val scriptDomains = listOf("java-api", "java-runtime").map { ResolutionDomain.script("same", it) }
        val policy = ResolverPolicy(scopedPins = scriptDomains.map { ScopedPin(it, Coordinate.parse("example:lib:2")) })
        val effective = effectiveConfiguration(p, policy)
        val content = MemoryContent(); val service = DependencyLocks(content, noPom)
        val network = Transport(component("example:lib:1") + component("example:lib:2"))
        val lock = service.resolve(effective, network)
        assertEquals(6, lock.selections.size)
        lock.selections.forEach { assertEquals(if (it.domain.consumer is ResolutionConsumer.Script) "2" else "1", it.artifacts.single().coordinate.version) }
        val calls = network.calls.toList(); val puts = content.puts
        assertEquals(lock, service.loadLocked(lock.bytes(), effective))
        assertEquals(calls, network.calls); assertEquals(puts, content.puts)
        assertFails { service.loadLocked(lock.bytes(), effectiveConfiguration(p)) }
        val forged = lock.copy(selections = lock.selections.map { if (it.domain == scriptDomains.first()) it.copy(domain = ResolutionDomain("same", "jvm", "main", "java-api")) else it })
        assertFails { service.loadLocked(forged.bytes(), effective) }
        val blob = lock.selections.first().artifacts.single().digest
        content.blobs[blob] = "bad".encodeToByteArray()
        assertFails { service.loadLocked(lock.bytes(), effective) }
        save("script-lock.json", lock.toJson()); save("script-effective.json", effective.document)
    }

    @Test fun privateProducerInputsArePrerequisitesWithoutLeakingIntoScriptCompileClosure() {
        val p = project(listOf(
            module("library", listOf(dependency("api", module = true, exported = true), dependency("hidden", module = true), dependency("runtime", module = true, runtime = true))),
            module("api", listOf(dependency("example:api:1", exported = true))),
            module("hidden", listOf(dependency("example:hidden:1"))), module("runtime"), module("unused")),
            mapOf("run" to script(listOf(obj("module" to "library".json())))))
        val expanded = expandScript(p.project, "run")
        assertEquals(listOf("library", "api"), scriptDependencyClosure(p.project, expanded, DependencyUsage.COMPILE).modules)
        assertEquals(listOf(Coordinate.parse("example:api:1")), scriptDependencyClosure(p.project, expanded, DependencyUsage.COMPILE).external)
        assertEquals(listOf("library", "api", "hidden", "runtime"), scriptDependencyClosure(p.project, expanded, DependencyUsage.RUNTIME).modules)
        assertEquals(setOf("library", "api", "hidden", "runtime"), scriptPrerequisiteModules(p.project, expanded).toSet())
        val effective = effectiveConfiguration(p, scopes = emptyList(), scriptScopes = listOf(ScriptDependencyScope("run", "java-api")))
        assertEquals(listOf("example:api:1"), effective.requests.filter { it.domain.consumer is ResolutionConsumer.Script }.map { it.coordinate.toString() })
        assertTrue(effective.requests.any { it.domain.consumer == ResolutionConsumer.Module("hidden") && it.coordinate.name == "hidden" })
        assertFalse(effective.document.objectValue().getValue("domains").canonical().contains("\"module\":\"unused\""))
        val plan = projectInputPlan(p, SnapshotSelection.Script("run"))
        assertEquals(setOf("library/main", "api/main", "hidden/main", "runtime/main"), plan.directories.map { it.value }.toSet())
        assertEquals(listOf("assets", "scripts/Run.kt", "scripts/more"), plan.scriptRoots.map { it.value })
        assertTrue(plan.files.isEmpty())
    }

    @Test fun scriptMetadataCorrectionIsScopedAndReplayRequiresExactProducerBytes() {
        val p = project(listOf(module("same", listOf(dependency("example:parent:1")))),
            mapOf("same" to script(listOf(obj("coordinate" to "example:parent:1".json())))))
        val entries = component("example:parent:1", listOf("example:leaf:1")) + component("example:leaf:1")
        val metadata = entries.getValue("${repository.url}/example/parent/1/parent-1.module")
        val domain = ResolutionDomain.script("same", "java-api")
        val rule = ScopedMetadataExclusion(domain, MetadataEdgeExclusion(Coordinate.parse("example:parent:1"), Sha256.digest(metadata),
            "java-api", Coordinate.parse("example:leaf:1"), "Exact fixture correction"))
        assertEquals(rule, parseMetadataEdgeExclusion(rule.argument()))
        val effective = effectiveConfiguration(p, ResolverPolicy(metadataExclusions = listOf(rule)))
        val content = MemoryContent(); val service = DependencyLocks(content, noPom)
        val lock = service.resolve(effective, Transport(entries))
        lock.selections.forEach { selection ->
            assertEquals(selection.domain != domain, selection.artifacts.any { it.coordinate.name == "leaf" })
        }
        val puts = content.puts
        assertEquals(lock, service.loadLocked(lock.bytes(), effective)); assertEquals(puts, content.puts)
        assertFails { service.loadLocked(lock.bytes(), effectiveConfiguration(p)) }
        assertFails { effectiveConfiguration(p, ResolverPolicy(metadataExclusions = listOf(rule.copy(domain = ResolutionDomain.script("missing", "java-api"))))) }
        val query = queryProjectDependencies("kvik.json", "lock.json", "cache", p, effective, { lock.bytes() }, content, noPom, 1000,
            ProjectQueryFilter(scripts = listOf("same"), usage = "java-api"))
        assertEquals("complete", query.values.getValue("status").stringValue())
        val edges = query.values.getValue("domains").arrayValue().single().objectValue().getValue("graph").objectValue().getValue("edges").arrayValue()
        assertTrue(edges.any { it.objectValue().getValue("kind").stringValue() == "excluded" })
        save("script-exclusion-lock.json", lock.toJson()); save("script-exclusion-query.json", query)
    }

    @Test fun profileWorkspacePluginUsesHostDomainAndSnapshotsPrivateProducerInputs() {
        val p = project(listOf(module("plugin", listOf(dependency("helper", module = true), dependency("example:host:1"))), module("helper"), module("unused")),
            mapOf("run" to script(profiles = listOf("base"))), mapOf("base" to obj("compilerPlugins" to listOf(plugin("plugin")).json())))
        val expanded = expandScript(p.project, "run")
        assertEquals("/dependencyProfiles/base/compilerPlugins/0", expanded.compilerPlugins.single().origin.declaration)
        val effective = effectiveConfiguration(p, scopes = emptyList())
        val host = ResolutionDomain.script("run", "java-runtime", "compiler-plugin", "example.plugin")
        assertEquals(listOf(Coordinate.parse("example:host:1")), effective.requests.filter { it.domain == host }.map { it.coordinate })
        assertTrue(effective.requests.none { it.domain.consumer is ResolutionConsumer.Script && !it.domain.isHostTool })
        val plan = projectInputPlan(p, SnapshotSelection.Script("run"))
        assertEquals(listOf("helper/main", "plugin/main"), plan.directories.map { it.value })
        assertEquals(listOf(LogicalPath("plugin/options.json")), plan.files)
        assertEquals(setOf("plugin", "helper"), scriptPrerequisiteModules(p.project, expanded).toSet())
    }

    @Test fun standaloneKeepsCompanionDocumentAndCannotBorrowWorkspaceModules() {
        val document = obj("schemaVersion" to 1.json(), "name" to "tool".json(), "repositories" to listOf(obj("id" to repository.id.json(), "url" to repository.url.json())).json(),
            "dependencyCatalog" to obj("lib" to "example:lib:1".json()), "script" to script(listOf(obj("catalog" to "lib".json()))))
        val configuration = parseStandaloneScriptConfiguration(document)
        val effective = effectiveConfiguration(configuration)
        assertEquals(document, effective.document.objectValue().getValue("standaloneScript"))
        assertNull(effective.document.objectValue()["project"])
        assertEquals(emptyList(), effective.document.objectValue().getValue("scopes").arrayValue())
        assertTrue(effective.requests.all { it.domain.consumer == ResolutionConsumer.Script("tool") })
        assertEquals("/script/dependencies/0", configuration.expanded().dependencies.single().origin.declaration)
        assertEquals("/dependencyCatalog/lib", configuration.expanded().dependencies.single().origin.catalog)
        val content = MemoryContent(); val service = DependencyLocks(content, noPom)
        val lock = service.resolve(effective, Transport(component("example:lib:1")))
        assertEquals(lock, service.loadLocked(lock.bytes(), effective))
        assertFails { parseStandaloneScriptConfiguration(JsonValue.Object(document.values + ("script" to script(listOf(obj("module" to "library".json())))))) }
        save("standalone-lock.json", lock.toJson()); save("standalone-effective.json", effective.document)
    }

    @Test fun typedDomainParserAndCliScopePinsRejectMixedOrUnsupportedDomains() {
        val domain = ResolutionDomain.script("same", "java-api")
        assertEquals(domain, ResolutionDomain.fromJson(domain.toJson()))
        for (bad in listOf(JsonValue.Object(domain.toJson().values + ("module" to "same".json())),
            JsonValue.Object(domain.toJson().values - "script"), JsonValue.Object(domain.toJson().values + ("compilation" to "main".json())),
            JsonValue.Object(domain.toJson().values + ("target" to "macosArm64".json())), JsonValue.Object(domain.toJson().values + ("script" to "same\n".json())))) {
            assertFails { ResolutionDomain.fromJson(bad) }
        }
        val pins = listOf(ScopedPin(domain, Coordinate.parse("example:lib:1")), ScopedPin(ResolutionDomain("same", "jvm", "main", "java-api"), Coordinate.parse("example:lib:2")),
            ScopedPin(ResolutionDomain.script("same", "java-runtime", "compiler-plugin", "example.first"), Coordinate.parse("example:lib:3")),
            ScopedPin(ResolutionDomain("script", "jvm", "main", "java-runtime", "compiler-plugin", "example.second"), Coordinate.parse("example:lib:4")))
        pins.forEach { assertEquals(it.domain, ResolutionDomain.fromJson(it.domain.toJson())) }
        val options = projectOptions(pins.flatMap { listOf("--scope-pin", it.argument()) }, lockOptions = true)
        assertEquals(pins, options.scopedPins)
        assertEquals("script:same:jvm:script:java-api:dependencies=example:lib:1", pins.first().argument())
        val commonPin = ScopedPin(ResolutionDomain("same", "common", "test", "kotlin-metadata", sourceSet = "commonTest"), Coordinate.parse("example:lib:2"))
        assertEquals("source-set:same:commonTest:common:test:kotlin-metadata:dependencies=example:lib:2", commonPin.argument())
        assertEquals(listOf(commonPin), projectOptions(listOf("--scope-pin", commonPin.argument()), true).scopedPins)
        assertFails { projectOptions(listOf("--scope-pin", "source-set:same:commonTest:jvm:test:java-api:dependencies=example:lib:2"), true) }
        assertFails { projectOptions(listOf("--scope-pin", "script:same:jvm:main:java-api:dependencies=example:lib:1"), true) }
        for (bad in listOf("same:jvm:main:java-runtime:dependencies:example.plugin", "same:jvm:main:java-runtime:compiler-plugin:",
            "same:jvm:main:java-runtime:compiler-plugin:bad/id", "script:same:jvm:script:java-api:compiler-plugin:example.plugin")) {
            assertFails { projectOptions(listOf("--scope-pin", "$bad=example:lib:1"), true) }
        }
        assertFails { domain.copy(pluginId = "example.plugin") }
        assertFails { ResolutionDomain.fromJson(JsonValue.Object(pins.last().domain.toJson().values + ("pluginId" to JsonValue.Null))) }
        assertFails { ResolverPolicy(scopedPins = listOf(pins.first(), pins.first())) }
        assertFails { effectiveConfiguration(project(emptyList(), mapOf("other" to script())), options.resolverPolicy()) }
    }

    @Test fun projectQueriesFilterTypedConsumersAfterReadonlyCompleteLockVerification() {
        val p = project(listOf(module("same", listOf(dependency("example:lib:1")))), mapOf("same" to script(listOf(obj("coordinate" to "example:lib:1".json())))))
        val effective = effectiveConfiguration(p)
        val content = MemoryContent(); val service = DependencyLocks(content, noPom)
        val lock = service.resolve(effective, Transport(component("example:lib:1")))
        val puts = content.puts
        fun query(filter: ProjectQueryFilter) = queryProjectDependencies("kvik.json", "kvik.lock.json", "cache", p, effective, { lock.bytes() }, content, noPom, 1000, filter)
        val scripts = query(ProjectQueryFilter(scripts = listOf("same")))
        assertEquals("complete", scripts.values.getValue("status").stringValue())
        assertEquals(2, scripts.values.getValue("domains").arrayValue().size)
        assertTrue(scripts.values.getValue("domains").arrayValue().all { "script" in it.objectValue().getValue("domain").objectValue() })
        val modules = query(ProjectQueryFilter(modules = listOf("same")))
        assertEquals(4, modules.values.getValue("domains").arrayValue().size)
        assertTrue(modules.values.getValue("domains").arrayValue().all { "module" in it.objectValue().getValue("domain").objectValue() })
        val declaration = scripts.values.getValue("declarations").arrayValue().single { "script" in it.objectValue() }
        assertEquals("/scripts/same/dependencies/0", declaration.objectValue().getValue("origin").objectValue().getValue("declaration").stringValue())
        assertEquals(puts, content.puts)
        assertFails { query(ProjectQueryFilter(scripts = listOf("missing"))) }
        save("script-query.json", scripts); save("module-query.json", modules)
        content.blobs.remove(lock.selections.first().metadata.first().digest)
        assertEquals("incomplete", query(ProjectQueryFilter(scripts = listOf("same"))).values.getValue("status").stringValue())
    }
}

private fun write(path: String, text: String) {
    NativeFiles.ensureDirectory(path.substringBeforeLast('/'))
    val stream = fopen(path, "wb") ?: error("Cannot write fixture $path")
    try {
        val bytes = text.encodeToByteArray()
        if (bytes.isNotEmpty()) bytes.usePinned { check(fwrite(it.addressOf(0), 1u, bytes.size.toULong(), stream) == bytes.size.toULong()) }
    } finally { fclose(stream) }
}
private fun fixedTime(path: String) = memScoped {
    val times = allocArray<timeval>(2)
    for (i in 0..1) { times[i].tv_sec = 1_234_567_890; times[i].tv_usec = 0 }
    check(utimes(path, times) == 0)
}
private class SnapshotFixture {
    val root = NativeFiles.tempDirectory("/private/tmp")
    val external = NativeFiles.tempDirectory("/private/tmp")
    val lock = "$external/lock.json"
    val content = MemoryContent()
    val configuration = project(listOf(module("library", listOf(dependency("private", module = true))), module("private"), module("unrelated")),
        mapOf("run" to script(listOf(obj("module" to "library".json())), profiles = listOf("base")),
            "other" to obj("sources" to listOf("other.kt".json()).json())),
        mapOf("base" to obj("compilerPlugins" to listOf(plugin()).json())))
    val snapshotter = NativeProjectSnapshotter(root, content)
    init {
        write("$root/kvik.json", configuration.document.canonical()); write(lock, "exact lock\n")
        for ((name, bytes) in mapOf("scripts/Run.kt" to "fun main() = Unit", "library/main/Lib.kt" to "class Lib", "library/test/Test.kt" to "class Test",
            "private/main/Private.kt" to "class Private", "unrelated/main/Other.kt" to "class Other", "plugin/options.json" to "{}", "assets/value" to "data", "other.kt" to "fun other() = Unit")) write("$root/$name", bytes)
    }
    fun capture(selection: SnapshotSelection = SnapshotSelection.Script("run")) =
        assertIs<SnapshotCapture.Captured>(snapshotter.capture(LogicalPath("kvik.json"), lock, selection = selection)).snapshot
    fun close() { NativeFiles.removeTree(root); NativeFiles.removeTree(external) }
}
private inline fun fixture(block: (SnapshotFixture) -> Unit) { val f = SnapshotFixture(); try { block(f) } finally { f.close() } }

class ScriptSnapshotTest {
    @Test fun selectionCapturesOnlyScriptAndRequiredProducerMainInputsAndDurablyRecomputes() = fixture { f ->
        val snapshot = f.capture()
        assertEquals(setOf("scripts/Run.kt", "library/main/Lib.kt", "private/main/Private.kt", "plugin/options.json", "assets/value"), snapshot.inputs.files.map { it.path.value }.toSet())
        assertEquals(SnapshotSelection.Script("run"), snapshot.selection)
        val restored = SnapshotSelection.fromJson(parseJson(snapshot.selection.toJson().canonical()))
        val readonly = object : ContentStore {
            override fun get(digest: Digest): ByteArray? = error("Read-only recomputation must not consult CAS")
            override fun put(bytes: ByteArray): Digest = error("Read-only recomputation must not publish")
        }
        val query = NativeProjectSnapshotter(f.root, readonly)
        assertEquals(snapshot.fingerprint, query.currentFingerprint(LogicalPath("kvik.json"), f.lock, selection = restored))
        write("${f.root}/unrelated/main/Other.kt", "changed"); write("${f.root}/library/test/Test.kt", "changed"); write("${f.root}/other.kt", "changed")
        assertEquals(Freshness.CURRENT, f.snapshotter.freshness(snapshot))
        assertNotEquals(snapshot.fingerprint, f.snapshotter.currentFingerprint(LogicalPath("kvik.json"), f.lock))
        write("${f.root}/private/main/Private.kt", "changed")
        assertEquals(Freshness.STALE, f.snapshotter.freshness(snapshot))
    }

    @Test fun scriptFilesResourcesAndMissingDirectoriesDetectContentAddDeleteRename() = fixture { f ->
        val path = "${f.root}/scripts/Run.kt"
        fixedTime(path); val before = f.capture()
        write(path, "fun main() = null"); fixedTime(path)
        assertEquals(Freshness.STALE, f.snapshotter.freshness(before))
        var snapshot = f.capture()
        write("${f.root}/scripts/more/New.kt", "class New")
        assertEquals(Freshness.STALE, f.snapshotter.freshness(snapshot))
        snapshot = f.capture(); check(rename("${f.root}/scripts/more/New.kt", "${f.root}/scripts/more/Renamed.kt") == 0)
        assertEquals(Freshness.STALE, f.snapshotter.freshness(snapshot))
        snapshot = f.capture(); check(unlink("${f.root}/scripts/more/Renamed.kt") == 0)
        assertEquals(Freshness.STALE, f.snapshotter.freshness(snapshot))
        snapshot = f.capture(); write("${f.root}/assets/value", "other")
        assertEquals(Freshness.STALE, f.snapshotter.freshness(snapshot))
        snapshot = f.capture(); write("${f.root}/plugin/options.json", "{\"enabled\":true}")
        assertEquals(Freshness.STALE, f.snapshotter.freshness(snapshot))
    }

    @Test fun exactConfigurationLockAndSelectionBindFingerprintsAndPreflightPrecedesCas() = fixture { f ->
        val captured = f.capture(); val expected = Sha256.digest(captured.configurationBytes)
        write("${f.root}/kvik.json", f.configuration.document.canonical() + "\n")
        val puts = f.content.puts
        assertIs<SnapshotCapture.Indeterminate>(f.snapshotter.capture(LogicalPath("kvik.json"), f.lock, expected, captured.selection))
        assertEquals(puts, f.content.puts)
        assertEquals(Freshness.STALE, f.snapshotter.freshness(captured))
        val second = f.capture(); write(f.lock, "changed lock\n")
        assertEquals(Freshness.STALE, f.snapshotter.freshness(second))
        assertNotEquals(f.capture().fingerprint, f.capture(SnapshotSelection.Project).fingerprint)
        assertFails { SnapshotSelection.fromJson(obj("kind" to "project".json(), "name" to "run".json())) }
        assertFails { SnapshotSelection.fromJson(obj("kind" to "script".json())) }
    }

    @Test fun selectedStoreGuardAndInputKindsFailClosed() = fixture { f ->
        val file = ProjectFile(f.root, LogicalPath("kvik.json"), f.configuration.document.canonical().encodeToByteArray(), f.configuration)
        requireDisjointStore(file, "${f.root}/unrelated/main", f.lock, "${f.root}/tools.json", SnapshotSelection.Script("run"))
        assertFails { requireDisjointStore(file, "${f.root}/private/main", f.lock, "${f.root}/tools.json", SnapshotSelection.Script("run")) }
        assertFails { requireDisjointStore(file, "${f.root}/scripts/more", f.lock, "${f.root}/tools.json", SnapshotSelection.Script("run")) }
        check(unlink("${f.root}/plugin/options.json") == 0); NativeFiles.ensureDirectory("${f.root}/plugin/options.json")
        assertFails { f.capture() }
        NativeFiles.removeTree("${f.root}/plugin/options.json"); write("${f.root}/plugin/options.json", "{}")
        NativeFiles.removeTree("${f.root}/library/main"); write("${f.root}/library/main", "not a directory")
        assertFails { f.capture() }
    }

    @Test fun scriptLinksAliasesBoundsAndConcurrentEditsNeverYieldCurrentCapture() = fixture { f ->
        val path = "${f.root}/scripts/Run.kt"
        check(unlink(path) == 0); check(symlink("../other.kt", path) == 0)
        assertFails { f.capture() }
        check(unlink(path) == 0); check(link("${f.root}/assets/value", path) == 0)
        assertFails { f.capture() }
        check(unlink(path) == 0); write(path, "fun main() = Unit")
        assertFails { NativeProjectSnapshotter(f.root, f.content, SnapshotLimits(maxDepth = 1)).capture(LogicalPath("kvik.json"), f.lock, selection = SnapshotSelection.Script("run")) }
        var changed = false
        f.content.onPut = { bytes ->
            if (!changed && bytes.contentEquals("fun main() = Unit".encodeToByteArray())) {
                changed = true; write(path, "changed after the source bytes were captured")
            }
        }
        val result = f.snapshotter.capture(LogicalPath("kvik.json"), f.lock, selection = SnapshotSelection.Script("run"))
        assertIs<SnapshotCapture.Indeterminate>(result)
    }

    @Test fun standaloneSnapshotRetainsCompanionAndNeverUsesProjectParser() = fixture { f ->
        val companion = obj("schemaVersion" to 1.json(), "name" to "tool".json(), "repositories" to listOf(obj("id" to repository.id.json(), "url" to repository.url.json())).json(), "script" to script())
        write("${f.root}/tool.script.json", companion.canonical())
        val snapshot = assertIs<SnapshotCapture.Captured>(f.snapshotter.capture(LogicalPath("tool.script.json"), f.lock, selection = SnapshotSelection.StandaloneScript)).snapshot
        assertEquals(companion, snapshot.standaloneConfiguration.document)
        assertFails { snapshot.configuration }
        assertEquals(setOf("scripts/Run.kt", "assets/value"), snapshot.inputs.files.map { it.path.value }.toSet())
        assertEquals(snapshot.fingerprint, f.snapshotter.currentFingerprint(LogicalPath("tool.script.json"), f.lock, selection = SnapshotSelection.StandaloneScript))
        assertFails { f.snapshotter.currentFingerprint(LogicalPath("tool.script.json"), f.lock) }
    }
}
