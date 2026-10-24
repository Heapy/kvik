@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.projectsnapshot

import kvik.cache.NativeFiles
import kvik.core.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.*

private class Store : ContentStore {
    val blobs = mutableMapOf<Digest, ByteArray>()
    var puts = 0
    var onPut: ((ByteArray) -> Unit)? = null
    override fun put(bytes: ByteArray): Digest {
        puts++
        onPut?.invoke(bytes)
        return Sha256.digest(bytes).also { blobs[it] = bytes.copyOf() }
    }
    override fun get(digest: Digest) = blobs[digest]?.copyOf()
}

private fun write(path: String, data: String) {
    chmod(path, 384u)
    val file = fopen(path, "wb") ?: error("Cannot write $path")
    try { val bytes = data.encodeToByteArray(); if (bytes.isNotEmpty()) bytes.usePinned { check(fwrite(it.addressOf(0), 1u, bytes.size.toULong(), file) == bytes.size.toULong()) } }
    finally { fclose(file) }
}
private fun fixedTime(path: String) = memScoped {
    val times = allocArray<timeval>(2)
    for (i in 0..1) { times[i].tv_sec = 1_234_567_890; times[i].tv_usec = 0 }
    check(utimes(path, times) == 0)
}
private fun document(roots: List<String> = listOf("src", "absent"), plugin: String? = "plugin/options.json"): String = obj(
    "schemaVersion" to 1.json(), "name" to "snapshot".json(),
    "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
    "modules" to listOf(obj("id" to "library".json(), "sourceSets" to listOf(obj("name" to "main".json(),
        "directories" to roots.map { it.json() }.json(), "resources" to listOf("resources".json()).json(), "dependsOn" to emptyList<JsonValue>().json(),
        "dependencies" to emptyList<JsonValue>().json())).json(), "targets" to listOf(obj("name" to "jvm".json(), "main" to "main".json(),
        "test" to JsonValue.Null, "compilerOptions" to emptyList<JsonValue>().json())).json(), "compilerPlugins" to listOfNotNull(plugin?.let {
            obj("coordinate" to "example:plugin:1".json(), "targets" to listOf("jvm".json()).json(), "scopes" to listOf("main".json()).json(),
                "options" to emptyList<JsonValue>().json(), "inputs" to listOf(it.json()).json()) }).json())).json()).canonical()

private class Fixture {
    val root = NativeFiles.tempDirectory("/private/tmp")
    val external = NativeFiles.tempDirectory("/private/tmp")
    val lock = "$external/shared.lock.json"
    val store = Store()
    val snapshotter = NativeProjectSnapshotter(root, store)
    init {
        listOf("src", "resources", "plugin", ".kvik/generated").forEach { NativeFiles.ensureDirectory("$root/$it") }
        write("$root/kvik.json", document()); write(lock, "exact external lock bytes\n")
        write("$root/src/A.kt", "fun a() = 1\n"); write("$root/resources/data.txt", "resource")
        write("$root/plugin/options.json", "{}"); write("$root/.kvik/generated/unrelated", "ignored")
    }
    fun capture(snapshotter: NativeProjectSnapshotter = this.snapshotter) = (snapshotter.capture(LogicalPath("kvik.json"), lock) as SnapshotCapture.Captured).snapshot
    fun close() { NativeFiles.removeTree(root); NativeFiles.removeTree(external) }
}
private inline fun fixture(block: (Fixture) -> Unit) { val f = Fixture(); try { block(f) } finally { f.close() } }

class ProjectSnapshotTest {
    @Test fun abiChecksCaptureBaselinesWhileDumpAndBuildCanCreateTheFirstCandidate() = fixture { f ->
        write("${f.root}/kvik.json", document().replace("\"compilerOptions\":[]",
            "\"compilerOptions\":[],\"abi\":{\"baseline\":\"api/library.jvm.api\"}"))
        val build = f.capture()
        assertFalse(build.inputs.files.any { it.path.value.startsWith("api/") })
        val selection = SnapshotSelection.AbiCheck(setOf("library"), setOf("jvm"))
        assertEquals(selection, SnapshotSelection.fromJson(selection.toJson()))
        assertFails { f.snapshotter.capture(LogicalPath("kvik.json"), f.lock, selection = selection) }
        NativeFiles.ensureDirectory("${f.root}/api")
        write("${f.root}/api/library.jvm.api", "public class Library\n")
        val checked = assertIs<SnapshotCapture.Captured>(f.snapshotter.capture(LogicalPath("kvik.json"), f.lock, selection = selection)).snapshot
        assertTrue(checked.inputs.files.any { it.path.value == "api/library.jvm.api" })
        write("${f.root}/api/library.jvm.api", "public class Changed\n")
        assertEquals(Freshness.STALE, f.snapshotter.freshness(checked))
        assertEquals(Freshness.CURRENT, f.snapshotter.freshness(build))
        assertFails { SnapshotSelection.fromJson(obj("kind" to "project".json(), "modules" to listOf("library".json()).json())) }
        assertFails { SnapshotSelection.fromJson(obj("kind" to "abi-check".json(),
            "modules" to listOf("library".json(), "library".json()).json(), "targets" to listOf("jvm".json()).json())) }
    }

