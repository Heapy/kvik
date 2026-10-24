package kvik.library.tests

import kvik.core.*
import kvik.library.*
import kotlin.test.*

private val composition = Sha256.digest("composition".encodeToByteArray())
private val publicDependency = LibraryDependency(Coordinate("org.example", "api", "1.0"), true)
private val privateDependency = LibraryDependency(Coordinate("org.example", "runtime", "2.0"), false)
private fun selection(target: String = "jvm", dependencies: List<LibraryDependency> = listOf(privateDependency, publicDependency)) =
    LibraryPackage("library", target, Coordinate("org.example", "library-$target", "1.0"), dependencies, if (target == "jvm") 11 else null)
private class Store : ContentStore {
    val blobs = mutableMapOf<Digest, ByteArray>()
    var gets = 0
    var puts = 0
    var corruptPut = false
    var afterGet: (() -> Unit)? = null
    override fun put(bytes: ByteArray): Digest {
        puts++
        val digest = Sha256.digest(bytes)
        blobs[digest] = if (corruptPut) byteArrayOf(99) else bytes.copyOf()
        return digest
    }
    override fun get(digest: Digest): ByteArray? {
        gets++
        val bytes = blobs[digest]?.copyOf()
        afterGet?.invoke()
        return bytes
    }
    fun file(path: String, bytes: ByteArray, executable: Boolean = false) = FileEntry(LogicalPath(path), put(bytes), executable)
}
private fun complete(store: ContentStore, action: ActionSpec): ActionResult = LibraryPackageExecutor(store).start("run", "package", action).use { running ->
    repeat(action.inputs.files.size + 8) { running.poll()?.let { return it } }
    error("Package did not finish")
}
private fun Store.bytes(result: ActionResult, extension: String): ByteArray =
    checkNotNull(get(result.outputs.files.single { it.path.value.endsWith(extension) }.digest))
private fun action(store: Store, files: List<FileEntry> = listOf(store.file("classes/value.txt", "123456789".encodeToByteArray())), value: LibraryPackage = selection()) =
    libraryPackageAction(value, TreeManifest(files), composition)
private fun number(bytes: ByteArray, offset: Int, width: Int): Long =
    (0 until width).fold(0L) { value, index -> value or ((bytes[offset + index].toLong() and 255) shl (8 * index)) }
private data class ZipEntry(val name: String, val crc: Long, val data: ByteArray, val mode: Long)

/** Independent field reader checks both records, not the writer's internal entry data. */
private fun readZip(bytes: ByteArray): List<ZipEntry> {
    val end = bytes.size - 22
    assertEquals(0x06054b50, number(bytes, end, 4))
    assertEquals(0, number(bytes, end + 4, 4))
    val count = number(bytes, end + 8, 2).toInt()
    assertEquals(count.toLong(), number(bytes, end + 10, 2))
    assertEquals(0, number(bytes, end + 20, 2))
    var central = number(bytes, end + 16, 4).toInt()
    assertEquals(end.toLong(), central + number(bytes, end + 12, 4))
    val result = mutableListOf<ZipEntry>()
    repeat(count) {
        assertEquals(0x02014b50, number(bytes, central, 4))
        assertEquals(0x0314, number(bytes, central + 4, 2))
        assertEquals(20, number(bytes, central + 6, 2))
        assertEquals(0x800, number(bytes, central + 8, 2))
        assertEquals(0, number(bytes, central + 10, 2))
        assertEquals(0, number(bytes, central + 12, 2))
        assertEquals(33, number(bytes, central + 14, 2))
        val crc = number(bytes, central + 16, 4)
        val size = number(bytes, central + 20, 4).toInt()
        assertEquals(size.toLong(), number(bytes, central + 24, 4))
        val length = number(bytes, central + 28, 2).toInt()
        assertEquals(0, number(bytes, central + 30, 4)) // No extra fields or comments.
        assertEquals(0, number(bytes, central + 34, 4)) // No alternate disks or internal flags.
        val mode = number(bytes, central + 38, 4) ushr 16
        val local = number(bytes, central + 42, 4).toInt()
        val name = bytes.copyOfRange(central + 46, central + 46 + length).decodeToString(throwOnInvalidSequence = true)
        assertEquals(0x04034b50, number(bytes, local, 4))
        assertEquals(20, number(bytes, local + 4, 2))
        assertEquals(0x800, number(bytes, local + 6, 2))
        assertEquals(0, number(bytes, local + 8, 2)); assertEquals(0, number(bytes, local + 10, 2))
        assertEquals(33, number(bytes, local + 12, 2)); assertEquals(crc, number(bytes, local + 14, 4))
        assertEquals(size.toLong(), number(bytes, local + 18, 4)); assertEquals(size.toLong(), number(bytes, local + 22, 4))
        assertEquals(length.toLong(), number(bytes, local + 26, 2)); assertEquals(0, number(bytes, local + 28, 2))
        assertEquals(name, bytes.copyOfRange(local + 30, local + 30 + length).decodeToString())
        result += ZipEntry(name, crc, bytes.copyOfRange(local + 30 + length, local + 30 + length + size), mode)
        central += 46 + length
    }
    assertEquals(end, central)
    assertEquals(result.map { it.name }.sorted(), result.map { it.name })
    return result
}

