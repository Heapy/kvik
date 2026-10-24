package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kvik.resolution.PomParser
import kvik.resolution.MissingResource
import kotlin.test.*

class ScriptDeclarationLockTest {
    private val repository = "https://example.org/scripts"
    private val primary = LogicalPath("task.kts")
    private fun config(text: String) = parseStandaloneScriptConfiguration(obj("schemaVersion" to 1.json(), "name" to "task".json(),
        "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
        "script" to ScriptSpec(listOf(primary), entryScript = primary).toJson())).withScriptDeclarations { text.encodeToByteArray() }

    private fun component(coordinate: String, dependencies: List<String> = emptyList()): Map<String, ByteArray> {
        val c = Coordinate.parse(coordinate)
        val bytes = coordinate.encodeToByteArray()
        val digest = Sha256.digest(bytes)
        val base = "$repository/${c.group.replace('.', '/')}/${c.name}/${c.version}/${c.name}-${c.version}"
        val variants = listOf("java-api", "java-runtime").map { usage -> obj("name" to usage.json(),
            "attributes" to obj("org.gradle.category" to "library".json(), "org.gradle.usage" to usage.json(), "org.jetbrains.kotlin.platform.type" to "jvm".json()),
            "files" to listOf(obj("name" to "${c.name}-${c.version}.jar".json(), "url" to "${c.name}-${c.version}.jar".json(),
                "size" to digest.sizeBytes.json(), "sha256" to digest.hash.json())).json(),
            "dependencies" to dependencies.map { value -> Coordinate.parse(value).let {
                obj("group" to it.group.json(), "module" to it.name.json(), "version" to obj("requires" to it.version.json())) } }.json()) }
        return mapOf("$base.jar" to bytes, "$base.module" to obj("formatVersion" to "1.1".json(),
            "component" to obj("group" to c.group.json(), "module" to c.name.json(), "version" to c.version.json()),
            "variants" to variants.json()).canonical().encodeToByteArray())
    }

    @Test fun annotationRootsResolveTransitivesAndReplayOfflineWithoutExecutingScript() {
        val text = "@file:DependsOn(\"g:root:1\")\n@file:Repository(\"$repository\")\nerror(\"never execute at resolve\")"
        val configuration = config(text)
        val effective = effectiveConfiguration(configuration)
        val entries = component("g:root:1", listOf("g:leaf:2")) + component("g:leaf:2")
        val blobs = mutableMapOf<Digest, ByteArray>()
        val store = object : ContentStore {
            override fun put(bytes: ByteArray) = Sha256.digest(bytes).also { blobs[it] = bytes.copyOf() }
            override fun get(digest: Digest) = blobs[digest]?.copyOf()
        }
        val calls = mutableListOf<String>()
        val transport = object : ArtifactTransport {
            override fun fetch(url: String): ByteArray { calls += url; return entries[url] ?: throw MissingResource(url) }
        }
        val service = DependencyLocks(store, PomParser { error("Fixture only uses Gradle module metadata") })
        val lock = service.resolve(effective, transport)
        assertEquals(2, lock.selections.size)
        assertTrue(lock.selections.all { it.domain.consumer == ResolutionConsumer.Script("task") })
        assertEquals(setOf("g:root:1", "g:leaf:2"), lock.selections.flatMap { it.artifacts }.map { it.coordinate.toString() }.toSet())
        assertTrue(lock.selections.flatMap { it.artifacts }.all { it.url.startsWith(repository) && it.repository.startsWith("script-") })
        val before = calls.toList()
        assertEquals(lock, service.loadLocked(lock.bytes(), effectiveConfiguration(config(text + "\nprintln(42)"))))
        assertEquals(before, calls)
        assertFails { service.loadLocked(lock.bytes(), effectiveConfiguration(config(text.replace("root:1", "root:2")))) }
        assertFails { service.loadLocked(lock.bytes(), effectiveConfiguration(config(text.replace(repository, "https://other.org/scripts")))) }
        val project = parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "aggregate".json(),
            "repositories" to configuration.document.values.getValue("repositories"), "modules" to emptyList<JsonValue>().json(),
            "scripts" to JsonValue.Object(listOf("first", "second").associateWith { name ->
                val source = LogicalPath("$name.kts")
                ScriptSpec(listOf(source), entryScript = source).toJson()
            }))).withScriptDeclarations { path ->
                (if (path.value == "first.kts") text else text.replace(repository, "$repository/")).encodeToByteArray()
            }
        val combined = effectiveConfiguration(project)
        val combinedLock = service.resolve(combined, transport)
        assertEquals(4, combinedLock.selections.size)
        assertEquals(combinedLock, service.loadLocked(combinedLock.bytes(), combined))
        assertEquals(1, combinedLock.selections.flatMap { it.artifacts }.map { it.repository }.distinct().size)
    }
}
