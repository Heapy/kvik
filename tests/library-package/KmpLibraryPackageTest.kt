package kvik.library.tests

import kvik.core.*
import kvik.library.*
import kotlin.test.*

private val kmpComposition = Sha256.digest("kmp-composition".encodeToByteArray())
private class KmpStore : ContentStore {
    val blobs = mutableMapOf<Digest, ByteArray>()
    var reads = 0
    var writes = 0
    var corruptWrites = false
    override fun put(bytes: ByteArray): Digest {
        writes++
        return Sha256.digest(bytes).also { blobs[it] = if (corruptWrites) byteArrayOf(0) else bytes.copyOf() }
    }
    override fun get(digest: Digest): ByteArray? { reads++; return blobs[digest]?.copyOf() }
    fun file(path: String, bytes: ByteArray) = FileEntry(LogicalPath(path), put(bytes))
}
private fun runPackage(executor: ActionExecutor, action: ActionSpec): ActionResult = executor.start("run", "publication", action).use { running ->
    repeat(action.inputs.files.size + 10) { running.poll()?.let { return it } }
    error("Package did not complete")
}
private class KmpFixture(platformTarget: String = "macosArm64") {
    val store = KmpStore()
    val root = Coordinate("org.example", "root", "1.0")
    val dependency = Coordinate("org.example", "dependency", "2.0")
    val targets = listOf(LibraryPackage("root", "jvm", Coordinate("org.example", "root-jvm", "1.0"),
        listOf(LibraryDependency(dependency, true)), 11, root), LibraryPackage("root", platformTarget,
        Coordinate("org.example", "root-${platformTarget.lowercase()}", "1.0"), listOf(LibraryDependency(dependency, true)), rootCoordinate = root))
    val selection = KmpLibraryPackage("root", root, targets,
        listOf(KmpSourceSetMetadata("commonBase", emptyList(), listOf(dependency)),
            KmpSourceSetMetadata("sharedMain", listOf("commonBase"), listOf(dependency))),
        mapOf("jvm" to listOf("sharedMain", "commonBase"), platformTarget to listOf("commonBase", "sharedMain")))
    // Opaque byte fixtures exercise packaging only, not compiler KLIB validity.
    val metadata = selection.sourceSets.flatMap { source -> listOf(
        store.file("metadata/${source.name}/default/manifest", "fixture=${source.name}\n".encodeToByteArray()),
        store.file("metadata/${source.name}/default/linkdata/module", "opaque metadata ${source.name}".encodeToByteArray())) }
    val platformFiles = targets.flatMap { target ->
        val inputs = if (target.target == "jvm") TreeManifest(listOf(store.file("classes/Example.class", byteArrayOf(1, 2))))
            else TreeManifest(listOf(store.file("module.klib", byteArrayOf(3, 4))))
        val result = runPackage(LibraryPackageExecutor(store), libraryPackageAction(target, inputs, kmpComposition))
        check(result.successful)
        result.outputs.files
    }
    val files = platformFiles + metadata
    fun action(value: KmpLibraryPackage = selection, entries: List<FileEntry> = files) =
        kmpLibraryPackageAction(value, TreeManifest(entries), kmpComposition)
    fun bytes(result: ActionResult, extension: String) = checkNotNull(store.get(result.outputs.files.single { it.path.value.endsWith(extension) }.digest))
}
private fun little(bytes: ByteArray, offset: Int, count: Int): Int = (0 until count).fold(0) { value, index ->
    value or ((bytes[offset + index].toInt() and 255) shl (8 * index))
}
private fun jarFiles(bytes: ByteArray): Map<String, ByteArray> {
    val result = linkedMapOf<String, ByteArray>()
    var offset = 0
    while (little(bytes, offset, 4) == 0x04034b50) {
        assertEquals(0, little(bytes, offset + 8, 2))
        val size = little(bytes, offset + 18, 4)
        val length = little(bytes, offset + 26, 2)
        assertEquals(0, little(bytes, offset + 28, 2))
        val name = bytes.copyOfRange(offset + 30, offset + 30 + length).decodeToString()
        assertFalse(name in result)
        result[name] = bytes.copyOfRange(offset + 30 + length, offset + 30 + length + size)
        offset += 30 + length + size
    }
    assertEquals(0x02014b50, little(bytes, offset, 4))
    return result
}

