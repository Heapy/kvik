@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.preferences

import kvik.core.*
import kvik.cache.NativeFiles
import kvik.locking.*
import kvik.packages.queryProjectDependencies
import kvik.resolution.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.*

private val repo = Repository("central", "https://repo.maven.apache.org/maven2")
private val native = VariantRequest("macosArm64", "kotlin-api", emptyMap())
private val parent = Coordinate("example", "parent", "1")
private val leaf = Coordinate("example", "leaf", "9")
private fun url(c: Coordinate) = "${repo.url}/${c.group.replace('.', '/')}/${c.name}/${c.version}/${c.name}-${c.version}.module"
private fun edge(c: Coordinate = leaf, semantics: String = "prefers", version: JsonValue = obj(semantics to c.version.json())) =
    obj("group" to c.group.json(), "module" to c.name.json(), "version" to version)
private fun metadata(c: Coordinate, dependencies: List<JsonValue> = emptyList(), constraints: List<JsonValue> = emptyList(), redirect: Coordinate? = null, owner: Coordinate? = null): ByteArray {
    val attributes = obj("org.gradle.category" to "library".json(), "org.gradle.usage" to "kotlin-api".json(),
        "org.jetbrains.kotlin.platform.type" to "native".json(), "org.jetbrains.kotlin.native.target" to "macos_arm64".json())
    val variant = if (redirect == null) obj("name" to "native".json(), "attributes" to attributes, "dependencies" to dependencies.json(), "dependencyConstraints" to constraints.json())
        else obj("name" to "native".json(), "attributes" to attributes, "available-at" to obj("group" to redirect.group.json(), "module" to redirect.name.json(), "version" to redirect.version.json(),
            "url" to "../../${redirect.name}/${redirect.version}/${redirect.name}-${redirect.version}.module".json()))
    val identity = owner ?: c
    val component = obj("group" to identity.group.json(), "module" to identity.name.json(), "version" to identity.version.json()).let {
        if (owner == null) it else JsonValue.Object(it.objectValue() + ("url" to "../../${owner.name}/${owner.version}/${owner.name}-${owner.version}.module".json()))
    }
    return obj("formatVersion" to "1.1".json(), "component" to component, "variants" to listOf(variant).json()).canonical().encodeToByteArray()
}
private class Transport(val entries: Map<String, ByteArray>) : ArtifactTransport {
    val calls = mutableListOf<String>()
    override fun fetch(url: String): ByteArray { calls += url; return entries[url] ?: throw MissingResource(url) }
}
private class Content : ContentStore {
    val entries = mutableMapOf<Digest, ByteArray>()
    var puts = 0
    override fun put(bytes: ByteArray): Digest = Sha256.digest(bytes).also { puts++; entries[it] = bytes.copyOf() }
    override fun get(digest: Digest) = entries[digest]?.copyOf()
}
private fun resolver(entries: Map<String, ByteArray>, pins: List<Coordinate> = emptyList(), exclusions: List<MetadataEdgeExclusion> = emptyList()) =
    MavenResolver(Transport(entries), NativePomParser(), pins, metadataExclusions = exclusions)
private fun project(coordinate: Coordinate) = parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "preferred".json(),
    "repositories" to listOf(obj("id" to repo.id.json(), "url" to repo.url.json())).json(), "modules" to listOf(obj("id" to "sample".json(),
        "sourceSets" to listOf(obj("name" to "main".json(), "directories" to emptyList<JsonValue>().json(), "resources" to emptyList<JsonValue>().json(), "dependsOn" to emptyList<JsonValue>().json(),
            "dependencies" to listOf(obj("coordinate" to coordinate.toString().json())).json())).json(),
        "targets" to listOf(obj("name" to "macosArm64".json(), "main" to "main".json(), "test" to JsonValue.Null, "compilerOptions" to emptyList<JsonValue>().json())).json(),
        "compilerPlugins" to emptyList<JsonValue>().json())).json()))
private fun effective(coordinate: Coordinate, pins: List<Coordinate>) = effectiveConfiguration(project(coordinate), ResolverPolicy(pins), listOf(DependencyScope("sample", "macosArm64", "main", "kotlin-api")))

class PreferredVersionsTest {
    @Test fun preferenceNeedsAnExplicitPinAndDoesNotImposeAMinimum() {
        val original = metadata(parent, listOf(edge()))
        for (version in listOf("1", "9", "10", "2.4.20-RC1")) {
            val chosen = leaf.copy(version = version)
            val graph = resolver(mapOf(url(parent) to original, url(chosen) to metadata(chosen)), listOf(chosen)).resolveGraph(listOf(parent), native, listOf(repo))
            val requirement = graph.edges.single()
            assertEquals(leaf, requirement.requirement); assertEquals(chosen, requirement.selected)
            assertEquals("prefers", requirement.semantics); assertEquals("dependency", requirement.kind)
        }
        val transport = Transport(mapOf(url(parent) to original, url(leaf) to metadata(leaf)))
        val failure = assertFails { MavenResolver(transport, NativePomParser()).resolve(parent, native, listOf(repo)) }
        assertTrue(failure.message.orEmpty().contains("requires an explicit version pin"))
        assertEquals(listOf(url(parent)), transport.calls)
        // A prior explicit root is not an implicit pin; refusal is encounter-order independent.
        for (roots in listOf(listOf(leaf, parent), listOf(parent, leaf)))
            assertFails { resolver(transport.entries).resolveAll(roots, native, listOf(repo)) }
    }

