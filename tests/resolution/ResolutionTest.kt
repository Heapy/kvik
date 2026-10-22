@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.resolution

import kotlin.test.*
import kotlinx.cinterop.*
import platform.posix.*
import kvik.core.*

private val repo = Repository("central", "https://repo.maven.apache.org/maven2")
private val jvm = VariantRequest("jvm", "java-runtime", emptyMap())
private val native = VariantRequest("macosArm64", "kotlin-api", emptyMap())
private val root: String get() = getenv("KVIK_RESOLUTION_ROOT")!!.toKString()
private fun read(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    try {
        check(fseek(file, 0, SEEK_END) == 0)
        val size = ftell(file); require(size in 0..67108864)
        rewind(file)
        return ByteArray(size.toInt()).also { if (it.isNotEmpty()) it.usePinned { p -> check(fread(p.addressOf(0), 1u, size.toULong(), file) == size.toULong()) } }
    } finally { fclose(file) }
}
private fun write(path: String, bytes: ByteArray) {
    val file = fopen(path, "wb") ?: error("Cannot write $path")
    try { if (bytes.isNotEmpty()) bytes.usePinned { check(fwrite(it.addressOf(0), 1u, bytes.size.toULong(), file) == bytes.size.toULong()) } }
    finally { fclose(file) }
}

private class FixtureTransport : ArtifactTransport {
    val manifest = parseJson(read("$root/tests/resolution/fixtures/manifest.json")!!.decodeToString()).objectValue()
    val blobs = mutableMapOf<Digest, ByteArray>()
    val urls = mutableListOf<String>()
    override fun fetch(url: String): ByteArray {
        urls += url
        val metadata = manifest[url]?.objectValue()
        val bytes = if (metadata != null) {
            read("$root/tests/resolution/fixtures/${metadata.getValue("file").stringValue()}")!!.also {
                assertEquals(Digest(metadata.getValue("hash").stringValue(), metadata.getValue("sizeBytes").longValue()), Sha256.digest(it))
            }
        } else if (url.endsWith(".module") || url.endsWith(".pom")) throw MissingResource(url)
        else {
            val path = "$root/.kvik/tests/resolution/${Sha256.digest(url.encodeToByteArray()).hash}.blob"
            read(path) ?: NativeHttpsTransport().fetch(url).also { write(path, it) }
        }
        blobs[Sha256.digest(bytes)] = bytes
        return bytes
    }
}
private fun module(c: Coordinate, variants: String) = """{"formatVersion":"1.1","component":{"group":"${c.group}","module":"${c.name}","version":"${c.version}"},"variants":[$variants]}""".encodeToByteArray()
private fun variant(dependencies: String = "", extra: String = "", attrs: String = "") = """{"name":"runtime","attributes":{"org.gradle.category":"library","org.gradle.usage":"java-runtime","org.jetbrains.kotlin.platform.type":"jvm"$attrs}$dependencies$extra}"""
private val example = Coordinate("example", "a", "1.0")
private class MemoryTransport(val entries: Map<String, ByteArray>) : ArtifactTransport {
    override fun fetch(url: String) = entries[url] ?: throw MissingResource(url)
}
private fun resolve(bytes: ByteArray, request: VariantRequest = jvm, extra: Map<String, ByteArray> = emptyMap()) =
    MavenResolver(MemoryTransport(extra + ("${repo.url}/example/a/1.0/a-1.0.module" to bytes)), NativePomParser()).resolve(example, request, listOf(repo))