class KmpLibraryPackageTest {
    @Test fun jsAndWasmRootRedirectsIncludeApiRuntimeAndMatchingSharedSourceMappings() {
        for (target in KotlinJsTarget.entries) {
            val fixture = KmpFixture(target.projectName)
            val result = runPackage(KmpLibraryPackageExecutor(fixture.store), fixture.action())
            assertEquals(ActionStatus.EXECUTED, result.status)
            val variants = parseJson(fixture.bytes(result, ".module").decodeToString()).objectValue().getValue("variants").arrayValue().map { it.objectValue() }
            val expectedNames = listOf("${target.projectName}ApiElements", "${target.projectName}RuntimeElements")
            val redirects = variants.filter { it.getValue("name").stringValue() in expectedNames }
            assertEquals(expectedNames, redirects.map { it.getValue("name").stringValue() })
            val selected = fixture.targets.single { it.target == target.projectName }
            val targetFile = fixture.platformFiles.single { it.path.value.endsWith("/${selected.coordinate.name}-1.0.module") }
            val published = parseJson(fixture.store.get(targetFile.digest)!!.decodeToString()).objectValue()
            val targetVariants = published.getValue("variants").arrayValue().associateBy { it.objectValue().getValue("name").stringValue() }
            assertEquals(obj("group" to "org.example".json(), "module" to "root".json(), "version" to "1.0".json(),
                "url" to "../../root/1.0/root-1.0.module".json()), published.getValue("component"))
            for (redirect in redirects) {
                assertFalse("files" in redirect)
                assertFalse("dependencies" in redirect)
                assertEquals(targetVariants.getValue(redirect.getValue("name").stringValue()).objectValue().getValue("attributes"), redirect.getValue("attributes"))
                assertEquals(obj("group" to "org.example".json(), "module" to selected.coordinate.name.json(), "version" to "1.0".json(),
                    "url" to "../../${selected.coordinate.name}/1.0/${selected.coordinate.name}-1.0.module".json()), redirect.getValue("available-at"))
            }
            val psm = parseJson(jarFiles(fixture.bytes(result, ".jar")).getValue("META-INF/kotlin-project-structure-metadata.json").decodeToString())
                .objectValue().getValue("projectStructure").objectValue().getValue("variants").arrayValue().associate { variant ->
                    variant.objectValue().let { it.getValue("name").stringValue() to it.getValue("sourceSet").arrayValue().map(JsonValue::stringValue) }
                }
            assertEquals(setOf("jvmApiElements", "jvmRuntimeElements") + expectedNames, psm.keys)
            expectedNames.forEach { assertEquals(listOf("commonBase", "sharedMain"), psm.getValue(it)) }
            val changed = fixture.selection.copy(targets = fixture.targets.map { if (it.target == target.projectName) it.copy(dependencies = emptyList()) else it })
            assertFails { fixture.action(changed) }
            assertEquals(fixture.selection.toJson(), KmpLibraryPackage.fromJson(fixture.selection.toJson()).toJson())
        }
    }

    @Test fun rootCanIncludeEverySupportedTargetAndKeepsMappingsExact() {
        val fixture = KmpFixture()
        val names = listOf("jvm") + NativeTarget.entries.map { it.projectName } + KotlinJsTarget.entries.map { it.projectName }
        val targets = names.map { name -> LibraryPackage("root", name, Coordinate("org.example", "root-${name.lowercase()}", "1.0"),
            emptyList(), if (name == "jvm") 11 else null, fixture.root) }
        val selected = fixture.selection.copy(targets = targets, sourceSetsByTarget = names.associateWith { listOf("commonBase", "sharedMain") })
        assertEquals(names.size, selected.targets.size)
        assertEquals(selected.toJson(), KmpLibraryPackage.fromJson(selected.toJson()).toJson())
        assertFails { selected.copy(targets = targets + targets.last()) }
        assertFails { selected.copy(sourceSetsByTarget = selected.sourceSetsByTarget - "wasmJs") }
    }

