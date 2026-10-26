package kvik.projectgraph

import kvik.core.*
import kotlin.test.*

class IdeInputsExecutorTest {
    @Test fun webModelsRejectMixedPlatformsAndPreserveTargetInActionIdentity() {
        val library = LogicalPath("deps/stdlib.klib")
        val main = JvmIdeCompilation("app", "main", emptyList(), emptyList(), listOf(library), emptyList(), false, target = "js")
        val model = IdeInputs("app", listOf(main), "js")
        assertEquals(model, IdeInputs.fromJson(model.toJson()))
        assertFails { main.copy(libraries = listOf(LogicalPath("deps/stdlib.jar"))) }
        assertFails { IdeInputs("app", listOf(main)) }
        assertFails { IdeInputs("app", listOf(main, main.copy(module = "other", target = "wasmJs")), "js") }
        val files = TreeManifest(listOf(FileEntry(library, composition)))
        val js = ideInputsAction(model, files, composition, tools)
        val wasm = ideInputsAction(IdeInputs("app", listOf(main.copy(target = "wasmJs")), "wasmJs"), files, composition, tools)
        assertNotEquals(js.key(Sha256), wasm.key(Sha256))
        assertEquals("js", js.target)
        assertEquals("wasmJs", wasm.target)
    }

    @Test fun sharedModelsValidateRefinementAndFriendBoundaries() {
        val main = JvmIdeCompilation("app", "main", emptyList(), emptyList(), emptyList(), emptyList(), false,
            refines = listOf("app.common.commonMain"))
        val shared = JvmIdeCompilation("app", "main", listOf(IdeInputRoot(LogicalPath("common"), "commonMain", common = true)),
            emptyList(), listOf(LogicalPath("metadata/0.klib")), emptyList(), false, "commonMain", setOf("jvm", "js"))
        val test = shared.copy(compilation = "test", sourceSet = "commonTest", roots = emptyList(), friends = listOf(shared.name))
        val model = IdeInputs("app", listOf(main, shared, test))
        assertEquals(model, IdeInputs.fromJson(model.toJson()))
        assertFails { shared.copy(libraries = listOf(LogicalPath("jvm.jar"))) }
        assertFails { shared.copy(targets = setOf("jvm")) }
        assertFails { shared.copy(roots = listOf(IdeInputRoot(LogicalPath("other"), "other", common = true))) }
        assertFails { IdeInputs("app", listOf(main, shared, test.copy(refines = listOf(shared.name)))) }
        assertFails { IdeInputs("app", listOf(main, shared.copy(refines = listOf(main.name)))) }
        assertFails { IdeInputs("app", listOf(main, shared.copy(targets = setOf("js", "wasmJs")))) }
        assertFails { IdeInputs("app", listOf(main, shared, test.copy(friends = listOf(main.name)))) }
        assertFails { IdeInputs("app", listOf(main, shared, test.copy(friends = listOf("missing")))) }
    }
    @Test fun successfulPreparationHasADistinctProtocolEvent() {
        val event = RunEvent("run", 1, "ide-inputs-ready", obj("directory" to "/run/ide".json()))
        assertEquals("ide-inputs-ready", event.toJson().objectValue().getValue("type").stringValue())
    }
    private class Store : ContentStore {
        val blobs = mutableMapOf<Digest, ByteArray>()
        override fun get(digest: Digest) = blobs[digest]?.copyOf()
        override fun put(bytes: ByteArray): Digest = Sha256.digest(bytes).also { blobs[it] = bytes.copyOf() }
    }
    private val composition = Sha256.digest("runtime".encodeToByteArray())
    private val tools = listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith { composition }
    private fun model() = IdeInputs("app", listOf(JvmIdeCompilation("app", "main", listOf(
        IdeInputRoot(LogicalPath("src"), "main"), IdeInputRoot(LogicalPath("generated/code"), "main", generated = true),
    ), emptyList(), listOf(LogicalPath("deps/library.jar")), emptyList(), false)))
    private fun files(store: Store) = TreeManifest(listOf(
        FileEntry(LogicalPath("deps/library.jar"), store.put("jar".encodeToByteArray())),
        FileEntry(LogicalPath("generated/code/Generated.kt"), store.put("class Generated".encodeToByteArray())),
    ))
    @Test fun packagesVerifiedBytesAndPortableModelWithoutAuthoredSourceCopies() {
        val store = Store()
        val input = files(store)
        val action = ideInputsAction(model(), input, composition, tools)
        val running = IdeInputsExecutor(store).start("run", "app:jvm:ide-inputs", action)
        assertNull(running.poll())
        assertNull(running.poll())
        val result = assertNotNull(running.poll())
        assertTrue(result.successful)
        assertEquals(input.files.map { it.digest }.toSet(), result.outputs.files.filter { it.path.value.startsWith("ide/input/") }.map { it.digest }.toSet())
        val metadata = result.outputs.files.single { it.path.value == "ide/model.json" }
        assertEquals(model(), IdeInputs.fromJson(parseJson(checkNotNull(store.get(metadata.digest)).decodeToString())))
        assertEquals(result, running.poll())
        running.close()
        assertFails { running.poll() }
    }
    @Test fun rejectsMissingCorruptOrUnrelatedInputsAndHonorsCancellation() {
        val store = Store()
        val input = files(store)
        val action = ideInputsAction(model(), input, composition, tools)
        store.blobs[input.files.first().digest] = "corrupt".encodeToByteArray()
        IdeInputsExecutor(store).start("run", "app:jvm:ide-inputs", action).use { running ->
            assertEquals(ActionStatus.FAILED, assertNotNull(running.poll()).status)
        }
        store.blobs.clear()
        IdeInputsExecutor(store).start("run", "app:jvm:ide-inputs", action).use { running ->
            assertEquals(ActionStatus.FAILED, assertNotNull(running.poll()).status)
        }
        IdeInputsExecutor(store).start("run", "app:jvm:ide-inputs", action).use { running ->
            running.cancel()
            assertEquals(ActionStatus.CANCELLED, assertNotNull(running.poll()).status)
        }
        assertFails { ideInputsAction(model(), TreeManifest(input.files + FileEntry(LogicalPath("src/App.kt"), composition)), composition, tools) }
        assertFails { ideInputsAction(model(), TreeManifest(input.files.drop(1)), composition, tools) }
        assertFails { IdeInputsExecutor(store).start("run", "app:jvm:ide-inputs", action.copy(arguments = listOf("unexpected"))) }
    }
}