class ResolutionTest {
    @Test fun actualPinnedFixtureClosuresAndOfflineReplay() {
        val transport = FixtureTransport()
        val resolver = MavenResolver(transport, NativePomParser(), listOf(Coordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")))
        val roots = listOf("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0", "it.krzeminski:snakeyaml-engine-kmp:5.0.0", "com.squareup.okio:okio:3.18.2", "org.jetbrains.kotlin:kotlin-stdlib:2.4.20").map(Coordinate::parse)
        val result = listOf(jvm, native).flatMap { request ->
            val selections = resolver.resolveAll(roots, request, listOf(repo))
            val artifacts = selections.flatMap { it.artifacts }.distinctBy { it.url }
            val names = artifacts.map { it.coordinate.name }.toSet()
            assertEquals(if (request == jvm) setOf("kotlinx-serialization-core-jvm", "kotlinx-serialization-json-jvm", "kotlin-stdlib", "annotations", "snakeyaml-engine-kmp-jvm", "okio-jvm", "urlencoder-lib-jvm") else setOf("kotlinx-serialization-core-macosarm64", "kotlinx-serialization-json-macosarm64", "snakeyaml-engine-kmp-macosarm64", "okio-macosarm64", "urlencoder-lib-macosarm64"), names)
            selections.forEach { s ->
                assertEquals(s.artifacts.map { it.digest }, verifyLocked(s) { transport.blobs[it] }.map(Sha256::digest))
                assertEquals(s, resolver.resolve(s.requested, request, listOf(repo)))
                assertFailsWith<IllegalStateException> { verifyLocked(s) { null } }
                assertFailsWith<IllegalArgumentException> { verifyLocked(s) { byteArrayOf(1) } }
            }
            selections.map { s -> obj("requested" to s.requested.toString().json(), "platform" to request.platform.json(), "artifacts" to s.artifacts.map { a -> obj("coordinate" to a.coordinate.toString().json(), "variant" to a.variant.json(), "url" to a.url.json(), "digest" to a.digest.toJson()) }.json(), "metadata" to s.metadata.map { m -> obj("url" to m.url.json(), "digest" to m.digest.toJson()) }.json()) }
        }
        assertEquals(parseJson(read("$root/tests/resolution/fixtures/selections.json")!!.decodeToString()), result.json())
        write("$root/.kvik/tests/resolution/selections.json", result.json().canonical().encodeToByteArray())
        println("Verified 10 fixture closures, 7 JVM and 5 Native artifacts, deterministic/offline replay")
    }

    @Test fun ambiguityMissingPlatformsAndUnknownAttributesFail() {
        assertFailsWith<IllegalArgumentException> { resolve(module(example, variant() + "," + variant())) }
        assertFailsWith<IllegalArgumentException> { resolve(module(example, variant()), native) }
        assertFailsWith<IllegalArgumentException> { resolve(module(example, variant(attrs = ",\"unknown.attribute\":\"value\""))) }
        assertFailsWith<IllegalArgumentException> { resolve(module(example, variant(extra = ",\"capabilities\":[]"))) }
    }

    @Test fun unsupportedVersionExclusionAndRedirectSemanticsFail() {
        for (dependency in listOf(
            """{"group":"g","module":"b","version":{"requires":"1.+"}}""",
            """{"group":"g","module":"b","version":{"strictly":"1.0"}}""",
            """{"group":"g","module":"b","version":{"requires":"1.0"},"excludes":[]}""",
        )) assertFailsWith<IllegalArgumentException> { resolve(module(example, variant(dependencies = ",\"dependencies\":[$dependency]"))) }
        assertFailsWith<IllegalArgumentException> { resolve(module(example, variant(extra = """, "available-at":{"group":"evil","module":"a","version":"1.0","url":"https://evil/a.module"}"""))) }
        assertFailsWith<IllegalArgumentException> { resolve(module(example, variant(extra = """, "available-at":{"group":"example","module":"a","version":"1.0","url":"../../a/1.0/a-1.0.module"}"""))) }
    }

    @Test fun digestIdentityAndBoundsFail() {
        assertFailsWith<IllegalArgumentException> { resolve(module(Coordinate("evil", "a", "1.0"), variant())) }
        val bytes = byteArrayOf(1,2,3)
        val file = """, "files":[{"name":"a.jar","url":"a.jar","size":3,"sha256":"${"0".repeat(64)}"}]"""
        assertFailsWith<IllegalArgumentException> { resolve(module(example, variant(extra = file)), extra = mapOf("${repo.url}/example/a/1.0/a.jar" to bytes)) }
        assertFailsWith<IllegalArgumentException> { resolve(module(example, variant(extra = file.replace("\"url\":\"a.jar\"", "\"url\":\"../a.jar\"")))) }
        assertFailsWith<IllegalArgumentException> { NativeHttpsTransport().fetch("http://repo.maven.apache.org/") }
        assertFailsWith<IllegalArgumentException> { NativeHttpsTransport(timeoutSeconds = 0) }
    }

    @Test fun conflictRequiresExplicitCompatiblePin() {
        val a = Coordinate("g", "a", "1.0"); val b = Coordinate("g", "b", "1.0")
        val aBytes = module(a, variant(dependencies = """, "dependencies":[{"group":"g","module":"b","version":{"requires":"2.0"}}]"""))
        val entries = mapOf("${repo.url}/g/a/1.0/a-1.0.module" to aBytes, "${repo.url}/g/b/2.0/b-2.0.module" to module(b.copy(version="2.0"), variant()), "${repo.url}/g/b/1.0/b-1.0.module" to module(b, variant()))
        assertFailsWith<IllegalArgumentException> { MavenResolver(MemoryTransport(entries), NativePomParser()).resolveAll(listOf(a,b), jvm, listOf(repo)) }
        assertEquals(2, MavenResolver(MemoryTransport(entries), NativePomParser(), listOf(b.copy(version="2.0"))).resolveAll(listOf(a,b), jvm, listOf(repo)).size)
        assertFailsWith<IllegalArgumentException> { MavenResolver(MemoryTransport(entries), NativePomParser(), listOf(b)).resolve(a, jvm, listOf(repo)) }
        assertFailsWith<IllegalArgumentException> { MavenResolver(MemoryTransport(entries), NativePomParser(), maxComponents=1).resolve(a, jvm, listOf(repo)) }
    }

    @Test fun nativeXmlParserRejectsDtdParentsAndDuplicates() {
        val parser = NativePomParser()
        assertFailsWith<IllegalStateException> { parser.parse("<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><project>&x;</project>".encodeToByteArray()) }
        assertFailsWith<IllegalStateException> { parser.parse("<project>".encodeToByteArray()) }
        val base = "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>a</artifactId><version>1.0</version>%s</project>"
        for (unsupported in listOf("<parent/>", "<profiles/>", "<repositories/>", "<groupId>duplicate</groupId>")) {
            val bytes = base.replace("%s", unsupported).encodeToByteArray()
            assertFailsWith<IllegalArgumentException> { MavenResolver(MemoryTransport(mapOf("${repo.url}/example/a/1.0/a-1.0.pom" to bytes)), parser).resolve(example, jvm, listOf(repo)) }
        }
    }

    @Test fun constraintFailureAndRequestBound() {
        val c = Coordinate("g", "b", "1.0")
        val bytes = module(example, variant(dependencies = """, "dependencies":[{"group":"g","module":"b","version":{"requires":"1.0"}}], "dependencyConstraints":[{"group":"g","module":"b","version":{"requires":"2.0"}}]"""))
        val entries = mapOf("${repo.url}/example/a/1.0/a-1.0.module" to bytes, "${repo.url}/g/b/1.0/b-1.0.module" to module(c, variant()))
        assertFailsWith<IllegalArgumentException> { MavenResolver(MemoryTransport(entries), NativePomParser()).resolve(example, jvm, listOf(repo)) }
        assertFailsWith<IllegalArgumentException> { MavenResolver(MemoryTransport(entries), NativePomParser(), maxRequests = 1).resolve(example, jvm, listOf(repo)) }
    }

    @Test fun exactAttributesAndApiUsageAreRespected() {
        val bytes = module(example, variant() + "," + variant().replace("runtime", "api"))
        assertEquals("java-api", resolve(bytes, VariantRequest("jvm", "java-api", emptyMap())).variant.usage)
        assertFailsWith<IllegalArgumentException> { resolve(bytes, jvm.copy(attributes = mapOf("org.gradle.jvm.version" to "26"))) }
        assertFailsWith<IllegalArgumentException> { resolve(bytes, jvm.copy(usage = "kotlin-api")) }
    }

    @Test fun transportDoesNotHideOperationalFailure() {
        val transport = object : ArtifactTransport { override fun fetch(url: String): ByteArray = error("HTTP 503") }
        assertEquals("HTTP 503", assertFailsWith<IllegalStateException> { MavenResolver(transport, NativePomParser()).resolve(example, jvm, listOf(repo)) }.message)
    }

    @Test fun nativeHttpsSmoke() {
        if (getenv("KVIK_RESOLUTION_ONLINE")?.toKString() != "True") return
        val url = "${repo.url}/org/jetbrains/annotations/13.0/annotations-13.0.pom"
        assertTrue(NativeHttpsTransport().fetch(url).decodeToString().contains("<artifactId>annotations</artifactId>"))
        assertFailsWith<MissingResource> { NativeHttpsTransport().fetch("${repo.url}/kvik-does-not-exist/0/a.pom") }
        assertFailsWith<IllegalStateException> { NativeHttpsTransport(maxBytes=16).fetch(url) }
        val responses = mutableMapOf<String, ByteArray>()
        val live = object : ArtifactTransport {
            override fun fetch(url: String): ByteArray = responses.getOrPut(url) { NativeHttpsTransport().fetch(url) }
        }
        val resolver = MavenResolver(live, NativePomParser(), listOf(Coordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")))
        val expected = parseJson(read("$root/tests/resolution/fixtures/selections.json")!!.decodeToString()).arrayValue()
        for (entry in expected) {
            val selection = entry.objectValue()
            val request = if (selection.getValue("platform").stringValue() == "jvm") jvm else native
            val actual = resolver.resolve(Coordinate.parse(selection.getValue("requested").stringValue()), request, listOf(repo))
            assertEquals(selection.getValue("artifacts").arrayValue().map { it.objectValue().getValue("digest") }, actual.artifacts.map { it.digest.toJson() })
            assertEquals(selection.getValue("metadata").arrayValue().map { it.objectValue().getValue("digest") }, actual.metadata.map { it.digest.toJson() })
        }
        println("Live native HTTPS resolution matches all 10 recorded closures")
    }
}
