package kvik.kodesource

import kvik.core.*
import kvik.projectsnapshot.SnapshotFailure
import kotlin.test.*

private fun registry(vararg entries: JsonValue): JsonValue.Object = obj(
    "schemaVersion" to 1.json(), "operation" to "projects.list".json(),
    "home" to "/tmp/kode".json(), "projects" to entries.toList().json(),
)

private class RegistryTransport(var bytes: ByteArray = registry().canonical().encodeToByteArray()) : KodeTransport {
    var exitCode = 0
    var stderr = byteArrayOf()
    var calls = 0
    var duringExecute: (() -> Unit)? = null
    override fun execute(arguments: List<String>, maxOutputBytes: Int, checkpoint: () -> Unit): KodeResponse {
        calls++
        assertEquals(listOf("projects", "list"), arguments)
        assertEquals(2 * 1024 * 1024, maxOutputBytes)
        duringExecute?.invoke()
        checkpoint()
        return KodeResponse(exitCode, bytes, stderr)
    }
}

private fun registryProvider(value: JsonValue) = KodeSourceProvider(RegistryTransport(value.canonical().encodeToByteArray()))
private fun JsonValue.Object.withField(name: String, value: JsonValue) = JsonValue.Object(values + (name to value))

class KodeRegistryTest {
    @Test fun returnsSortedTypedMetadataWithoutFollowingHeads() {
        val sha1 = "a".repeat(40)
        val sha256 = "b".repeat(64)
        val transport = RegistryTransport(registry(
            obj("name" to "zeta".json(), "source" to "https://example.test/repository".json(),
                "sourceRef" to "refs/heads/main".json(), "description" to "Описание".json(), "main" to sha1.json()),
            obj("name" to "alpha".json()),
            obj("name" to "beta".json(), "main" to sha256.json()),
        ).canonical().encodeToByteArray())
        assertEquals(listOf(KodeProjectEntry("alpha"), KodeProjectEntry("beta", main = sha256),
            KodeProjectEntry("zeta", "https://example.test/repository", "refs/heads/main", "Описание", sha1)),
            KodeSourceProvider(transport).projects())
        assertEquals(1, transport.calls)
        assertEquals(emptyList(), registryProvider(registry()).projects())
    }

    @Test fun rejectsMalformedEnvelopesAndDuplicateJsonMembers() {
        val document = registry()
        val invalid = listOf<JsonValue>(
            JsonValue.Null, emptyList<JsonValue>().json(),
            document.withField("schemaVersion", 2.json()), document.withField("schemaVersion", "1".json()),
            document.withField("operation", "projects.add".json()), document.withField("operation", JsonValue.Null),
            document.withField("projects", obj()), document.withField("home", JsonValue.Null),
            document.withField("home", 1.json()), document.withField("extra", true.json()),
            JsonValue.Object(document.values - "home"), JsonValue.Object(document.values - "projects"),
            JsonValue.Object(document.values - "schemaVersion"), JsonValue.Object(document.values - "operation"),
        )
        for (value in invalid) assertFails(value.toString()) { registryProvider(value).projects() }
        val malformed = listOf("{", """{"schemaVersion":1,"operation":"projects.list","home":"/tmp","projects":[],"projects":[]}""",
            """{"schemaVersion":1,"operation":"projects.list","home":"/tmp","projects":[{"name":"a","name":"b"}]}""")
        for (text in malformed) assertFails { KodeSourceProvider(RegistryTransport(text.encodeToByteArray())).projects() }
        assertFails { KodeSourceProvider(RegistryTransport(byteArrayOf(-1))).projects() }
    }

    @Test fun validatesProjectNamesUniquenessAndEntryShapes() {
        val invalidEntries = listOf<JsonValue>(JsonValue.Null, "project".json(), obj(),
            obj("name" to 1.json()), obj("name" to "valid".json(), "extra" to true.json())) +
            listOf("", "Upper", "../outside", "a/b", "a".repeat(65), "a\u0000", "é").map { obj("name" to it.json()) }
        for (entry in invalidEntries) assertFails(entry.toString()) { registryProvider(registry(entry)).projects() }
        assertFails { registryProvider(registry(obj("name" to "same".json()), obj("name" to "same".json()))).projects() }
        val maximumName = "a".repeat(64)
        assertEquals(maximumName, registryProvider(registry(obj("name" to maximumName.json()))).projects().single().name)
    }

