@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.kodesource

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.projectsnapshot.*
import kotlin.io.encoding.Base64
import platform.posix.usleep

/** One invocation owns the supplied host. Every request closes its child and private output directory. */
class NativeKodeTransport(
    private val host: ProcessHost,
    private val executable: String,
    private val home: String,
    private val workParent: String,
    private val gitExecutable: String = "/usr/bin/git",
    private val timeoutMillis: Long = 30_000,
) : KodeTransport {
    init {
        listOf(executable, home, workParent, gitExecutable).forEach(NativeFiles::validateAbsolute)
        require(timeoutMillis in 1..300_000) { "Invalid kode request timeout" }
    }

    override fun execute(arguments: List<String>, maxOutputBytes: Int, checkpoint: () -> Unit): KodeResponse {
        // A 64 MiB selected file can expand sixfold when encoded as a JSON string.
        require(maxOutputBytes in 0..(64 * 1024 * 1024 * 6 + 4096)) { "Invalid kode output limit" }
        require(arguments.isNotEmpty() && arguments.size <= 32 && arguments.none { '\u0000' in it })
        require(arguments == listOf("projects", "list") || arguments.first() in setOf("resolve", "revision-object", "tree", "blob", "log")) {
            "Only read-only kode source operations are supported"
        }
        require(arguments.sumOf { it.encodeToByteArray().size.toLong() } <= 64 * 1024)
        require(arguments.none { it == "--home" || it.startsWith("--home=") }) { "Transport owns kode home selection" }
        checkpoint()
        requireExecutable(executable)
        requireExecutable(gitExecutable)
        NativeFiles.ensureDirectory(home, create = false)
        NativeFiles.ensureDirectory(workParent)
        val directory = NativeFiles.tempDirectory(workParent)
        return try {
            val stdout = "$directory/stdout"
            val stderr = "$directory/stderr"
            host.start(ProcessRequest(listOf(executable, "--home", home) + arguments, directory,
                mapOf("PATH" to "/usr/bin:/bin:/usr/sbin:/sbin", "HOME" to home, "TMPDIR" to directory,
                    "KODE_GIT" to gitExecutable, "LC_ALL" to "C"), stdout, stderr, timeoutMillis)).use { process ->
                try {
                    var result: ProcessResult?
                    do {
                        checkpoint()
                        checkOutput(stdout, maxOutputBytes)
                        checkOutput(stderr, STDERR_LIMIT)
                        result = process.poll()
                        if (result == null) {
                            usleep(10_000u)
                        }
                    } while (result == null)
                    if (result.setupFailure != null) throw SnapshotFailure("Cannot launch kode: ${result.setupFailure.stage.name.lowercase()} (${result.setupFailure.errno})")
                    if (result.timedOut) throw SnapshotFailure("Kode source request timed out after $timeoutMillis ms")
                    if (result.cancelled) throw SnapshotFailure("Kode source request was cancelled")
                    checkpoint()
                    KodeResponse(result.exitCode, NativeFiles.read(stdout, maxOutputBytes), NativeFiles.read(stderr, STDERR_LIMIT))
                } catch (failure: Throwable) {
                    process.cancel()
                    throw failure
                }
            }
        } finally {
            NativeFiles.removeTree(directory)
        }
    }

    private fun requireExecutable(path: String) {
        NativeFiles.ensureDirectory(path.substringBeforeLast('/'), create = false)
        val info = NativeFiles.info(path)
        require(info != null && NativeFiles.regular(info) && info.mode and 73 != 0) { "Kode tool must be a regular executable: $path" }
    }

    private fun checkOutput(path: String, limit: Int) {
        val info = NativeFiles.info(path) ?: return
        if (!NativeFiles.regular(info) || info.size < 0 || info.size > limit) throw SnapshotFailure("Kode source response exceeds its output limit")
    }

    companion object { private const val STDERR_LIMIT = 64 * 1024 }
}

/** Immutable lookup records scoped by the configured provider endpoint, separate from action content identity.
 * A digest record is only a hint: KodeSourceProvider authenticates every selected blob against its Git identity. */
class NativeKodeSourceCache(private val root: String, private val namespace: Digest) : KodeSourceCache {
    init { NativeFiles.validateAbsolute(root) }