    @Test fun requiredDependenciesAndConstraintsStillWinInEitherRootOrder() {
        val required = Coordinate("example", "required", "1")
        val entries = mapOf(url(parent) to metadata(parent, listOf(edge())), url(required) to metadata(required, listOf(edge(leaf.copy(version = "2"), "requires"))))
        for (roots in listOf(listOf(parent, required), listOf(required, parent))) {
            val selected = leaf.copy(version = "2")
            val graph = resolver(entries + (url(selected) to metadata(selected)), listOf(selected)).resolveGraph(roots, native, listOf(repo))
            assertEquals(setOf("2"), graph.nodes.filter { it.coordinate.name == "leaf" }.map { it.coordinate.version }.toSet())
            assertEquals(setOf("requires", "prefers"), graph.edges.map { it.semantics }.toSet())
            val lower = leaf.copy(version = "1")
            assertFails { resolver(entries + (url(lower) to metadata(lower)), listOf(lower)).resolveAll(roots, native, listOf(repo)) }
            val constrained = entries + (url(required) to metadata(required, constraints = listOf(edge(leaf.copy(version = "3"), "requires"))))
            assertFails { resolver(constrained + (url(selected) to metadata(selected)), listOf(selected)).resolveAll(roots, native, listOf(repo)) }
        }
    }

    @Test fun softConstraintsNeitherIntroduceDependenciesNorRejectStrongerSelections() {
        val selected = leaf.copy(version = "2")
        val absent = Coordinate("example", "absent", "100")
        val transport = Transport(mapOf(url(parent) to metadata(parent, listOf(edge(selected, "requires")), listOf(edge(), edge(absent))), url(selected) to metadata(selected)))
        val graph = MavenResolver(transport, NativePomParser()).resolveGraph(listOf(parent), native, listOf(repo))
        val constraints = graph.edges.filter { it.kind == "constraint" }
        assertEquals(2, constraints.size); assertTrue(constraints.all { it.semantics == "prefers" && !it.transitive })
        assertEquals(selected, constraints.single { it.requirement == leaf }.selected)
        assertNull(constraints.single { it.requirement == absent }.target)
        assertNull(constraints.single { it.requirement == absent }.selected)
        assertFalse(transport.calls.any { "/absent/" in it })
        val work = getenv("KVIK_METADATA_WORK")!!.toKString()
        NativeFiles.publish("$work/preferred-constraints.json", graph.toJson().canonical().encodeToByteArray(), 1024 * 1024)
    }

    @Test fun unsupportedRichVersionsAndMutablePreferencesFailClosed() {
        val selected = leaf.copy(version = "2")
        val invalid = listOf(obj(), obj("requires" to "1".json(), "prefers" to "2".json()), obj("strictly" to "2".json()),
            obj("prefers" to "2".json(), "rejects" to listOf("1".json()).json()), obj("prefers" to JsonValue.Null), obj("prefers" to 2.json())) +
            listOf("", "[1,2]", "1.+", "latest.release", "2-SNAPSHOT", "2\n").map { obj("prefers" to it.json()) }
        for (version in invalid) for (constraint in listOf(false, true)) {
            val dependencies = listOf(edge(version = version))
            val bytes = if (constraint) metadata(parent, constraints = dependencies) else metadata(parent, dependencies)
            assertFails { resolver(mapOf(url(parent) to bytes, url(selected) to metadata(selected)), listOf(selected)).resolve(parent, native, listOf(repo)) }
        }
        for (version in listOf("latest.release", "2-SNAPSHOT"))
            assertFails { resolver(mapOf(url(parent) to metadata(parent, listOf(edge()))), listOf(leaf.copy(version = version))).resolve(parent, native, listOf(repo)) }
    }

    @Test fun exclusionMatchesTheRawPreferenceBeforePinAndCannotExcludeAConstraint() {
        val bytes = metadata(parent, listOf(edge()))
        val rule = MetadataEdgeExclusion(parent, Sha256.digest(bytes), "native", leaf, "Explicit fixture edge correction")
        val graph = resolver(mapOf(url(parent) to bytes), listOf(leaf.copy(version = "2")), listOf(rule)).resolveGraph(listOf(parent), native, listOf(repo), true)
        assertEquals(leaf, graph.edges.single().requirement); assertEquals(rule, graph.edges.single().exclusion)
        assertNull(graph.edges.single().selected)
        assertFails { resolver(mapOf(url(parent) to bytes), listOf(leaf.copy(version = "2")), listOf(rule.copy(dependency = leaf.copy(version = "2")))).resolve(parent, native, listOf(repo)) }
        val constraint = metadata(parent, constraints = listOf(edge()))
        assertFails { resolver(mapOf(url(parent) to constraint), exclusions = listOf(rule.copy(metadata = Sha256.digest(constraint)))).resolve(parent, native, listOf(repo)) }
    }

