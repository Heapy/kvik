package kvik.cache.remote.tests

import kvik.cache.*
import kvik.cache.remote.*
import kvik.core.*

internal fun transferChecks(root: String, endpoint: RemoteEndpoint, original: ActionSpec, limits: TransferLimits) {
    fun fails(block: () -> Unit) {
        check(runCatching(block).isFailure) { "Invalid transfer statistics accepted" }
    }
    val sample = CacheTransferStats(2, 123, 456, 789)
    check(CacheTransferStats.fromJson(sample.toJson()) == sample) { "Nonzero statistics JSON round-trip failed" }
    check(CacheTransferStats.fromJson(CacheTransferStats(0, 0, 0, 0).toJson()) == CacheTransferStats(0, 0, 0, 0)) {
        "Zero statistics JSON round-trip failed"
    }
    for (name in sample.toJson().values.keys) {
        fails { CacheTransferStats.fromJson(JsonValue.Object(sample.toJson().values - name)) }
        fails { CacheTransferStats.fromJson(JsonValue.Object(sample.toJson().values + (name to (-1L).json()))) }
        fails { CacheTransferStats.fromJson(JsonValue.Object(sample.toJson().values + (name to "1".json()))) }
    }
    fails { CacheTransferStats.fromJson(JsonValue.Object(sample.toJson().values + ("extra" to 0.json()))) }
    fails { CacheTransferStats.fromJson(JsonValue.Object(sample.toJson().values + ("requests" to Long.MAX_VALUE.json()))) }

    // The body accounting includes exactly the five-byte gRPC frame, not HTTP headers.
    val session = RpcSession(endpoint, limits)
    val request = session.request {}
    val response = session.rpc("Capabilities", "GetCapabilities", request)
    val first = session.stats()
    check(first.requests == 1 && first.uploadedBytes == request.size + 5L && first.downloadedBytes == response.size + 5L) {
        "Capabilities body counters: $first; expected upload=${request.size + 5L}, download=${response.size + 5L}"
    }
    check(first.durationNanos > 0) { "Capabilities duration was not measured: $first" }
    // Inputs may exceed the blob transfer limit and need not be present locally.
    val largeInput = Sha256.digest(ByteArray(1024 * 1024 + 1) { 7 })
    val missingAction = original.copy(arguments = original.arguments + "transfer-statistics-missing",
        inputs = TreeManifest(listOf(FileEntry(LogicalPath("src/input.txt"), largeInput))))
    val missing = session.request { bytes(2, ReapiMapping(missingAction, limits).actionDigest.wire()) }
    try {
        session.rpc("ActionCache", "GetActionResult", missing)
        error("Expected cache miss")
    } catch (failure: RemoteGrpcFailure) { check(failure.status == 5) { "Expected NOT_FOUND, got ${failure.status}" } }
    val afterMiss = session.stats()
    check(afterMiss.requests == 2 && afterMiss.uploadedBytes == first.uploadedBytes + missing.size + 5L) {
        "Miss counters: before=$first, after=$afterMiss; request body=${missing.size + 5L}"
    }
    check(afterMiss.downloadedBytes >= first.downloadedBytes && afterMiss.durationNanos >= first.durationNanos) {
        "Miss counters regressed: before=$first, after=$afterMiss"
    }
    try {
        session.rpc("Capabilities", "UnknownTransferStatisticsMethod", request)
        error("Expected unsupported RPC failure")
    } catch (failure: RemoteGrpcFailure) { check(failure.status == 12) { "Expected UNIMPLEMENTED, got ${failure.status}" } }
    val afterFailure = session.stats()
    check(afterFailure.requests == 3 && afterFailure.uploadedBytes == afterMiss.uploadedBytes + request.size + 5L) {
        "Failed RPC counters: before=$afterMiss, after=$afterFailure; request body=${request.size + 5L}"
    }
    check(afterFailure.downloadedBytes >= afterMiss.downloadedBytes && afterFailure.durationNanos >= afterMiss.durationNanos) {
        "Failed RPC counters regressed: before=$afterMiss, after=$afterFailure"
    }

    val bounded = RpcSession(endpoint, limits.copy(requests = 1))
    bounded.capabilities(false)
    val beforeRejected = bounded.stats()
    fails { bounded.capabilities(false) }
    val afterRejected = bounded.stats()
    check(afterRejected.requests == 1 && afterRejected.uploadedBytes == beforeRejected.uploadedBytes &&
        afterRejected.downloadedBytes == beforeRejected.downloadedBytes) {
        "Rejected request counted as transferred: before=$beforeRejected, after=$afterRejected"
    }

    val content = NativeContentStore("$root/transfer-cas")
    val local = NativeActionCache("$root/transfer-actions", content)
    val approval = setOf(PortabilityApproval(original.implementation, original.target, original.platform, "transfer fixture"))
    val remote = RemoteActionCache(content, local, endpoint, approval, limits)
    val miss = remote.lookupRemote(missingAction)
    check(miss.action == null && miss.diagnostic == null) { "Expected ordinary remote miss: $miss" }
    check(requireNotNull(miss.stats).let { it.requests == 2 && it.uploadedBytes > 0 && it.downloadedBytes > 0 }) {
        "Missing remote miss counters: $miss"
    }
    val payload = content.put("transfer-statistics-payload".encodeToByteArray())
    val outputs = TreeManifest(listOf(FileEntry(LogicalPath("out/value"), payload), FileEntry(LogicalPath("single.txt"), payload)))
    val result = ActionResult("fixture", missingAction.key(Sha256), ActionStatus.EXECUTED, outputs, 0)
    val publication = remote.publishRemote(missingAction, result)
    check(publication.published && publication.diagnostic == null) { "Publication failed: $publication" }
    check(requireNotNull(publication.stats).let { it.requests > 2 && it.uploadedBytes > payload.sizeBytes && it.downloadedBytes > 0 }) {
        "Missing publication counters: $publication; payload=${payload.sizeBytes}"
    }
    val fresh = RemoteActionCache(NativeContentStore("$root/transfer-fresh-cas"), local, endpoint, approval, limits)
    val hit = fresh.lookupRemote(missingAction)
    check(hit.action?.outputs?.toJson() == outputs.toJson() && hit.diagnostic == null) {
        "Restored output manifest differs: $hit; expected=${outputs.toJson().canonical()}"
    }
    check(requireNotNull(hit.stats).let { it.requests > 2 && it.uploadedBytes > 0 && it.downloadedBytes > payload.sizeBytes }) {
        "Missing restored hit counters: $hit; payload=${payload.sizeBytes}"
    }
    val changedInput = missingAction.copy(inputs = original.inputs)
    check(changedInput.key(Sha256) != missingAction.key(Sha256))
    val changedLookup = fresh.lookupRemote(changedInput)
    check(changedLookup.action == null && changedLookup.diagnostic == null) {
        "Changed input digest must miss even when input bytes are not uploaded: $changedLookup"
    }

    val unavailable = RemoteEndpoint("http://127.0.0.1:1", trustedWriters = true)
    val offline = RemoteActionCache(content, local, unavailable, approval, limits)
    val failedLookup = offline.lookupRemote(missingAction)
    check(failedLookup.action == null && failedLookup.diagnostic?.code == "remote-cache-lookup-failed") {
        "Expected diagnosed connection failure: $failedLookup"
    }
    val failedPublish = offline.publishRemote(missingAction, result)
    check(!failedPublish.published && failedPublish.diagnostic?.code == "remote-publish-failed") {
        "Expected diagnosed publication connection failure: $failedPublish"
    }
    for (stats in listOf(failedLookup.stats, failedPublish.stats)) {
        check(requireNotNull(stats).let { it.requests == 1 && it.uploadedBytes == 0L && it.downloadedBytes == 0L && it.durationNanos > 0 }) {
            "Connection refused counters must retain one attempt with zero body bytes: $stats"
        }
    }
    val rejected = remote.publishRemote(missingAction, result.copy(status = ActionStatus.FAILED, exitCode = 1))
    check(!rejected.published && requireNotNull(rejected.stats).let { it.requests == 0 && it.uploadedBytes == 0L && it.downloadedBytes == 0L }) {
        "Rejected publication performed remote activity: $rejected"
    }
    val ineligible = missingAction.copy(cachePolicy = CachePolicy.NEVER)
    check(remote.lookupRemote(ineligible).stats == null && remote.publishRemote(ineligible, result).stats == null) {
        "Ineligible action must not record remote statistics"
    }
    println("PASS remote transfer statistics: exact framed bodies, miss, hit, publish, rejected budgets, failed connection, strict JSON")
}
