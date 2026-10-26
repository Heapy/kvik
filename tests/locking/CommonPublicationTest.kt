package kvik.locking

import kvik.core.*
import kvik.resolution.*
import kotlin.test.*

private fun commonModule(id: String, sharedDependencies: List<Dependency> = emptyList(), published: Boolean = true): JsonValue {
    fun dependency(value: Dependency): JsonValue = obj(
        (if (value is Dependency.Module) "module" else "coordinate") to (when (value) {
            is Dependency.Module -> value.module
            is Dependency.External -> value.coordinate.toString()
        }).json(), "scope" to value.scope.name.lowercase().json(), "exported" to value.exported.json())
    fun source(name: String, parents: List<String>, dependencies: List<Dependency>) = obj("name" to name.json(),
        "directories" to listOf("$id/$name".json()).json(), "resources" to emptyList<JsonValue>().json(),
        "dependsOn" to parents.map { it.json() }.json(), "dependencies" to dependencies.map(::dependency).json())
    val fields = obj("id" to id.json(), "sourceSets" to listOf(
        source("sharedBase", emptyList(), sharedDependencies), source("sharedMain", listOf("sharedBase"), emptyList()),
        source("jvmMain", listOf("sharedMain"), listOf(Dependency.External(Coordinate("platform", "jvm", "1")))),
        source("nativeMain", listOf("sharedMain"), listOf(Dependency.External(Coordinate("platform", "native", "1")))),
        source("tests", listOf("sharedMain"), listOf(Dependency.External(Coordinate("platform", "test", "1"))))).json(),
        "targets" to listOf("jvm" to "jvmMain", "macosArm64" to "nativeMain").map { (target, main) ->
            obj("name" to target.json(), "main" to main.json(), "test" to "tests".json(), "compilerOptions" to emptyList<JsonValue>().json())
        }.json(), "compilerPlugins" to emptyList<JsonValue>().json()).objectValue()
    return JsonValue.Object(fields + if (published) mapOf("publication" to obj("coordinate" to "example:$id:1".json())) else emptyMap())
}
private fun commonProject(vararg modules: JsonValue) = parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "sample".json(),
    "repositories" to listOf(obj("id" to "central".json(), "url" to "https://example.test/repository".json())).json(), "modules" to modules.toList().json()))
private fun external(name: String, exported: Boolean = false, runtime: Boolean = false) =
    Dependency.External(Coordinate("example", name, "1"), if (runtime) DependencyScopeKind.RUNTIME else DependencyScopeKind.COMPILE, exported)
private val commonDomain = ResolutionDomain("root", "common", "main", "kotlin-metadata")
private val commonScope = DependencyScope("root", "common", "main", "kotlin-metadata")

class CommonPublicationTest {
    @Test fun sourceSetMetadataUsesTargetIntersectionsAndExportedVisibilityWithoutPublication() {
        val dependency = parseJson(commonModule("dependency", listOf(Dependency.Module("exported", exported = true),
            Dependency.Module("private"), external("public", true), external("hidden")), published = false).canonical()
            .replace("sharedBase", "foundation").replace("sharedMain", "production"))
        val configuration = commonProject(commonModule("root", listOf(Dependency.Module("dependency"), external("own-private"), external("runtime", runtime = true)), false),
            dependency, commonModule("exported", published = false), commonModule("private", published = false))
        val root = configuration.project.modules.first()
        assertEquals(listOf("sharedBase", "sharedMain", "tests"), commonMetadataSourceSets(root).map { it.sourceSet })
        assertEquals(listOf("main", "main", "test"), commonMetadataSourceSets(root).map { it.compilation })
        val closure = commonMetadataDependencyClosure(configuration.project, "root", "sharedMain")
        assertEquals(listOf("dependency" to "foundation", "dependency" to "production", "exported" to "sharedBase", "exported" to "sharedMain"),
            closure.sources.map { it.module to it.sourceSet })
        assertEquals(setOf("own-private", "public"), closure.external.map { it.name }.toSet())
        val scope = commonScope.copy(sourceSet = "sharedMain")
        val effective = effectiveConfiguration(configuration, scopes = listOf(scope))
        assertTrue(effective.requests.all { it.domain.sourceSet != null && it.variant.usage == "kotlin-metadata" })
        assertEquals(setOf("own-private", "public", "kotlin-stdlib"), effective.requests.filter { it.domain == scope.domain() }.map { it.coordinate.name }.toSet())
        assertTrue(effective.requests.any { it.domain.module == "private" }) // Needed by dependency's own editor classpath.
        assertTrue(effective.requests.none { it.coordinate.group == "platform" || it.coordinate.name == "runtime" })
        assertEquals(effective.digest, effectiveConfiguration(configuration, scopes = listOf(scope)).digest)
        assertFails { effectiveConfiguration(configuration, scopes = listOf(scope, scope)) }
    }

