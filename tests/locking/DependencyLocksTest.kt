@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.locking

import kvik.core.*
import kvik.cache.*
import kvik.resolution.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.*

private val root get() = getenv("KVIK_LOCKING_ROOT")!!.toKString()
private val work get() = getenv("KVIK_LOCKING_WORK")!!.toKString()
private val ktc get() = getenv("KVIK_KTC_CACHE")!!.toKString()
private val repo = "https://repo.maven.apache.org/maven2"
private var storeNumber = 0
private fun store() = NativeContentStore("$work/store-${++storeNumber}")
private fun read(path: String) = NativeFiles.read(path, 64 * 1024 * 1024)
private fun publish(path: String, bytes: ByteArray) = NativeFiles.publish(path, bytes, 16 * 1024 * 1024)
private fun config(roots: List<String>, testRoots: List<String> = emptyList(), native: Boolean = false): ProjectConfiguration {
    fun source(name: String, dependencies: List<String>) = obj("name" to name.json(), "directories" to emptyList<JsonValue>().json(),
        "resources" to emptyList<JsonValue>().json(), "dependsOn" to emptyList<JsonValue>().json(),
        "dependencies" to dependencies.map { obj("coordinate" to it.json()) }.json())
    fun target(name: String) = obj("name" to name.json(), "main" to "main".json(), "test" to "test".json(), "compilerOptions" to emptyList<JsonValue>().json())
    return parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "sample".json(),
        "repositories" to listOf(obj("id" to "central".json(), "url" to repo.json())).json(),
        "modules" to listOf(obj("id" to "sample".json(), "sourceSets" to listOf(source("main", roots), source("test", testRoots)).json(),
            "targets" to (listOf(target("jvm")) + if (native) listOf(target("macosArm64")) else emptyList()).json(),
            "compilerPlugins" to emptyList<JsonValue>().json())).json()))
}
private fun scope(platform: String = "jvm", name: String = "main", usage: String = if (platform == "jvm") "java-runtime" else "kotlin-api") = DependencyScope("sample", platform, name, usage)
private fun effective(project: ProjectConfiguration, pins: List<Coordinate> = emptyList(), scopes: List<DependencyScope> = listOf(scope())) = effectiveConfiguration(project, ResolverPolicy(pins), scopes)

private class MemoryTransport(val entries: Map<String, ByteArray>) : ArtifactTransport {
    val calls = mutableListOf<String>()
    override fun fetch(url: String): ByteArray { calls += url; return entries[url] ?: throw MissingResource(url) }
}
private fun component(coordinate: String, dependencies: List<String> = emptyList()): Map<String, ByteArray> {
    val c = Coordinate.parse(coordinate)
    val base = "$repo/${c.group.replace('.', '/')}/${c.name}/${c.version}/${c.name}-${c.version}"
    val bytes = coordinate.encodeToByteArray()
    val digest = Sha256.digest(bytes)
    val variants = listOf("java-api", "java-runtime").map { usage -> obj("name" to usage.json(),
        "attributes" to obj("org.gradle.category" to "library".json(), "org.gradle.usage" to usage.json(), "org.jetbrains.kotlin.platform.type" to "jvm".json()),
        "files" to listOf(obj("name" to "${c.name}-${c.version}.jar".json(), "url" to "${c.name}-${c.version}.jar".json(), "size" to digest.sizeBytes.json(), "sha256" to digest.hash.json())).json(),
        "dependencies" to dependencies.map { value -> Coordinate.parse(value).let { d ->
            obj("group" to d.group.json(), "module" to d.name.json(), "version" to obj("requires" to d.version.json())) } }.json()) }
    val metadata = obj("formatVersion" to "1.1".json(), "component" to obj("group" to c.group.json(), "module" to c.name.json(), "version" to c.version.json()), "variants" to variants.json())
    return mapOf("$base.module" to metadata.canonical().encodeToByteArray(), "$base.jar" to bytes)
}
private val smallEntries get() = component("example:root:1.0", listOf("example:leaf:1.0")) + component("example:leaf:1.0")

