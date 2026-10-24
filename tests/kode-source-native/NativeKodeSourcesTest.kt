@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.kodesource

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.projectsnapshot.*
import kotlin.test.*

private class SourceProcessHost(
    private val output: ByteArray = byteArrayOf(),
    private val errors: ByteArray = byteArrayOf(),
    private val result: ProcessResult? = ProcessResult(0),
) : ProcessHost {
    var request: ProcessRequest? = null
    var cancelled = false
    var closed = false
    override fun start(request: ProcessRequest): OwnedProcess {
        this.request = request
        NativeFiles.writeNew(request.stdoutFile, output)
        NativeFiles.writeNew(request.stderrFile, errors)
        return object : OwnedProcess {
            override fun poll() = result
            override fun cancel() { cancelled = true }
            override fun close() { closed = true }
        }
    }
    override fun close() = Unit
}

class NativeKodeSourcesTest {
    private fun fixture(block: (String, String) -> Unit) {
        val root = NativeFiles.tempDirectory("/private/tmp")
        try {
            val executable = "$root/kode"
            NativeFiles.writeNew(executable, "fixture executable".encodeToByteArray(), executable = true)
            block(root, executable)
        } finally { NativeFiles.removeTree(root) }
    }

    @Test fun transportUsesLiteralArgumentsExplicitEnvironmentAndClosesOwnedChild() = fixture { root, executable ->
        val host = SourceProcessHost("source bytes".encodeToByteArray(), "diagnostic".encodeToByteArray(), ProcessResult(3))
        val transport = NativeKodeTransport(host, executable, root, "$root/work", executable, 1234)
        val arguments = listOf("resolve", "project", "tasks/literal;echo", "--json")
        val reply = transport.execute(arguments, 1024) {}
        assertEquals(3, reply.exitCode)
        assertContentEquals("source bytes".encodeToByteArray(), reply.stdout)
        assertContentEquals("diagnostic".encodeToByteArray(), reply.stderr)
        val request = checkNotNull(host.request)
        assertEquals(listOf(executable, "--home", root) + arguments, request.arguments)
        assertEquals(1234L, request.timeoutMillis)
        assertEquals(setOf("PATH", "HOME", "TMPDIR", "KODE_GIT", "LC_ALL"), request.environment.keys)
        assertEquals(executable, request.environment["KODE_GIT"])
        assertTrue(host.closed)
        assertNull(NativeFiles.info(request.workingDirectory))
    }

    @Test fun cancellationAndOutputBoundsCloseChildrenBeforeRemovingPrivateFiles() = fixture { root, executable ->
        for (host in listOf(SourceProcessHost(ByteArray(2)), SourceProcessHost(errors = ByteArray(65537)))) {
            assertFailsWith<SnapshotFailure> { NativeKodeTransport(host, executable, root, "$root/work", executable)
                .execute(listOf("blob", "project", "id", "--raw"), 1) {} }
            assertTrue(host.cancelled && host.closed)
            assertNull(NativeFiles.info(checkNotNull(host.request).workingDirectory))
        }
        class Cancelled : Exception()
        val host = SourceProcessHost(result = null)
        val failure = Cancelled()
        assertSame(failure, assertFailsWith<Cancelled> {
            NativeKodeTransport(host, executable, root, "$root/work", executable).execute(listOf("resolve", "p", "main"), 1) {
                if (host.request != null) throw failure
            }
        })
        assertTrue(host.cancelled && host.closed)
        assertNull(NativeFiles.info(checkNotNull(host.request).workingDirectory))
    }

    @Test fun transportReportsTimeoutAndSetupFailuresInsteadOfEmptySuccess() = fixture { root, executable ->
        for (result in listOf(ProcessResult(124, timedOut = true), ProcessResult(130, cancelled = true),
            ProcessResult(127, setupFailure = ProcessSetupFailure(ProcessSetupStage.EXEC, 2)))) {
            val host = SourceProcessHost(result = result)
            assertFailsWith<SnapshotFailure> { NativeKodeTransport(host, executable, root, "$root/work", executable)
                .execute(listOf("tree", "p", "--rev", "revision"), 1) {} }
            assertTrue(host.closed)
        }
    }

    @Test fun transportRejectsMutationAndHomeOverridesBeforeSpawning() = fixture { root, executable ->
        for (arguments in listOf(listOf("tx", "p"), listOf("resolve", "p", "main", "--home", root),
            listOf("resolve", "p", "main", "--home=$root"))) {
            val host = SourceProcessHost()
            assertFails { NativeKodeTransport(host, executable, root, "$root/work", executable).execute(arguments, 1) {} }
            assertNull(host.request)
        }
    }