    @Test fun intermediateSourceSetSelectsOnlyTheRelevantDependencyFragments() {
        val base = commonProject(commonModule("root", listOf(Dependency.Module("dependency")), false), commonModule("dependency", published = false)).project
        val project = base.copy(modules = base.modules.map { module -> module.copy(
            sourceSets = module.sourceSets + SourceSet("linuxMain", emptyList(), emptyList(), listOf("sharedBase"), listOf(external("linux-only"))),
            targets = module.targets + module.targets.last().copy(name = "linuxX64", main = "linuxMain", test = null)) })
        val root = project.modules.first()
        assertEquals(setOf("jvm", "macosArm64", "linuxX64"), commonMetadataSource(root, "sharedBase").targets)
        assertEquals(setOf("jvm", "macosArm64"), commonMetadataSource(root, "sharedMain").targets)
        assertEquals(listOf("sharedBase"), commonMetadataDependencyClosure(project, "root", "sharedBase").sources.map { it.sourceSet })
        assertEquals(listOf("sharedBase", "sharedMain"), commonMetadataDependencyClosure(project, "root", "sharedMain").sources.map { it.sourceSet })
        val missing = project.copy(modules = project.modules.map { if (it.id == "dependency") it.copy(targets = it.targets.filter { target -> target.name != "linuxX64" }) else it })
        assertFails { commonMetadataDependencyClosure(missing, "root", "sharedBase") }
        assertFails { commonMetadataSource(root, "jvmMain") }
    }

    @Test fun commonTestsInheritPrivateMainInputsWithoutAnExplicitRefinementEdge() {
        val original = commonModule("root", listOf(external("production-private"), external("runtime", runtime = true)), false)
        val fields = original.objectValue()
        val sources = fields.getValue("sourceSets").arrayValue().map { value ->
            val source = value.objectValue()
            if (source.getValue("name").stringValue() != "tests") value else JsonValue.Object(source + ("dependsOn" to emptyList<JsonValue>().json()))
        }
        val configuration = commonProject(JsonValue.Object(fields + ("sourceSets" to sources.json())))
        assertEquals(listOf("sharedBase", "sharedMain", "tests"), commonMetadataInputs(configuration.project.modules.single(), "tests").map { it.name })
        val scope = commonScope.copy(scope = "test", sourceSet = "tests")
        val effective = effectiveConfiguration(configuration, scopes = listOf(scope))
        assertEquals(setOf("production-private", "test", "kotlin-stdlib"), effective.requests.filter { it.domain == scope.domain() }.map { it.coordinate.name }.toSet())
        assertTrue(effective.requests.filter { it.domain.compilation == "main" }.none { it.coordinate.name == "test" })
        assertFails { effectiveConfiguration(configuration, scopes = listOf(scope.copy(scope = "main"))) }
        assertFails { effectiveConfiguration(configuration, scopes = listOf(scope.copy(sourceSet = "missing"))) }
    }

    @Test fun sourceSetDomainIsOptionalStrictAndDistinctFromLegacyAggregateMetadata() {
        assertNull(commonDomain.toJson().values["sourceSet"])
        assertEquals(commonDomain, ResolutionDomain.fromJson(commonDomain.toJson()))
        val main = commonDomain.copy(sourceSet = "sharedMain")
        val test = commonDomain.copy(compilation = "test", sourceSet = "tests")
        for (domain in listOf(main, test)) assertEquals(domain, ResolutionDomain.fromJson(domain.toJson()))
        assertEquals(3, setOf(commonDomain, main, test).size)
        assertFalse(main.isHostTool)
        for (invalid in listOf({ main.copy(target = "jvm", usage = "java-api") }, { main.copy(sourceSet = "") },
            { main.copy(sourceSet = "sharedMain\n") }, { main.copy(sourceSet = "../sharedMain") },
            { main.copy(consumer = ResolutionConsumer.Script("root")) }, { main.copy(purpose = "compiler-plugin") },
            { test.copy(sourceSet = null) })) assertFails { invalid() }
    }