class DependencyLocksTest {
    @Test fun nativeKspAndProcessorsHaveSeparateHostDomainsAndOfflineReplay() {
        val legacy = config(emptyList(), native = true)
        val settings = """"nativeTestSettings":{"engine":"kotest","kspVersion":"2.3.12","processor":"io.kotest:kotest-framework-symbol-processor:6.2.5"},"""
        val project = parseProjectConfiguration(parseJson(legacy.document.canonical().replace("\"name\":\"macosArm64\"", settings + "\"name\":\"macosArm64\"")))
        val configuration = effectiveConfiguration(project)
        val runtime = ResolutionDomain("sample", "macosArm64", "test", "java-runtime", "ksp-runtime")
        val processor = runtime.copy(purpose = "symbol-processor")
        assertEquals(setOf(runtime, processor), configuration.requests.map { it.domain }.toSet())
        assertEquals(4, configuration.requests.size)
        assertTrue(configuration.requests.all { it.variant == jvmVariantRequest("java-runtime", 25) })
        val entries = configuration.requests.flatMap { request -> component(request.coordinate.toString(),
            listOf("com.google.devtools.ksp:symbol-processing-api:" + if (request.domain == runtime) "2.3.12" else "2.3.9")).entries }
            .associate { it.toPair() } + component("com.google.devtools.ksp:symbol-processing-api:2.3.12") + component("com.google.devtools.ksp:symbol-processing-api:2.3.9")
        val service = DependencyLocks(store(), NativePomParser())
        val transport = MemoryTransport(entries)
        val lock = service.resolve(configuration, transport)
        for (selection in lock.selections) assertEquals(setOf(if (selection.domain == runtime) "2.3.12" else "2.3.9"),
            selection.artifacts.filter { it.coordinate.name == "symbol-processing-api" }.map { it.coordinate.version }.toSet())
        val calls = transport.calls.toList()
        assertEquals(lock, service.loadLocked(lock.bytes(), configuration))
        assertEquals(calls, transport.calls)
        assertFails { service.loadLocked(lock.bytes(), effectiveConfiguration(legacy)) }
        assertFails { runtime.copy(target = "jvm") }
        assertFails { runtime.copy(compilation = "main") }
        assertFails { runtime.copy(usage = "kotlin-api") }
        assertFails { ScopedSelection(runtime, lock.selections.first().selection.copy(variant = VariantRequest("macosArm64", "kotlin-api", emptyMap()))) }
        assertEquals(runtime, ResolutionDomain.fromJson(runtime.toJson()))
    }
    @Test fun compilationDomainsPreserveDifferentVersionsAndReplayTheirOwnPins() {
        val project = config(listOf("example:main:1.0"), listOf("example:tests:1.0"))
        val testDomains = listOf("java-api", "java-runtime").map { ResolutionDomain("sample", "jvm", "test", it) }
        val mutablePins = testDomains.map { ScopedPin(it, Coordinate.parse("example:annotations:23.0")) }.toMutableList()
        val configuration = effectiveConfiguration(project, ResolverPolicy(scopedPins = mutablePins))
        val before = configuration.document.canonical()
        mutablePins.clear()
        assertEquals(2, configuration.policy.scopedPins.size)
        assertEquals(before, configuration.document.canonical())
        val entries = component("example:main:1.0", listOf("example:annotations:13.0")) +
            component("example:tests:1.0", listOf("example:annotations:23.0")) + component("example:annotations:13.0") + component("example:annotations:23.0")
        val service = DependencyLocks(store(), NativePomParser())
        val transport = MemoryTransport(entries)
        val lock = service.resolve(configuration, transport)
        assertEquals(6, lock.selections.size)
        for (selection in lock.selections) {
            val expected = if (selection.domain.compilation == "main") "13.0" else "23.0"
            assertEquals(setOf(expected), selection.artifacts.filter { it.coordinate.name == "annotations" }.map { it.coordinate.version }.toSet())
            assertFalse(selection.variant.attributes.keys.any { it.contains("scope") || it.contains("domain") })
        }
        val fetched = transport.calls.toList()
        assertEquals(lock, service.loadLocked(lock.bytes(), configuration))
        assertEquals(fetched, transport.calls)
        val changed = lock.copy(selections = lock.selections.map { entry ->
            if (entry.domain.compilation == "test" && entry.requested.name == "main") entry.copy(selection = lock.selections.first { it.domain.compilation == "main" && it.variant == entry.variant }.selection)
            else entry
        })
        assertFails { service.loadLocked(changed.bytes(), configuration) }
        assertFails { service.loadLocked(lock.bytes(), effectiveConfiguration(project)) }
        assertFails { service.resolve(effectiveConfiguration(project), MemoryTransport(entries)) }
        assertFails { parseDependencyLock(lock.toJson().canonical().replace("\"schemaVersion\":2", "\"schemaVersion\":1").encodeToByteArray()) }
        assertFails { parseDependencyLock(lock.toJson().canonical().replace("\"purpose\":\"dependencies\"", "\"purpose\":\"unknown\"").encodeToByteArray()) }
        publish("$work/scoped-lock.json", lock.bytes())
    }