class LibraryPackageTest {
    @Test fun linuxKlibMetadataNamesActualPlatformAndApiRuntimeVariants() {
        val store = Store(); val input = store.file("module.klib", byteArrayOf(9, 8, 7))
        val result = complete(store, action(store, listOf(input), selection("linuxX64")))
        assertEquals(ActionStatus.EXECUTED, result.status)
        assertEquals(input.digest, result.outputs.files.single { it.path.value.endsWith(".klib") }.digest)
        val variants = parseJson(store.bytes(result, ".module").decodeToString()).objectValue().getValue("variants").arrayValue().map { it.objectValue() }
        assertEquals(listOf("linuxX64ApiElements", "linuxX64RuntimeElements"), variants.map { it.getValue("name").stringValue() })
        for (variant in variants) {
            val attributes = variant.getValue("attributes").objectValue()
            assertEquals("linux_x64", attributes.getValue("org.jetbrains.kotlin.native.target").stringValue())
            assertEquals("org.jetbrains.kotlin.klib", attributes.getValue("artifactType").stringValue())
        }
        assertEquals(listOf(1, 2), variants.map { it.getValue("dependencies").arrayValue().size })
    }

    @Test fun rootDependencyUsesTargetCoordinateForPomConsumers() {
        val store = Store()
        val root = Coordinate("org.example", "base", "1.0")
        val dependency = LibraryDependency(root, true, root.copy(name = "base-jvm"))
        assertEquals(dependency, LibraryDependency.fromJson(dependency.toJson()))
        val result = complete(store, action(store, value = selection(dependencies = listOf(dependency))))
        val pom = store.bytes(result, ".pom").decodeToString()
        assertTrue("<artifactId>base-jvm</artifactId>" in pom)
        assertFalse("<artifactId>base</artifactId>" in pom)
        val metadata = parseJson(store.bytes(result, ".module").decodeToString()).objectValue()
        for (variant in metadata.getValue("variants").arrayValue()) {
            assertEquals("base", variant.objectValue().getValue("dependencies").arrayValue().single()
                .objectValue().getValue("module").stringValue())
        }
        assertNotEquals(action(store, value = selection(dependencies = listOf(dependency))).key(Sha256),
            action(store, value = selection(dependencies = listOf(dependency.copy(pomCoordinate = null)))).key(Sha256))
    }

