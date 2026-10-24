@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.abi

import kotlin.test.*
import kvik.core.*
import kvik.cache.NativeContentStore
import kvik.cache.NativeFiles
import kvik.compiler.jvm.*
import kvik.execution.NativeRunDirectory
import platform.posix.usleep

class AbiExecutorTest {
    private val identity = Sha256.digest("composition".encodeToByteArray())
    private val tools = listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith { identity }
    private val noHost = object : ProcessHost {
        override fun start(request: ProcessRequest): OwnedProcess = error("No worker expected")
        override fun close() = Unit
    }
    private val noTools = JvmToolchainProvider { error("Comparison must not verify installed tools") }
    private fun await(handle: RunningAction): ActionResult {
        repeat(5000) { handle.poll()?.let { return it }; usleep(1000u) }
        error("ABI action did not finish")
    }

    @Test fun typedRecipesRejectUndeclaredInputsAndForgedExecutionPolicy() {
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("classes/Api.class"), identity)))
        val extraction = AbiExtraction("sample", "jvm", LogicalPath("classes"))
        val action = abiDumpAction(extraction, inputs, identity, tools)
        assertEquals(ActionKind.GENERATE, action.kind)
        assertEquals(CachePolicy.LOCAL, action.cachePolicy)
        assertEquals(extraction, AbiExtraction.fromJson(action.parameters))
        assertFails { abiDumpAction(extraction, inputs, identity, tools - "junit") }
        assertFails { abiDumpAction(extraction, TreeManifest(listOf(FileEntry(LogicalPath("classes/Huge.class"),
            identity.copy(sizeBytes = 64L * 1024 * 1024 + 1)))), identity, tools) }
        assertFails { abiDumpAction(extraction, TreeManifest((1..5).map { FileEntry(LogicalPath("classes/Huge$it.class"),
            identity.copy(sizeBytes = 64L * 1024 * 1024)) }), identity, tools) }
        assertFails { abiDumpAction(extraction, TreeManifest(inputs.files + FileEntry(LogicalPath("extra.class"), identity)), identity, tools) }
        val root = NativeRunDirectory("/private/tmp").path
        try {
            val executor = AbiActionExecutor("run", root, noHost, NativeContentStore("$root/store"), noTools)
            assertFails { executor.start("run", "dump", action.copy(cachePolicy = CachePolicy.REMOTE)) }
            val comparison = AbiComparison("sample", "jvm", LogicalPath("candidate.json"), LogicalPath("baseline.txt"))
            assertFails { abiCheckAction(comparison, inputs, identity) }
        } finally { NativeFiles.removeTree(root) }
    }

    @Test fun comparisonNormalizesOnlyLineEndingsAndKeepsFailedCandidateOutsideSuccessfulOutputs() {
        for ((baseline, expected) in listOf("public class Api\n" to true, "public class Api" to true,
            "public class Api\r\n" to true, "public class Other\n" to false, "public class Api\n\n" to false)) {
            val root = NativeRunDirectory("/private/tmp").path
            try {
                val content = NativeContentStore("$root/store")
                val snapshot = AbiSnapshot("sample", "jvm", identity, "public class Api\n")
                val comparison = AbiComparison("sample", "jvm", LogicalPath("candidate.json"), LogicalPath("baseline.txt"))
                val input = TreeManifest(listOf(FileEntry(comparison.snapshot, content.put(snapshot.toJson().canonical().encodeToByteArray())),
                    FileEntry(comparison.baseline, content.put(baseline.encodeToByteArray()))))
                val action = abiCheckAction(comparison, input, identity)
                val executor = AbiActionExecutor("run", root, noHost, content, noTools)
                val result = executor.start("run", "check", action).use(::await)
                assertEquals(if (expected) ActionStatus.EXECUTED else ActionStatus.FAILED, result.status)
                assertEquals(if (expected) 2 else 0, result.outputs.files.size)
                val observation = executor.observations.getValue("check")
                assertEquals(snapshot.api, NativeFiles.read("${observation.directory}/output/api.txt", 1000).decodeToString())
                if (!expected) assertEquals("KVIK_ABI_CHANGED", observation.diagnostics.single().code)
            } finally { NativeFiles.removeTree(root) }
        }
    }

    @Test fun comparisonRefusesCorruptContentScopeAndInvalidUtf8() {
        for (variant in listOf("scope", "utf8", "digest")) {
            val root = NativeRunDirectory("/private/tmp").path
            try {
                val store = NativeContentStore("$root/store")
                val snapshot = AbiSnapshot(if (variant == "scope") "wrong" else "sample", "jvm", identity, "api\n")
                val comparison = AbiComparison("sample", "jvm", LogicalPath("candidate.json"), LogicalPath("baseline.txt"))
                val inputs = TreeManifest(listOf(FileEntry(comparison.snapshot, store.put(snapshot.toJson().canonical().encodeToByteArray())),
                    FileEntry(comparison.baseline, store.put(if (variant == "utf8") byteArrayOf(0xff.toByte()) else "api".encodeToByteArray()))))
                val content = if (variant != "digest") store else object : ContentStore {
                    override fun put(bytes: ByteArray) = store.put(bytes)
                    override fun get(digest: Digest) = "wrong".encodeToByteArray()
                }
                val executor = AbiActionExecutor("run", root, noHost, content, noTools)
                val result = executor.start("run", "check", abiCheckAction(comparison, inputs, identity)).use(::await)
                assertEquals(ActionStatus.FAILED, result.status)
                assertTrue(result.outputs.files.isEmpty())
                assertEquals("KVIK_ABI_COMPARISON_INVALID", executor.observations.getValue("check").diagnostics.single().code)
            } finally { NativeFiles.removeTree(root) }
        }
    }

    @Test fun comparisonCancellationNeverReadsOrStartsWorker() {
        val root = NativeRunDirectory("/private/tmp").path
        try {
            val comparison = AbiComparison("sample", "jvm", LogicalPath("candidate.json"), LogicalPath("baseline.txt"))
            val action = abiCheckAction(comparison, TreeManifest(listOf(FileEntry(comparison.snapshot, identity), FileEntry(comparison.baseline, identity))), identity)
            val executor = AbiActionExecutor("run", root, noHost, NativeContentStore("$root/store"), noTools)
            executor.start("run", "check", action).use { handle ->
                handle.cancel()
                assertEquals(ActionStatus.CANCELLED, assertNotNull(handle.poll()).status)
            }
        } finally { NativeFiles.removeTree(root) }
    }

    @Test fun extractionValidatesBoundedWorkerOutputAndDrainsBeforeAccepting() {
        for (variant in listOf("valid", "wrong-inputs", "wrong-target", "text-mismatch", "extra", "executable", "timeout", "cancelled", "failure", "no-result")) {
            val root = NativeRunDirectory("/private/tmp").path
            try {
                val content = NativeContentStore("$root/store")
                val manifest = obj("compiler" to "fixture".json())
                NativeFiles.writeNew("$root/compiler.json", manifest.canonical().encodeToByteArray())
                val ids = tools + ("jvm-compiler" to Sha256.digest(manifest))
                val extraction = AbiExtraction("sample", "jvm", LogicalPath("classes"))
                val inputs = TreeManifest(listOf(FileEntry(LogicalPath("classes/Api.class"), content.put(byteArrayOf(1)))))
                val action = abiDumpAction(extraction, inputs, identity, ids)
                var drained = false
                var launched = false
                val host = object : ProcessHost {
                    override fun start(request: ProcessRequest): OwnedProcess {
                        launched = true
                        assertContains(request.arguments, "kvik.jvm.AbiWorker")
                        assertEquals(action.timeoutMillis, request.timeoutMillis)
                        val directory = request.workingDirectory
                        assertNull(NativeFiles.info("$directory/output"))
                        val worker = AbiWorkerRequest.fromJson(parseJson(NativeFiles.read("$directory/request.json", 100000).decodeToString()))
                        assertEquals(inputs, worker.inputs)
                        assertEquals(extraction, worker.extraction)
                        assertContentEquals(byteArrayOf(1), NativeFiles.read("$directory/input/classes/Api.class", 1000))
                        return object : OwnedProcess {
                            override fun poll() = when (variant) {
                                "timeout" -> ProcessResult(124, timedOut = true)
                                "cancelled" -> ProcessResult(130, cancelled = true)
                                "failure" -> ProcessResult(1)
                                else -> ProcessResult(0)
                            }
                            override fun cancel() = Unit
                            override fun close() {
                                drained = true
                                // Creating output only at close proves validation happens after ownership drains.
                                NativeFiles.ensureDirectory("$directory/output")
                                val snapshot = AbiSnapshot("sample", if (variant == "wrong-target") "macosArm64" else "jvm",
                                    if (variant == "wrong-inputs") identity else Sha256.digest(inputs.toJson()), "public class Api\n")
                                NativeFiles.writeNew("$directory/output/abi.json", snapshot.toJson().canonical().encodeToByteArray())
                                NativeFiles.writeNew("$directory/output/api.txt", (if (variant == "text-mismatch") "wrong" else snapshot.api).encodeToByteArray(), variant == "executable")
                                if (variant == "extra") NativeFiles.writeNew("$directory/output/extra", byteArrayOf())
                                if (variant != "no-result") NativeFiles.writeNew("$directory/result.json", WorkerResult(worker.requestId,
                                    if (variant == "failure") WorkerStatus.COMPILATION_ERROR else WorkerStatus.SUCCESS, if (variant == "failure") 1 else 0, emptyList()).toJson().canonical().encodeToByteArray())
                            }
                        }
                    }
                    override fun close() = Unit
                }
                val provider = JvmToolchainProvider { JvmToolchainLayout(ids, "/java", "/worker", "/bta.jar", "/stdlib.jar", "/junit.jar", "/artifacts", "$root/compiler.json") }
                val executor = AbiActionExecutor("run", root, host, content, provider)
                val result = executor.start("run", "dump", action).use(::await)
                assertTrue(launched && drained)
                assertEquals(when (variant) { "valid" -> ActionStatus.EXECUTED; "cancelled" -> ActionStatus.CANCELLED; else -> ActionStatus.FAILED }, result.status, variant)
                assertEquals(if (variant == "valid") 2 else 0, result.outputs.files.size)
                assertEquals("jvm", executor.observations.getValue("dump").diagnostics.firstOrNull()?.target ?: "jvm")
            } finally { NativeFiles.removeTree(root) }
        }
    }

    @Test fun preparationFailureAndCancellationCannotLaunchWorker() {
        for (cancel in listOf(false, true)) {
            val root = NativeRunDirectory("/private/tmp").path
            try {
                val action = abiDumpAction(AbiExtraction("sample", "jvm", LogicalPath("classes")),
                    TreeManifest(listOf(FileEntry(LogicalPath("classes/Api.class"), identity))), identity, tools)
                val executor = AbiActionExecutor("run", root, noHost, NativeContentStore("$root/store"), noTools)
                executor.start("run", "dump", action).use { handle ->
                    if (cancel) { handle.cancel(); assertEquals(ActionStatus.CANCELLED, await(handle).status) }
                    else assertFails { await(handle) }
                }
            } finally { NativeFiles.removeTree(root) }
        }
    }
}