    @Test fun linuxRootRedirectAndProjectStructureUseActualTargetNames() {
        val fixture = KmpFixture("linuxX64")
        val result = runPackage(KmpLibraryPackageExecutor(fixture.store), fixture.action())
        assertEquals(ActionStatus.EXECUTED, result.status)
        val variants = parseJson(fixture.bytes(result, ".module").decodeToString()).objectValue().getValue("variants").arrayValue().map { it.objectValue() }
        val linux = variants.single { it.getValue("name").stringValue() == "linuxX64ApiElements" }
        assertEquals("linux_x64", linux.getValue("attributes").objectValue().getValue("org.jetbrains.kotlin.native.target").stringValue())
        assertEquals("root-linuxx64", linux.getValue("available-at").objectValue().getValue("module").stringValue())
        val psm = parseJson(jarFiles(fixture.bytes(result, ".jar")).getValue("META-INF/kotlin-project-structure-metadata.json").decodeToString())
            .objectValue().getValue("projectStructure").objectValue().getValue("variants").arrayValue().map { it.objectValue().getValue("name").stringValue() }
        assertEquals(listOf("jvmApiElements", "jvmRuntimeElements", "linuxX64ApiElements"), psm)
        val mac = LibraryPackage("root", "macosArm64", Coordinate("org.example", "root-macosarm64", "1.0"), emptyList(), rootCoordinate = fixture.root)
        assertEquals(3, fixture.selection.copy(targets = fixture.targets + mac,
            sourceSetsByTarget = fixture.selection.sourceSetsByTarget + ("macosArm64" to listOf("commonBase", "sharedMain"))).targets.size)
    }

    @Test fun rootJarPreservesEverySuppliedSharedByteAndPublishedHierarchy() {
        val fixture = KmpFixture(); val action = fixture.action()
        assertEquals("common", action.target); assertEquals(CachePolicy.LOCAL, action.cachePolicy)
        val result = runPackage(KmpLibraryPackageExecutor(fixture.store), action)
        assertEquals(ActionStatus.EXECUTED, result.status)
        assertEquals(3, result.outputs.files.size)
        assertTrue(result.outputs.files.all { it.path.value.startsWith("library/org/example/root/1.0/root-1.0.") })
        val jar = jarFiles(fixture.bytes(result, ".jar"))
        val directories = setOf("META-INF/", "commonBase/", "commonBase/default/", "commonBase/default/linkdata/",
            "sharedMain/", "sharedMain/default/", "sharedMain/default/linkdata/")
        assertEquals(fixture.metadata.map { it.path.value.removePrefix("metadata/") }.toSet() +
            setOf("META-INF/MANIFEST.MF", "META-INF/kotlin-project-structure-metadata.json") + directories, jar.keys)
        directories.forEach { assertEquals(0, jar.getValue(it).size) }
        assertEquals(jar.keys.sorted(), jar.keys.toList())
        fixture.metadata.forEach { entry -> assertContentEquals(fixture.store.get(entry.digest), jar[entry.path.value.removePrefix("metadata/")]) }
        val psm = parseJson(jar.getValue("META-INF/kotlin-project-structure-metadata.json").decodeToString()).objectValue().getValue("projectStructure").objectValue()
        assertEquals("0.3.3", psm.getValue("formatVersion").stringValue())
        assertEquals("true", psm.getValue("isPublishedAsRoot").stringValue())
        val variants = psm.getValue("variants").arrayValue().map { it.objectValue() }
        assertEquals(listOf("jvmApiElements", "jvmRuntimeElements", "macosArm64ApiElements"), variants.map { it.getValue("name").stringValue() })
        assertTrue(variants.all { it.getValue("sourceSet").arrayValue().map(JsonValue::stringValue) == listOf("commonBase", "sharedMain") })
        val sources = psm.getValue("sourceSets").arrayValue().map { it.objectValue() }
        assertEquals(listOf("commonBase"), sources.single { it.getValue("name").stringValue() == "sharedMain" }.getValue("dependsOn").arrayValue().map(JsonValue::stringValue))
        assertTrue(sources.all { it.getValue("binaryLayout").stringValue() == "klib" &&
            it.getValue("moduleDependency").arrayValue().map(JsonValue::stringValue) == listOf("org.example:dependency") })
    }