    @Test fun optionalRootOwnerKeepsLegacyJsonAndArtifactBytesButAddsMetadataBacklink() {
        val store = Store()
        val original = selection(dependencies = emptyList())
        val legacy = """{"coordinate":"org.example:library-jvm:1.0","dependencies":[],"jvmTarget":11,"module":"library","schemaVersion":1,"target":"jvm"}"""
        assertEquals(legacy, original.toJson().canonical())
        assertEquals(original, LibraryPackage.fromJson(parseJson(legacy)))
        val root = Coordinate("org.example", "library", "1.0")
        val owned = original.copy(rootCoordinate = root)
        assertEquals(owned, LibraryPackage.fromJson(owned.toJson()))
        val files = listOf(store.file("classes/Example.class", byteArrayOf(1, 2)))
        val standaloneAction = action(store, files, original)
        val ownedAction = action(store, files, owned)
        assertNotEquals(standaloneAction.key(Sha256), ownedAction.key(Sha256))
        val standaloneResult = complete(store, standaloneAction)
        val ownedResult = complete(store, ownedAction)
        assertContentEquals(store.bytes(standaloneResult, ".jar"), store.bytes(ownedResult, ".jar"))
        assertContentEquals(store.bytes(standaloneResult, ".pom"), store.bytes(ownedResult, ".pom"))
        val standaloneMetadata = parseJson(store.bytes(standaloneResult, ".module").decodeToString()).objectValue()
        val ownedMetadata = parseJson(store.bytes(ownedResult, ".module").decodeToString()).objectValue()
        assertEquals(obj("group" to "org.example".json(), "module" to "library-jvm".json(), "version" to "1.0".json()),
            standaloneMetadata.getValue("component"))
        assertEquals(obj("group" to "org.example".json(), "module" to "library".json(), "version" to "1.0".json(),
            "url" to "../../library/1.0/library-1.0.module".json()), ownedMetadata.getValue("component"))
        assertEquals(standaloneMetadata.getValue("variants"), ownedMetadata.getValue("variants"))
        for (invalid in listOf(root.copy(group = "other"), root.copy(version = "2.0"), original.coordinate)) {
            assertFails { original.copy(rootCoordinate = invalid) }
        }
    }

    @Test fun storedJarHasCorrectCrcHeadersUtf8AndFixedManifest() {
        val store = Store()
        val files = listOf(store.file("classes/é/资源.txt", "123456789".encodeToByteArray(), true),
            store.file("classes/fixture/Example.class", byteArrayOf(0xca.toByte(), 0xfe.toByte())))
        val spec = action(store, files)
        val result = complete(store, spec)
        assertEquals(ActionStatus.EXECUTED, result.status)
        assertEquals(spec.key(Sha256), result.key)
        assertEquals(3, result.outputs.files.size)
        assertTrue(result.outputs.files.all { !it.executable && it.path.value.startsWith("library/org/example/library-jvm/1.0/library-jvm-1.0.") })
        val entries = readZip(store.bytes(result, ".jar"))
        assertEquals(listOf("META-INF/MANIFEST.MF", "fixture/Example.class", "é/资源.txt"), entries.map { it.name })
        assertEquals("Manifest-Version: 1.0\r\n\r\n", entries[0].data.decodeToString())
        assertEquals(0x81a4, entries[0].mode)
        assertEquals(0xcbf43926L, entries[2].crc)
        assertEquals(0x81ed, entries[2].mode)
        assertEquals("123456789", entries[2].data.decodeToString())
    }

    @Test fun repeatedPackagingAndInputOrderProduceExactSameBytes() {
        val store = Store()
        val files = listOf(store.file("classes/z", byteArrayOf()), store.file("classes/a", byteArrayOf(1)))
        val first = action(store, files)
        val second = action(store, files.reversed(), selection(dependencies = selection().dependencies.reversed()))
        assertEquals(first.key(Sha256), second.key(Sha256))
        assertEquals(complete(store, first).outputs, complete(store, second).outputs)
        assertEquals(384, LibraryPackageExecutor(store).resources(first).memoryMiB)
    }

    @Test fun pomAndGradleMetadataRetainApiRuntimeScopeAndRealArtifactHash() {
        val store = Store(); val result = complete(store, action(store))
        val pom = store.bytes(result, ".pom").decodeToString()
        assertContains(pom, "<!-- do_not_remove: published-with-gradle-metadata -->")
        assertContains(pom, "<artifactId>api</artifactId><version>1.0</version><scope>compile</scope>")
        assertContains(pom, "<artifactId>runtime</artifactId><version>2.0</version><scope>runtime</scope>")
        val metadata = store.bytes(result, ".module").decodeToString()
        assertTrue(metadata.startsWith("{\"formatVersion\":\"1.1\",\"component\":"))
        val module = parseJson(metadata).objectValue()
        assertEquals("1.1", module.getValue("formatVersion").stringValue())
        val variants = module.getValue("variants").arrayValue().map { it.objectValue() }
        assertEquals(listOf("jvmApiElements", "jvmRuntimeElements"), variants.map { it.getValue("name").stringValue() })
        assertEquals(listOf("java-api", "java-runtime"), variants.map { it.getValue("attributes").objectValue().getValue("org.gradle.usage").stringValue() })
        assertEquals(listOf(1, 2), variants.map { it.getValue("dependencies").arrayValue().size })
        val artifact = result.outputs.files.single { it.path.value.endsWith(".jar") }
        for (variant in variants) {
            val attributes = variant.getValue("attributes").objectValue()
            assertEquals(11, attributes.getValue(JVM_TARGET_VERSION_ATTRIBUTE).longValue())
            assertEquals("jar", attributes.getValue("org.gradle.libraryelements").stringValue())
            assertEquals("external", attributes.getValue("org.gradle.dependency.bundling").stringValue())
            val file = variant.getValue("files").arrayValue().single().objectValue()
            assertEquals(artifact.digest.hash, file.getValue("sha256").stringValue())
            assertEquals(artifact.digest.sizeBytes, file.getValue("size").longValue())
            assertEquals("library-jvm-1.0.jar", file.getValue("url").stringValue())
        }
    }