    @Test fun preferredAliasRetainsOriginalRequirementAndRedirectIdentity() {
        val chosen = leaf.copy(version = "2")
        val redirected = Coordinate("example", "leaf-native", "2")
        val entries = mapOf(url(parent) to metadata(parent, listOf(edge())), url(chosen) to metadata(chosen, redirect = redirected),
            url(redirected) to metadata(redirected, owner = chosen))
        val graph = resolver(entries, listOf(chosen)).resolveGraph(listOf(parent), native, listOf(repo))
        assertEquals(leaf, graph.edges.single { it.semantics == "prefers" }.requirement)
        assertEquals(chosen, graph.edges.single { it.semantics == "prefers" }.selected)
        assertEquals(redirected, graph.edges.single { it.kind == "redirect" }.selected)
        assertEquals(3, graph.selections.single().metadata.size)
    }

    @Test fun actualAtomicfuPreferencePinsNativeStdlibAndSurvivesOfflineLockAndQueryReplay() {
        val root = getenv("KVIK_RESOLUTION_ROOT")!!.toKString()
        val cache = getenv("KVIK_KTC_CACHE")!!.toKString()
        val work = getenv("KVIK_METADATA_WORK")!!.toKString()
        val manifest = parseJson(NativeFiles.read("$root/tests/resolution/fixtures/manifest.json", 1024 * 1024).decodeToString()).objectValue()
        val fetched = mutableListOf<String>()
        val transport = object : ArtifactTransport {
            override fun fetch(url: String): ByteArray {
            fetched += url
            val fixture = manifest[url]?.objectValue()
            return if (fixture != null) NativeFiles.read("$root/tests/resolution/fixtures/${fixture.getValue("file").stringValue()}", 1024 * 1024).also {
                assertEquals(Digest(fixture.getValue("hash").stringValue(), fixture.getValue("sizeBytes").longValue()), Sha256.digest(it))
            } else {
                require(url.startsWith(repo.url + "/org/jetbrains/kotlinx/atomicfu-macosarm64/0.32.1/") && url.endsWith(".klib")) { "Unexpected actual fixture request: $url" }
                NativeFiles.read("$cache/${url.removePrefix(repo.url + "/")}", 1024 * 1024)
            }
        }
        }
        val coordinate = Coordinate.parse("org.jetbrains.kotlinx:atomicfu:0.32.1")
        val stdlib = Coordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
        val configuration = effective(coordinate, listOf(stdlib))
        val content = Content(); val locks = DependencyLocks(content, NativePomParser())
        val lock = locks.resolve(configuration, transport)
        assertEquals(listOf(Digest("368865987bfa5da765e40b15b4df60fd12f257688ed65a96ca71ebf2cf1a5261", 83073),
            Digest("165c584c77f7c716cdc5a083093ef235c9bacfeabad6ed32ca9f7b928acbe292", 3545)), lock.selections.single().artifacts.map { it.digest })
        val parentMetadata = Digest("650c1dc58d4c9dca48b8bf3d575545e073193d73287ebbee820bec6a7a962728", 4162)
        assertTrue(lock.selections.single().metadata.any { it.digest == parentMetadata })
        val before = fetched.toList(); val writes = content.puts
        assertEquals(lock, locks.loadLocked(lock.bytes(), configuration)); assertEquals(before, fetched); assertEquals(writes, content.puts)
        val query = queryProjectDependencies("/fixture/kvik.json", "/fixture/kvik.lock.json", "/fixture/store", project(coordinate), configuration,
            { lock.bytes() }, content, NativePomParser(), 1L)
        assertEquals("complete", query.objectValue().getValue("status").stringValue())
        val graph = query.objectValue().getValue("domains").arrayValue().single().objectValue().getValue("graph").objectValue()
        val preference = graph.getValue("edges").arrayValue().map(JsonValue::objectValue).single { it.getValue("requirement").objectValue().getValue("semantics").stringValue() == "prefers" }
        assertEquals("org.jetbrains.kotlin:kotlin-stdlib:2.2.0", preference.getValue("requirement").objectValue().getValue("coordinate").stringValue())
        assertEquals(stdlib.toString(), preference.getValue("selected").stringValue())
        assertEquals(before, fetched); assertEquals(writes, content.puts)
        NativeFiles.publish("$work/preferred-project.json", query.canonical().encodeToByteArray(), 1024 * 1024)
        NativeFiles.publish("$work/preferred-lock.json", lock.bytes(), 1024 * 1024)
        val original = content.entries.getValue(parentMetadata)
        content.entries.remove(parentMetadata); assertFails { locks.loadLocked(lock.bytes(), configuration) }
        content.entries[parentMetadata] = byteArrayOf(0); assertFails { locks.loadLocked(lock.bytes(), configuration) }
        content.entries[parentMetadata] = original
        assertFails { locks.loadLocked(lock.bytes(), effective(coordinate, emptyList())) }
        assertFails { locks.loadLocked(lock.bytes(), effective(coordinate, listOf(stdlib.copy(version = "2.3.20")))) }
    }
}
