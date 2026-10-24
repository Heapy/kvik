package kvik.kodesource

import kvik.core.Digest
import kvik.core.RevisionClaims
import kvik.projectsnapshot.SourceBlob
import kvik.projectsnapshot.SourceManifest

/** Literal arguments, without executable/home configuration. Bound both output streams while reading;
 * propagate cancellation after draining the owned process. No shell or automatic command retries. */
fun interface KodeTransport {
    fun execute(arguments: List<String>, maxOutputBytes: Int, checkpoint: () -> Unit): KodeResponse
}

data class KodeResponse(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray)

/** Hints only. Manifests and selected blob bytes are reverified against native Git identities on every use.
 * Implementations must bound stored-record reads and propagate I/O/cancellation failures. */
interface KodeSourceCache {
    fun claims(project: String, revision: String): RevisionClaims?
    fun storeClaims(project: String, revision: String, claims: RevisionClaims)
    fun revision(project: String, revision: String): ByteArray?
    fun storeRevision(project: String, revision: String, bytes: ByteArray)
    fun manifest(project: String, tree: String): SourceManifest?
    fun storeManifest(project: String, tree: String, manifest: SourceManifest)
    fun knownDigest(project: String, blob: SourceBlob): Digest?
    fun storeDigest(project: String, blob: SourceBlob, digest: Digest)
}
