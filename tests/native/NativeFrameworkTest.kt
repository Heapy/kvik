@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.compiler.native

import kotlin.test.*
import kvik.core.*
import kvik.cache.NativeContentStore
import kvik.cache.NativeFiles
import kvik.execution.NativeRunDirectory
import platform.posix.*

class NativeFrameworkTest {
    private val digest = Sha256.digest(byteArrayOf(1))
    private val included = LogicalPath("module.klib")
    private fun compilation(target: NativeTarget, static: Boolean = false) = NativeCompilation("sample", emptyList(),
        output = LogicalPath("Sample.framework"), produce = NativeOutput.FRAMEWORK, includedLibrary = included,
        target = target, isStaticFramework = static)

    @Test fun iosFrameworkContractsBindHostTargetStaticAndSourceFreeLink() {
        for (target in listOf(NativeTarget.IOS_ARM64, NativeTarget.IOS_SIMULATOR_ARM64)) {
            val tools = target.toolIdentityNames.associateWith { digest }
            val inputs = TreeManifest(listOf(FileEntry(included, digest)))
            val dynamic = compilation(target)
            assertFalse("isStaticFramework" in dynamic.toJson().values)
            val static = dynamic.copy(isStaticFramework = true)
            for (value in listOf(dynamic, static)) {
                assertEquals(value, NativeCompilation.fromJson(value.toJson()))
                val action = nativeCompileAction(value, inputs, digest, tools)
                assertEquals(ActionKind.LINK, action.kind)
                assertEquals(NativeTarget.MACOS_ARM64.platform, action.platform)
                assertEquals(target.projectName, action.target)
                assertEquals(target.compilerName, action.arguments[action.arguments.indexOf("-target") + 1])
                assertEquals("framework", action.arguments[action.arguments.indexOf("-produce") + 1])
                assertContains(action.arguments, "-Xinclude=input/module.klib")
                assertContains(action.arguments, "-Xpartial-linkage-loglevel=error")
                assertEquals(value.isStaticFramework, "-Xstatic-framework" in action.arguments)
                assertEquals(if (value.isStaticFramework) listOf(LogicalPath("Sample.framework")) else
                    listOf(LogicalPath("Sample.framework"), LogicalPath("Sample.framework.dSYM")), action.outputs)
            }
            assertNotEquals(nativeCompileAction(dynamic, inputs, digest, tools).key(Sha256), nativeCompileAction(static, inputs, digest, tools).key(Sha256))
            assertFails { dynamic.copy(sources = listOf(LogicalPath("Sample.kt"))) }
            assertFails { dynamic.copy(includedLibrary = null, sources = listOf(LogicalPath("Sample.kt"))) }
            assertFails { dynamic.copy(output = LogicalPath("bad-name.framework")) }
            assertFails { dynamic.copy(target = NativeTarget.MACOS_ARM64) }
            assertFails { dynamic.copy(target = NativeTarget.LINUX_X64) }
            assertFails { static.copy(produce = NativeOutput.PROGRAM, output = LogicalPath("program.kexe")) }
            val executable = LogicalPath("tests.kexe")
            val tests = TreeManifest(listOf(FileEntry(executable, digest, true)))
            assertFails { nativeTestAction("sample", executable, tests, digest, tools, target = target) }
            assertFails { kotestNativeTestAction("sample", executable, tests, digest, tools, target = target) }
            assertFails { dynamic.copy(produce = NativeOutput.TEST, output = executable) }
            val library = NativeCompilation("sample", listOf(LogicalPath("Sample.kt")), output = included, target = target)
            val compile = nativeCompileAction(library, TreeManifest(listOf(FileEntry(LogicalPath("Sample.kt"), digest))), digest, tools)
            assertEquals(NativeTarget.MACOS_ARM64.platform, compile.platform)
            val layout = NativeToolchainLayout("/native/bin/konanc", "/jdk", "/ios-sdk", "/dependencies", "/developer", target = target)
            assertEquals("/ios-sdk", layout.environment()["SDKROOT"])
            assertEquals("/developer", layout.environment()["DEVELOPER_DIR"])
            assertFails { layout.copy(sdk = "") }
        }
    }

