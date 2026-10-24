@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.kodesource

import kotlinx.cinterop.toKString
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeProcessHost
import kvik.projectsnapshot.*
import platform.posix.getenv
import kotlin.test.*

private class RealSourceContent : ContentStore {
    private val blobs = mutableMapOf<Digest, ByteArray>()
    override fun get(digest: Digest) = blobs[digest]?.copyOf()
    override fun put(bytes: ByteArray) = Sha256.digest(bytes).also { blobs[it] = bytes.copyOf() }
}

/** The harness creates two real revisions, advances main, and supplies the earlier immutable ID. */
class RealKodeSourceTest {
    private fun environment(name: String) = checkNotNull(getenv(name)?.toKString()) { "Run through tools/test_kode_source.py: missing $name" }

    @Test fun realRevisionMatchesLocalInputsAndReusesVerifiedCache() {
        val root = environment("KVIK_KODE_CASE")
        val revision = environment("KVIK_KODE_REVISION")
        val executable = environment("KVIK_KODE_EXECUTABLE")
        val content = RealSourceContent()
        NativeProcessHost(environment("KVIK_KODE_GUARDIAN")).use { host ->
            val transport = NativeKodeTransport(host, executable, "$root/store", "$root/work")
            val calls = mutableListOf<List<String>>()
            var transportAllowed = true
            val observed = KodeTransport { args, bound, checkpoint ->
                calls += args
                check(transportAllowed) { "Offline capture attempted to invoke kode" }
                transport.execute(args, bound, checkpoint)
            }
            fun capture(selector: String, offline: Boolean = false, selectedContent: ContentStore = content) = ProviderProjectSnapshotter(
                KodeSourceProvider(observed, NativeKodeSourceCache("$root/cache", Sha256.digest(executable.encodeToByteArray())), offline),
                selectedContent, SourcePathPolicy.CaseSensitive).capture(SourceSelector("fixture", selector),
                    LogicalPath("kvik.json"), LogicalPath("kvik.lock.json"))

            val first = capture(revision)
            val local = assertIs<SnapshotCapture.Captured>(NativeProjectSnapshotter("$root/local", RealSourceContent())
                .capture(LogicalPath("kvik.json"), "$root/local/kvik.lock.json")).snapshot
            assertEquals(local.inputs, first.snapshot.inputs)
            assertEquals(local.fingerprint, first.snapshot.fingerprint)
            assertEquals(revision, first.provenance.revision)
            assertEquals(listOf("resolve", "revision-object", "tree", "blob", "blob", "blob", "blob"), calls.map { it.first() })
            assertEquals(listOf("assets/binary.bin", "src/Main.kt"), first.snapshot.inputs.files.map { it.path.value })
            assertTrue(first.snapshot.inputs.files.single { it.path.value == "src/Main.kt" }.executable)

            calls.clear()
            val repeat = capture(revision)
            assertEquals(first.snapshot.fingerprint, repeat.snapshot.fingerprint)
            assertEquals(listOf(listOf("resolve", "fixture", revision)), calls)
            calls.clear()
            transportAllowed = false
            assertEquals(first.snapshot.fingerprint, capture(revision, offline = true).snapshot.fingerprint)
            assertFails { capture(revision, offline = true, selectedContent = RealSourceContent()) }
            assertFails { capture("main", offline = true) }
            assertTrue(calls.isEmpty())
            transportAllowed = true
            val current = capture("main")
            assertNotEquals(revision, current.provenance.revision)
            assertNotEquals(first.snapshot.fingerprint, current.snapshot.fingerprint)
            // Kode accepts a terminator; it must not bypass the transport-owned store.
            val terminated = transport.execute(listOf("resolve", "fixture", revision, "--"), 16384) {}
            assertEquals(0, terminated.exitCode)
            assertEquals(revision, parseJson(terminated.stdout.decodeToString()).objectValue().getValue("revision").stringValue())
            assertFailsWith<SnapshotFailure> { capture("f".repeat(40)) }
            assertTrue(NativeFiles.names("$root/work").isEmpty())
        }
    }
}
