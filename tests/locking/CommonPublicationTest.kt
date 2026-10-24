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
        assertFalse(defaultDependencyScopes(old).any { it.target == "common" })
        assertNull(effectiveConfiguration(old).document.objectValue().getValue("resolver").objectValue()["commonMetadata"])
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