    @Test fun changedPreflightConfigurationNeverPublishesInputs() = fixture { f ->
        val expected = Sha256.digest(NativeFiles.read("${f.root}/kvik.json", 4096))
        write("${f.root}/kvik.json", document(listOf("src", "new-store-input")))
        val result = f.snapshotter.capture(LogicalPath("kvik.json"), f.lock, expected)
        assertIs<SnapshotCapture.Indeterminate>(result)
        assertTrue(result.reason.contains("preflight"))
        assertEquals(0, f.store.puts)
        assertNull(f.snapshotter.currentFingerprint(LogicalPath("kvik.json"), f.lock, expected))
        val accepted = f.snapshotter.capture(LogicalPath("kvik.json"), f.lock,
            Sha256.digest(NativeFiles.read("${f.root}/kvik.json", 4096)))
        assertIs<SnapshotCapture.Captured>(accepted)
        assertTrue(f.store.puts > 0)
    }

    @Test fun exactBytesAndDeclaredInputsAreStoredAndFreshnessNeverPublishes() = fixture { f ->
        val snapshot = f.capture()
        assertEquals(setOf("src/A.kt", "resources/data.txt", "plugin/options.json"), snapshot.inputs.files.map { it.path.value }.toSet())
        assertEquals(NativeFiles.read("${f.root}/kvik.json", 4096).toList(), snapshot.configurationBytes.toList())
        assertEquals(NativeFiles.read(f.lock, 4096).toList(), snapshot.lockBytes.toList())
        assertEquals("snapshot", snapshot.configuration.project.name)
        snapshot.inputs.files.forEach { assertEquals(it.digest, Sha256.digest(f.store.get(it.digest)!!)) }
        assertTrue(f.store.blobs.containsKey(Sha256.digest(snapshot.configurationBytes)))
        assertTrue(f.store.blobs.containsKey(Sha256.digest(snapshot.lockBytes)))
        val puts = f.store.puts
        write("${f.root}/.kvik/generated/unrelated", "changed"); write("${f.root}/unrelated", "new")
        assertEquals(Freshness.CURRENT, f.snapshotter.freshness(snapshot))
        assertEquals(snapshot.fingerprint, f.snapshotter.currentFingerprint(LogicalPath("kvik.json"), f.lock))
        val inaccessible = object : ContentStore {
            override fun get(digest: Digest): ByteArray? = error("Freshness must not read CAS")
            override fun put(bytes: ByteArray): Digest = error("Freshness must not write CAS")
        }
        assertEquals(snapshot.fingerprint, NativeProjectSnapshotter(f.root, inaccessible).currentFingerprint(LogicalPath("kvik.json"), f.lock))
        assertEquals(puts, f.store.puts)
        val copy = snapshot.configurationBytes; copy[0] = 0
        assertEquals(Freshness.CURRENT, f.snapshotter.freshness(snapshot))
    }

    @Test fun preservedTimestampEditsAndExecutableModeChangeInvalidate() = fixture { f ->
        val source = "${f.root}/src/A.kt"
        fixedTime(source)
        val before = f.capture()
        write(source, "fun a() = 2\n"); fixedTime(source)
        assertEquals(Freshness.STALE, f.snapshotter.freshness(before))
        val changed = f.capture()
        check(chmod(source, 448u) == 0)
        assertEquals(Freshness.STALE, f.snapshotter.freshness(changed))
    }

    @Test fun addDeleteRenameAndMissingEmptyDirectoriesInvalidate() = fixture { f ->
        var before = f.capture()
        NativeFiles.ensureDirectory("${f.root}/absent")
        assertEquals(Freshness.STALE, f.snapshotter.freshness(before))
        before = f.capture(); NativeFiles.ensureDirectory("${f.root}/src/empty")
        assertEquals(Freshness.STALE, f.snapshotter.freshness(before))
        before = f.capture(); write("${f.root}/src/New.kt", "new")
        assertEquals(Freshness.STALE, f.snapshotter.freshness(before))
        before = f.capture(); check(rename("${f.root}/src/New.kt", "${f.root}/src/Renamed.kt") == 0)
        assertEquals(Freshness.STALE, f.snapshotter.freshness(before))
        before = f.capture(); check(unlink("${f.root}/src/Renamed.kt") == 0)
        assertEquals(Freshness.STALE, f.snapshotter.freshness(before))
        before = f.capture(); check(rmdir("${f.root}/src/empty") == 0)
        assertEquals(Freshness.STALE, f.snapshotter.freshness(before))
    }

    @Test fun configurationWhitespaceLockAndPluginBytesAreIdentityInputs() = fixture { f ->
        var before = f.capture()
        write("${f.root}/kvik.json", document() + "\n")
        assertEquals(Freshness.STALE, f.snapshotter.freshness(before))
        before = f.capture(); write(f.lock, "changed lock")
        assertEquals(Freshness.STALE, f.snapshotter.freshness(before))
        before = f.capture(); write("${f.root}/plugin/options.json", "{ }")
        assertEquals(Freshness.STALE, f.snapshotter.freshness(before))
        before = f.capture(); write("${f.root}/kvik.json", "invalid")
        assertEquals(Freshness.INDETERMINATE, f.snapshotter.freshness(before))
    }

