@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cache.remote.tests

import kotlinx.cinterop.*
import kvik.cache.remote.platform.*
import kvik.cache.*
import kvik.cache.remote.*
import kvik.core.*
import kotlin.time.TimeSource

internal fun httpChecks(operation: String, root: String, url: String, action: ActionSpec) {
    val limits = TransferLimits(rpcMillis = 2000, operationMillis = 5000)
    val content = NativeContentStore("$root/cas")
    val endpoint = HttpCacheEndpoint(url, username = "token-auth", password = "fixture-remote-token", trustedWriters = true)
    val approvals = setOf(PortabilityApproval(action.implementation, action.target, action.platform, "HTTP fixture"))
    val client = HttpActionCache(content, endpoint, approvals, limits)
    val digest = content.put("archive-payload".encodeToByteArray())
    val outputs = TreeManifest(listOf(FileEntry(LogicalPath("out/bin/run"), digest, true),
        FileEntry(LogicalPath("single.txt"), digest)))
    when (operation) {
        "http-publish" -> {
            val result = client.publishRemote(action, ActionResult("fixture", action.key(Sha256), ActionStatus.EXECUTED, outputs, 0))
            println("PUBLISHED ${result.published} ${result.diagnostic?.code}")
            val requests = requireNotNull(result.stats).requests
            check(requests in 1..2 && (!result.published || requests == 2))
        }
        "http-lookup" -> {
            val result = client.lookupRemote(action)
            println(if (result.action == null) "MISS ${result.diagnostic?.code}" else "HIT")
            result.action?.let { NativeOutputTrees(content).materialize(it.outputs, "$root/restored") }
            check(requireNotNull(result.stats).requests == 1)
        }
        "http-cancel" -> {
            val started = TimeSource.Monotonic.markNow()
            class Cancelled : Exception()
            try {
                client.lookupRemote(action) { if (started.elapsedNow().inWholeMilliseconds >= 100) throw Cancelled() }
                error("Cancellation became a cache fallback")
            } catch (_: Cancelled) { check(started.elapsedNow().inWholeMilliseconds < 2500) }
            println("PASS HTTP cancellation")
        }
        "http-selftest" -> {
            fun rejected(block: () -> Unit) {
                var failed = false
                try { block() } catch (_: Exception) { failed = true }
                check(failed) { "Malformed archive/configuration accepted" }
            }
            val codec = HttpCacheArchive(limits)
            val bytes = codec.encode(action, outputs, content) {}
            val fresh = NativeContentStore("$root/fresh")
            check(codec.decode(action, bytes, fresh) {}.outputs.toJson() == outputs.toJson())
            check(bytes.contentEquals(codec.encode(action, TreeManifest(outputs.files.reversed()), content) {}))
            val gzip = HttpCacheGzip(limits.totalBytes)
            val compressed = gzip.encode(bytes) {}
            check(compressed.contentEquals(gzip.encode(bytes) {}))
            check(compressed.copyOfRange(0, 10).contentEquals(byteArrayOf(31, -117, 8, 0, 0, 0, 0, 0, 0, -1)))
            check(gzip.decode(compressed) {}.contentEquals(bytes))
            check(HttpCacheGzip(bytes.size.toLong()).decode(compressed) {}.contentEquals(bytes))
            check(gzip.decode(gzip.encode(byteArrayOf()) {}) {}.isEmpty())
            rejected { gzip.decode(bytes) {} }
            rejected { gzip.decode(byteArrayOf()) {} }
            rejected { gzip.decode(compressed + byteArrayOf(0)) {} }
            rejected { gzip.decode(compressed + compressed) {} }
            rejected { HttpCacheGzip(bytes.size.toLong() - 1).decode(compressed) {} }
            rejected { HttpCacheGzip(compressed.size.toLong() - 1).encode(bytes) {} }
            for (size in (0..minOf(20, compressed.size)) + (compressed.size - 8 until compressed.size))
                rejected { gzip.decode(compressed.copyOf(size)) {} }
            for (offset in listOf(0, compressed.size - 8, compressed.size - 4)) {
                val corrupt = compressed.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
                rejected { gzip.decode(corrupt) {} }
                corrupt.usePinned { pinned ->
                    val failed = requireNotNull(kvik_gzip_codec(pinned.addressOf(0).reinterpret(), corrupt.size.convert(),
                        limits.totalBytes.convert(), 1, null, null))
                    try { check(failed.pointed.error != 0 && failed.pointed.data == null && failed.pointed.size == 0uL) }
                    finally { kvik_gzip_free(failed) }
                }
            }
            val expanded = ByteArray(256 * 1024) { 0 }
            val small = gzip.encode(expanded) {}
            check(small.size < 1024)
            rejected { HttpCacheGzip(1024).decode(small) {} }
            // Cancellation must survive the C callback and must interrupt multi-step work.
            class CodecCancelled : Exception()
            val cancelled = CodecCancelled()
            var randomState = 1
            val incompressible = ByteArray(256 * 1024) {
                randomState = randomState * 1664525 + 1013904223
                (randomState ushr 24).toByte()
            }
            for (decompress in listOf(false, true)) {
                var checkpoints = 0
                try {
                    val check = { if (++checkpoints == 3) throw cancelled }
                    if (decompress) gzip.decode(small, check) else gzip.encode(incompressible, check)
                    error("Gzip cancellation ignored")
                } catch (failure: CodecCancelled) { check(failure === cancelled && checkpoints == 3) }
            }
            println("PASS deterministic bounded gzip, empty members, CRC/truncation/trailing/concatenation rejection and cancellation")

            rejected { codec.decode(action.copy(arguments = action.arguments + "changed"), bytes, fresh) {} }
            rejected { codec.decode(action, bytes.copyOf(bytes.size - 1), fresh) {} }
            rejected { codec.decode(action, bytes + byteArrayOf(0), fresh) {} }
            rejected { codec.decode(action, bytes.copyOf().also { it[8] = 127 }, fresh) {} }
            rejected { codec.decode(action, bytes.copyOf().also { it[it.lastIndex] = 0 }, fresh) {} }
            val manifestSize = (8..11).fold(0) { n, i -> (n shl 8) or (bytes[i].toInt() and 255) }
            val manifest = parseJson(bytes.copyOfRange(12, 12 + manifestSize).decodeToString()) as JsonValue.Object
            fun altered(header: JsonValue): ByteArray {
                val data = header.canonical().encodeToByteArray()
                val prefix = bytes.copyOfRange(0, 12)
                for (i in 0..3) prefix[8 + i] = (data.size ushr ((3 - i) * 8)).toByte()
                return prefix + data + bytes.copyOfRange(12 + manifestSize, bytes.size)
            }
            for (path in listOf("../escaped", "/absolute", "out/../escape", "out\\escape", "out//double", "elsewhere")) {
                val changed = outputs.toJson().canonical().replace("out/bin/run", path.replace("\\", "\\\\"))
                val header = JsonValue.Object(manifest.values + ("outputs" to parseJson(changed)))
                rejected { codec.decode(action, altered(header), fresh) {} }
            }
            val duplicate = obj("schemaVersion" to 1.json(), "files" to listOf(outputs.files[0], outputs.files[0]).map { it.toJson() }.json())
            rejected { codec.decode(action, altered(JsonValue.Object(manifest.values + ("outputs" to duplicate))), fresh) {} }
            val symlink = obj("schemaVersion" to 1.json(), "files" to listOf(
                JsonValue.Object((outputs.files[0].toJson() as JsonValue.Object).values + ("symlink" to "elsewhere".json()))).json())
            rejected { codec.decode(action, altered(JsonValue.Object(manifest.values + ("outputs" to symlink))), fresh) {} }
            rejected { HttpCacheArchive(limits.copy(blobBytes = 1)).encode(action, outputs, content) {} }
            rejected { HttpCacheArchive(limits.copy(files = 1)).decode(action, bytes, fresh) {} }
            rejected { HttpCacheArchive(limits.copy(depth = 1)).decode(action, bytes, fresh) {} }
            var writes = 0
            val noWrite = object : ContentStore {
                override fun get(digest: Digest): ByteArray? = null
                override fun put(bytes: ByteArray): Digest { writes++; return Sha256.digest(bytes) }
            }
            rejected { codec.decode(action, bytes.copyOf().also { it[it.lastIndex] = 0 }, noWrite) {} }
            rejected { codec.decode(action, gzip.decode(compressed.copyOf().also {
                it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte()
            }) {}, noWrite) {} }
            check(writes == 0) { "Invalid archive published blobs before validating every file" }
            check(!client.remoteEligible(action.copy(cachePolicy = CachePolicy.LOCAL)))
            check(!HttpActionCache(content, endpoint, emptySet(), limits).remoteEligible(action))
            check(!client.publishRemote(action, ActionResult("fixture", action.key(Sha256), ActionStatus.FAILED, outputs, 1)).published)
            for (invalid in listOf("http://example.org/cache", "https://user:secret@example.org", "https://example.org/a/../b", "https://example.org/?secret=value"))
                rejected { HttpCacheEndpoint(invalid, username = "token-auth", trustedWriters = true) }
            rejected { HttpCacheEndpoint(url, username = "user:password", trustedWriters = true) }
            val transportKey = endpoint.entryUrl(action.key(Sha256)).substringAfterLast('/')
            check(transportKey.matches(Regex("[0-9a-f]{32}")))
            val expectedKey = Sha256.digest(obj("format" to "kvik-http-v2".json(), "instance" to "".json(),
                "action" to action.key(Sha256).toJson())).hash.take(32)
            check(transportKey == expectedKey)
            check(endpoint.entryUrl(action.key(Sha256)) != HttpCacheEndpoint(url, "other", "token-auth", trustedWriters = true).entryUrl(action.key(Sha256)))
            println("PASS HTTP archive integrity, output contracts, limits, eligibility and endpoint validation")
        }
        else -> error("Unknown HTTP check")
    }
}