    @Test fun nativeCopiesOriginalKlibAndSeparatesApiFromRuntimeOnlyDependencies() {
        val store = Store(); val input = store.file("module.klib", byteArrayOf(9, 8, 7))
        val result = complete(store, action(store, listOf(input), selection("macosArm64")))
        assertEquals(ActionStatus.EXECUTED, result.status)
        assertEquals(input.digest, result.outputs.files.single { it.path.value.endsWith(".klib") }.digest)
        assertEquals(3, result.outputs.files.size)
        val metadata = store.bytes(result, ".module").decodeToString()
        assertTrue(metadata.startsWith("{\"formatVersion\":\"1.1\",\"component\":"))
        val variants = parseJson(metadata).objectValue().getValue("variants").arrayValue()
        assertEquals(listOf("macosArm64ApiElements", "macosArm64RuntimeElements"), variants.map { it.objectValue().getValue("name").stringValue() })
        assertEquals(listOf("kotlin-api", "kotlin-runtime"), variants.map {
            it.objectValue().getValue("attributes").objectValue().getValue("org.gradle.usage").stringValue()
        })
        assertEquals(listOf(listOf("api"), listOf("api", "runtime")), variants.map { variant ->
            variant.objectValue().getValue("dependencies").arrayValue().map { it.objectValue().getValue("module").stringValue() }
        })
        for (variant in variants.map { it.objectValue() }) {
            val attrs = variant.getValue("attributes").objectValue()
            assertEquals("macos_arm64", attrs.getValue("org.jetbrains.kotlin.native.target").stringValue())
            assertEquals("org.jetbrains.kotlin.klib", attrs.getValue("artifactType").stringValue())
            assertFalse(JVM_TARGET_VERSION_ATTRIBUTE in attrs)
        }
    }

    @Test fun pathsManifestAndDeclaredBoundsRejectBeforeCasReads() {
        val store = Store()
        fun reject(vararg names: String) {
            val files = names.map { FileEntry(LogicalPath(it), composition) }
            assertFails { action(store, files) }
        }
        reject("other/file"); reject("classes/META-INF/MANIFEST.MF"); reject("classes/meta-inf/manifest.mf")
        reject("classes/META-INF"); reject("classes/A/one", "classes/a/two"); reject("classes/Foo", "classes/foo/bar")
        reject("classes/foo", "classes/foo")
        assertFails { action(store, listOf(FileEntry(LogicalPath("classes/huge"), composition.copy(sizeBytes = 64L * 1024 * 1024)))) }
        assertFails { action(store, List(10001) { FileEntry(LogicalPath("classes/$it"), composition.copy(sizeBytes = 0)) }) }
        assertFails { action(store, listOf(FileEntry(LogicalPath("module.klib"), composition, true)), selection("macosArm64")) }
        assertFails { action(store, emptyList(), selection("macosArm64")) }
        assertEquals(0, store.gets)
    }

