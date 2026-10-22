@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.packages

import kvik.core.*
import kvik.cache.*
import kvik.resolution.*
import kotlinx.cinterop.*
import platform.posix.*

internal val repository = Repository("central", "https://repo.maven.apache.org/maven2")
internal val testRoot get() = getenv("KVIK_QUERY_ROOT")!!.toKString()
internal val testWork get() = getenv("KVIK_QUERY_WORK")!!.toKString()
internal val ktcCache get() = getenv("KVIK_KTC_CACHE")!!.toKString()
internal fun read(path: String) = NativeFiles.read(path, 64 * 1024 * 1024)
internal fun save(name: String, value: JsonValue) = NativeFiles.writeNew("$testWork/$name", value.canonical().encodeToByteArray())
internal class MemoryCache : QueryCache {
    val entries = mutableMapOf<String, CachedResponse>(); var writes = 0
    override fun read(url: String) = entries[url]
    override fun write(response: CachedResponse) { writes++; entries[response.url] = response }
}
internal class MemoryNetwork(val entries: Map<String, ByteArray>) : ArtifactTransport {
    val calls = mutableListOf<String>()
    override fun fetch(url: String): ByteArray { calls += url; return entries[url] ?: throw MissingResource(url) }
}
internal class MemoryContent : ContentStore {
    val entries = mutableMapOf<Digest, ByteArray>(); var writes = 0
    override fun put(bytes: ByteArray): Digest = Sha256.digest(bytes).also { writes++; entries[it] = bytes.copyOf() }
    override fun get(digest: Digest) = entries[digest]?.copyOf()
}
internal fun component(coordinate: String, dependencies: List<String> = emptyList()): Map<String, ByteArray> {
    val c = Coordinate.parse(coordinate)
    val base = "${repository.url}/${c.group.replace('.', '/')}/${c.name}/${c.version}/${c.name}-${c.version}"
    val bytes = coordinate.encodeToByteArray(); val digest = Sha256.digest(bytes)
    val variants = listOf("java-api", "java-runtime").map { usage -> obj("name" to usage.json(),
        "attributes" to obj("org.gradle.category" to "library".json(), "org.gradle.usage" to usage.json(), "org.jetbrains.kotlin.platform.type" to "jvm".json()),
        "files" to listOf(obj("name" to "${c.name}-${c.version}.jar".json(), "url" to "${c.name}-${c.version}.jar".json(), "size" to digest.sizeBytes.json(), "sha256" to digest.hash.json())).json(),
        "dependencies" to dependencies.map { Coordinate.parse(it).let { d -> obj("group" to d.group.json(), "module" to d.name.json(), "version" to obj("requires" to d.version.json())) } }.json()) }
    return mapOf("$base.module" to obj("formatVersion" to "1.1".json(), "component" to obj("group" to c.group.json(), "module" to c.name.json(), "version" to c.version.json()), "variants" to variants.json()).canonical().encodeToByteArray(), "$base.jar" to bytes)
}
internal fun project(main: List<String>, tests: List<String> = emptyList()): ProjectConfiguration {
    fun source(name: String, dependencies: List<String>) = obj("name" to name.json(), "directories" to emptyList<JsonValue>().json(), "resources" to emptyList<JsonValue>().json(),
        "dependsOn" to emptyList<JsonValue>().json(), "dependencies" to dependencies.map { obj("coordinate" to it.json()) }.json())
    return parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "queries".json(),
        "repositories" to listOf(obj("id" to repository.id.json(), "url" to repository.url.json())).json(), "modules" to listOf(obj("id" to "sample".json(),
            "sourceSets" to listOf(source("main", main), source("test", tests)).json(), "targets" to listOf(obj("name" to "jvm".json(), "main" to "main".json(), "test" to "test".json(),
                "compilerOptions" to emptyList<JsonValue>().json())).json(), "compilerPlugins" to emptyList<JsonValue>().json())).json()))
}
internal fun fixtureTransport(): ArtifactTransport {
    val manifest = parseJson(read("$testRoot/tests/resolution/fixtures/manifest.json").decodeToString()).objectValue()
    return object : ArtifactTransport {
        override fun fetch(url: String): ByteArray {
            val metadata = manifest[url]?.objectValue()
            return if (metadata != null) read("$testRoot/tests/resolution/fixtures/${metadata.getValue("file").stringValue()}").also {
                check(Sha256.digest(it) == Digest(metadata.getValue("hash").stringValue(), metadata.getValue("sizeBytes").longValue()))
            } else if (url.endsWith(".module") || url.endsWith(".pom")) throw MissingResource(url)
            else read("$ktcCache/${url.substringAfter("/maven2/")}")
        }
    }
}
