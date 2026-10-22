@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.application.tests

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.*
import kvik.application.*
import kvik.actioncache.LocalCachingActionExecutor
import kvik.cache.*
import kvik.core.*

private val identity = Sha256.digest("composition".encodeToByteArray())
private fun launch(arguments: List<String> = emptyList(), environment: Map<String, String> = emptyMap()) =
    ApplicationLaunch("app", "jvm", ApplicationSpec("fixture.MainKt", arguments, environment),
        listOf(LogicalPath("classes"), LogicalPath("runtime/dependency.jar")))
private class Fixture(name: String) {
    val root = checkNotNull(getenv("KVIK_APPLICATION_TEST_ROOT")).toKString() + "/" + name
    val content = NativeContentStore("$root/content")
    val files = listOf(
        FileEntry(LogicalPath("classes/fixture/MainKt.class"), content.put(byteArrayOf(0, 1, 2))),
        FileEntry(LogicalPath("classes/message.txt"), content.put("hello resource".encodeToByteArray())),
        FileEntry(LogicalPath("runtime/dependency.jar"), content.put(byteArrayOf(3, 4, 5))),
    )
    fun action(value: ApplicationLaunch = launch(), inputs: List<FileEntry> = files) =
        applicationBundleAction(value, TreeManifest(inputs), identity, mapOf("jdk" to identity))
}
private fun complete(executor: ActionExecutor, action: ActionSpec, id: String = "app:package"): ActionResult =
    executor.start("run", id, action).use { running ->
        repeat(action.inputs.files.size + 2) { running.poll()?.let { return it } }
        error("Bundle did not complete within its bounded poll count")
    }
private class ObservedStore(private val actual: ContentStore) : ContentStore {
    var gets = 0
    var puts = 0
    var getOverride: ((Digest) -> ByteArray?)? = null
    var putOverride: ((ByteArray) -> Digest)? = null
    override fun get(digest: Digest): ByteArray? { gets++; return getOverride?.invoke(digest) ?: actual.get(digest) }
    override fun put(bytes: ByteArray): Digest { puts++; return putOverride?.invoke(bytes) ?: actual.put(bytes) }
}

class ApplicationBundleTest {
    @Test fun jvmBundleHasExactPortableMetadataAndRuntimeInputs() {
        val fixture = Fixture("jvm")
        val value = launch(listOf("space value", "$(literal)", ""), mapOf("VALUE" to "line\nvalue"))
        val action = fixture.action(value)
        assertEquals(ActionKind.PACKAGE, action.kind)
        assertEquals(CachePolicy.LOCAL, action.cachePolicy)
        assertEquals(emptyList(), action.arguments)
        assertEquals(emptyMap(), action.environment)
        val store = ObservedStore(fixture.content)
        val executor = ApplicationBundleExecutor(store)
        executor.start("run", "current-id", action).use { running ->
            assertEquals(0, store.gets)
            repeat(fixture.files.size) { assertNull(running.poll()); assertEquals(it + 1, store.gets) }
            val result = assertNotNull(running.poll())
            assertEquals(ActionStatus.EXECUTED, result.status)
            assertEquals("current-id", result.actionId)
            assertEquals(action.key(Sha256), result.key)
            assertEquals(1, store.puts)
            assertEquals(fixture.files.map { it.copy(path = LogicalPath("application/input/${it.path.value}")) }.toSet(),
                result.outputs.files.filter { it.path.value != "application/launch.json" }.toSet())
            val metadata = result.outputs.files.single { it.path.value == "application/launch.json" }
            assertFalse(metadata.executable)
            assertEquals(value.toJson().canonical(), fixture.content.get(metadata.digest)!!.decodeToString())
            NativeOutputTrees(fixture.content).materialize(result.outputs, "${fixture.root}/restored")
            assertEquals("hello resource", NativeFiles.read("${fixture.root}/restored/application/input/classes/message.txt", 100).decodeToString())
        }
    }

    @Test fun nativeBundlePreservesExecutableAndDoesNotRunIt() {
        val fixture = Fixture("native")
        // Deliberately not a valid program: packaging must never execute or inspect program syntax.
        val executable = FileEntry(LogicalPath("bin/application.kexe"), fixture.content.put("not executable code".encodeToByteArray()), true)
        val value = ApplicationLaunch("nativeApp", "macosArm64", ApplicationSpec("fixture.main"), executable = executable.path)
        val action = fixture.action(value, listOf(executable, fixture.files[1]))
        val result = complete(ApplicationBundleExecutor(fixture.content), action)
        assertEquals(ActionStatus.EXECUTED, result.status)
        assertTrue(result.outputs.files.single { it.path.value.endsWith("application.kexe") }.executable)
        assertEquals(value.toJson(), parseJson(fixture.content.get(result.outputs.files.single { it.path.value == "application/launch.json" }.digest)!!.decodeToString()))
    }