    @Test fun sourceSetScopedPinsAndLockReplayKeepMainAndTestSelectionsIndependent() {
        val original = commonModule("root", listOf(external("dependency")), false)
        val fields = original.objectValue()
        val sources = fields.getValue("sourceSets").arrayValue().map { value ->
            val source = value.objectValue()
            if (source.getValue("name").stringValue() != "tests") value else JsonValue.Object(source + ("dependencies" to emptyList<JsonValue>().json()))
        }
        val configuration = commonProject(JsonValue.Object(fields + ("sourceSets" to sources.json())))
        val scope = commonScope.copy(scope = "test", sourceSet = "tests")
        val pin = ScopedPin(scope.domain(), Coordinate("example", "dependency", "2"))
        val effective = effectiveConfiguration(configuration, ResolverPolicy(scopedPins = listOf(pin)), listOf(scope))
        val store = object : ContentStore {
            val blobs = mutableMapOf<Digest, ByteArray>()
            override fun put(bytes: ByteArray): Digest = Sha256.digest(bytes).also { blobs[it] = bytes.copyOf() }
            override fun get(digest: Digest): ByteArray? = blobs[digest]?.copyOf()
        }
        val entries = mutableMapOf<String, ByteArray>()
        for (coordinate in listOf(Coordinate("example", "dependency", "1"), pin.coordinate, Coordinate("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20"))) {
            val path = "https://example.test/repository/${coordinate.group.replace('.', '/')}/${coordinate.name}/${coordinate.version}/${coordinate.name}-${coordinate.version}"
            val artifact = coordinate.toString().encodeToByteArray(); val digest = Sha256.digest(artifact)
            entries["$path.jar"] = artifact
            entries["$path.module"] = obj("formatVersion" to "1.1".json(), "component" to obj("group" to coordinate.group.json(), "module" to coordinate.name.json(), "version" to coordinate.version.json()),
                "variants" to listOf(obj("name" to "metadata".json(), "attributes" to obj("org.gradle.category" to "library".json(), "org.gradle.usage" to "kotlin-metadata".json(), "org.jetbrains.kotlin.platform.type" to "common".json()),
                    "files" to listOf(obj("name" to path.substringAfterLast('/').plus(".jar").json(), "url" to path.substringAfterLast('/').plus(".jar").json(), "size" to digest.sizeBytes.json(), "sha256" to digest.hash.json())).json())).json()).canonical().encodeToByteArray()
        }
        val service = DependencyLocks(store, PomParser { error("Metadata resolution must not use POM") })
        val lock = service.resolve(effective, object : ArtifactTransport { override fun fetch(url: String): ByteArray = entries[url] ?: throw MissingResource(url) })
        assertEquals(lock, service.loadLocked(lock.bytes(), effective))
        assertEquals(setOf("sharedBase", "sharedMain", "tests"), lock.selections.map { it.domain.sourceSet }.toSet())
        for (selection in lock.selections.filter { it.requested.name == "dependency" })
            assertEquals(if (selection.domain == scope.domain()) "2" else "1", selection.artifacts.single().coordinate.version)
        assertFails { service.loadLocked(lock.bytes(), effectiveConfiguration(configuration, scopes = listOf(scope))) }
        val tampered = lock.copy(selections = lock.selections.map { selection ->
            if (selection.domain.sourceSet == "sharedBase") selection.copy(domain = selection.domain.copy(sourceSet = null)) else selection
        })
        assertFails { service.loadLocked(tampered.bytes(), effective) }
    }

    @Test fun iosFrameworkDomainsKeepCompileAndRuntimeClosuresSeparate() {
        val original = commonProject(commonModule("root", listOf(external("api"), external("runtime", runtime = true))))
        for (target in listOf("iosArm64", "iosSimulatorArm64")) {
            val configuration = parseProjectConfiguration(parseJson(original.document.canonical()
                .replace("macosArm64", target).replace("\"test\":\"tests\"", "\"test\":null")))
            val api = ResolutionDomain("root", target, "main", "kotlin-api")
            val runtime = api.copy(usage = "kotlin-runtime")
            val effective = effectiveConfiguration(configuration, ResolverPolicy(scopedPins = listOf(ScopedPin(runtime, Coordinate("example", "api", "2")))))
            assertTrue(effective.requests.filter { it.domain == api }.none { it.coordinate.name == "runtime" })
            assertTrue(effective.requests.filter { it.domain == runtime }.any { it.coordinate.name == "runtime" })
            assertTrue(effective.requests.filter { it.domain.target == target }.all { it.variant == VariantRequest(target, "kotlin-api", emptyMap()) })
            assertEquals(emptyList(), effective.policy.pinsFor(api))
            assertEquals(listOf(Coordinate("example", "api", "2")), effective.policy.pinsFor(runtime))
            assertEquals(runtime, ResolutionDomain.fromJson(runtime.toJson()))
        }
    }

