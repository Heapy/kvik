package kvik.compiler.js

import kvik.core.*
import kvik.compiler.jvm.JvmToolchainProvider
import kotlin.test.*

class JsSchedulingTest {
    @Test fun executorRequestsDeclaredTestResourcesAndFixedCompilerBudget() {
        val digest = Sha256.digest(byteArrayOf(1))
        val resources = TestResources(768, setOf("server", "database"))
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess = error("Resource queries must not launch")
            override fun close() = Unit
        }
        val store = object : ContentStore {
            override fun put(bytes: ByteArray): Digest = error("No writes")
            override fun get(digest: Digest): ByteArray? = error("No reads")
        }
        val executor = JsCompilerExecutor("run", "/unused", host, store,
            JvmToolchainProvider { error("No compiler") }, NodeToolchainProvider { _, _ -> error("No Node") })
        for (target in KotlinJsTarget.entries) {
            val selection = JsTestSelection("sample", target, LogicalPath("tests.mjs"))
            val inputs = TreeManifest(listOf(FileEntry(selection.entryPoint, digest)))
            fun action(value: JsTestSelection) = jsTestAction(value, inputs, digest, mapOf("node" to digest))
            assertEquals(ResourceRequest(512), executor.resources(action(selection)))
            assertEquals(resources.resourceRequest(), executor.resources(action(selection.copy(resources = resources))))
            val compile = jsCompileAction(JsCompilation("sample", target, listOf(LogicalPath("Main.kt")), emptyList(), LogicalPath("main.klib")),
                TreeManifest(listOf(FileEntry(LogicalPath("Main.kt"), digest))), digest,
                listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith { digest })
            assertEquals(ResourceRequest(2048), executor.resources(compile))
        }
    }
}