    @Test fun actionIdentityBindsSettingsClosureCompositionAndTools() {
        val fixture = Fixture("keys")
        val baseline = fixture.action()
        val key = baseline.key(Sha256)
        val changes = listOf(
            fixture.action(launch(listOf("new"))), fixture.action(launch(environment = mapOf("VALUE" to "new"))),
            fixture.action(launch().copy(classpath = launch().classpath.reversed())),
            fixture.action(inputs = fixture.files.mapIndexed { index, file -> if (index == 1) file.copy(digest = identity) else file }),
            fixture.action(inputs = fixture.files.mapIndexed { index, file -> if (index == 1) file.copy(executable = true) else file }),
            applicationBundleAction(launch(), baseline.inputs, Sha256.digest(byteArrayOf(1)), baseline.toolchains),
            applicationBundleAction(launch(), baseline.inputs, identity, mapOf("jdk" to Sha256.digest(byteArrayOf(2)))),
        )
        changes.forEach { assertNotEquals(key, it.key(Sha256)) }
        assertEquals(key, fixture.action(inputs = fixture.files.reversed()).key(Sha256))
    }

    @Test fun undeclaredClasspathAndNonExecutableNativeInputsFailBeforeReads() {
        val fixture = Fixture("invalid-inputs")
        assertFails { fixture.action(inputs = fixture.files.dropLast(1)) }
        assertFails { fixture.action(launch().copy(classpath = listOf(LogicalPath("absent")))) }
        val native = ApplicationLaunch("app", "macosArm64", ApplicationSpec("fixture.main"), executable = fixture.files[0].path)
        assertFails { fixture.action(native) }
        assertFails { applicationBundleAction(launch(), TreeManifest(fixture.files), identity, emptyMap()) }
    }

    @Test fun exactTypedActionRejectsTamperedFieldsBeforeCasAccess() {
        val fixture = Fixture("tampered")
        val action = fixture.action()
        val store = ObservedStore(fixture.content)
        val executor = ApplicationBundleExecutor(store)
        val variants = listOf(
            action.copy(implementation = "other"), action.copy(kind = ActionKind.COMPILE),
            action.copy(platform = mapOf("os" to "host")), action.copy(target = "macosArm64"),
            action.copy(outputs = listOf(LogicalPath("different"))), action.copy(arguments = listOf("execute")),
            action.copy(environment = mapOf("PATH" to "/host")), action.copy(timeoutMillis = 1),
            action.copy(cachePolicy = CachePolicy.REMOTE),
            action.copy(parameters = JsonValue.Object(action.parameters.values + ("unknown" to true.json()))),
        )
        for (variant in variants) assertFails { executor.start("run", "id", variant) }
        assertEquals(0, store.gets)
        assertEquals(0, store.puts)
    }

    @Test fun actualMissingAndSameSizeCorruptBlobsProduceNoOutputs() {
        for (corrupt in listOf(false, true)) {
            val fixture = Fixture("bad-blob-$corrupt")
            val action = fixture.action()
            val file = fixture.files[0]
            NativeFiles.removeEntry(fixture.content.blobPath(file.digest))
            if (corrupt) NativeFiles.writeNew(fixture.content.blobPath(file.digest), byteArrayOf(9, 9, 9))
            val store = ObservedStore(fixture.content)
            val result = complete(ApplicationBundleExecutor(store), action)
            assertEquals(ActionStatus.FAILED, result.status)
            assertEquals(emptyList(), result.outputs.files)
            assertEquals(0, store.puts)
            assertNotNull(result.diagnostic)
        }
    }

    @Test fun untrustedStoreCannotLieAboutInputOrMetadata() {
        for (mode in listOf("input", "put", "readback")) {
            val fixture = Fixture("lying-$mode")
            val store = ObservedStore(fixture.content)
            if (mode == "input") store.getOverride = { byteArrayOf(99) }
            if (mode == "put") store.putOverride = { identity }
            if (mode == "readback") store.getOverride = { if (store.puts > 0) byteArrayOf(99) else fixture.content.get(it) }
            val result = complete(ApplicationBundleExecutor(store), fixture.action())
            assertEquals(ActionStatus.FAILED, result.status)
            assertTrue(result.outputs.files.isEmpty())
        }
    }

    @Test fun storeFailureProducesStableFailedTerminalResult() {
        for (write in listOf(false, true)) {
            val fixture = Fixture("io-$write")
            val store = ObservedStore(fixture.content)
            if (write) store.putOverride = { error("injected publication outage") }
            else store.getOverride = { error("injected read outage") }
            ApplicationBundleExecutor(store).start("run", "id", fixture.action()).use { running ->
                var result: ActionResult? = null
                repeat(fixture.files.size + 1) { if (result == null) result = running.poll() }
                assertEquals(ActionStatus.FAILED, result!!.status)
                assertEquals(result, running.poll())
                running.cancel()
                assertEquals(result, running.poll())
            }
        }
    }