    @Test fun rootMetadataPointsToVerifiedTargetsAndDeduplicatesSharedDependencies() {
        val fixture = KmpFixture(); val result = runPackage(KmpLibraryPackageExecutor(fixture.store), fixture.action())
        val raw = fixture.bytes(result, ".module").decodeToString()
        assertTrue(raw.startsWith("{\"formatVersion\":\"1.1\","))
        val variants = parseJson(raw).objectValue().getValue("variants").arrayValue().map { it.objectValue() }
        val psmVariants = parseJson(jarFiles(fixture.bytes(result, ".jar")).getValue("META-INF/kotlin-project-structure-metadata.json").decodeToString())
            .objectValue().getValue("projectStructure").objectValue().getValue("variants").arrayValue()
            .associate { value -> value.objectValue().let { it.getValue("name").stringValue() to it.getValue("sourceSet") } }
        val common = variants.first()
        assertEquals("metadataApiElements", common.getValue("name").stringValue())
        assertEquals("kotlin-metadata", common.getValue("attributes").objectValue().getValue("org.gradle.usage").stringValue())
        assertEquals(1, common.getValue("dependencies").arrayValue().size)
        val file = common.getValue("files").arrayValue().single().objectValue()
        val jar = result.outputs.files.single { it.path.value.endsWith(".jar") }
        assertEquals(jar.digest.hash, file.getValue("sha256").stringValue())
        assertEquals(jar.digest.sizeBytes, file.getValue("size").longValue())
        for (variant in variants.drop(1)) {
            assertFalse("files" in variant)
            val pointer = variant.getValue("available-at").objectValue()
            val target = fixture.targets.single { it.coordinate.name == pointer.getValue("module").stringValue() }
            assertEquals(target.coordinate.group, pointer.getValue("group").stringValue())
            assertEquals(target.coordinate.version, pointer.getValue("version").stringValue())
            assertEquals("../../${target.coordinate.name}/1.0/${target.coordinate.name}-1.0.module", pointer.getValue("url").stringValue())
            val module = fixture.platformFiles.single { it.path.value.endsWith("/${target.coordinate.name}-1.0.module") }
            val targetMetadata = parseJson(fixture.store.get(module.digest)!!.decodeToString()).objectValue()
            assertEquals(obj("group" to "org.example".json(), "module" to "root".json(), "version" to "1.0".json(),
                "url" to "../../root/1.0/root-1.0.module".json()), targetMetadata.getValue("component"))
            val actual = targetMetadata.getValue("variants").arrayValue()
            val usage = variant.getValue("attributes").objectValue().getValue("org.gradle.usage")
            val resolvedVariant = actual.single { it.objectValue().getValue("attributes").objectValue().getValue("org.gradle.usage") == usage }.objectValue()
            assertEquals(resolvedVariant.getValue("attributes"), variant.getValue("attributes"))
            if (target.target == "macosArm64") assertEquals("org.jetbrains.kotlin.klib",
                variant.getValue("attributes").objectValue().getValue("artifactType").stringValue())
            // KGP/KTC use the resolved target variant name as the PSM lookup key, not its usage attribute.
            val targetName = resolvedVariant.getValue("name").stringValue()
            assertEquals(variant.getValue("name").stringValue(), targetName)
            assertEquals(listOf("commonBase", "sharedMain"), psmVariants.getValue(targetName).arrayValue().map(JsonValue::stringValue))
        }
        assertContains(fixture.bytes(result, ".pom").decodeToString(), "<artifactId>dependency</artifactId><version>2.0</version><scope>compile</scope>")
    }

    @Test fun deterministicOrderAndInputChangesAreKeyBound() {
        val fixture = KmpFixture(); val action = fixture.action()
        val reordered = fixture.selection.copy(targets = fixture.targets.reversed(), sourceSets = fixture.selection.sourceSets.reversed(),
            sourceSetsByTarget = fixture.selection.sourceSetsByTarget.mapValues { it.value.reversed() })
        val reverse = fixture.action(reordered, fixture.files.reversed())
        assertEquals(action.key(Sha256), reverse.key(Sha256))
        assertEquals(runPackage(KmpLibraryPackageExecutor(fixture.store), action).outputs,
            runPackage(KmpLibraryPackageExecutor(fixture.store), reverse).outputs)
        val changed = fixture.files.map { if (it == fixture.metadata.first()) fixture.store.file(it.path.value, byteArrayOf(8)) else it }
        assertNotEquals(action.key(Sha256), fixture.action(entries = changed).key(Sha256))
    }

    @Test fun malformedHierarchyAndMappingsFailBeforePackaging() {
        val fixture = KmpFixture(); val value = fixture.selection
        assertEquals(value.toJson(), KmpLibraryPackage.fromJson(value.toJson()).toJson())
        assertFails { value.copy(sourceSets = emptyList()) }
        assertFails { value.copy(targets = value.targets.map { it.copy(rootCoordinate = null) }) }
        assertFails { value.copy(targets = value.targets.map { it.copy(rootCoordinate = fixture.root.copy(name = "other")) }) }
        assertFails { value.copy(sourceSets = listOf(KmpSourceSetMetadata("a", listOf("b"), emptyList()), KmpSourceSetMetadata("b", listOf("a"), emptyList()))) }
        assertFails { value.copy(sourceSetsByTarget = mapOf("jvm" to listOf("sharedMain"), "macosArm64" to listOf("commonBase"))) }
        assertFails { value.copy(sourceSetsByTarget = value.sourceSetsByTarget - "jvm") }
        assertFails { value.copy(sourceSets = value.sourceSets + KmpSourceSetMetadata("unused", emptyList(), emptyList())) }
        assertFails { value.copy(sourceSets = listOf(value.sourceSets[0], value.sourceSets[1].copy(dependencies = listOf(fixture.dependency.copy(version = "3.0"))))) }
        assertFails { KmpLibraryPackage.fromJson(JsonValue.Object(value.toJson().values + ("extra" to true.json()))) }
    }

