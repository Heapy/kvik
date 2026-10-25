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
    @Test fun sqlDelightCompilerMetadataAcceptsItsDeclaredGroupAndModuleExclusions() {
        val coordinate = Coordinate.parse("app.cash.sqldelight:compiler-env:2.3.2")
        val document = parseJson(read("$root/tests/resolution/fixtures/app.cash.sqldelight__compiler-env__2.3.2.module")!!.decodeToString()).objectValue()
        // This test exercises the published metadata, without downloading the 48 MiB compiler.
        // Artifact verification is exercised separately, including excluded incoming paths.
        val metadata = JsonValue.Object(document + ("variants" to document.getValue("variants").arrayValue().map {
            JsonValue.Object(it.objectValue() - "files")
        }.json())).canonical().encodeToByteArray()
        val base = "${repo.url}/app/cash/sqldelight/compiler-env/2.3.2/compiler-env-2.3.2.module"
        val stdlib = Coordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:2.3.10")
        val dependencies = ",\"dependencies\":[{\"group\":\"com.jetbrains.rd\",\"module\":\"excluded\",\"version\":{\"requires\":\"1\"}},{\"group\":\"com.github.jetbrains\",\"module\":\"jetCheck\",\"version\":{\"requires\":\"1\"}}]"
        val entries = mapOf(base to metadata, "${repo.url}/org/jetbrains/kotlin/kotlin-stdlib/2.3.10/kotlin-stdlib-2.3.10.module" to module(stdlib, variant(dependencies)))
        val graph = MavenResolver(MemoryTransport(entries), NativePomParser()).resolveGraph(listOf(coordinate), jvmVariantRequest("java-runtime", 25), listOf(repo))
        assertEquals(setOf(coordinate, stdlib), graph.nodes.map { it.coordinate }.toSet())
        assertEquals(2, graph.edges.count { it.semantics == "metadata-exclusion" })
        assertEquals("runtimeElements", graph.nodes.single { it.coordinate == coordinate }.variant)
    }

    @Test fun jsAndWasmSelectOnlyTheirExactIrPlatformAndUsage() {
        val bytes = "IR klib".encodeToByteArray(); val digest = Sha256.digest(bytes)
        fun selected(target: KotlinJsTarget, usage: String) = obj("name" to "${target.projectName}-$usage".json(),
            "attributes" to JsonValue.Object(mapOf("org.gradle.category" to "library", "org.gradle.usage" to usage,
                "org.jetbrains.kotlin.platform.type" to target.platformType, "org.gradle.libraryelements" to "klib")
                .plus(target.variantAttributes).mapValues { it.value.json() }),
            "files" to listOf(obj("name" to "a.klib".json(), "url" to "a.klib".json(), "size" to bytes.size.json(), "sha256" to digest.hash.json())).json()).canonical()
        val artifacts = mapOf("${repo.url}/example/a/1.0/a.klib" to bytes)
        val variants = KotlinJsTarget.entries.flatMap { target -> listOf("kotlin-api", "kotlin-runtime").map { selected(target, it) } }
        for (target in KotlinJsTarget.entries) for (usage in listOf("kotlin-api", "kotlin-runtime")) {
            val request = VariantRequest(target.projectName, usage, target.variantAttributes)
            val valid = selected(target, usage)
            assertEquals("${target.projectName}-$usage", resolve(module(example, variants.joinToString(",")), request, artifacts).artifacts.single().variant)
            assertFails { resolve(module(example, variants.filter { it != valid }.joinToString(",")), request, artifacts) }
            assertFails { resolve(module(example, "$valid,$valid"), request, artifacts) }
            for (wrongType in listOf("jvm", "common", "native", if (target == KotlinJsTarget.JS) "wasm" else "js")) {
                assertFails { resolve(module(example, valid.replace("\"org.jetbrains.kotlin.platform.type\":\"${target.platformType}\"", "\"org.jetbrains.kotlin.platform.type\":\"$wrongType\"")), request, artifacts) }
            }
            val (key, expected) = target.variantAttributes.entries.single()
            for (replacement in listOf("legacy", "wasi")) assertFails {
                resolve(module(example, valid.replace("\"$key\":\"$expected\"", "\"$key\":\"$replacement\"")), request, artifacts)
            }
            assertFails { resolve(module(example, valid.replace(",\"$key\":\"$expected\"", "")), request, artifacts) }
            assertFails { resolve(module(example, valid.replace("a.klib", "a.jar")), request, mapOf("${repo.url}/example/a/1.0/a.jar" to bytes)) }
            for (extra in listOf("\"org.jetbrains.kotlin.native.target\":\"macos_arm64\"", "\"unknown.consumer.mode\":\"fast\"", "\"org.jetbrains.kotlin.klib.packaging\":\"unpacked\"")) {
                assertFails { resolve(module(example, valid.replace("\"attributes\":{", "\"attributes\":{$extra,")), request, artifacts) }
            }
            assertFails { resolve(module(example, valid), request.copy(attributes = target.variantAttributes + ("unknown" to "value")), artifacts) }
            val pom = """<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>a</artifactId><version>1.0</version></project>""".encodeToByteArray()
            assertFails { MavenResolver(MemoryTransport(mapOf("${repo.url}/example/a/1.0/a-1.0.pom" to pom)), NativePomParser()).resolve(example, request, listOf(repo)) }
        }
    }

    @Test fun publishedJsAndWasmStdlibTestsAndSerializationResolveToVerifiedKlibs() {
        val standard = Coordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
        val tests = Coordinate.parse("org.jetbrains.kotlin:kotlin-test:2.4.20")
        val serialization = Coordinate.parse("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
        for (target in KotlinJsTarget.entries) for (usage in listOf("kotlin-api", "kotlin-runtime")) {
            val transport = FixtureTransport()
            val request = VariantRequest(target.projectName, usage, target.variantAttributes)
            val selections = MavenResolver(transport, NativePomParser(), listOf(standard))
                .resolveAll(listOf(standard, tests, serialization), request, listOf(repo))
            val suffix = if (target == KotlinJsTarget.JS) "js" else "wasm-js"
            assertEquals("kotlin-stdlib-$suffix", selections[0].artifacts.single().coordinate.name)
            assertEquals(setOf("kotlin-stdlib-$suffix", "kotlin-test-$suffix"), selections[1].artifacts.map { it.coordinate.name }.toSet())
            val expected = setOf("kotlin-stdlib-$suffix", "kotlinx-serialization-core-$suffix") +
                if (target == KotlinJsTarget.JS) setOf("kotlin-dom-api-compat") else emptySet()
            assertEquals(expected, selections[2].artifacts.map { it.coordinate.name }.toSet())
            assertTrue(selections.flatMap { it.artifacts }.all { it.url.endsWith(".klib") &&
                it.variant.contains(if (usage == "kotlin-api") "ApiElements" else "RuntimeElements") })
            for (selection in selections) assertEquals(selection.artifacts.map { it.digest }, verifyLocked(selection) { transport.blobs[it] }.map(Sha256::digest))
        }
    }

    @Test fun iosDeviceAndSimulatorSelectOnlyTheirOwnNativeMetadata() {
        val bytes = "ios klib".encodeToByteArray()
        val digest = Sha256.digest(bytes)
        fun selected(target: NativeTarget) = """{"name":"${target.projectName}","attributes":{"org.gradle.category":"library","org.gradle.usage":"kotlin-api","org.jetbrains.kotlin.platform.type":"native","org.jetbrains.kotlin.native.target":"${target.compilerName}","org.gradle.libraryelements":"klib"},"files":[{"name":"a.klib","url":"a.klib","size":${bytes.size},"sha256":"${digest.hash}"}]}"""
        val variants = NativeTarget.entries.joinToString(",", transform = ::selected)
        val artifacts = mapOf("${repo.url}/example/a/1.0/a.klib" to bytes)
        for (target in listOf(NativeTarget.IOS_ARM64, NativeTarget.IOS_SIMULATOR_ARM64)) {
            val request = VariantRequest(target.projectName, "kotlin-api", emptyMap())
            val result = resolve(module(example, variants), request, artifacts)
            assertEquals(target.projectName, result.artifacts.single().variant)
            assertEquals(digest, result.artifacts.single().digest)
            assertFails { resolve(module(example, NativeTarget.entries.filter { it != target }.joinToString(",", transform = ::selected)), request, artifacts) }
            assertFails { resolve(module(example, selected(target).replace("a.klib", "a.jar")), request,
                mapOf("${repo.url}/example/a/1.0/a.jar" to bytes)) }
            val pom = """<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>a</artifactId><version>1.0</version></project>""".encodeToByteArray()
            assertFails { MavenResolver(MemoryTransport(mapOf("${repo.url}/example/a/1.0/a-1.0.pom" to pom)), NativePomParser()).resolve(example, request, listOf(repo)) }
        }
    }

    @Test fun linuxVariantSelectionNeverSubstitutesMacJvmOrPom() {
        val request = VariantRequest("linuxX64", "kotlin-api", emptyMap())
        val bytes = "linux klib".encodeToByteArray(); val digest = Sha256.digest(bytes)
        fun selected(name: String, target: String) = """{"name":"$name","attributes":{"org.gradle.category":"library","org.gradle.usage":"kotlin-api","org.jetbrains.kotlin.platform.type":"native","org.jetbrains.kotlin.native.target":"$target","org.gradle.libraryelements":"klib"},"files":[{"name":"a.klib","url":"a.klib","size":${bytes.size},"sha256":"${digest.hash}"}]}"""
        val linux = selected("linux", "linux_x64")
        val mac = selected("mac", "macos_arm64")
        val artifacts = mapOf("${repo.url}/example/a/1.0/a.klib" to bytes)
        val result = resolve(module(example, "$mac,$linux"), request, artifacts)
        assertEquals("linux", result.artifacts.single().variant)
        assertEquals(digest, result.artifacts.single().digest)
        assertEquals("mac", resolve(module(example, "$mac,$linux"), native, artifacts).artifacts.single().variant)
        assertFails { resolve(module(example, mac), request, artifacts) }
        assertFails { resolve(module(example, "$linux,$linux"), request, artifacts) }
        assertFails { resolve(module(example, linux.replace(",\"org.jetbrains.kotlin.native.target\":\"linux_x64\"", "")), request, artifacts) }
        assertFails { resolve(module(example, linux.replace("a.klib", "a.jar")), request, mapOf("${repo.url}/example/a/1.0/a.jar" to bytes)) }
        val pom = """<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>a</artifactId><version>1.0</version></project>""".encodeToByteArray()
        assertFails { MavenResolver(MemoryTransport(mapOf("${repo.url}/example/a/1.0/a-1.0.pom" to pom)), NativePomParser()).resolve(example, request, listOf(repo)) }
    }

    @Test fun commonMetadataSelectsPinnedRootArtifactsAndVerifiesTheirBytes() {
        val transport = FixtureTransport()
        val standard = Coordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
        val serialization = Coordinate.parse("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
        val request = VariantRequest("common", "kotlin-metadata", emptyMap())
        val selections = MavenResolver(transport, NativePomParser(), listOf(standard))
            .resolveAll(listOf(standard, serialization), request, listOf(repo))
        assertEquals(listOf(standard), selections.first().artifacts.map { it.coordinate })
        assertTrue(selections.first().artifacts.single().url.endsWith("kotlin-stdlib-2.4.20-all.jar"))
        assertEquals(setOf(standard, serialization), selections.last().artifacts.map { it.coordinate }.toSet())
        assertTrue(selections.flatMap { it.artifacts }.all { it.variant == "metadataApiElements" && it.url.endsWith(".jar") })
        assertEquals("74d9d7cf112bbf3d14612bb9fb867456a0ae36398d330df8178f0de42a61f476", selections.first().artifacts.single().digest.hash)
        for (selection in selections) assertEquals(selection.artifacts.map { it.digest }, verifyLocked(selection) { transport.blobs[it] }.map(Sha256::digest))
    }

    @Test fun commonMetadataNeverFallsBackToPlatformArtifactsOrPom() {
        val request = VariantRequest("common", "kotlin-metadata", emptyMap())
        val bytes = "metadata".encodeToByteArray(); val digest = Sha256.digest(bytes)
        val file = ""","files":[{"name":"a.jar","url":"a.jar","size":${bytes.size},"sha256":"${digest.hash}"}]"""
        val common = """{"name":"metadata","attributes":{"org.gradle.category":"library","org.gradle.usage":"kotlin-metadata","org.jetbrains.kotlin.platform.type":"common","org.gradle.libraryelements":"jar"}$file}"""
        val artifact = mapOf("${repo.url}/example/a/1.0/a.jar" to bytes)
        assertEquals("metadata", resolve(module(example, common), request, artifact).artifacts.single().variant)
        for (bad in listOf(common.replace("\"common\"", "\"jvm\""), common.replace("\"common\"", "\"native\""),
            common.replace("kotlin-metadata", "java-api"), common.replace("a.jar", "a.klib"), common.replace(file, ""),
            common.replace("\"jar\"", "\"klib\""), common.replace("\"jar\"", "\"jar\",\"org.jetbrains.kotlin.native.target\":\"macos_arm64\"")))
            assertFails { resolve(module(example, bad), request, artifact) }
        assertFails { resolve(module(example, "$common,$common"), request, artifact) }
        assertFails { resolve(module(example, common), request.copy(usage = "kotlin-api"), artifact) }
        val path = "${repo.url}/example/a/1.0/a-1.0"
        val pom = """<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>a</artifactId><version>1.0</version></project>""".encodeToByteArray()
        assertFails { MavenResolver(MemoryTransport(mapOf("$path.pom" to pom, "$path.jar" to bytes)), NativePomParser()).resolve(example, request, listOf(repo)) }
    }

    @Test fun pinnedCompilerPomWildcardEdgesPreserveApiRuntimeAndLockedMetadata() {
        val transport = FixtureTransport()
        val coordinate = Coordinate.parse("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20")
        val expected = parseJson(read("$root/tests/resolution/fixtures/compiler-embeddable-artifacts.json")!!.decodeToString()).objectValue()
        for (usage in listOf("java-api", "java-runtime")) {
            val selection = MavenResolver(transport, NativePomParser(), listOf(Coordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:2.4.20"), Coordinate.parse("org.jetbrains:annotations:13.0")))
                .resolve(coordinate, jvmVariantRequest(usage, 25), listOf(repo))
            val artifacts = expected.getValue(usage).arrayValue().map { it.objectValue() }
            assertEquals(artifacts.map { it.getValue("coordinate").stringValue() }, selection.artifacts.map { it.coordinate.toString() })
            assertEquals(artifacts.map { Digest.fromJson(it.getValue("digest")) }, selection.artifacts.map { it.digest })
            assertEquals(if (usage == "java-api") 2 else 8, selection.artifacts.size)
            val compilerTool = parseJson(read("$root/experiments/k01/artifacts.lock.json")!!.decodeToString()).objectValue().getValue("artifacts").arrayValue().map(JsonValue::objectValue)
            for (artifact in selection.artifacts.take(2)) {
                val pinned = compilerTool.single { it.getValue("coordinate").stringValue() == artifact.coordinate.toString() }
                assertEquals(Digest(pinned.getValue("sha256").stringValue(), pinned.getValue("size").longValue()), artifact.digest)
            }
            assertTrue(selection.metadata.any { it.url.endsWith("kotlin-compiler-embeddable-2.4.20.pom") })
            assertEquals(selection.artifacts.map { it.digest }, verifyLocked(selection) { transport.blobs[it] }.map(Sha256::digest))
            val pomDigest = selection.metadata.first().digest
            assertFails { verifyLocked(selection) { if (it == pomDigest) byteArrayOf(1) else transport.blobs[it] } }
        }
    }
    @Test fun wildcardAllExclusionsAreEdgeLocalInEitherEncounterOrder() {
        fun url(name: String, suffix: String) = "${repo.url}/example/$name/1/$name-1.$suffix"
        fun dependency(name: String, exclusions: String = "") = "<dependency><groupId>example</groupId><artifactId>$name</artifactId><version>1</version>$exclusions</dependency>"
        val all = "<exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>"
        fun component(name: String, dependencies: String = "") = mapOf(url(name, "pom") to
            "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>$name</artifactId><version>1</version><dependencies>$dependencies</dependencies></project>".encodeToByteArray(), url(name, "jar") to name.encodeToByteArray())
        val base = component("excluded", dependency("middle", all)) + component("ordinary", dependency("middle")) + component("middle", dependency("leaf")) + component("leaf")
        fun resolve(names: List<String>, entries: Map<String, ByteArray> = base) = MavenResolver(MemoryTransport(entries), NativePomParser())
            .resolveAll(names.map { Coordinate("example", it, "1") }, jvm, listOf(repo))
        assertEquals(listOf("excluded", "middle"), resolve(listOf("excluded")).single().artifacts.map { it.coordinate.name })
        for (order in listOf(listOf("excluded", "ordinary"), listOf("ordinary", "excluded"))) {
            val selections = resolve(order).associateBy { it.requested.name }
            assertEquals(listOf("excluded", "middle"), selections.getValue("excluded").artifacts.map { it.coordinate.name })
            assertEquals(listOf("ordinary", "middle", "leaf"), selections.getValue("ordinary").artifacts.map { it.coordinate.name })
            for (children in listOf(order, order.reversed())) {
                val single = resolve(listOf("root"), base + component("root", children.joinToString("") { dependency(it) })).single()
                assertEquals(setOf("root", "excluded", "ordinary", "middle", "leaf"), single.artifacts.map { it.coordinate.name }.toSet())
            }
        }
        for (selective in listOf(all.replace("<artifactId>*", "<artifactId>leaf"), all.replace("<groupId>*", "<groupId>example"))) {
            assertEquals(listOf("excluded", "middle"), resolve(listOf("excluded"), base + component("excluded", dependency("middle", selective)))
                .single().artifacts.map { it.coordinate.name })
        }
        for (unsupported in listOf(all.replace("<artifactId>*", "<artifactId>leaf*"), all.replace("<groupId>*", "<groupId>example?"), all.replace("</exclusion>", "<unknown>*</unknown></exclusion>"))) {
            assertFails { resolve(listOf("excluded"), base + component("excluded", dependency("middle", unsupported))) }
        }
        val managed = component("excluded", dependency("middle")).mapValues { (path, bytes) -> if (path.endsWith(".pom")) bytes.decodeToString()
            .replace("</project>", "<dependencyManagement><dependencies>${dependency("middle", all)}</dependencies></dependencyManagement></project>").encodeToByteArray() else bytes }
        assertFails { resolve(listOf("excluded"), base + managed) }
        // Excluding transitives does not bypass producer variant or artifact verification.
        val wrong = module(Coordinate("example", "middle", "1"), variant(attrs = ",\"unsupported.attribute\":\"x\""))
        assertFails { resolve(listOf("excluded"), base + (url("middle", "module") to wrong)) }
        val file = ",\"files\":[{\"name\":\"middle.jar\",\"url\":\"middle-1.jar\",\"size\":0,\"sha256\":\"${"0".repeat(64)}\"}]"
        assertFails { resolve(listOf("excluded"), base + (url("middle", "module") to module(Coordinate("example", "middle", "1"), variant(extra = file)))) }
    }
    @Test fun actualParentPomsPreserveExactArtifactAndMetadataClosures() {
        val transport = FixtureTransport()
        val expected = parseJson(read("$root/compiler/jvm/kotaml-artifacts.lock.json")!!.decodeToString()).objectValue().getValue("artifacts").arrayValue()
            .associate { value -> value.objectValue().let { it.getValue("coordinate").stringValue() to Digest(it.getValue("sha256").stringValue(), it.getValue("size").longValue()) } }
        for (name in listOf("io.github.java-diff-utils:java-diff-utils:4.16", "net.bytebuddy:byte-buddy:1.17.6", "net.bytebuddy:byte-buddy-agent:1.17.6")) {
            val coordinate = Coordinate.parse(name)
            val selection = MavenResolver(transport, NativePomParser()).resolve(coordinate, jvmVariantRequest("java-runtime", 11), listOf(repo))
            assertEquals(listOf(coordinate), selection.artifacts.map { it.coordinate })
            assertEquals(expected.getValue(name), selection.artifacts.single().digest)
            assertEquals(2, selection.metadata.size)
            assertEquals(selection.artifacts.map { it.digest }, verifyLocked(selection) { transport.blobs[it] }.map(Sha256::digest))
            val parent = selection.metadata.last().digest
            assertFails { verifyLocked(selection) { if (it == parent) byteArrayOf(0) else transport.blobs[it] } }
            assertFails { verifyLocked(selection) { if (it == parent) null else transport.blobs[it] } }
        }
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
            """{"group":"g","module":"b","version":{"requires":"1.0"},"excludes":[{"group":"g*","module":"bad"}]}""",
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
        for (unsupported in listOf("<parent/>", "<profiles><profile><dependencies/></profile></profiles>", "<repositories><repository><id>releases</id><url>https://example.test/releases</url></repository></repositories>", "<groupId>duplicate</groupId>")) {
            val bytes = base.replace("%s", unsupported).encodeToByteArray()
            assertFails { MavenResolver(MemoryTransport(mapOf("${repo.url}/example/a/1.0/a-1.0.pom" to bytes)), parser).resolve(example, jvm, listOf(repo)) }
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
