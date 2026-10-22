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
    @Test fun pinnedDiffUtilsParentPomRemainsAnExplicitUnsupportedBoundary() {
        val transport = FixtureTransport()
        val failure = assertFails { MavenResolver(transport, NativePomParser()).resolve(Coordinate.parse("io.github.java-diff-utils:java-diff-utils:4.16"), jvmVariantRequest("java-runtime", 11), listOf(repo)) }
        assertTrue(failure.message!!.contains("Unsupported POM semantics"))
        assertFalse(transport.urls.any { it.endsWith(".jar") })
    }
    @Test fun actualKotlinTestCapabilitiesSelectOnlyDefaultAndExplicitJunit5Variants() {
        val transport = FixtureTransport()
        val pins = listOf("org.junit.jupiter:junit-jupiter-api:5.13.4", "org.junit.jupiter:junit-jupiter-engine:5.13.4", "org.junit.platform:junit-platform-launcher:1.13.4").map(Coordinate::parse)
        val resolver = MavenResolver(transport, NativePomParser(), pins)
        val expected = parseJson(read("$root/compiler/jvm/kotaml-artifacts.lock.json")!!.decodeToString()).objectValue().getValue("artifacts").arrayValue()
            .associate { value -> value.objectValue().let { it.getValue("coordinate").stringValue() to Digest(it.getValue("sha256").stringValue(), it.getValue("size").longValue()) } }
        for (usage in listOf("java-api", "java-runtime")) {
            val request = jvmVariantRequest(usage, 11)
            val base = resolver.resolve(Coordinate.parse("org.jetbrains.kotlin:kotlin-test:2.4.20"), request, listOf(repo))
            assertEquals(listOf("kotlin-test", "kotlin-stdlib", "annotations"), base.artifacts.map { it.coordinate.name })
            val result = resolver.resolve(Coordinate.parse("org.jetbrains.kotlin:kotlin-test-junit5:2.4.20"), request, listOf(repo))
            assertEquals("jvmJUnit5${if (usage == "java-api") "Api" else "Runtime"}Elements", result.artifacts.first().variant)
            assertEquals(if (usage == "java-api") 8 else 10, result.artifacts.size)
            result.artifacts.forEach { assertEquals(expected.getValue(it.coordinate.toString()), it.digest) }
            assertEquals(result, resolver.resolve(result.requested, request, listOf(repo)))
            assertTrue(result.metadata.any { it.url.endsWith("kotlin-test-2.4.20.module") })
            assertFalse(transport.urls.any { it.contains("kotlin-test-junit/") || it.contains("kotlin-test-testng/") })
        }
    }

    @Test fun capabilitiesFilterFeaturesAndRejectConflictingProvidersAndUnsupportedSelectors() {
        fun caps(vararg names: String) = ",\"capabilities\":[" + names.joinToString(",") { """{"group":"example","name":"$it","version":"1.0"}""" } + "]"
        val feature = variant(extra = caps("feature"), attrs = ",\"unrequested.feature\":\"x\"")
        assertTrue(resolve(module(example, variant() + "," + feature)).artifacts.isEmpty())
        assertTrue(resolve(module(example, variant(extra = caps("a", "framework")))).artifacts.isEmpty())
        assertFails { resolve(module(example, feature)) }
        assertFails { resolve(module(example, variant() + "," + variant(extra = caps("a", "framework")))) }
        for (bad in listOf("null", "[{}]", "[{\"group\":\"example\",\"name\":\"a\",\"version\":\"1.0\",\"unknown\":true}]",
            "[{\"group\":\"example\",\"name\":\"a\",\"version\":\"LATEST\"}]"))
            assertFails { resolve(module(example, variant(extra = ",\"capabilities\":$bad"))) }
        assertFails { resolve(module(example, variant(extra = caps("a", "a")))) }
        val b = Coordinate("example", "b", "1.0")
        val bUrl = "${repo.url}/example/b/1.0/b-1.0.module"
        val dependency = ",\"dependencies\":[{\"group\":\"example\",\"module\":\"b\",\"version\":{\"requires\":\"1.0\"}}]"
        val conflict = assertFails { resolve(module(example, variant(dependency, caps("a", "framework"))), extra = mapOf(bUrl to module(b, variant(extra = caps("b", "framework"))))) }
        assertTrue(conflict.message!!.contains("Conflicting capability example:framework"))
        assertFails { resolve(module(example, variant(dependency, caps("a", "b"))), extra = mapOf(bUrl to module(b, variant()))) }
        val requested = dependency.replace("\"version\":{", "\"requestedCapabilities\":[{\"group\":\"example\",\"name\":\"feature\",\"version\":\"1.0\"}],\"version\":{")
        assertFails { resolve(module(example, variant(requested))) }
        val redirect = ",\"available-at\":{\"url\":\"../../b/1.0/b-1.0.module\",\"group\":\"example\",\"module\":\"b\",\"version\":\"1.0\"}"
        val target = module(example, variant(extra = caps("b", "a"))).decodeToString().replace("\"component\":{", "\"component\":{\"url\":\"../../a/1.0/a-1.0.module\",").encodeToByteArray()
        assertTrue(resolve(module(example, variant(extra = redirect)), extra = mapOf(bUrl to target)).artifacts.isEmpty())
    }
    @Test fun actualJunitApiRuntimeAndBomMetadataResolveWithExplicitJvmTarget() {
        val transport = FixtureTransport()
        val resolver = MavenResolver(transport, NativePomParser())
        val coordinate = Coordinate.parse("org.junit.jupiter:junit-jupiter-api:5.13.4")
        val expected = parseJson(read("$root/tests/resolution/fixtures/junit-artifacts.json")!!.decodeToString()).objectValue()
        for (usage in listOf("java-api", "java-runtime")) {
            val request = jvmVariantRequest(usage, 11)
            val selection = resolver.resolve(coordinate, request, listOf(repo))
            val names = listOf("junit-jupiter-api", "opentest4j", "junit-platform-commons") + if (usage == "java-api") listOf("apiguardian-api") else emptyList()
            assertEquals(names, selection.artifacts.map { it.coordinate.name })
            assertEquals(names.size + 1, selection.metadata.size)
            assertTrue(selection.metadata.any { it.url.endsWith("junit-bom-5.13.4.module") })
            assertTrue(selection.metadata.all { it.url.endsWith(".module") })
            assertEquals(request, selection.variant)
            selection.artifacts.forEach { artifact ->
                val recorded = expected.getValue(artifact.coordinate.toString()).objectValue()
                assertEquals(recorded.getValue("url").stringValue(), artifact.url)
                assertEquals(Digest.fromJson(recorded.getValue("digest")), artifact.digest)
                assertEquals(if (usage == "java-api") "apiElements" else "runtimeElements", artifact.variant)
            }
            assertEquals(selection.artifacts.map { it.digest }, verifyLocked(selection) { transport.blobs[it] }.map(Sha256::digest))
            assertEquals(selection, resolver.resolve(coordinate, request, listOf(repo)))
        }
        assertFails { resolver.resolve(coordinate, native, listOf(repo)) }
        println("Verified JUnit 5.13.4 API/runtime closures and BOM metadata with explicit consumer JVM 11")
    }

    @Test fun jvmVersionCompatibilityIsTypedBoundedAndAmbiguityFails() {
        fun metadata(version: String) = module(example, variant(attrs = ",\"org.gradle.jvm.version\":$version"))
        for (version in listOf(8, 11)) assertEquals(jvmVariantRequest("java-runtime", 11), resolve(metadata(version.toString()), jvmVariantRequest("java-runtime", 11)).variant)
        assertFails { resolve(metadata("17"), jvmVariantRequest("java-runtime", 11)) }
        assertEquals(17, supportedJvmTarget(resolve(metadata("17"), jvmVariantRequest("java-runtime", 17)).variant.attributes.getValue(JVM_TARGET_VERSION_ATTRIBUTE)))
        for (invalid in listOf("\"8\"", "true", "0", "-1", "2147483648")) assertFails { resolve(metadata(invalid), jvmVariantRequest("java-runtime", 11)) }
        assertFails { resolve(metadata("8")) } // Legacy unversioned request cannot prove version compatibility.
        assertFails { resolve(module(example, variant(attrs = ",\"org.gradle.jvm.version\":8") + "," + variant(attrs = ",\"org.gradle.jvm.version\":11")), jvmVariantRequest("java-runtime", 17)) }
        assertFails { resolve(metadata("8"), jvm.copy(attributes = mapOf("unreviewed" to "x"))) }
        assertFails { resolve(metadata("8"), native.copy(attributes = mapOf(JVM_TARGET_VERSION_ATTRIBUTE to "11"))) }
        assertFails { resolve(module(example, variant(attrs = ",\"org.gradle.jvm.version\":8,\"unreviewed\":true")), jvmVariantRequest("java-runtime", 11)) }
    }

    @Test fun explicitJavaVariantGuardsAndPomVersionPolicyDoNotInventNativeFallback() {
        val java = variant(attrs = ",\"org.gradle.libraryelements\":\"jar\",\"org.gradle.jvm.version\":8")
            .replace(",\"org.jetbrains.kotlin.platform.type\":\"jvm\"", "")
        assertTrue(resolve(module(example, java), jvmVariantRequest("java-runtime", 11)).artifacts.isEmpty())
        assertFails { resolve(module(example, java), native) }
        assertFails { resolve(module(example, java.replace(",\"org.gradle.libraryelements\":\"jar\"", "")), jvmVariantRequest("java-runtime", 11)) }
        assertFails { resolve(module(example, java.replace("\"jar\"", "\"classes\"")), jvmVariantRequest("java-runtime", 11)) }
        assertFails { resolve(module(example, java.replace("\"org.gradle.jvm.version\":8", "\"org.gradle.jvm.version\":8,\"org.jetbrains.kotlin.native.target\":\"macos_arm64\"")), jvmVariantRequest("java-runtime", 11)) }
        val pom = "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>a</artifactId><version>1.0</version></project>".encodeToByteArray()
        val base = "${repo.url}/example/a/1.0/a-1.0"
        val resolver = MavenResolver(MemoryTransport(mapOf("$base.pom" to pom, "$base.jar" to byteArrayOf(1))), NativePomParser())
        assertEquals("maven-jar-java-runtime", resolver.resolve(example, jvmVariantRequest("java-runtime", 11), listOf(repo)).artifacts.single().variant)
        assertFails { resolver.resolve(example, jvm.copy(attributes = mapOf("unknown" to "x")), listOf(repo)) }
        assertFails { resolver.resolve(example, native, listOf(repo)) }
    }

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
        assertTrue(resolve(module(example, variant(extra = ",\"capabilities\":[]"))).artifacts.isEmpty())
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