    @Test fun cancelBeforeAndBetweenReadsNeverPublishesMetadata() {
        val fixture = Fixture("cancel")
        for (pollFirst in listOf(false, true)) {
            val store = ObservedStore(fixture.content)
            val running = ApplicationBundleExecutor(store).start("run", "cancelled", fixture.action())
            if (pollFirst) assertNull(running.poll())
            running.cancel()
            val result = assertNotNull(running.poll())
            assertEquals(ActionStatus.CANCELLED, result.status)
            assertEquals(130, result.exitCode)
            assertTrue(result.outputs.files.isEmpty())
            assertEquals(0, store.puts)
            assertEquals(result, running.poll())
            running.close(); running.close()
            assertFails { running.poll() }
        }
    }

    @Test fun mutableCallerAndReturnedManifestsCannotChangeRunningIdentity() {
        val fixture = Fixture("snapshots")
        val args = mutableListOf("original"); val env = mutableMapOf("VALUE" to "original")
        val files = fixture.files.toMutableList(); val tools = mutableMapOf("jdk" to identity)
        val action = applicationBundleAction(launch(args, env), TreeManifest(files), identity, tools)
        val expected = action.key(Sha256)
        args.clear(); env.clear(); files.clear(); tools.clear()
        assertEquals(expected, action.key(Sha256))
        val running = ApplicationBundleExecutor(fixture.content).start("run", "id", action)
        (action.inputs.files as MutableList<FileEntry>).clear()
        var result: ActionResult? = null
        repeat(fixture.files.size + 1) { result = running.poll() ?: result }
        assertEquals(expected, result!!.key)
        (result!!.outputs.files as MutableList<FileEntry>).clear()
        assertEquals(4, running.poll()!!.outputs.files.size)
        running.cancel(); assertEquals(ActionStatus.EXECUTED, running.poll()!!.status)
        running.close()
    }

    @Test fun localCacheReusesBundleButChangedRuntimeSettingsInvalidateIt() {
        val fixture = Fixture("cache")
        val executor = LocalCachingActionExecutor(ApplicationBundleExecutor(fixture.content),
            NativeActionCache("${fixture.root}/actions", fixture.content), fixture.content)
        val action = fixture.action()
        val first = complete(executor, action, "first")
        val second = complete(executor, action, "second")
        assertEquals(ActionStatus.EXECUTED, first.status)
        assertEquals(ActionStatus.LOCAL_HIT, second.status)
        assertEquals("second", second.actionId)
        assertEquals(first.outputs, second.outputs)
        assertEquals(first.key, second.key)
        val changed = complete(executor, fixture.action(launch(listOf("changed"))))
        assertEquals(ActionStatus.EXECUTED, changed.status)
        assertNotEquals(first.key, changed.key)
        assertEquals(first.outputs.files.filter { "/input/" in it.path.value }, changed.outputs.files.filter { "/input/" in it.path.value })
    }

    @Test fun failedAndCancelledPreparationsNeverPublishActionRecords() {
        for (cancel in listOf(false, true)) {
            val fixture = Fixture("uncached-$cancel")
            val action = fixture.action()
            val cache = NativeActionCache("${fixture.root}/actions", fixture.content)
            val executor = LocalCachingActionExecutor(ApplicationBundleExecutor(fixture.content), cache, fixture.content)
            if (!cancel) NativeFiles.removeEntry(fixture.content.blobPath(fixture.files.first().digest))
            executor.start("run", "id", action).use { running ->
                if (cancel) running.cancel()
                val result = assertNotNull(running.poll())
                assertEquals(if (cancel) ActionStatus.CANCELLED else ActionStatus.FAILED, result.status)
                assertTrue(result.outputs.files.isEmpty())
            }
            assertNull(NativeFiles.info(cache.recordPath(action.key(Sha256))))
            assertNull(cache.lookup(action.key(Sha256)))
        }
    }

    @Test fun mutableInvalidMetadataIsRevalidatedAndResourcesAccountForBlobSize() {
        val fixture = Fixture("bounds")
        val args = mutableListOf("valid")
        val value = launch(args)
        args[0] = "invalid\u0000argument"
        assertFails { fixture.action(value) }
        val action = fixture.action()
        val largest = action.inputs.files.maxOf { it.digest.sizeBytes }
        val resources = ApplicationBundleExecutor(fixture.content).resources(action)
        assertTrue(resources.memoryMiB * 1024 * 1024 >= largest * 2)
        assertTrue(resources.exclusive.isEmpty())
    }
}
