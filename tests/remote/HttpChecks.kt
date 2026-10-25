package kvik.cache.remote.tests

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
            check(requireNotNull(result.stats).requests == 1)
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
            check(writes == 0) { "Invalid archive published blobs before validating every file" }
            check(!client.remoteEligible(action.copy(cachePolicy = CachePolicy.LOCAL)))
            check(!HttpActionCache(content, endpoint, emptySet(), limits).remoteEligible(action))
            check(!client.publishRemote(action, ActionResult("fixture", action.key(Sha256), ActionStatus.FAILED, outputs, 1)).published)
            for (invalid in listOf("http://example.org/cache", "https://user:secret@example.org", "https://example.org/a/../b", "https://example.org/?secret=value"))
                rejected { HttpCacheEndpoint(invalid, username = "token-auth", trustedWriters = true) }
            rejected { HttpCacheEndpoint(url, username = "user:password", trustedWriters = true) }
            check(endpoint.entryUrl(action.key(Sha256)) != HttpCacheEndpoint(url, "other", "token-auth", trustedWriters = true).entryUrl(action.key(Sha256)))
            println("PASS HTTP archive integrity, output contracts, limits, eligibility and endpoint validation")
        }
        else -> error("Unknown HTTP check")
    }
}