    @Test fun linuxResolutionDomainsPreserveConsumerScopesAndHostPluginIdentity() {
        val original = commonProject(commonModule("root", listOf(external("api"), external("runtime", runtime = true))))
        val linux = parseProjectConfiguration(parseJson(original.document.canonical().replace("macosArm64", "linuxX64")))
        val api = ResolutionDomain("root", "linuxX64", "main", "kotlin-api")
        val runtime = api.copy(usage = "kotlin-runtime")
        val policy = ResolverPolicy(scopedPins = listOf(ScopedPin(runtime, Coordinate("example", "api", "2"))))
        val effective = effectiveConfiguration(linux, policy)
        val apiRequests = effective.requests.filter { it.domain == api }
        val runtimeRequests = effective.requests.filter { it.domain == runtime }
        assertTrue(apiRequests.none { it.coordinate.name == "runtime" })
        assertTrue(runtimeRequests.any { it.coordinate.name == "runtime" })
        assertTrue((apiRequests + runtimeRequests).all { it.variant == VariantRequest("linuxX64", "kotlin-api", emptyMap()) })
        assertEquals(emptyList(), effective.policy.pinsFor(api))
        assertEquals(listOf(Coordinate("example", "api", "2")), effective.policy.pinsFor(runtime))
        assertEquals(runtime, ResolutionDomain.fromJson(runtime.toJson()))
        assertNotEquals(effective.digest, effectiveConfiguration(original).digest)
        assertTrue(effective.requests.any { it.domain.target == "common" && it.coordinate.name == "kotlin-stdlib" })
        for (purpose in listOf("compiler-plugin", "ksp-runtime", "symbol-processor")) {
            val host = ResolutionDomain("root", "linuxX64", "test", "java-runtime", purpose)
            assertTrue(host.isHostTool)
            assertEquals(host, ResolutionDomain.fromJson(host.toJson()))
        }
        assertFails { ResolutionDomain("root", "linuxArm64", "main", "kotlin-api") }
        assertFails { ResolutionDomain("root", "linuxX64", "main", "java-runtime", "ksp-runtime") }
    }

    @Test fun sharedIntersectionAndVisibilityAreIndependentOfPlatformAndTestDeclarations() {
        val configuration = commonProject(commonModule("root", listOf(Dependency.Module("dependency"), external("own-private"), external("runtime", runtime = true))),
            commonModule("dependency", listOf(Dependency.Module("exported", exported = true), Dependency.Module("private"), external("public", true), external("hidden"))),
            commonModule("exported"), commonModule("private"))
        val root = configuration.project.modules.first()
        assertEquals(listOf("sharedBase", "sharedMain"), commonPublicationSourceSets(root).map { it.name })
        val closure = commonPublicationDependencyClosure(configuration.project, "root")
        assertEquals(listOf("dependency", "exported"), closure.modules)
        assertEquals(setOf("public", "own-private"), closure.external.map { it.name }.toSet())
        val effective = effectiveConfiguration(configuration, scopes = listOf(commonScope))
        assertTrue(effective.requests.all { it.variant == VariantRequest("common", "kotlin-metadata", emptyMap()) })
        val rootRequests = effective.requests.filter { it.domain == commonDomain }
        assertEquals(setOf("public", "own-private", "kotlin-stdlib"), rootRequests.map { it.coordinate.name }.toSet())
        assertEquals("2.4.20", rootRequests.single { it.coordinate.name == "kotlin-stdlib" }.coordinate.version)
        assertEquals(setOf("root", "dependency", "exported", "private"), effective.requests.map { it.domain.module }.toSet())
        assertTrue(effective.requests.none { it.coordinate.group == "platform" || it.coordinate.name == "runtime" })
    }

