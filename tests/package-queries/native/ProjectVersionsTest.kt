package kvik.packages

import kvik.core.*
import kvik.locking.*
import kvik.resolution.*
import kotlin.test.*

class ProjectVersionsTest {
    @Test fun scopedLockedVersionsAndTransitivePackagesShareOneObservation() {
        val configuration = project(listOf("example:root:1.0"))
        val policy = ResolverPolicy(scopedPins = listOf("java-api", "java-runtime").map {
            ScopedPin(ResolutionDomain("sample", "jvm", "test", it), Coordinate.parse("example:leaf:2.0"))
        })
        val effective = effectiveConfiguration(configuration, policy)
        val content = MemoryContent()
        val lock = DependencyLocks(content, NativePomParser()).resolve(effective, MemoryNetwork(
            component("example:root:1.0", listOf("example:leaf:1.0")) + component("example:leaf:1.0") + component("example:leaf:2.0")))
        val cache = MemoryCache()
        val network = MemoryNetwork(listOf("root", "leaf").associate { name ->
            "${repository.url}/example/$name/maven-metadata.xml" to
                "<metadata><groupId>example</groupId><artifactId>$name</artifactId><versioning><versions><version>1.0</version><version>2.0</version><version>3.0-RC1</version></versions></versioning></metadata>".encodeToByteArray()
        })
        fun base(filter: ProjectQueryFilter = ProjectQueryFilter()) = queryProjectDependencies("/project/kvik.json", "/project/kvik.lock.json",
            "/project/cache", configuration, effective, { lock.bytes() }, content, NativePomParser(), 1000, filter)
        fun add(result: JsonValue.Object, mode: QueryMode) = withAvailableVersions(result, effective) { id, repositories ->
            queryVersions("/project/kvik.json", id, repositories, QueryTransport(repositories, cache, network, mode, 1000), NativeVersionXmlParser(), "stable", 1)
        }
        val original = base()
        val queried = add(original, QueryMode.CACHE)
        assertEquals("complete", queried.values.getValue("status").stringValue())
        assertEquals(original.values.getValue("domains"), queried.values.getValue("domains"))
        assertEquals(original.values.getValue("dependencyLock"), queried.values.getValue("dependencyLock"))
        val packages = queried.values.getValue("availableVersions").objectValue().getValue("packages").arrayValue()
        assertEquals(2, packages.size)
        val leaf = packages.first().objectValue()
        assertEquals(listOf("1.0".json(), "2.0".json()).json(), leaf.getValue("lockedVersions"))
        assertEquals(4, leaf.getValue("domains").arrayValue().size)
        val available = leaf.getValue("available").objectValue()
        assertEquals("example:leaf", available.getValue("query").objectValue().getValue("coordinate").stringValue())
        assertEquals(1, available.getValue("sources").arrayValue().size)
        val cursor = available.getValue("page").objectValue().getValue("nextCursor").stringValue()
        val next = queryVersions("/project/kvik.json", PackageId("example", "leaf"), listOf(repository),
            QueryTransport(listOf(repository), cache, null, QueryMode.OFFLINE, 1000), NativeVersionXmlParser(), "stable", 1, cursor)
        assertEquals("2.0", next.values.getValue("versions").arrayValue().single().objectValue().getValue("version").stringValue())
        val calls = network.calls.size; val writes = cache.writes; val contentWrites = content.writes
        val offline = add(original, QueryMode.OFFLINE)
        assertEquals("complete", offline.values.getValue("status").stringValue())
        assertEquals(calls, network.calls.size); assertEquals(writes, cache.writes); assertEquals(contentWrites, content.writes)
        val filtered = add(base(ProjectQueryFilter(compilation = "main", usage = "java-api")), QueryMode.OFFLINE)
        val selected = filtered.values.getValue("availableVersions").objectValue().getValue("packages").arrayValue().first().objectValue()
        assertEquals(listOf("1.0".json()).json(), selected.getValue("lockedVersions"))
        assertEquals(1, selected.getValue("domains").arrayValue().size)
        val resumed = withAvailableVersions(original, effective, setOf(PackageId("example", "leaf")), singleQuery = true) { id, repositories ->
            queryVersions("/project/kvik.json", id, repositories, QueryTransport(repositories, cache, null, QueryMode.OFFLINE, 1000),
                NativeVersionXmlParser(), "stable", 1, cursor)
        }
        assertEquals(1, resumed.values.getValue("availableVersions").objectValue().getValue("packages").arrayValue().size)
        assertEquals("complete", resumed.values.getValue("status").stringValue())
        assertFailsWith<IllegalArgumentException> {
            withAvailableVersions(original, effective, setOf(PackageId("example", "absent"))) { _, _ -> error("Must not query unknown packages") }
        }
        assertFailsWith<IllegalArgumentException> {
            withAvailableVersions(original, effective, singleQuery = true) { _, _ -> error("Must reject ambiguous cursor before querying") }
        }
        save("project-available-versions.json", queried)
    }

