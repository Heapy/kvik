@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.compilertools.cancellationtests

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.test.*
import kvik.cache.NativeFiles
import kvik.compilertools.*
import kvik.core.*
import kvik.toolchains.*

class CancellationTest {
    private fun fixture(block: (String) -> Unit) {
        val path = memScoped {
            val template = "/private/tmp/kvik-verification-cancellation-XXXXXX".cstr.getPointer(this)
            checkNotNull(mkdtemp(template)).toKString()
        }
        try { block(path) } finally { NativeFiles.removeTree(path) }
    }

    private fun put(path: String, bytes: ByteArray, executable: Boolean = false): Digest {
        NativeFiles.ensureDirectory(path.substringBeforeLast('/'))
        NativeFiles.writeNew(path, bytes, executable)
        return Sha256.digest(bytes)
    }

    /** Cover entry, in-progress work and final validation without repeated full inventory campaigns. */
    private fun cancelAtRepresentativeBoundaries(operation: (() -> Unit) -> Any?): Int {
        val baseline = NativeFiles.names("/dev/fd").toSet()
        var count = 0
        operation { count++ }
        assertTrue(count > 0)
        for (stop in setOf(1, maxOf(2, count / 2), count)) {
            var seen = 0
            val cancellation = Exception("cancel checkpoint $stop")
            val failure = assertFails {
                operation { if (++seen == stop) throw cancellation }
            }
            assertSame(cancellation, failure)
            assertEquals(stop, seen)
            assertEquals(baseline, NativeFiles.names("/dev/fd").toSet(), "Descriptor leak at checkpoint $stop")
        }
        operation {}
        assertEquals(baseline, NativeFiles.names("/dev/fd").toSet())
        return count
    }

    @Test fun streamedFileCancellationClosesDescriptorAndResumesFreshHashing() = fixture { root ->
        val bytes = ByteArray(1024 * 1024 + 7) { (it % 251).toByte() }
        val expected = put("$root/tool.jar", bytes)
        val count = cancelAtRepresentativeBoundaries { checkpoint -> CompilerFiles.digest("$root/tool.jar", bytes.size.toLong(), checkpoint) }
        assertTrue(count >= 3 * 17, "Expected checkpoints within each 64 KiB read/hash chunk")
        assertEquals(expected, CompilerFiles.digest("$root/tool.jar", bytes.size.toLong()))
    }

    @Test fun inventoryCancellationCoversDirectorySortingLinksAndChunks() = fixture { root ->
        put("$root/b/tool", ByteArray(131073) { 1 })
        put("$root/a/tool", "a".encodeToByteArray())
        put("$root/c/tool", "c".encodeToByteArray())
        assertEquals(0, symlink("b/tool", "$root/link"))
        val expected = inventory(root)
        cancelAtRepresentativeBoundaries { checkpoint -> inventory(root, checkpoint = checkpoint) }
        assertEquals(expected, inventory(root))
        cancelAtRepresentativeBoundaries { checkpoint -> CompilerFiles.names(root, checkpoint) }
    }

    private fun installations(root: String): Pair<Map<ToolComponent, String>, String> {
        val developer = "$root/Xcode/Contents/Developer"
        val roots = ToolComponent.entries.associateWith { component -> when (component) {
            ToolComponent.SDK -> "$developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk"
            ToolComponent.XCODE_TOOLCHAIN -> "$developer/Toolchains/XcodeDefault.xctoolchain"
            ToolComponent.LLVM -> "$root/data/dependencies/llvm-21-aarch64-macos-essentials-97"
            ToolComponent.LIBFFI -> "$root/data/dependencies/libffi-3.3-1-macos-arm64"
            else -> "$root/${component.lockName}"
        } }
        for (path in roots.values) put("$path/tool", "tool".encodeToByteArray())
        put("${roots.getValue(ToolComponent.SDK)}/SDKSettings.json", "{\"Version\":\"fixture\"}".encodeToByteArray())
        for (path in listOf("${roots.getValue(ToolComponent.JDK)}/bin/java",
            "${roots.getValue(ToolComponent.KOTLIN_NATIVE)}/bin/konanc", "${roots.getValue(ToolComponent.KOTLIN_NATIVE)}/bin/run_konan",
            "${roots.getValue(ToolComponent.XCODE_TOOLCHAIN)}/usr/bin/ld")) put(path, "fixture executable".encodeToByteArray(), true)
        val konan = checkNotNull(getenv("KVIK_TEST_KONAN_HOME")).toKString()
        put("${roots.getValue(ToolComponent.KOTLIN_NATIVE)}/konan/konan.properties", NativeFiles.read("$konan/konan/konan.properties", 65536))
        put("$root/data/dependencies/.extracted", "llvm-21-aarch64-macos-essentials-97\nlibffi-3.3-1-macos-arm64\nlldb-4-macos\n".encodeToByteArray())
        put("$root/data/dependencies/lldb-4-macos/present", byteArrayOf())
        val host = nativeHost()
        val lock = obj("schemaVersion" to 1.json(), "kotlinVersion" to "2.4.20".json(),
            "host" to obj("os" to host.first.json(), "architecture" to host.second.json(), "release" to host.third.json(), "sdk" to "fixture".json()),
            "components" to JsonValue.Object(roots.map { (component, path) -> component.lockName to inventory(path).digest.toJson() }.toMap()))
        return roots to lock.canonical()
    }

