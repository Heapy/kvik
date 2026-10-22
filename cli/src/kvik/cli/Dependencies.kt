@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.CacheFailure
import kvik.cache.NativeContentStore
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.locking.*
import kvik.resolution.NativeHttpsTransport
import kvik.resolution.NativePomParser
import kotlinx.cinterop.*
import platform.posix.*

private const val LOCK_LIMIT = 16 * 1024 * 1024
private const val PUBLICATION_LOCK = ".kvik-dependency-publication.lock"

private fun sameEntry(left: String, right: String): Boolean = memScoped {
    val a = alloc<stat>()
    val b = alloc<stat>()
    if (lstat(left, a.ptr) != 0) { if (errno == ENOENT) return false; NativeFiles.fail("lstat $left") }
    if (lstat(right, b.ptr) != 0) { if (errno == ENOENT) return false; NativeFiles.fail("lstat $right") }
    a.st_dev == b.st_dev && a.st_ino == b.st_ino
}

private fun lockOutsideStore(destination: String, store: String): Boolean {
    if (destination == store || destination.startsWith("$store/")) return false
    var parent = destination
    while (parent.isNotEmpty()) {
        if (sameEntry(parent, store)) return false
        parent = parent.substringBeforeLast('/', "")
    }
    return true
}

/** A check never creates directories, repairs a blob, or receives a network transport. */
internal class ReadOnlyContentStore(private val root: String, private val maxBlobBytes: Int = 64 * 1024 * 1024) : ContentStore {
    init {
        require(maxBlobBytes in 1..128 * 1024 * 1024) { "Unsupported dependency blob read bound" }
        NativeFiles.ensureDirectory(root, create = false)
        NativeFiles.ensureDirectory("$root/blobs", create = false)
    }
    override fun put(bytes: ByteArray): Digest = error("Locked dependency checks cannot publish content")
    override fun get(digest: Digest): ByteArray? {
        if (digest.sizeBytes > maxBlobBytes) return null
        return try {
            NativeFiles.read("$root/blobs/${digest.hash}-${digest.sizeBytes}", maxBlobBytes).takeIf { Sha256.digest(it) == digest }
        } catch (_: CacheFailure) { null }
    }
}

private fun existingLock(path: String): Digest? {
    val info = NativeFiles.info(path) ?: return null
    require(NativeFiles.regular(info)) { "Dependency lock must be a regular file: $path" }
    return Sha256.digest(NativeFiles.read(path, LOCK_LIMIT))
}

/** Only explicit resolve publishes. Concurrent edits are checked under a persistent publication lock. */
private fun publishLock(project: ProjectFile, destination: String, previous: Digest?, bytes: ByteArray) {
    val parent = destination.substringBeforeLast('/')
    // One persistent inode per destination directory also coordinates different projects
    // and case/Unicode aliases of the same destination filename.
    NativeFiles.withLock("$parent/$PUBLICATION_LOCK") {
        require(!sameEntry(destination, project.path)) { "Dependency lock cannot overwrite project configuration through a path alias" }
        if (existingLock(destination) != previous || !NativeFiles.read(project.path, 4 * 1024 * 1024).contentEquals(project.bytes)) {
            throw CliFailure("KVIK_INPUT_CHANGED", DiagnosticCategory.CONFIGURATION, "Project or dependency lock changed during resolution; retry", ExitStatus.INCOMPLETE)
        }
        val stage = NativeFiles.tempDirectory(parent)
        try {
            val path = "$stage/lock.json"
            NativeFiles.writeNew(path, bytes)
            if (chmod(path, 420.convert()) != 0) NativeFiles.fail("chmod dependency lock")
            val fd = open(path, O_RDONLY or O_NOFOLLOW)
            if (fd < 0) NativeFiles.fail("open staged dependency lock")
            try { if (fsync(fd) != 0) NativeFiles.fail("fsync dependency lock") } finally { close(fd) }
            if (rename(path, destination) != 0) NativeFiles.fail("publish dependency lock")
            NativeFiles.syncDirectory(parent)
        } finally { NativeFiles.removeTree(stage) }
    }
}

internal fun dependencyCommand(args: List<String>, updateRequested: Boolean): JsonValue {
    val options = projectOptions(args, lockOptions = true)
    val project = readProject(options.project)
    val effective = effectiveConfiguration(project.configuration, options.resolverPolicy())
    val destination = projectPath(project.root, options.lock)
    val store = projectPath(project.root, options.store)
    require(destination.substringAfterLast('/').lowercase() != PUBLICATION_LOCK) { "Dependency lock cannot replace its publication coordination file" }
    require(destination != project.path && !sameEntry(destination, project.path)) { "Dependency lock cannot overwrite project configuration through a path alias" }
    require(lockOutsideStore(destination, store)) { "Dependency lock must be outside the content store" }
    val update = updateRequested && !options.offline
    var observedLock: Digest? = null
    val lock = if (update) {
        NativeFiles.ensureDirectory(destination.substringBeforeLast('/'))
        val previous = existingLock(destination)
        val content = NativeContentStore(store, maxBlobBytes = effective.maxArtifactBytes)
        require(lockOutsideStore(destination, store)) { "Dependency lock must be outside the content store" }
        val resolved = try {
            DependencyLocks(content, NativePomParser()).resolve(effective, NativeHttpsTransport(maxBytes = effective.maxArtifactBytes))
        } catch (failure: Exception) {
            throw CliFailure("KVIK_RESOLUTION_FAILED", DiagnosticCategory.INFRASTRUCTURE, failure.message ?: "Dependency resolution failed")
        }
        publishLock(project, destination, previous, resolved.bytes())
        observedLock = resolved.digest
        resolved
    } else try {
        NativeFiles.ensureDirectory(destination.substringBeforeLast('/'), create = false)
        val bytes = NativeFiles.read(destination, LOCK_LIMIT)
        observedLock = Sha256.digest(bytes)
        DependencyLocks(ReadOnlyContentStore(store, effective.maxArtifactBytes), NativePomParser()).loadLocked(bytes, effective)
    } catch (failure: Exception) {
        throw CliFailure("KVIK_LOCK_INVALID", DiagnosticCategory.CONFIGURATION, failure.message ?: "Dependency lock is unavailable", ExitStatus.INVALID_CONFIGURATION)
    }
    // Checking also refuses success if its configuration changed while verifying the lock.
    if (!NativeFiles.read(project.path, 4 * 1024 * 1024).contentEquals(project.bytes) || existingLock(destination) != observedLock) {
        throw CliFailure("KVIK_INPUT_CHANGED", DiagnosticCategory.CONFIGURATION, "Project or dependency lock changed during verification; retry", ExitStatus.INCOMPLETE)
    }
    return obj("schemaVersion" to CONTRACT_VERSION.json(), "operation" to (if (update) "resolve" else "check-lock").json(),
        "configuration" to effective.digest.toJson(), "dependencyLock" to lock.digest.toJson(), "lockPath" to destination.json(),
        "selections" to lock.selections.size.json(), "artifacts" to lock.selections.flatMap { it.artifacts }.map { it.digest }.distinct().size.json(),
        "metadata" to lock.selections.flatMap { it.metadata }.map { it.digest }.distinct().size.json())
}