    @Test fun validatesOptionalStringsAndFullNonzeroHeads() {
        val entry = obj("name" to "project".json())
        for (field in listOf("source", "sourceRef", "description", "main")) {
            for (value in listOf(JsonValue.Null, 1.json(), obj(), emptyList<JsonValue>().json()))
                assertFails(field) { registryProvider(registry(entry.withField(field, value))).projects() }
        }
        for (head in listOf("main", "a".repeat(39), "a".repeat(41), "a".repeat(63), "a".repeat(65),
            "A".repeat(40), "g".repeat(40), "0".repeat(40), "0".repeat(64)))
            assertFails(head) { registryProvider(registry(entry.withField("main", head.json()))).projects() }
    }

    @Test fun enforcesUtf8StringBoundsAndProjectCount() {
        val entry = obj("name" to "project".json())
        for (field in listOf("source", "sourceRef", "description")) {
            for (text in listOf("a".repeat(4096), "é".repeat(2048)))
                assertEquals(1, registryProvider(registry(entry.withField(field, text.json()))).projects().size)
            for (text in listOf("a".repeat(4097), "é".repeat(2049)))
                assertFails(field) { registryProvider(registry(entry.withField(field, text.json()))).projects() }
        }
        assertEquals(emptyList(), registryProvider(registry().withField("home", "é".repeat(2048).json())).projects())
        assertFails { registryProvider(registry().withField("home", "é".repeat(2049).json())).projects() }
        val entries = (0..256).map { obj("name" to "p$it".json()) }
        assertEquals(256, registryProvider(registry(*entries.take(256).toTypedArray())).projects().size)
        assertFails { registryProvider(registry(*entries.toTypedArray())).projects() }
    }

    @Test fun rejectsTransportFailureAndOverBoundCapture() {
        val failed = RegistryTransport().also { it.exitCode = 7 }
        assertFailsWith<SnapshotFailure> { KodeSourceProvider(failed).projects() }
        assertEquals(1, failed.calls)
        val response = registry().canonical().encodeToByteArray()
        val exact = response + ByteArray(2 * 1024 * 1024 - response.size) { 32 }
        assertEquals(emptyList(), KodeSourceProvider(RegistryTransport(exact)).projects())
        assertFails { KodeSourceProvider(RegistryTransport(exact + byteArrayOf(32))).projects() }
        val oversizedError = RegistryTransport().also { it.stderr = ByteArray(2 * 1024 * 1024 + 1) }
        assertFails { KodeSourceProvider(oversizedError).projects() }
    }

    @Test fun offlineDiscoveryFailsWithoutTransportOrCacheFallback() {
        val transport = RegistryTransport()
        val failure = assertFailsWith<SnapshotFailure> { KodeSourceProvider(transport, offline = true).projects() }
        assertTrue(failure.message.orEmpty().contains("Offline kode project discovery"))
        assertEquals(0, transport.calls)
    }

    @Test fun cancellationPropagatesBeforeDuringAndAfterTransport() {
        class Cancelled : RuntimeException()
        val cancellation = Cancelled()
        val transport = RegistryTransport()
        assertSame(cancellation, assertFailsWith<Cancelled> { KodeSourceProvider(transport).projects { throw cancellation } })
        assertEquals(0, transport.calls)
        transport.duringExecute = { throw cancellation }
        assertSame(cancellation, assertFailsWith<Cancelled> { KodeSourceProvider(transport).projects() })
        assertEquals(1, transport.calls)
        val entries = registry(obj("name" to "first".json()), obj("name" to "second".json()))
        val parsingTransport = RegistryTransport(entries.canonical().encodeToByteArray())
        var checks = 0
        // Preflight, before execute, transport checkpoint, after execute, then one checkpoint per entry.
        assertSame(cancellation, assertFailsWith<Cancelled> {
            KodeSourceProvider(parsingTransport).projects { if (++checks == 6) throw cancellation }
        })
        assertEquals(1, parsingTransport.calls)
        assertEquals(6, checks)
    }
}