    @Test fun scopedPinsRejectUnknownDomainsDuplicatesAndRecordExplicitOverrides() {
        val project = config(listOf("example:main:1.0"))
        val domain = ResolutionDomain("sample", "jvm", "test", "java-runtime")
        val pin = ScopedPin(domain, Coordinate.parse("example:annotations:23.0"))
        val policy = ResolverPolicy(listOf(Coordinate.parse("example:annotations:13.0")), scopedPins = listOf(pin))
        assertEquals(listOf(pin.coordinate), policy.pinsFor(domain))
        assertEquals(listOf(Coordinate.parse("example:annotations:13.0")), policy.pinsFor(domain.copy(compilation = "main")))
        assertFails { ResolverPolicy(scopedPins = listOf(pin, pin)) }
        assertFails { effectiveConfiguration(project, policy, listOf(scope())) }
        assertFails { effectiveConfiguration(project, ResolverPolicy(scopedPins = listOf(pin.copy(domain = domain.copy(module = "typo"))))) }
        assertFails { ResolutionDomain("sample", "jvm", "main", "kotlin-api") }
        assertEquals(domain, ResolutionDomain.fromJson(domain.toJson()))
        assertFails { ResolutionDomain.fromJson(JsonValue.Object(domain.toJson().objectValue() + ("unknown" to true.json()))) }
        val effective = effectiveConfiguration(project, policy)
        assertTrue(effective.document.canonical().contains("scopedPins"))
    }
    @Test fun actualJunitTargetPolicyLockReplaysApiRuntimeAndBomFromCas() {
        val configuration = effectiveConfiguration(config(listOf("org.junit.jupiter:junit-jupiter-api:5.13.4")))
        val manifest = parseJson(read("$root/tests/resolution/fixtures/manifest.json").decodeToString()).objectValue()
        val expected = parseJson(read("$root/tests/resolution/fixtures/junit-artifacts.json").decodeToString()).objectValue()
        var fetches = 0
        val transport = object : ArtifactTransport {
            override fun fetch(url: String): ByteArray {
                fetches++
                val metadata = manifest[url]?.objectValue()
                return if (metadata != null) read("$root/tests/resolution/fixtures/${metadata.getValue("file").stringValue()}").also {
                    assertEquals(Digest(metadata.getValue("hash").stringValue(), metadata.getValue("sizeBytes").longValue()), Sha256.digest(it))
                } else if (url.endsWith(".module") || url.endsWith(".pom")) throw MissingResource(url)
                else read("$ktc/${url.substringAfter("/maven2/")}")
            }
        }
        val service = DependencyLocks(store(), NativePomParser())
        val locked = service.resolve(configuration, transport)
        assertEquals(4, locked.selections.size)
        for (selection in locked.selections) {
            assertEquals(mapOf(JVM_TARGET_VERSION_ATTRIBUTE to "11"), selection.variant.attributes)
            assertTrue(selection.metadata.any { it.url.endsWith("junit-bom-5.13.4.module") })
            selection.artifacts.forEach { assertEquals(Digest.fromJson(expected.getValue(it.coordinate.toString()).objectValue().getValue("digest")), it.digest) }
            assertEquals(if (selection.variant.usage == "java-api") 4 else 3, selection.artifacts.size)
        }
        val before = fetches
        assertEquals(locked, service.loadLocked(locked.bytes(), configuration))
        assertEquals(before, fetches)
        publish("$work/junit-lock.json", locked.bytes())
    }
    @Test fun consumerTargetPolicyIsExplicitAndStaleVersionSelectionsAreRejected() {
        val project = config(listOf("example:root:1.0"))
        val original = effective(project)
        assertEquals(mapOf(JVM_TARGET_VERSION_ATTRIBUTE to "11"), original.requests.single().variant.attributes)
        val newer = effective(parseProjectConfiguration(parseJson(project.document.canonical().replace("\"compilerOptions\":[]", "\"compilerOptions\":[\"-jvm-target=17\"]"))))
        assertEquals(mapOf(JVM_TARGET_VERSION_ATTRIBUTE to "17"), newer.requests.single().variant.attributes)
        assertNotEquals(original.digest, newer.digest)
        val policy = original.document.objectValue().getValue("resolver").objectValue()
        assertEquals("kvik-maven-v3", policy.getValue("implementation").stringValue())
        assertEquals(25, policy.getValue("jvmCompatibility").objectValue().getValue("compilerPluginHost").longValue().toInt())
        val service = DependencyLocks(store(), NativePomParser())
        val lock = service.resolve(original, MemoryTransport(smallEntries))
        assertFails { service.loadLocked(lock.bytes(), newer) }
        val changed = lock.selections.single().let { it.copy(selection = it.selection.copy(variant = it.variant.copy(attributes = emptyMap()))) }
        assertFails { service.loadLocked(lock.copy(selections = listOf(changed)).bytes(), original) }
        assertEquals(lock, service.loadLocked(lock.bytes(), original))
        for (bad in listOf(listOf("-jvm-target"), listOf("-jvm-target=8"), listOf("-jvm-target=26"), listOf("-jvm-target=+11"), listOf("-jvm-target=11", "-jvm-target", "17")))
            assertFails { jvmTargetFromOptions(bad) }
        assertEquals(21, jvmTargetFromOptions(listOf("-jvm-target", "21")))
        assertEquals(25, jvmTargetFromOptions(listOf("-jvm-target=25")))
    }
    @Test fun effectiveIdentityCoversPolicyScopesAndProject() {
        val project = config(listOf("example:root:1.0"), listOf("example:test:1.0"))
        val main = effective(project)
        assertEquals(listOf(Coordinate.parse("example:root:1.0")), main.requests.map { it.coordinate })
        val test = effective(project, scopes = listOf(scope(name = "test")))
        assertEquals(setOf("root", "test"), test.requests.map { it.coordinate.name }.toSet())
        assertNotEquals(main.digest, test.digest)
        assertNotEquals(main.digest, effective(project, listOf(Coordinate.parse("example:leaf:2.0"))).digest)
        assertNotEquals(main.digest, effectiveConfiguration(project, ResolverPolicy(maxRequests = 1025), listOf(scope())).digest)
        assertNotEquals(main.digest, effective(config(listOf("example:root:2.0"))).digest)
        val defaults = effectiveConfiguration(project)
        assertEquals(4, defaultDependencyScopes(project).size)
        assertEquals(6, defaults.requests.size)
        val scopes = listOf(scope(), scope(usage = "java-api"))
        assertEquals(effective(project, scopes = scopes).digest, effective(project, scopes = scopes.reversed()).digest)
        assertFails { effective(project, scopes = listOf(scope(), scope())) }
        assertFails { effective(project, scopes = listOf(scope(platform = "macosArm64"))) }
        assertFails { effective(project, scopes = listOf(scope(usage = "kotlin-api"))) }
        assertFails { ResolverPolicy(listOf(Coordinate.parse("example:leaf:1.0"), Coordinate.parse("example:leaf:2.0"))) }
    }