    @Test fun callerOptionTerminatorCannotHideOwnedHome() = fixture { root, executable ->
        for (suffix in listOf("--", "--ignored")) {
            val host = SourceProcessHost()
            NativeKodeTransport(host, executable, root, "$root/work", executable)
                .execute(listOf("resolve", "p", "main", suffix), 1) {}
            assertEquals(listOf(executable, "--home", root, "resolve", "p", "main", suffix), host.request!!.arguments)
        }
    }

    @Test fun cacheRoundTripsWithoutCrossProjectOrEndpointReuse() = fixture { root, _ ->
        val cacheRoot = "$root/cache"
        val namespace = Sha256.digest("provider A".encodeToByteArray())
        val cache = NativeKodeSourceCache(cacheRoot, namespace)
        val tree = "a".repeat(40)
        val blob = SourceBlob("b".repeat(40), 1)
        val manifest = SourceManifest(listOf(SourceFileEntry(LogicalPath("src/file"), blob)), listOf(LogicalPath("src")))
        assertNull(cache.manifest("project", tree))
        assertNull(NativeFiles.info(cacheRoot))
        cache.storeManifest("project", tree, manifest)
        cache.storeManifest("project", tree, manifest)
        val digest = Sha256.digest(byteArrayOf(1))
        cache.storeDigest("project", blob, digest)
        val reopened = NativeKodeSourceCache(cacheRoot, namespace)
        assertEquals(manifest.toJson(), reopened.manifest("project", tree)?.toJson())
        assertEquals(digest, reopened.knownDigest("project", blob))
        assertNull(reopened.knownDigest("other", blob))
        assertNull(reopened.manifest("other", tree))
        val other = NativeKodeSourceCache(cacheRoot, Sha256.digest("provider B".encodeToByteArray()))
        assertNull(other.manifest("project", tree))
        assertFails { cache.storeDigest("project", blob, Sha256.digest(byteArrayOf(2))) }
        assertFails { cache.storeDigest("project", blob, Sha256.digest(byteArrayOf(1, 2))) }
    }

    @Test fun swappedOrCorruptCacheRecordsFailInsteadOfChangingSelectedIdentity() = fixture { root, _ ->
        val namespace = Sha256.digest(byteArrayOf(1))
        val cache = NativeKodeSourceCache("$root/cache", namespace)
        val blob = SourceBlob("a".repeat(40), 1)
        cache.storeDigest("first", blob, Sha256.digest(byteArrayOf(1)))
        cache.storeDigest("second", blob, Sha256.digest(byteArrayOf(2)))
        val directory = "$root/cache/${namespace.hash}"
        val paths = NativeFiles.names(directory).map { "$directory/$it" }
        val bytes = NativeFiles.read(paths[0], 16 * 1024)
        NativeFiles.removeTree(paths[1])
        NativeFiles.writeNew(paths[1], bytes)
        assertFails { cache.knownDigest("first", blob); cache.knownDigest("second", blob) }
        NativeFiles.removeTree(paths[0])
        NativeFiles.writeNew(paths[0], byteArrayOf(0xff.toByte()))
        assertFails { cache.knownDigest("first", blob); cache.knownDigest("second", blob) }
    }

    @Test fun revisionCachePreservesRawBytesAndRejectsConflictingOrOversizedRecords() = fixture { root, _ ->
        val namespace = Sha256.digest(byteArrayOf(1))
        val cache = NativeKodeSourceCache("$root/cache", namespace)
        val revision = "c".repeat(40)
        val bytes = byteArrayOf(0, -1, 13, 10, 1)
        assertNull(cache.revision("first", revision))
        cache.storeRevision("first", revision, bytes)
        assertContentEquals(bytes, NativeKodeSourceCache("$root/cache", namespace).revision("first", revision))
        assertNull(cache.revision("second", revision))
        assertNull(NativeKodeSourceCache("$root/cache", Sha256.digest(byteArrayOf(2))).revision("first", revision))
        assertFails { cache.storeRevision("first", revision, byteArrayOf(2)) }
        assertFails { cache.storeRevision("first", "d".repeat(40), ByteArray(1024 * 1024 + 1)) }
        val path = NativeFiles.names("$root/cache/${namespace.hash}").single().let { "$root/cache/${namespace.hash}/$it" }
        val fields = parseJson(NativeFiles.read(path, 1536 * 1024).decodeToString()).objectValue()
        NativeFiles.removeTree(path)
        NativeFiles.writeNew(path, JsonValue.Object(fields + ("value" to "not-base64!".json())).canonical().encodeToByteArray())
        assertFails { cache.revision("first", revision) }
    }
}