    @Test fun installedInventoryVerificationCancellationPropagatesAndRecovers() = fixture { root ->
        val (roots, lock) = installations(root)
        val verifier = InstalledToolchainVerifier()
        cancelAtRepresentativeBoundaries { checkpoint -> verifier.verify(lock, roots, checkpoint) }
        cancelAtRepresentativeBoundaries { checkpoint -> verifier.verifyJdk(lock, roots.getValue(ToolComponent.JDK), checkpoint) }
    }

    @Test fun nativeProviderPropagatesCancellationWithoutPartialLayout() = fixture { root ->
        val (roots, lock) = installations(root)
        val verified = InstalledToolchainVerifier().verify(lock, roots)
        val identities = mapOf("native" to verified[ToolComponent.KOTLIN_NATIVE].digest,
            "jdk" to verified[ToolComponent.JDK].digest, "sdk" to verified[ToolComponent.SDK].digest,
            "native-dependencies" to kvik.compiler.native.nativeDependenciesIdentity(verified[ToolComponent.LLVM].digest,
                verified[ToolComponent.LIBFFI].digest, verified[ToolComponent.XCODE_TOOLCHAIN].digest))
        val provider = InstalledNativeToolchainProvider(lock, roots, "$root/data")
        val expected = provider.verified(identities)
        cancelAtRepresentativeBoundaries { checkpoint -> provider.verified(identities, checkpoint) }
        assertEquals(expected, provider.verified(identities))
    }

    @Test fun workerBundleAndJvmProviderPropagateCancellationThroughCompilerJars() = fixture { root ->
        val (roots, lock) = installations(root)
        val bundle = "$root/bundle"
        val classDigest = put("$bundle/classes/Fake.class", "fake class".encodeToByteArray())
        val classes = TreeManifest(listOf(FileEntry(LogicalPath("Fake.class"), classDigest)))
        val entries = listOf("api", "stdlib", "junit").associateWith { role ->
            FileEntry(LogicalPath("lib/$role.jar"), put("$bundle/lib/$role.jar", "$role jar".encodeToByteArray()))
        }
        val compiler = obj("schemaVersion" to 1.json(), "version" to "2.4.20".json(), "artifacts" to listOf(
            obj("path" to "compiler.jar".json(), "digest" to put("$root/artifacts/compiler.jar", ByteArray(131073) { 2 }).toJson())
        ).json(), "serializationPlugin" to JsonValue.Null)
        val compilerDigest = put("$bundle/compiler-manifest.json", compiler.canonical().encodeToByteArray())
        val worker = Sha256.digest(obj("schemaVersion" to 1.json(), "implementation" to "kvik-jvm-worker-bundle-v1".json(),
            "workerTree" to Sha256.digest(classes.toJson()).toJson(), "api" to entries.getValue("api").digest.toJson(),
            "stdlib" to entries.getValue("stdlib").digest.toJson(), "junit" to entries.getValue("junit").digest.toJson()))
        val manifest = JsonValue.Object(entries.mapValues { it.value.toJson() } + mapOf("schemaVersion" to 1.json(),
            "classes" to classes.toJson(), "compilerManifest" to FileEntry(LogicalPath("compiler-manifest.json"), compilerDigest).toJson(),
            "worker" to worker.toJson()))
        val expectedManifest = put("$bundle/manifest.json", manifest.canonical().encodeToByteArray())
        cancelAtRepresentativeBoundaries { checkpoint -> verifyJvmWorkerBundle(bundle, expectedManifest, checkpoint) }
        val identities = mapOf("jdk" to inventory(roots.getValue(ToolComponent.JDK)).digest, "jvm-compiler" to compilerDigest,
            "jvm-worker" to worker, "junit" to entries.getValue("junit").digest)
        val provider = InstalledJvmToolchainProvider(lock, roots, bundle, expectedManifest, "$root/artifacts")
        val expected = provider.verified(identities)
        cancelAtRepresentativeBoundaries { checkpoint -> provider.verified(identities, checkpoint) }
        assertEquals(expected, provider.verified(identities))
    }
}
