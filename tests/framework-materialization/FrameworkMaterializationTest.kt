package kvik.cli

import kotlin.test.*
import kvik.core.*
import kvik.cache.*

private class FrameworkFixture : AutoCloseable {
    val root = NativeFiles.tempDirectory("/private/tmp")
    val content = NativeContentStore("$root/store")
    val run = "$root/run".also { NativeFiles.ensureDirectory(it) }
    val identity = Sha256.digest("identity".encodeToByteArray())
    val selections = listOf(FrameworkSelection("library", "iosArm64", NativeFrameworkSpec("Device", false)),
        FrameworkSelection("library", "iosSimulatorArm64", NativeFrameworkSpec("Simulator", true)))
    val outputs = selections.map { selection ->
        val base = selection.specification.baseName
        val paths = listOf("$base.framework/$base", "$base.framework/Headers/$base.h", "$base.framework/Modules/module.modulemap", "$base.framework/Info.plist") +
            if (selection.specification.isStatic) emptyList() else listOf("$base.framework.dSYM/Contents/Info.plist", "$base.framework.dSYM/Contents/Resources/DWARF/$base")
        TreeManifest(paths.map { path -> FileEntry(LogicalPath(path), content.put(path.encodeToByteArray()),
            path == "$base.framework/$base" && !selection.specification.isStatic) })
    }
    val actions = selections.zip(outputs).map { (selection, output) -> ActionResult(selection.actionId, identity, ActionStatus.LOCAL_HIT, output, 0) }
    val record = VerificationRecord("run", selections.map { it.check }.toSet(), selections.map { it.check }.toSet(),
        identity, identity, identity, identity, emptyMap(), actions, emptyList(), TestCounts(0, 0, 0, 0, true),
        VerificationStatus.PASSED, Freshness.CURRENT, listOf("kvik", "build"))
    fun expose(record: VerificationRecord = this.record, store: ContentStore = content, gate: () -> Boolean = { true }) =
        materializeFrameworks(record, selections, store, run, gate)
    fun assertUnexposed() {
        assertNull(NativeFiles.info("$run/frameworks"))
        assertTrue(NativeFiles.names(run).isEmpty(), "Temporary staging must be removed")
    }
    override fun close() { NativeFiles.removeTree(root) }
}

class FrameworkMaterializationTest {
    @Test fun mixedTargetBundlesBecomeVisibleTogetherWithExactBytesModesAndEventPaths() = FrameworkFixture().use { f ->
        var calls = 0
        assertTrue(f.expose {
            calls++
            assertNull(NativeFiles.info("${f.run}/frameworks"), "Final path must remain absent while validating freshness")
            true
        })
        assertEquals(1, calls)
        assertEquals(listOf("frameworks"), NativeFiles.names(f.run))
        for ((selection, output) in f.selections.zip(f.outputs)) {
            val directory = "${f.run}/frameworks/${selection.module}/${selection.target}"
            val actual = NativeOutputTrees(object : ContentStore {
                override fun get(digest: Digest): ByteArray? = error("No reads")
                override fun put(bytes: ByteArray) = Sha256.digest(bytes)
            }).capture(directory)
            assertEquals(output.toJson(), actual.toJson())
            val event = selection.event(f.run).values
            assertEquals(directory, event.getValue("directory").stringValue())
            assertEquals("$directory/${selection.specification.baseName}.framework", event.getValue("framework").stringValue())
            assertEquals(selection.specification.isStatic, event.getValue("dSYM") == JsonValue.Null)
        }
    }

    @Test fun cancellationOrStaleFreshnessVetoLeavesNoBundleOrPendingDirectory() = FrameworkFixture().use { f ->
        var calls = 0
        assertFalse(f.expose { calls++; false })
        assertEquals(1, calls)
        f.assertUnexposed()
        // A refused preparation must not leave a destination that prevents a later valid attempt.
        assertTrue(f.expose())
    }

    @Test fun missingOrCorruptSecondTargetBlobCannotExposeFirstTarget() {
        for (corrupt in listOf(false, true)) FrameworkFixture().use { f ->
            val entry = f.outputs.last().files.last()
            val path = f.content.blobPath(entry.digest)
            NativeFiles.removeEntry(path)
            if (corrupt) NativeFiles.writeNew(path, ByteArray(entry.digest.sizeBytes.toInt()) { 1 })
            var called = false
            assertFails { f.expose { called = true; true } }
            assertFalse(called)
            f.assertUnexposed()
        }
    }

    @Test fun dishonestContentStoreIsRehashedBeforeExposure() = FrameworkFixture().use { f ->
        val bad = object : ContentStore {
            override fun put(bytes: ByteArray): Digest = error("No cache writes")
            override fun get(digest: Digest) = byteArrayOf(0)
        }
        assertFails { f.expose(store = bad) }
        f.assertUnexposed()
    }

    @Test fun preexistingDestinationCannotBeOverwrittenAndStagingIsRemoved() = FrameworkFixture().use { f ->
        NativeFiles.ensureDirectory("${f.run}/frameworks")
        NativeFiles.writeNew("${f.run}/frameworks/kept", "original".encodeToByteArray())
        assertFails { f.expose() }
        assertEquals(listOf("frameworks"), NativeFiles.names(f.run))
        assertEquals("original", NativeFiles.read("${f.run}/frameworks/kept", 100).decodeToString())
        assertEquals(listOf("kept"), NativeFiles.names("${f.run}/frameworks"))
    }

    @Test fun incompleteFailedOrWrongScopedResultsCannotReachMaterialization() = FrameworkFixture().use { f ->
        val invalid = listOf(f.record.copy(status = VerificationStatus.FAILED),
            f.record.copy(completedChecks = setOf(f.selections.first().check)),
            f.record.copy(actions = f.actions.map { it.copy(status = ActionStatus.FAILED) }),
            f.record.copy(actions = f.actions.drop(1)),
            f.record.copy(actions = f.actions.map { it.copy(outputs = TreeManifest(emptyList())) }))
        for (record in invalid) {
            assertFails { f.expose(record) }
            f.assertUnexposed()
        }
    }
}