    @Test fun linksSpecialFilesAndAliasesAreRejected() = fixture { f ->
        val link = "${f.root}/src/link"
        check(symlink(f.lock, link) == 0); assertFailsWith<SnapshotFailure> { f.capture() }; unlink(link)
        check(mkfifo(link, 384u) == 0); assertFailsWith<SnapshotFailure> { f.capture() }; unlink(link)
        check(platform.posix.link("${f.root}/src/A.kt", link) == 0); assertFailsWith<SnapshotFailure> { f.capture() }; unlink(link)
        write("${f.root}/kvik.json", document(listOf("src", "SRC")))
        assertFailsWith<SnapshotFailure> { f.capture() }
        write("${f.root}/kvik.json", document())
        check(unlink(f.lock) == 0); check(platform.posix.link("${f.root}/kvik.json", f.lock) == 0)
        assertFailsWith<SnapshotFailure> { f.capture() }
    }

    @Test fun declaredNestedLinksAndGeneratedPathsAreRejected() = fixture { f ->
        check(symlink("src", "${f.root}/alias") == 0)
        write("${f.root}/kvik.json", document(listOf("alias/nested")))
        assertFailsWith<SnapshotFailure> { f.capture() }
        write("${f.root}/kvik.json", document(listOf(".kvik/generated")))
        assertFailsWith<SnapshotFailure> { f.capture() }
        write("${f.root}/kvik.json", document())
        NativeFiles.ensureDirectory("${f.root}/src/.kvik")
        assertFailsWith<SnapshotFailure> { f.capture() }
    }

    @Test fun requiredPluginFilesAndExternalLockAncestorsFailClosed() = fixture { f ->
        check(unlink("${f.root}/plugin/options.json") == 0)
        assertFailsWith<SnapshotFailure> { f.capture() }
        write("${f.root}/plugin/options.json", "{}")
        check(symlink(f.external, "${f.root}/lock-alias") == 0)
        assertFailsWith<SnapshotFailure> { f.snapshotter.capture(LogicalPath("kvik.json"), "${f.root}/lock-alias/shared.lock.json") }
        val before = f.capture()
        check(unlink(f.lock) == 0)
        assertEquals(Freshness.INDETERMINATE, f.snapshotter.freshness(before))
    }

    @Test fun observedMutationReturnsIndeterminateInsteadOfMixedSnapshot() = fixture { f ->
        f.store.onPut = { bytes -> if (bytes.decodeToString() == "fun a() = 1\n") write("${f.root}/src/A.kt", "fun a() = 2\n") }
        assertIs<SnapshotCapture.Indeterminate>(f.snapshotter.capture(LogicalPath("kvik.json"), f.lock))
        f.store.onPut = null
        assertEquals(Freshness.CURRENT, f.snapshotter.freshness(f.capture()))
        f.store.onPut = { bytes -> if (bytes.decodeToString() == "fun a() = 2\n") write("${f.root}/resources/Added.txt", "added during capture") }
        assertIs<SnapshotCapture.Indeterminate>(f.snapshotter.capture(LogicalPath("kvik.json"), f.lock))
        f.store.onPut = { bytes -> if (bytes.decodeToString() == document()) write("${f.root}/kvik.json", document() + "\n") }
        assertIs<SnapshotCapture.Indeterminate>(f.snapshotter.capture(LogicalPath("kvik.json"), f.lock))
    }

    @Test fun fileDirectoryDepthEntryAndByteBoundsFailClosed() = fixture { f ->
        val configurations = listOf(SnapshotLimits(maxFiles = 2), SnapshotLimits(maxDirectories = 1), SnapshotLimits(maxEntries = 1),
            SnapshotLimits(maxFileBytes = 10), SnapshotLimits(maxTotalBytes = 10))
        for (limits in configurations) assertFailsWith<SnapshotFailure> { f.capture(NativeProjectSnapshotter(f.root, f.store, limits)) }
        write("${f.root}/kvik.json", document(plugin = null))
        // Directories have one component, but their files have two. No nested directory may
        // accidentally be the reason this regression rejects a too-deep file.
        val fileDepth = assertFailsWith<SnapshotFailure> { f.capture(NativeProjectSnapshotter(f.root, f.store, SnapshotLimits(maxDepth = 1))) }
        assertTrue(fileDepth.message!!.startsWith("Input file path exceeds depth limit:"))
        assertIs<SnapshotCapture.Captured>(NativeProjectSnapshotter(f.root, f.store, SnapshotLimits(maxDepth = 2)).capture(LogicalPath("kvik.json"), f.lock))
        NativeFiles.ensureDirectory("${f.root}/src/a/b")
        assertFailsWith<SnapshotFailure> { f.capture(NativeProjectSnapshotter(f.root, f.store, SnapshotLimits(maxDepth = 1))) }
    }
}