    @Test fun targetMetadataCannotLieAboutCoordinatesAttributesOrArtifactBytes() {
        val fixture = KmpFixture(); val reads = fixture.store.reads
        for (suffix in listOf(".module", ".pom", ".jar")) {
            val file = fixture.platformFiles.first { it.path.value.endsWith(suffix) }
            val changed = fixture.files.map { if (it == file) fixture.store.file(it.path.value, byteArrayOf(8, 9)) else it }
            assertFails { fixture.action(entries = changed) }
        }
        assertFails { fixture.action(fixture.selection.copy(targets = fixture.targets.map { if (it.target == "jvm") it.copy(jvmTarget = 17) else it })) }
        assertFails { fixture.action(entries = fixture.files + fixture.store.file("library/extra", byteArrayOf())) }
        assertFails { fixture.action(entries = fixture.files.filterNot { it == fixture.metadata[0] }) }
        assertFails { fixture.action(entries = fixture.files + fixture.store.file("metadata/undeclared/default/manifest", byteArrayOf())) }
        assertEquals(reads, fixture.store.reads)
    }

    @Test fun corruptionMissingBlobsAndPublicationFailureNeverReturnSuccess() {
        for (mode in listOf("target", "metadata", "missing", "readback")) {
            val fixture = KmpFixture(); val action = fixture.action()
            val digest = if (mode == "target") fixture.platformFiles.first().digest else fixture.metadata.first().digest
            if (mode == "missing") fixture.store.blobs.remove(digest)
            else if (mode == "readback") fixture.store.corruptWrites = true
            else fixture.store.blobs[digest] = byteArrayOf(42)
            val result = runPackage(KmpLibraryPackageExecutor(fixture.store), action)
            assertEquals(ActionStatus.FAILED, result.status)
            assertTrue(result.outputs.files.isEmpty())
        }
    }

    @Test fun cancellationBetweenInputsAndAfterArtifactHasNoSuccessfulOutputs() {
        for (polls in listOf(0, 1, 12, 13)) {
            val fixture = KmpFixture(); val action = fixture.action()
            KmpLibraryPackageExecutor(fixture.store).start("run", "id", action).use { running ->
                repeat(polls) { assertNull(running.poll()) }
                running.cancel()
                val result = assertNotNull(running.poll())
                assertEquals(ActionStatus.CANCELLED, result.status)
                assertEquals(130, result.exitCode)
                assertTrue(result.outputs.files.isEmpty())
                assertEquals(result, running.poll())
            }
        }
    }

    @Test fun typedActionRejectsTamperingAndDetachSurvivesCallerMutation() {
        val fixture = KmpFixture(); val inputs = fixture.files.toMutableList()
        val sourceSets = fixture.selection.sourceSets.toMutableList()
        val action = fixture.action(fixture.selection.copy(sourceSets = sourceSets), inputs)
        val key = action.key(Sha256)
        inputs.clear(); sourceSets.clear(); assertEquals(key, action.key(Sha256))
        val executor = KmpLibraryPackageExecutor(fixture.store)
        for (changed in listOf(action.copy(target = "jvm"), action.copy(cachePolicy = CachePolicy.REMOTE),
            action.copy(arguments = listOf("run")), action.copy(outputs = listOf(LogicalPath("root"))), action.copy(timeoutMillis = 1))) {
            assertFails { executor.start("run", "id", changed) }
        }
        val running = executor.start("run", "id", action)
        (action.inputs.files as? MutableList<FileEntry>)?.clear()
        var result: ActionResult? = null
        repeat(20) { result = running.poll() ?: result }
        assertEquals(key, result!!.key)
        (result!!.outputs.files as? MutableList<FileEntry>)?.clear()
        assertEquals(3, running.poll()!!.outputs.files.size)
        running.close(); running.close(); assertFails { running.poll() }
    }
}