    @Test fun unavailableMetadataAndInvalidLocksDoNotBecomeCompleteResults() {
        val configuration = project(listOf("example:root:1.0"))
        val effective = effectiveConfiguration(configuration)
        val content = MemoryContent()
        val lock = DependencyLocks(content, NativePomParser()).resolve(effective, MemoryNetwork(component("example:root:1.0")))
        fun base(bytes: ByteArray) = queryProjectDependencies("/project/kvik.json", "/project/kvik.lock.json", "/project/cache",
            configuration, effective, { bytes }, content, NativePomParser(), 1000)
        val cache = MemoryCache()
        var calls = 0
        fun add(value: JsonValue.Object) = withAvailableVersions(value, effective) { id, repositories ->
            calls++
            queryVersions("/project/kvik.json", id, repositories, QueryTransport(repositories, cache, null, QueryMode.OFFLINE, 1000), NativeVersionXmlParser())
        }
        val result = add(base(lock.bytes()))
        assertEquals("incomplete", result.values.getValue("status").stringValue())
        assertEquals(0, cache.writes)
        assertEquals(1, calls)
        assertTrue(result.values.getValue("diagnostics").arrayValue().any {
            it.objectValue().getValue("code").stringValue() == "KVIK_QUERY_VERSIONS_INCOMPLETE"
        })
        assertEquals("incomplete", add(base("{}".encodeToByteArray())).values.getValue("status").stringValue())
        assertEquals(1, calls)
        save("project-versions-unavailable.json", result)
    }

    @Test fun largeScopesBoundRepositoryQueriesAndReportOmittedPackages() {
        val coordinates = (1..33).map { "example:lib$it:1.0" }
        val configuration = project(coordinates)
        val effective = effectiveConfiguration(configuration)
        val content = MemoryContent()
        val lock = DependencyLocks(content, NativePomParser()).resolve(effective,
            MemoryNetwork(coordinates.flatMap { component(it).entries }.associate { it.toPair() }))
        val original = queryProjectDependencies("/project/kvik.json", "/project/kvik.lock.json", "/project/cache",
            configuration, effective, { lock.bytes() }, content, NativePomParser(), 1000)
        var calls = 0
        val result = withAvailableVersions(original, effective) { id, repositories ->
            calls++
            queryVersions("/project/kvik.json", id, repositories,
                QueryTransport(repositories, MemoryCache(), MemoryNetwork(emptyMap()), QueryMode.CACHE, 1000), NativeVersionXmlParser())
        }
        assertEquals(32, calls)
        assertEquals(33, result.values.getValue("availableVersions").objectValue().getValue("totalPackages").longValue().toInt())
        assertEquals("incomplete", result.values.getValue("status").stringValue())
        assertTrue(result.values.getValue("truncated").booleanValue())
        save("project-versions-bounded.json", result)
    }
}
