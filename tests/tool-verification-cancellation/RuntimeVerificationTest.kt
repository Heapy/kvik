@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compilertools.cancellationtests

import kotlin.test.*
import kvik.compiler.jvm.*
import kvik.core.*
import platform.posix.usleep

class RuntimeVerificationTest {
    @Test fun onlyCompilationRequiresCompilerVerificationBeforeAnyProcessCanStart() {
        val digest = Sha256.digest(byteArrayOf())
        val identities = setOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith { digest }
        val inputs = TreeManifest(listOf(FileEntry(LogicalPath("A.kt"), digest), FileEntry(LogicalPath("classes/A.class"), digest)))
        val content = object : ContentStore {
            override fun get(digest: Digest): ByteArray? = error("No materialization before verification")
            override fun put(bytes: ByteArray): Digest = error("No publication before verification")
        }
        val host = object : ProcessHost {
            override fun start(request: ProcessRequest): OwnedProcess = error("No process before verification")
            override fun close() = Unit
        }
        val provider = object : JvmToolchainProvider {
            override fun verified(identities: Map<String, Digest>): JvmToolchainLayout = error("compiler-verification")
            override fun verifiedExecution(identities: Map<String, Digest>, checkpoint: () -> Unit): JvmToolchainLayout {
                checkpoint()
                error("runtime-verification")
            }
        }
        val compile = jvmCompileAction(JvmCompilation("m", listOf(LogicalPath("A.kt")), emptyList(), LogicalPath("output")), inputs, digest, identities)
        val test = jvmTestAction(JvmTestSelection("m", listOf(LogicalPath("classes")), listOf("A")), inputs, digest, identities)
        for ((action, expected) in listOf(compile to "compiler-verification", test to "runtime-verification")) {
            val executor = JvmActionExecutor("run", "/unused", host, content, provider)
            executor.start("run", "action", action).use { running ->
                var failure: Exception? = null
                for (attempt in 0..5000) {
                    try { assertNull(running.poll()) } catch (caught: Exception) { failure = caught; break }
                    usleep(1000u)
                }
                assertEquals(expected, failure?.message)
            }
        }
        val script = jvmScriptAction(JvmScriptLaunch("script", LogicalPath("classes"), listOf(LogicalPath("classes"))), inputs, digest, identities, emptyMap(), 1000)
        val executor = JvmScriptExecutor("run", "/unused", "/unused", host, content, provider)
        executor.start("run", "script", script).use { running ->
            var result: ActionResult? = null
            for (attempt in 0..5000) {
                result = running.poll()
                if (result != null) break
                usleep(1000u)
            }
            assertEquals(ActionStatus.FAILED, result?.status)
            assertEquals("runtime-verification", result?.diagnostic)
            assertNull(executor.observations.getValue("script").processResult)
        }
    }
}
