@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.application.preparationtests

import kotlinx.cinterop.toKString
import platform.posix.*
import kotlin.test.*
import kvik.application.*
import kvik.cache.*
import kvik.core.*

private val identity = Sha256.digest("identity".encodeToByteArray())
private class Fixture(name: String, native: Boolean = false) {
    val root = checkNotNull(getenv("KVIK_APPLICATION_TEST_ROOT")).toKString() + "/$name"
    val content = NativeContentStore("$root/cas")
    val run = "$root/run".also { NativeFiles.ensureDirectory(it) }
    val input = FileEntry(LogicalPath(if (native) "bin/app.kexe" else "classes/MainKt.class"), content.put("fixture application".encodeToByteArray()), native)
    val launch = if (native) ApplicationLaunch("app", "macosArm64", ApplicationSpec("fixture.main"), executable = input.path)
        else ApplicationLaunch("app", "jvm", ApplicationSpec("fixture.MainKt", listOf("space value"), mapOf("FOO" to "bar")), listOf(LogicalPath("classes")))
    val action = applicationBundleAction(launch, TreeManifest(listOf(input)), identity, mapOf("compiler" to identity))
    val terminal = ApplicationBundleExecutor(content).start("run", "app:${launch.target}:application", action).use { handle ->
        var result: ActionResult? = null
        repeat(3) { if (result == null) result = handle.poll() }
        checkNotNull(result)
    }
    val record = VerificationRecord("run", setOf(terminal.actionId), setOf(terminal.actionId), identity, identity, identity, identity,
        mapOf("${launch.target}:compiler" to identity, "other:ignored" to identity), listOf(terminal), emptyList(), TestCounts(0, 0, 0, 0, true),
        VerificationStatus.PASSED, Freshness.CURRENT, listOf("kvik", "prepare"))
    fun prepare() = materializePreparedApplication(record, content, run)
    fun withOutputs(files: List<FileEntry>) = record.copy(actions = listOf(terminal.copy(outputs = TreeManifest(files))))
    fun replace(path: String, bytes: ByteArray, executable: Boolean = false) {
        NativeFiles.removeEntry(path); NativeFiles.writeNew(path, bytes, executable)
    }
}

