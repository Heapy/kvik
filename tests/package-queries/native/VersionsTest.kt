@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.packages

import kvik.core.*
import kvik.cache.*
import kotlin.test.*
import platform.posix.*

class VersionsTest {
    private val id = PackageId("example", "package")
    private val url = "${repository.url}/example/package/maven-metadata.xml"
    private fun xml(vararg versions: String) = "<metadata><groupId>example</groupId><artifactId>package</artifactId><versioning><versions>${versions.joinToString("") { "<version>$it</version>" }}</versions><latest>100</latest><lastUpdated>20261022120000</lastUpdated></versioning></metadata>".encodeToByteArray()
    private fun query(cache: QueryCache, network: MemoryNetwork?, mode: QueryMode, now: Long = 1000, limit: Int = 50, cursor: String? = null, channel: String = "all") =
        queryVersions("/project/kvik.json", id, listOf(repository), QueryTransport(listOf(repository), cache, network, mode, now), NativeVersionXmlParser(), channel, limit, cursor)
    private fun status(value: JsonValue) = value.objectValue().getValue("status").stringValue()
    private fun rows(value: JsonValue) = value.objectValue().getValue("versions").arrayValue().map { it.objectValue().getValue("version").stringValue() }

    @Test fun lexicalPagesBindObservationsFiltersExpiryAndChangedMetadata() {
        val cache = MemoryCache(); val network = MemoryNetwork(mapOf(url to xml("2", "10", "1", "3-rc1", "vendor-version")))
        val first = query(cache, network, QueryMode.CACHE, limit = 2)
        assertEquals(listOf("1", "10"), rows(first)); assertEquals("complete", status(first)); assertEquals(1, network.calls.size)
        val cursor = first.values.getValue("page").objectValue().getValue("nextCursor").stringValue()
        val next = query(cache, network, QueryMode.OFFLINE, limit = 2, cursor = cursor)
        assertEquals(listOf("2", "3-rc1"), rows(next)); assertEquals(1, cache.writes)
        for (bad in listOf(cursor.dropLast(4), "not-base64", "x".repeat(4096))) assertEquals("incomplete", status(query(cache, network, QueryMode.OFFLINE, limit = 2, cursor = bad)))
        assertEquals("incomplete", status(query(cache, network, QueryMode.OFFLINE, now = 4600, limit = 2, cursor = cursor)))
        assertEquals("incomplete", status(query(cache, network, QueryMode.OFFLINE, limit = 3, cursor = cursor)))
        assertEquals("incomplete", status(query(cache, network, QueryMode.REFRESH, now = 1001, limit = 2, cursor = cursor)))
        assertEquals(listOf("1", "10", "2"), rows(query(cache, network, QueryMode.OFFLINE, now = 1001, channel = "stable")))
        assertEquals(listOf("3-rc1"), rows(query(cache, network, QueryMode.OFFLINE, now = 1001, channel = "prerelease")))
        save("versions.json", first)
    }
    @Test fun offlineIsReadOnlyAndFreshnessAndCorruptionAreExplicit() {
        val cache = MemoryCache(); val network = MemoryNetwork(mapOf(url to xml("1")))
        assertEquals("incomplete", status(query(cache, network, QueryMode.OFFLINE))); assertTrue(network.calls.isEmpty()); assertEquals(0, cache.writes)
        query(cache, network, QueryMode.CACHE)
        val stale = query(cache, network, QueryMode.OFFLINE, now = 5000)
        assertEquals("complete", status(stale)); assertEquals("stale", stale.values.getValue("freshness").stringValue())
        query(cache, network, QueryMode.CACHE, now = 5000); assertEquals(2, network.calls.size)
        cache.entries[url] = cache.entries.getValue(url).copy(bytes = byteArrayOf(9))
        for (mode in listOf(QueryMode.OFFLINE, QueryMode.CACHE)) assertEquals("incomplete", status(query(cache, network, mode, now = 5000)))
        assertEquals(2, network.calls.size); assertEquals(2, cache.writes)
        assertEquals("complete", status(query(cache, network, QueryMode.REFRESH, now = 5000)))
        assertEquals(3, network.calls.size)
        val missing = MemoryNetwork(emptyMap()); val negative = MemoryCache()
        assertEquals("complete", status(query(negative, missing, QueryMode.CACHE)))
        assertEquals("not-found", query(negative, missing, QueryMode.OFFLINE).values.getValue("sources").arrayValue().single().objectValue().getValue("status").stringValue())
        save("versions-offline-missing.json", query(MemoryCache(), null, QueryMode.OFFLINE))
    }
    @Test fun multipleRepositoriesKeepProvenanceAndPartialFailureCannotPass() {
        val other = Repository("mirror", "https://mirror.example/repository")
        val otherUrl = url.replace(repository.url, other.url)
        val repos = listOf(repository, other)
        val cache = MemoryCache(); val network = MemoryNetwork(mapOf(url to xml("1", "2"), otherUrl to xml("2", "3-rc1")))
        val result = queryVersions("/project/kvik.json", id, repos, QueryTransport(repos, cache, network, QueryMode.CACHE, 1000), NativeVersionXmlParser())
        assertEquals("complete", status(result)); assertEquals(2, result.values.getValue("sources").arrayValue().size)
        assertEquals(listOf("central", "mirror"), result.values.getValue("versions").arrayValue()[1].objectValue().getValue("repositories").arrayValue().map { it.stringValue() })
        cache.entries.remove(otherUrl)
        val partial = queryVersions("/project/kvik.json", id, repos, QueryTransport(repos, cache, null, QueryMode.OFFLINE, 1000), NativeVersionXmlParser())
        assertEquals("incomplete", status(partial)); assertEquals(listOf("1", "2"), rows(partial)); assertEquals("missing", partial.values.getValue("freshness").stringValue())
        save("versions-multiple.json", result)
        val repeated = QueryTransport(listOf(repository), MemoryCache(), network, QueryMode.REFRESH, 1000)
        repeated.fetch(url); repeated.fetch(url)
        assertEquals("network", repeated.sources.single().cache)
        assertFailsWith<QueryUnavailable> {
            QueryTransport(listOf(repository, repository.copy(id = "alias")), MemoryCache(), network, QueryMode.CACHE, 1000).fetch(url)
        }
    }
    @Test fun primaryMetadataFixturesAndXmlFailures() {
        val provenance = parseJson(read("$testRoot/tests/package-queries/fixtures/provenance.json").decodeToString()).arrayValue()
        for (entry in provenance) {
            val fields = entry.objectValue(); val bytes = read("$testRoot/tests/package-queries/fixtures/${fields.getValue("file").stringValue()}")
            assertEquals(Digest.fromJson(fields.getValue("digest")), Sha256.digest(bytes))
            val coordinate = if (fields.getValue("file").stringValue().startsWith("junit")) PackageId("org.junit.jupiter", "junit-jupiter-api") else PackageId("org.jetbrains.kotlinx", "kotlinx-serialization-json")
            val metadata = publishedVersions(coordinate, bytes, NativeVersionXmlParser())
            assertTrue(if (coordinate.group.startsWith("org.junit")) "5.13.4" in metadata.versions else "1.11.0" in metadata.versions)
            assertTrue(metadata.versions.any { versionChannel(it) == "prerelease" })
        }
        for (bad in listOf(xml("1", "1"), xml("1").decodeToString().replace("<groupId>example", "<groupId>other").encodeToByteArray(),
            "<!DOCTYPE metadata [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><metadata>&x;</metadata>".encodeToByteArray(),
            xml("1").decodeToString().replace("<metadata>", "<metadata xmlns='https://foreign'>").encodeToByteArray(),
            xml("1").decodeToString().replace("</versioning>", "<unsupported/></versioning>").encodeToByteArray())) {
            val value = query(MemoryCache(), MemoryNetwork(mapOf(url to bad)), QueryMode.CACHE)
            assertEquals("unsupported", status(value))
        }
        assertEquals("unclassified", versionChannel("1.0-sp1")); assertEquals("stable", versionChannel("1.0-Final"))
        assertEquals("prerelease", versionChannel("2.4.20-Beta1")); assertEquals("unclassified", versionChannel("nightly"))
    }
    @Test fun nativeCacheRejectsCorruptBytesAndNeverCreatesOfflineDirectories() {
        val path = "$testWork/native-query-cache"
        val cache = NativeQueryCache(path)
        assertNull(cache.read(url)); assertNull(NativeFiles.info(path))
        val bytes = xml("1"); val response = CachedResponse(url, 1000, bytes, Sha256.digest(bytes))
        cache.write(response); assertContentEquals(bytes, cache.read(url)!!.bytes)
        val digest = response.digest!!; val blob = "$path/blobs/${digest.hash}-${digest.sizeBytes}"
        assertEquals(0, unlink(blob)); NativeFiles.writeNew(blob, byteArrayOf(4))
        assertFails { cache.read(url) }; assertFails { cache.write(response) }; assertContentEquals(byteArrayOf(4), read(blob))
        NativeQueryCache(path, allowRepair = true).write(response); assertContentEquals(bytes, cache.read(url)!!.bytes)
        val occupied = "$testWork/not-a-query-cache"; NativeFiles.ensureDirectory(occupied); NativeFiles.writeNew("$occupied/user.txt", bytes)
        assertFails { NativeQueryCache(occupied).write(response) }; assertNull(NativeFiles.info("$occupied/blobs")); assertContentEquals(bytes, read("$occupied/user.txt"))
    }
}