    private fun macho(type: Int) = byteArrayOf(0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte(),
        12, 0, 0, 1, 0, 0, 0, 0, type.toByte(), 0, 0, 0)

    @Test fun frameworkOutputValidationPrecedesCachingAndPreservesFailureCleanup() {
        for (variant in listOf("dynamic", "static", "missing-symbols", "wrong-binary", "missing-header", "symlink", "cancelled", "failed")) {
            val root = NativeRunDirectory("/private/tmp").path
            try {
                val store = NativeContentStore("$root/store")
                val input = store.put(byteArrayOf(1))
                val static = variant == "static"
                val compilation = compilation(NativeTarget.IOS_SIMULATOR_ARM64, static)
                val action = nativeCompileAction(compilation, TreeManifest(listOf(FileEntry(included, input))), digest,
                    compilation.target.toolIdentityNames.associateWith { digest })
                val header = "unique header for $variant".encodeToByteArray()
                var closed = false
                var launched = false
                val host = object : ProcessHost {
                    override fun start(request: ProcessRequest): OwnedProcess {
                        launched = true
                        assertEquals("/ios-sdk", request.environment["SDKROOT"])
                        assertContains(request.arguments, "-Xinclude=${request.workingDirectory}/input/module.klib")
                        val output = "${request.workingDirectory}/output/Sample.framework"
                        fun file(path: String, bytes: ByteArray, executable: Boolean = false) {
                            NativeFiles.ensureDirectory(path.substringBeforeLast('/'))
                            NativeFiles.writeNew(path, bytes, executable)
                        }
                        file("$output/Sample", if (static) "!<arch>\nmember".encodeToByteArray() else macho(if (variant == "wrong-binary") 2 else 6), !static)
                        if (variant != "missing-header") file("$output/Headers/Sample.h", header)
                        else NativeFiles.ensureDirectory("$output/Headers")
                        file("$output/Modules/module.modulemap", "framework module Sample {}".encodeToByteArray())
                        file("$output/Info.plist", "plist".encodeToByteArray())
                        if (variant == "symlink") {
                            NativeFiles.removeEntry("$output/Headers/Sample.h")
                            assertEquals(0, symlink("../Info.plist", "$output/Headers/Sample.h"))
                        }
                        if (!static && variant != "missing-symbols") {
                            file("$output.dSYM/Contents/Info.plist", "symbols".encodeToByteArray())
                            file("$output.dSYM/Contents/Resources/DWARF/Sample", macho(10))
                        }
                        writeNativeFile(request.stdoutFile, byteArrayOf())
                        writeNativeFile(request.stderrFile, byteArrayOf())
                        return object : OwnedProcess {
                            override fun poll() = when (variant) { "cancelled" -> ProcessResult(130, cancelled = true); "failed" -> ProcessResult(1); else -> ProcessResult(0) }
                            override fun cancel() = Unit
                            override fun close() { closed = true }
                        }
                    }
                    override fun close() = Unit
                }
                val executor = NativeCompilerExecutor("framework", root, host, store, NativeToolchainProvider {
                    NativeToolchainLayout("/native/bin/konanc", "/jdk", "/ios-sdk", "/dependencies", "/developer", target = compilation.target)
                })
                executor.start("framework", "link", action).use { running ->
                    var result: ActionResult? = null
                    for (attempt in 0..5000) {
                        result = running.poll()
                        if (result != null) break
                        usleep(1000u)
                    }
                    val completed = assertNotNull(result)
                    assertTrue(launched)
                    if (variant in setOf("dynamic", "static")) {
                        assertEquals(ActionStatus.EXECUTED, completed.status)
                        assertEquals(if (static) 4 else 6, completed.outputs.files.size)
                        assertNotNull(store.get(Sha256.digest(header)))
                    } else {
                        assertEquals(if (variant == "cancelled") ActionStatus.CANCELLED else ActionStatus.FAILED, completed.status)
                        assertTrue(completed.outputs.files.isEmpty())
                        assertNull(store.get(Sha256.digest(header)), "Rejected framework bytes must not be cached")
                    }
                }
                assertTrue(closed)
            } finally { NativeFiles.removeTree(root) }
        }
    }
}