class ApplicationPreparationTest {
    @Test fun realJvmBundleRoundTripsWithoutCasWritesOnValidation() {
        val f = Fixture("jvm")
        val descriptor = f.prepare()
        assertEquals(Sha256.digest(f.record.toJson()), descriptor.verification)
        assertEquals(Sha256.digest(f.terminal.outputs.toJson()), descriptor.outputs)
        assertEquals(descriptor, PreparedApplicationDescriptor.fromJson(descriptor.toJson()))
        val before = NativeOutputTrees(object : ContentStore {
            override fun put(bytes: ByteArray) = Sha256.digest(bytes)
            override fun get(digest: Digest): ByteArray? = error("Unexpected read")
        }).capture(f.root + "/cas")
        assertEquals(f.launch, validatePreparedApplication(f.record, descriptor, f.run))
        val after = NativeOutputTrees(object : ContentStore {
            override fun put(bytes: ByteArray) = Sha256.digest(bytes)
            override fun get(digest: Digest): ByteArray? = error("Unexpected read")
        }).capture(f.root + "/cas")
        assertEquals(before, after)
        assertEquals("fixture application", NativeFiles.read("${f.run}/prepared/application/input/${f.input.path.value}", 100).decodeToString())
    }
    @Test fun nativeExecutableModeIsBoundWithoutRunningIt() {
        val f = Fixture("native", native = true); val d = f.prepare()
        assertEquals(f.launch, validatePreparedApplication(f.record, d, f.run))
        assertEquals(0, chmod("${f.run}/prepared/application/input/${f.input.path.value}", 292u))
        assertFails { validatePreparedApplication(f.record, d, f.run) }
    }
    @Test fun descriptorStrictFieldsVersionAndSize() {
        val f = Fixture("descriptor"); val d = f.prepare()
        assertFails { PreparedApplicationDescriptor.fromJson(JsonValue.Object(d.toJson().values + ("extra" to true.json()))) }
        assertFails { PreparedApplicationDescriptor.fromJson(JsonValue.Object(d.toJson().values + ("schemaVersion" to 2.json()))) }
        assertFails { d.copy(runId = "x".repeat(8192)) }
        assertFails { d.copy(actionId = "app:jvm:application\n") }
        assertFails { d.copy(runId = "run\u0000") }
        assertFails { validatePreparedApplication(f.record, d.copy(runId = "other"), f.run) }
        assertFails { validatePreparedApplication(f.record, d.copy(verification = identity), f.run) }
        assertFails { validatePreparedApplication(f.record, d.copy(outputs = identity), f.run) }
        f.replace("${f.run}/prepared.json", (d.toJson().canonical() + "\n").encodeToByteArray())
        assertFails { validatePreparedApplication(f.record, d, f.run) }
    }
    @Test fun failedStaleMultipleAndMissingTerminalsCannotPrepare() {
        val f = Fixture("proof")
        val failed = f.record.copy(status = VerificationStatus.FAILED)
        val stale = f.record.copy(status = VerificationStatus.INCOMPLETE, freshnessAtCompletion = Freshness.STALE)
        val multiple = f.record.copy(requestedChecks = setOf(f.terminal.actionId, "other:jvm:application"), completedChecks = setOf(f.terminal.actionId, "other:jvm:application"))
        val missing = f.record.copy(actions = listOf(f.terminal.copy(actionId = "other:jvm:application")))
        val duplicate = f.record.copy(actions = listOf(f.terminal, f.terminal))
        for (record in listOf(failed, stale, multiple, missing, duplicate)) assertFails { materializePreparedApplication(record, f.content, f.run) }
        assertNull(NativeFiles.info("${f.run}/prepared")); assertNull(NativeFiles.info("${f.run}/prepared.json"))
    }
    @Test fun exactBundleShapeAndMetadataModeRequired() {
        val f = Fixture("shape"); val files = f.terminal.outputs.files
        val metadata = files.single { it.path.value == "application/launch.json" }
        val invalid = listOf(files.filter { it != metadata }, files + FileEntry(LogicalPath("unrelated.txt"), f.input.digest),
            files.map { if (it == metadata) it.copy(executable = true) else it }, listOf(metadata),
            files.map { if (it != metadata) it.copy(path = LogicalPath("application/input")) else it })
        for (items in invalid) assertFails { materializePreparedApplication(f.withOutputs(items), f.content, f.run) }
        assertNull(NativeFiles.info("${f.run}/prepared"))
    }
    @Test fun metadataScopeCanonicalBytesAndKeyAreVerified() {
        val f = Fixture("keys")
        fun altered(bytes: ByteArray) = f.withOutputs(f.terminal.outputs.files.map {
            if (it.path.value == "application/launch.json") it.copy(digest = f.content.put(bytes)) else it })
        for (record in listOf(altered(f.launch.copy(module = "other").toJson().canonical().encodeToByteArray()),
            altered((f.launch.toJson().canonical() + "\n").encodeToByteArray()), altered("{".encodeToByteArray()),
            f.record.copy(composition = Sha256.digest("other".encodeToByteArray())),
            f.record.copy(toolchains = mapOf("jvm:compiler" to Sha256.digest("other".encodeToByteArray()))),
            f.record.copy(actions = listOf(f.terminal.copy(key = identity))))) {
            assertFails { materializePreparedApplication(record, f.content, f.run) }
        }
        assertNull(NativeFiles.info("${f.run}/prepared"))
    }
    @Test fun missingAndCorruptCasNeverPublishHandoff() {
        for (corrupt in listOf(false, true)) {
            val f = Fixture("cas-$corrupt")
            val blob = f.content.blobPath(f.input.digest)
            NativeFiles.removeEntry(blob)
            if (corrupt) NativeFiles.writeNew(blob, ByteArray(f.input.digest.sizeBytes.toInt()) { 7 })
            assertFails { f.prepare() }
            assertNull(NativeFiles.info("${f.run}/prepared")); assertNull(NativeFiles.info("${f.run}/prepared.json"))
        }
    }
    @Test fun materializedCorruptionMissingExtraFilesAndDirectoriesReject() {
        for (kind in listOf("corrupt", "missing", "extra", "empty-directory")) {
            val f = Fixture("disk-$kind"); val d = f.prepare()
            val path = "${f.run}/prepared/application/input/${f.input.path.value}"
            when (kind) {
                "corrupt" -> f.replace(path, ByteArray(f.input.digest.sizeBytes.toInt()) { 8 })
                "missing" -> NativeFiles.removeEntry(path)
                "extra" -> NativeFiles.writeNew("${f.run}/prepared/extra", byteArrayOf(1))
                else -> NativeFiles.ensureDirectory("${f.run}/prepared/empty")
            }
            assertFails { validatePreparedApplication(f.record, d, f.run) }
        }
    }
    @Test fun symlinkAndSpecialFilesRejectWithoutFollowingOrBlocking() {
        for (kind in listOf("file-link", "directory-link", "fifo", "descriptor-link")) {
            val f = Fixture("links-$kind"); val d = f.prepare()
            val file = "${f.run}/prepared/application/input/${f.input.path.value}"
            when (kind) {
                "file-link" -> { NativeFiles.removeEntry(file); assertEquals(0, symlink(f.content.blobPath(f.input.digest), file)) }
                "directory-link" -> {
                    NativeFiles.removeTree("${f.run}/prepared/application/input/classes")
                    assertEquals(0, symlink("${f.root}/cas", "${f.run}/prepared/application/input/classes"))
                }
                "fifo" -> { NativeFiles.removeEntry(file); assertEquals(0, mkfifo(file, 384u)) }
                else -> { NativeFiles.removeEntry("${f.run}/prepared.json"); assertEquals(0, symlink("${f.root}/not-there", "${f.run}/prepared.json")) }
            }
            assertFails { validatePreparedApplication(f.record, d, f.run) }
        }
    }
    @Test fun existingDestinationsAreNeverOverwritten() {
        val f = Fixture("existing"); val d = f.prepare()
        assertFails { f.prepare() }
        assertEquals(f.launch, validatePreparedApplication(f.record, d, f.run))
        val other = Fixture("existing-descriptor")
        NativeFiles.writeNew("${other.run}/prepared.json", "unrelated".encodeToByteArray())
        assertFails { other.prepare() }
        assertNull(NativeFiles.info("${other.run}/prepared"))
        assertEquals("unrelated", NativeFiles.read("${other.run}/prepared.json", 100).decodeToString())
    }
    @Test fun mutatedCallerRecordIsRejectedAndObservedRecordIsDetached() {
        val f = Fixture("mutable")
        val actions = f.record.actions.toMutableList()
        val mutable = f.record.copy(actions = actions)
        val store = object : ContentStore {
            override fun put(bytes: ByteArray) = error("Preparation must not write CAS")
            override fun get(digest: Digest): ByteArray? { actions.clear(); return f.content.get(digest) }
        }
        val descriptor = materializePreparedApplication(mutable, store, f.run)
        assertEquals(f.launch, validatePreparedApplication(f.record, descriptor, f.run))
        assertFails { validatePreparedApplication(mutable, descriptor, f.run) }
    }
    @Test fun changedRecordRunAndOutputBindingsReject() {
        val f = Fixture("changed"); val d = f.prepare()
        for (record in listOf(f.record.copy(runId = "new"), f.record.copy(configuration = Sha256.digest("new".encodeToByteArray())),
            f.record.copy(actions = listOf(f.terminal.copy(outputs = TreeManifest(f.terminal.outputs.files.drop(1))))))) {
            assertFails { validatePreparedApplication(record, d, f.run) }
        }
    }
    @Test fun configuredArtifactsAbove64MiBArePreserved() {
        val f = Fixture("large")
        val content = NativeContentStore("${f.root}/large-cas", maxBlobBytes = 80 * 1024 * 1024)
        val large = FileEntry(LogicalPath("runtime/large.jar"), content.put(ByteArray(65 * 1024 * 1024) { (it % 251).toByte() }))
        val launch = f.launch.copy(classpath = listOf(large.path))
        val action = applicationBundleAction(launch, TreeManifest(listOf(large)), identity, mapOf("compiler" to identity))
        val result = ApplicationBundleExecutor(content).start("run", f.terminal.actionId, action).use { handle ->
            assertNull(handle.poll()); assertNotNull(handle.poll())
        }
        val record = f.record.copy(actions = listOf(result))
        val descriptor = materializePreparedApplication(record, content, f.run)
        assertEquals(launch, validatePreparedApplication(record, descriptor, f.run))
        assertEquals(large.digest.sizeBytes, NativeFiles.info("${f.run}/prepared/application/input/runtime/large.jar")!!.size)
    }

}
