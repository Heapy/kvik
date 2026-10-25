package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*

/** Retain each referenced action log once, preserving paths relative to the source run. */
internal fun retainTestLogs(directory: String, runId: String, tests: List<TestResult>, availableBytes: Long,
    retain: (String, ByteArray) -> Unit, checkpoint: () -> Unit = {}) {
    var remaining = availableBytes
    require(remaining >= 0) { "Retained report inputs exceed their size limit" }
    val logs = tests.flatMap { test -> test.details?.attempts.orEmpty().flatMap { it.logs } }.groupBy { it.path }
    for ((path, references) in logs.entries.sortedBy { it.key.value }) {
        checkpoint()
        val digests = references.map { it.digest }.distinct()
        require(digests.size == 1) { "Conflicting retained test log identities" }
        val digest = digests.single()
        require(digest.sizeBytes <= 64L * 1024 * 1024 && digest.sizeBytes <= remaining) {
            "Retained test logs exceed report size limits"
        }
        val source = "$directory/${path.value}"
        NativeFiles.ensureDirectory(source.substringBeforeLast('/'), create = false)
        val bytes = NativeFiles.read(source, 64 * 1024 * 1024)
        require(Sha256.digest(bytes) == digest) { "Retained test log digest differs" }
        remaining -= bytes.size
        retain("runs/$runId/${path.value}", bytes)
    }
}