    @Test fun commonScopesArePublicationOnlyAndRejectInvalidDomainsOrWorkspaceDependencies() {
        val published = commonProject(commonModule("root"))
        assertTrue(commonScope in defaultDependencyScopes(published))
        val old = commonProject(commonModule("root", published = false))
        assertFalse(defaultDependencyScopes(old).any { it.target == "common" && it.sourceSet == null })
        assertEquals(setOf("sharedBase", "sharedMain", "tests"), defaultDependencyScopes(old).filter { it.target == "common" }.map { it.sourceSet }.toSet())
        assertEquals(obj("stdlib" to "org.jetbrains.kotlin:kotlin-stdlib:2.4.20".json()),
            effectiveConfiguration(old).document.objectValue().getValue("resolver").objectValue()["commonMetadata"])
        assertEquals(obj("stdlib" to "org.jetbrains.kotlin:kotlin-stdlib:2.4.20".json()),
            effectiveConfiguration(published).document.objectValue().getValue("resolver").objectValue()["commonMetadata"])
        assertFails { effectiveConfiguration(old, scopes = listOf(commonScope)) }
        val module = published.project.modules.single()
        assertTrue(commonPublicationSourceSets(module.copy(targets = module.targets.take(1))).isEmpty())
        val disjoint = module.copy(targets = module.targets.mapIndexed { index, target -> target.copy(main = if (index == 0) "jvmMain" else "tests") },
            sourceSets = module.sourceSets.map { it.copy(dependsOn = emptyList()) })
        assertTrue(commonPublicationSourceSets(disjoint).isEmpty())
        assertFails { commonPublicationDependencyClosure(commonProject(commonModule("root", listOf(Dependency.Module("other"))), commonModule("other", published = false)).project, "root") }
        for (domain in listOf({ commonDomain.copy(compilation = "test") }, { commonDomain.copy(usage = "kotlin-api") },
            { commonDomain.copy(purpose = "compiler-plugin") }, { ResolutionDomain(ResolutionConsumer.Script("root"), "common", "main", "kotlin-metadata") })) assertFails { domain() }
        assertEquals(commonDomain, ResolutionDomain.fromJson(commonDomain.toJson()))
    }

    @Test fun scopedPolicyAndLockReplayPreserveTheCommonDomain() {
        val configuration = commonProject(commonModule("root", listOf(external("dependency"))))
        val pins = ResolverPolicy(scopedPins = listOf(ScopedPin(commonDomain, Coordinate("example", "dependency", "2"))))
        val effective = effectiveConfiguration(configuration, pins, listOf(commonScope))
        val store = object : ContentStore {
            val blobs = mutableMapOf<Digest, ByteArray>()
            override fun put(bytes: ByteArray): Digest = Sha256.digest(bytes).also { blobs[it] = bytes.copyOf() }
            override fun get(digest: Digest): ByteArray? = blobs[digest]?.copyOf()
        }
        val entries = mutableMapOf<String, ByteArray>()
        for (coordinate in listOf(Coordinate("example", "dependency", "2"), Coordinate("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20"))) {
            val path = "https://example.test/repository/${coordinate.group.replace('.', '/')}/${coordinate.name}/${coordinate.version}/${coordinate.name}-${coordinate.version}"
            val artifact = coordinate.toString().encodeToByteArray(); val digest = Sha256.digest(artifact)
            entries["$path.jar"] = artifact
            entries["$path.module"] = obj("formatVersion" to "1.1".json(), "component" to obj("group" to coordinate.group.json(), "module" to coordinate.name.json(), "version" to coordinate.version.json()),
                "variants" to listOf(obj("name" to "metadata".json(), "attributes" to obj("org.gradle.category" to "library".json(), "org.gradle.usage" to "kotlin-metadata".json(), "org.jetbrains.kotlin.platform.type" to "common".json()),
                    "files" to listOf(obj("name" to path.substringAfterLast('/').plus(".jar").json(), "url" to path.substringAfterLast('/').plus(".jar").json(), "size" to digest.sizeBytes.json(), "sha256" to digest.hash.json())).json())).json()).canonical().encodeToByteArray()
        }
        val service = DependencyLocks(store, PomParser { error("Common resolution must not use POM") })
        var calls = 0
        val lock = service.resolve(effective, object : ArtifactTransport { override fun fetch(url: String): ByteArray { calls++; return entries[url] ?: throw MissingResource(url) } })
        val fetched = calls
        assertEquals(lock, service.loadLocked(lock.bytes(), effective))
        assertEquals(fetched, calls)
        assertTrue(lock.selections.all { it.domain == commonDomain && it.variant.usage == "kotlin-metadata" })
        assertEquals("2", lock.selections.single { it.requested.name == "dependency" }.artifacts.single().coordinate.version)
        assertFails { service.loadLocked(lock.bytes(), effectiveConfiguration(configuration, scopes = listOf(commonScope))) }
        assertFails { lock.selections.first().copy(selection = lock.selections.first().selection.copy(variant = VariantRequest("common", "kotlin-api", emptyMap()))) }
        val blob = lock.selections.first().artifacts.single().digest
        store.blobs[blob] = byteArrayOf(0)
        assertFails { service.loadLocked(lock.bytes(), effective) }
    }
}