    @Test fun strictModelAndTypedActionCannotSmuggleArgumentsOrUnboundOutputs() {
        val value = selection()
        assertEquals(value.toJson(), LibraryPackage.fromJson(value.toJson()).toJson())
        assertFails { value.copy(jvmTarget = null) }
        assertFails { value.copy(dependencies = listOf(publicDependency, publicDependency.copy(api = false))) }
        assertFails { value.copy(dependencies = listOf(LibraryDependency(value.coordinate, true))) }
        val rootDependency = LibraryDependency(Coordinate("org.example", "base", "1.0"), true,
            Coordinate("org.example", "base-jvm", "1.0"))
        assertFails { value.copy(dependencies = listOf(rootDependency,
            LibraryDependency(Coordinate("org.example", "base-jvm", "2.0"), false))) }
        assertFails { value.copy(dependencies = listOf(rootDependency.copy(pomCoordinate = value.coordinate))) }
        assertFails { LibraryPackage.fromJson(JsonValue.Object(value.toJson().values + ("extra" to true.json()))) }
        val store = Store(); val spec = action(store)
        val executor = LibraryPackageExecutor(store)
        for (changed in listOf(spec.copy(arguments = listOf("run")), spec.copy(environment = mapOf("PATH" to "/host")),
            spec.copy(cachePolicy = CachePolicy.REMOTE), spec.copy(timeoutMillis = 1), spec.copy(toolchains = mapOf("jdk" to composition)),
            spec.copy(outputs = listOf(LogicalPath("elsewhere"))))) assertFails { executor.start("run", "id", changed) }
        assertEquals(0, store.gets)
    }

    @Test fun changedContentCoordinateAndDependencyScopeChangeKeys() {
        val store = Store(); val spec = action(store); val key = spec.key(Sha256)
        val changed = listOf(action(store, listOf(store.file("classes/value.txt", byteArrayOf(8)))),
            action(store, spec.inputs.files, selection().copy(coordinate = Coordinate("org.example", "different", "1.0"))),
            action(store, spec.inputs.files, selection(dependencies = listOf(privateDependency.copy(api = true), publicDependency))),
            libraryPackageAction(selection(), spec.inputs, Sha256.digest(byteArrayOf(8))))
        changed.forEach { assertNotEquals(key, it.key(Sha256)) }
    }

    @Test fun missingCorruptAndFailedReadbackReturnNoSuccessfulOutputs() {
        for (mode in listOf("missing", "corrupt", "readback")) {
            val store = Store(); val spec = action(store); val input = spec.inputs.files.single().digest
            if (mode == "missing") store.blobs.remove(input)
            if (mode == "corrupt") store.blobs[input] = byteArrayOf(1)
            if (mode == "readback") store.corruptPut = true
            val result = complete(store, spec)
            assertEquals(ActionStatus.FAILED, result.status)
            assertTrue(result.outputs.files.isEmpty())
            assertNotNull(result.diagnostic)
        }
    }

    @Test fun cancellationBeforeBetweenAndDuringReadsNeverReturnsPublication() {
        for (mode in 0..2) {
            val store = Store(); val spec = action(store); val initialPuts = store.puts
            LibraryPackageExecutor(store).start("run", "id", spec).use { running ->
                if (mode == 1) assertNull(running.poll()) // Fixed manifest only.
                if (mode == 2) { store.afterGet = { running.cancel() }; assertNull(running.poll()) }
                else running.cancel()
                val result = assertNotNull(running.poll())
                assertEquals(ActionStatus.CANCELLED, result.status)
                assertTrue(result.outputs.files.isEmpty())
                assertEquals(initialPuts, store.puts)
            }
        }
    }

    @Test fun callerCollectionsAndReturnedOutputMutationCannotChangePublication() {
        val store = Store(); val files = mutableListOf(store.file("classes/value.txt", byteArrayOf(1)), store.file("classes/other.txt", byteArrayOf(2)))
        val dependencies = mutableListOf(publicDependency)
        val spec = action(store, files, selection(dependencies = dependencies))
        val key = spec.key(Sha256)
        files.clear(); dependencies.clear()
        assertEquals(key, spec.key(Sha256))
        assertEquals(2, spec.inputs.files.size)
        assertEquals(listOf(publicDependency), LibraryPackage.fromJson(spec.parameters).dependencies)
        val running = LibraryPackageExecutor(store).start("run", "id", spec)
        // Immutable snapshots are valid too; mutable views must remain detached from the running action.
        (spec.inputs.files as? MutableList<FileEntry>)?.clear()
        var result: ActionResult? = null
        repeat(8) { result = running.poll() ?: result }
        assertEquals(key, result!!.key)
        (result!!.outputs.files as? MutableList<FileEntry>)?.clear()
        assertEquals(3, running.poll()!!.outputs.files.size)
        running.cancel(); assertEquals(ActionStatus.EXECUTED, running.poll()!!.status)
        running.close(); running.close(); assertFails { running.poll() }
    }
}
