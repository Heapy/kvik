package kvik.cache

import kvik.core.*

/** Whole-buffer prototype, bounded to 64 MiB per blob. */
class NativeContentStore(
    val root: String,
    val maxBlobBytes: Int = 64 * 1024 * 1024,
    private val diagnostic: (CacheDiagnostic) -> Unit = {},
) : ContentStore {
    init {
        require(maxBlobBytes > 0)
        NativeFiles.ensureDirectory(root)
        NativeFiles.ensureDirectory("$root/blobs")
        NativeFiles.ensureDirectory("$root/locks")
    }
    fun blobPath(digest: Digest): String = "$root/blobs/${digest.hash}-${digest.sizeBytes}"
    override fun put(bytes: ByteArray): Digest {
        require(bytes.size <= maxBlobBytes) { "Blob exceeds $maxBlobBytes byte limit" }
        val snapshot = bytes.copyOf()
        val digest = Sha256.digest(snapshot)
        NativeFiles.withLock("$root/locks/${digest.hash}-${digest.sizeBytes}.lock") {
            val path = blobPath(digest)
            val existing = NativeFiles.info(path)
            if (existing != null) {
                if (NativeFiles.directory(existing)) throw CacheFailure("Directory at blob address: $path")
                val valid = NativeFiles.regular(existing) && existing.size == digest.sizeBytes &&
                    NativeFiles.read(path, maxBlobBytes).contentEquals(snapshot)
                if (!valid) {
                    // Only invalid bytes/types are evicted; read I/O failures preserve the existing inode.
                    NativeFiles.removeEntry(path)
                    diagnostic(CacheDiagnostic("blob-repaired", "Removed corrupt entry: ${digest.hash}"))
                }
            }
            NativeFiles.publish(path, snapshot, maxBlobBytes)
        }
        return digest
    }
    override fun get(digest: Digest): ByteArray? {
        if (digest.sizeBytes > maxBlobBytes) {
            diagnostic(CacheDiagnostic("blob-too-large", "${digest.hash}: exceeds $maxBlobBytes byte limit"))
            return null
        }
        return try {
            val bytes = NativeFiles.read(blobPath(digest), maxBlobBytes)
            if (Sha256.digest(bytes) != digest) {
                diagnostic(CacheDiagnostic("blob-corrupt", "Size or SHA-256 mismatch: ${digest.hash}"))
                null
            } else bytes
        } catch (failure: CacheFailure) {
            diagnostic(CacheDiagnostic("blob-unavailable", failure.message ?: "Unreadable blob"))
            null
        }
    }
}

/** Metadata publication is only for successful eligible actions; the executor owns that decision. */
class NativeActionCache(
    val root: String,
    private val content: NativeContentStore,
    private val diagnostic: (CacheDiagnostic) -> Unit = {},
    private val maxRecordBytes: Int = 16 * 1024 * 1024,
) : ActionCache {
    init {
        require(maxRecordBytes > 0)
        NativeFiles.ensureDirectory(root)
        NativeFiles.ensureDirectory("$root/locks")
    }
    private fun decodeRecord(bytes: ByteArray, key: Digest): CachedAction {
        val fields = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
            .fields(setOf("schemaVersion", "key", "outputs"))
        fields.requireVersion()
        val record = CachedAction(Digest.fromJson(fields.getValue("key")), TreeManifest.fromJson(fields.getValue("outputs")))
        require(record.key == key) { "Action record key mismatch" }
        return record
    }
    fun recordPath(key: Digest): String = "$root/${key.hash}-${key.sizeBytes}.json"
    override fun lookup(key: Digest): CachedAction? = try {
        val bytes = NativeFiles.read(recordPath(key), maxRecordBytes)
        val record = decodeRecord(bytes, key)
        if (record.outputs.files.any { content.get(it.digest) == null }) {
            diagnostic(CacheDiagnostic("action-blob-missing", "Output unavailable for ${key.hash}"))
            null
        } else record
    } catch (failure: Exception) {
        diagnostic(CacheDiagnostic("action-unavailable", failure.message ?: "Invalid action record"))
        null
    }
    override fun publish(action: CachedAction) {
        val snapshot = CachedAction(action.key, TreeManifest(action.outputs.files.toList()))
        for (file in snapshot.outputs.files) {
            val bytes = content.get(file.digest) ?: throw CacheFailure("Cannot publish action: missing or corrupt ${file.digest.hash}")
            // Re-publication also fsyncs the blob namespace before metadata can become visible.
            check(content.put(bytes) == file.digest)
        }
        val bytes = obj("schemaVersion" to CONTRACT_VERSION.json(), "key" to snapshot.key.toJson(),
            "outputs" to snapshot.outputs.toJson()).canonical().encodeToByteArray()
        require(bytes.size <= maxRecordBytes) { "Action record exceeds $maxRecordBytes byte limit" }
        NativeFiles.withLock("$root/locks/${snapshot.key.hash}-${snapshot.key.sizeBytes}.lock") {
            val path = recordPath(snapshot.key)
            val existing = NativeFiles.info(path)
            if (existing != null) {
                if (NativeFiles.directory(existing)) throw CacheFailure("Directory at action address: $path")
                val oldBytes = if (NativeFiles.regular(existing) && existing.size <= maxRecordBytes)
                    NativeFiles.read(path, maxRecordBytes) else null
                val oldRecord = try { oldBytes?.let { decodeRecord(it, snapshot.key) } } catch (_: Exception) { null }
                if (oldRecord != null) {
                    if (oldRecord.outputs.toJson() != snapshot.outputs.toJson()) throw CacheFailure("Conflicting outputs for action ${snapshot.key.hash}")
                    NativeFiles.syncDirectory(root)
                    return@withLock
                }
                NativeFiles.removeEntry(path)
                diagnostic(CacheDiagnostic("action-repaired", "Removed invalid action record: ${snapshot.key.hash}"))
            }
            NativeFiles.publish(path, bytes, maxRecordBytes)
        }
    }
}