    override fun claims(project: String, revision: String): RevisionClaims? =
        read(objectKey("claims", project, revision), CLAIMS_LIMIT)?.let { value ->
            val fields = value.fields(setOf("claims", "digest"))
            val claims = fields.getValue("claims")
            require(Sha256.digest(claims) == Digest.fromJson(fields.getValue("digest"))) { "Corrupt cached revision claims" }
            RevisionClaims.fromJson(claims)
        }

    override fun storeClaims(project: String, revision: String, claims: RevisionClaims) {
        val value = claims.toJson()
        write(objectKey("claims", project, revision), obj("claims" to value, "digest" to Sha256.digest(value).toJson()), CLAIMS_LIMIT)
    }

    override fun revision(project: String, revision: String): ByteArray? =
        read(objectKey("revision", project, revision), REVISION_RECORD_LIMIT)?.let { value ->
            val encoded = value.stringValue()
            require(encoded.length <= ((MAX_REVISION_BYTES + 2) / 3) * 4) { "Cached revision exceeds limit" }
            Base64.decode(encoded).also { bytes ->
                require(bytes.size in 1..MAX_REVISION_BYTES && Base64.encode(bytes) == encoded) { "Invalid cached revision encoding or size" }
            }
        }

    override fun storeRevision(project: String, revision: String, bytes: ByteArray) {
        require(bytes.size in 1..MAX_REVISION_BYTES) { "Revision object exceeds cache limit" }
        write(objectKey("revision", project, revision), Base64.encode(bytes).json(), REVISION_RECORD_LIMIT)
    }

    override fun manifest(project: String, tree: String): SourceManifest? =
        read(treeKey(project, tree), MANIFEST_LIMIT)?.let(SourceManifest::fromJson)

    override fun storeManifest(project: String, tree: String, manifest: SourceManifest) =
        write(treeKey(project, tree), manifest.toJson(), MANIFEST_LIMIT)

    override fun knownDigest(project: String, blob: SourceBlob): Digest? =
        read(key("blob", project, blob.toJson()), DIGEST_LIMIT)?.let(Digest::fromJson)

    override fun storeDigest(project: String, blob: SourceBlob, digest: Digest) {
        require(blob.sizeBytes == digest.sizeBytes) { "Cached source digest has a different size" }
        write(key("blob", project, blob.toJson()), digest.toJson(), DIGEST_LIMIT)
    }

    private fun key(kind: String, project: String, identity: JsonValue): JsonValue.Object {
        SourceSelector(project, "cache") // Apply the same bounded opaque project identity contract.
        return obj("schemaVersion" to CONTRACT_VERSION.json(), "namespace" to namespace.toJson(),
            "kind" to kind.json(), "project" to project.json(), "identity" to identity)
    }

    private fun treeKey(project: String, tree: String): JsonValue.Object = objectKey("manifest", project, tree)

    private fun objectKey(kind: String, project: String, identity: String): JsonValue.Object {
        require(identity.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) { "Invalid cached Git object identity" }
        return key(kind, project, identity.json())
    }

    private fun path(key: JsonValue): String = "$root/${namespace.hash}/${Sha256.digest(key).hash}.json"

    private fun read(key: JsonValue.Object, limit: Int): JsonValue? {
        val path = path(key)
        val parent = path.substringBeforeLast('/')
        if (NativeFiles.info(root) == null) return null
        NativeFiles.ensureDirectory(root, create = false)
        if (NativeFiles.info(parent) == null) return null
        NativeFiles.ensureDirectory(parent, create = false)
        if (NativeFiles.info(path) == null) return null
        val fields = parseJson(NativeFiles.read(path, limit).decodeToString(throwOnInvalidSequence = true))
            .fields(setOf("key", "value"))
        require(fields.getValue("key") == key) { "Source cache record belongs to a different lookup" }
        return fields.getValue("value")
    }

    private fun write(key: JsonValue.Object, value: JsonValue, limit: Int) {
        val bytes = obj("key" to key, "value" to value).canonical().encodeToByteArray()
        require(bytes.size <= limit) { "Source cache record exceeds limit" }
        val path = path(key)
        NativeFiles.ensureDirectory(path.substringBeforeLast('/'))
        NativeFiles.publish(path, bytes, limit)
    }

    companion object {
        private const val MAX_REVISION_BYTES = 1024 * 1024
        private const val REVISION_RECORD_LIMIT = 1536 * 1024
        private const val MANIFEST_LIMIT = 17 * 1024 * 1024
        private const val DIGEST_LIMIT = 16 * 1024
        private const val CLAIMS_LIMIT = 2 * 1024 * 1024
    }
}