    @Test fun strictSchemaRoundtripAndNoSilentNetworkOnLockedUse() {
        val content = store(); val service = DependencyLocks(content, NativePomParser())
        val configuration = effective(config(listOf("example:root:1.0")))
        val transport = MemoryTransport(smallEntries)
        val lock = service.resolve(configuration, transport)
        val calls = transport.calls.toList()
        assertEquals(lock, parseDependencyLock(lock.bytes()))
        assertEquals(lock, service.loadLocked(lock.bytes(), configuration))
        assertEquals(calls, transport.calls)
        assertEquals(lock.digest, Sha256.digest(lock.bytes()))
        val json = lock.toJson().canonical()
        for (bad in listOf(json.replace("\"schemaVersion\":2", "\"schemaVersion\":1"), json.dropLast(1) + ",\"extra\":true}",
            json.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"), json + " trailing")) {
            assertFails { parseDependencyLock(bad.encodeToByteArray()) }
        }
        assertFails { parseDependencyLock(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFails { parseDependencyLock(lock.copy(selections = lock.selections + lock.selections).bytes()) }
        val selection = lock.selections.single()
        assertFails { parseDependencyLock(lock.copy(selections = listOf(selection.copy(selection = selection.selection.copy(metadata = selection.metadata + selection.metadata.first())))).bytes()) }
        assertFails { parseDependencyLock(lock.copy(selections = listOf(selection.copy(selection = selection.selection.copy(artifacts = selection.artifacts + selection.artifacts.first())))).bytes()) }
        val noDependencies = effective(config(emptyList()))
        val unusedTransport = MemoryTransport(emptyMap())
        val empty = service.resolve(noDependencies, unusedTransport)
        assertTrue(empty.selections.isEmpty())
        assertTrue(unusedTransport.calls.isEmpty())
        assertEquals(empty, service.loadLocked(empty.bytes(), noDependencies))
    }

    @Test fun absentCorruptAndStaleInputsRefuseWithoutRepair() {
        val content = store(); val service = DependencyLocks(content, NativePomParser())
        val configuration = effective(config(listOf("example:root:1.0")))
        val lock = service.resolve(configuration, MemoryTransport(smallEntries))
        val selection = lock.selections.single()
        // Exercise real K04 filesystem verification for both metadata and artifact corruption.
        for (digest in listOf(selection.metadata.first().digest, selection.artifacts.first().digest)) {
            val path = content.blobPath(digest); val original = read(path)
            assertEquals(0, unlink(path))
            assertFails { service.loadLocked(lock.bytes(), configuration) }
            publish(path, byteArrayOf(1, 2, 3))
            assertFails { service.loadLocked(lock.bytes(), configuration) }
            assertContentEquals(byteArrayOf(1, 2, 3), read(path)) // Locked use never repairs.
            assertEquals(digest, content.put(original))
        }
        val maliciousStore = object : ContentStore {
            override fun put(bytes: ByteArray) = error("No writes allowed")
            override fun get(digest: Digest) = byteArrayOf(9)
        }
        assertFails { DependencyLocks(maliciousStore, NativePomParser()).loadLocked(lock.bytes(), configuration) }
        for (other in listOf(effective(config(listOf("example:leaf:1.0"))), effective(config(listOf("example:root:1.0")), scopes = listOf(scope(name = "test"))),
            effective(config(listOf("example:root:1.0")), listOf(Coordinate.parse("example:leaf:2.0"))))) {
            assertFails { service.loadLocked(lock.bytes(), other) }
        }
        assertEquals(lock, service.loadLocked(lock.bytes(), configuration))
    }

    @Test fun metadataProofRejectsForgedClosureVariantsRepositoriesAndOrdering() {
        val content = store(); val service = DependencyLocks(content, NativePomParser())
        val configuration = effective(config(listOf("example:root:1.0")))
        val lock = service.resolve(configuration, MemoryTransport(smallEntries)); val s = lock.selections.single()
        val extra = "{}".encodeToByteArray(); val extraMetadata = MetadataIdentity("$repo/unrelated.module", content.put(extra))
        val invalid = listOf(s.selection.copy(artifacts = s.artifacts.take(1)), s.selection.copy(metadata = s.metadata.take(1)),
            s.selection.copy(metadata = s.metadata + extraMetadata), s.selection.copy(artifacts = s.artifacts.reversed()),
            s.selection.copy(artifacts = s.artifacts.map { it.copy(variant = "fabricated") }),
            s.selection.copy(artifacts = s.artifacts.map { it.copy(repository = "other") }),
            s.selection.copy(requested = Coordinate.parse("example:leaf:1.0")), s.selection.copy(variant = s.variant.copy(usage = "java-api")))
        invalid.forEach { assertFails { service.loadLocked(lock.copy(selections = listOf(s.copy(selection = it))).bytes(), configuration) } }
        assertFails { service.loadLocked(lock.copy(selections = emptyList()).bytes(), configuration) }
        val both = effective(config(listOf("example:root:1.0")), scopes = listOf(scope(), scope(usage = "java-api")))
        val bothLock = service.resolve(both, MemoryTransport(smallEntries))
        val changed = bothLock.selections.last().let { it.copy(selection = it.selection.copy(metadata = it.metadata.map { m -> m.copy(digest = Sha256.digest(byteArrayOf())) })) }
        assertFails { parseDependencyLock(bothLock.copy(selections = listOf(bothLock.selections.first(), changed)).bytes()) }
    }

    @Test fun compatiblePinsAreExplicitAndGraphConflictsFail() {
        val project = config(listOf("example:root:1.0", "example:leaf:2.0"))
        val service = DependencyLocks(store(), NativePomParser())
        val entries = smallEntries + component("example:leaf:2.0")
        assertFails { service.resolve(effective(project), MemoryTransport(entries)) }
        val configuration = effective(project, listOf(Coordinate.parse("example:leaf:2.0")))
        val lock = service.resolve(configuration, MemoryTransport(entries))
        assertTrue(lock.selections.flatMap { it.artifacts }.filter { it.coordinate.name == "leaf" }.all { it.coordinate.version == "2.0" })
        assertEquals(lock, service.loadLocked(lock.bytes(), configuration))
    }

    @Test fun moduleAndCompilerPluginDependenciesJoinRequestedScopes() {
        val a = config(listOf("example:root:1.0")).document.objectValue()
        val module = a.getValue("modules").arrayValue().single().objectValue()
        val dependency = JsonValue.Object(module + ("id" to "library".json()))
        val source = module.getValue("sourceSets").arrayValue().first().objectValue()
        val modifiedSource = JsonValue.Object(source + ("dependencies" to listOf(obj("module" to "library".json())).json()))
        val plugin = obj("coordinate" to "example:plugin:1.0".json(), "targets" to listOf("jvm".json()).json(),
            "scopes" to listOf("test".json()).json(), "options" to emptyList<JsonValue>().json(), "inputs" to emptyList<JsonValue>().json())
        val consumer = JsonValue.Object(module + mapOf("sourceSets" to listOf(modifiedSource, module.getValue("sourceSets").arrayValue().last()).json(), "compilerPlugins" to listOf(plugin).json()))
        val project = parseProjectConfiguration(JsonValue.Object(a + ("modules" to listOf(consumer, dependency).json())))
        assertEquals(setOf("root"), effective(project).requests.map { it.coordinate.name }.toSet())
        assertEquals(setOf("root", "plugin"), effective(project, scopes = listOf(scope(name = "test"))).requests.map { it.coordinate.name }.toSet())
        val selections = effective(project, scopes = listOf(scope(name = "test"))).requests
        assertEquals(mapOf(JVM_TARGET_VERSION_ATTRIBUTE to "25"), selections.single { it.coordinate.name == "plugin" }.variant.attributes)
        assertEquals(setOf(mapOf(JVM_TARGET_VERSION_ATTRIBUTE to "11")), selections.filter { it.coordinate.name == "root" }.map { it.variant.attributes }.toSet())
        assertEquals(setOf("sample", "library"), selections.filter { it.coordinate.name == "root" }.map { it.domain.module }.toSet())
        val mainPlugin = parseProjectConfiguration(parseJson(project.document.canonical().replace("\"scopes\":[\"test\"]", "\"scopes\":[\"main\"]")))
        assertEquals(setOf("root", "plugin"), effective(mainPlugin, scopes = listOf(scope(name = "test"))).requests.map { it.coordinate.name }.toSet())
    }

    @Test fun actualProductionClosuresUseCapturedMetadataAndKtcBinaryBytes() {
        val roots = listOf("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0",
            "it.krzeminski:snakeyaml-engine-kmp:5.0.0", "com.squareup.okio:okio:3.18.2", "org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
        val configuration = effective(config(roots, native = true), listOf(Coordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")),
            listOf(scope(), scope(usage = "java-api"), scope("macosArm64")))
        val manifest = parseJson(read("$root/tests/resolution/fixtures/manifest.json").decodeToString()).objectValue()
        var fetches = 0
        val transport = object : ArtifactTransport {
            override fun fetch(url: String): ByteArray {
                fetches++
                val entry = manifest[url]?.objectValue()
                return if (entry != null) read("$root/tests/resolution/fixtures/${entry.getValue("file").stringValue()}").also {
                    assertEquals(Digest(entry.getValue("hash").stringValue(), entry.getValue("sizeBytes").longValue()), Sha256.digest(it))
                } else if (url.endsWith(".module") || url.endsWith(".pom")) throw MissingResource(url)
                else read("$ktc/${url.substringAfter("/maven2/")}")
            }
        }
        val service = DependencyLocks(store(), NativePomParser())
        val lock = service.resolve(configuration, transport)
        val before = fetches
        assertEquals(lock, service.loadLocked(lock.bytes(), configuration))
        assertEquals(before, fetches)
        val expected = parseJson(read("$root/tests/resolution/fixtures/selections.json").decodeToString()).arrayValue()
        assertEquals(15, lock.selections.size)
        for (selection in lock.selections.filter { it.variant.usage != "java-api" }) {
            val reference = expected.single { it.objectValue().getValue("requested").stringValue() == selection.requested.toString() &&
                it.objectValue().getValue("platform").stringValue() == selection.variant.platform }.objectValue()
            assertEquals(reference.getValue("artifacts").arrayValue().map { it.objectValue().getValue("digest") }, selection.artifacts.map { it.digest.toJson() })
            assertEquals(reference.getValue("metadata").arrayValue().map { it.objectValue().getValue("digest") }, selection.metadata.map { it.digest.toJson() })
        }
        val yamlApi = lock.selections.single { it.requested.name == "snakeyaml-engine-kmp" && it.variant.usage == "java-api" }
        assertEquals(setOf("snakeyaml-engine-kmp-jvm", "kotlin-stdlib", "annotations"), yamlApi.artifacts.map { it.coordinate.name }.toSet())
        val yamlRuntime = lock.selections.single { it.requested.name == "snakeyaml-engine-kmp" && it.variant.usage == "java-runtime" }
        assertTrue(yamlRuntime.artifacts.any { it.coordinate.name == "urlencoder-lib-jvm" })
        assertEquals(12, lock.selections.flatMap { it.artifacts }.distinctBy { it.url }.size)
        publish("$work/production-lock.json", lock.bytes())
        publish("$work/effective-configuration.json", configuration.document.canonical().encodeToByteArray())
        println("Verified production-only 15 closures (JVM API/runtime, Native API), 12 KTC binary identities, captured metadata, CAS-only replay")
    }
}
