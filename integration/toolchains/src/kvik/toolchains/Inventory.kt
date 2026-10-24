@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.toolchains

import kotlinx.cinterop.*
import kvik.files.platform.kvik_files_ctime_nanoseconds
import kvik.files.platform.kvik_files_ctime_seconds
import kvik.files.platform.kvik_files_mtime_nanoseconds
import kvik.files.platform.kvik_files_mtime_seconds
import platform.posix.*
import kvik.core.*
import kvik.toolchains.hash.kvik_sha_finish
import kvik.toolchains.hash.kvik_sha_free
import kvik.toolchains.hash.kvik_sha_new
import kvik.toolchains.hash.kvik_sha_update

class ToolchainVerificationFailure(message: String) : Exception(message)

/** Limits metadata and traversal; file contents always use a reusable 64 KiB buffer. */
data class InventoryLimits(val maxEntries: Int = 2_000_000, val maxDirectoryEntries: Int = 200_000, val maxDepth: Int = 128) {
    init { require(maxEntries > 0 && maxDirectoryEntries > 0 && maxDepth in 1..256) }
}

data class InventorySummary(val digest: Digest, val regularFiles: Long, val symlinks: Long, val fileBytes: Long)

internal class StreamHash(private val checkpoint: () -> Unit = {}) : AutoCloseable {
    private val context = kvik_sha_new() ?: throw ToolchainVerificationFailure("Cannot allocate SHA-256 context")
    private var finished = false
    var size: Long = 0; private set
    fun update(bytes: ByteArray, count: Int = bytes.size) {
        checkpoint()
        check(!finished)
        require(count in 0..bytes.size && size <= Long.MAX_VALUE - count)
        if (count > 0) bytes.usePinned { check(kvik_sha_update(context, it.addressOf(0), count.toUInt()) == 1) }
        size += count
        checkpoint()
    }
    fun text(text: String) = update(text.encodeToByteArray(throwOnInvalidSequence = true))
    fun finish(): Digest {
        checkpoint()
        check(!finished)
        val bytes = ByteArray(32)
        bytes.usePinned { check(kvik_sha_finish(context, it.addressOf(0).reinterpret()) == 1) }
        finished = true
        return Digest(bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }, size)
    }
    override fun close() { kvik_sha_free(context) }
}

internal fun ioFailure(operation: String): Nothing = throw ToolchainVerificationFailure("$operation: errno=$errno")

internal fun physicalPath(path: String): String {
    require(path.startsWith('/') && '\u0000' !in path) { "Tool paths must be absolute without NUL" }
    path.encodeToByteArray(throwOnInvalidSequence = true)
    val resolved = realpath(path, null) ?: ioFailure("resolve $path")
    return try { resolved.readBytes(strlen(resolved).toInt()).decodeToString(throwOnInvalidSequence = true) } finally { free(resolved) }
}

private fun names(path: String, limit: Int, checkpoint: () -> Unit): List<String> {
    checkpoint()
    val directory = opendir(path) ?: ioFailure("opendir $path")
    try {
        val result = mutableListOf<Pair<String, ByteArray>>()
        while (true) {
            checkpoint()
            set_posix_errno(0)
            val entry = readdir(directory)
            if (entry == null) { if (errno != 0) ioFailure("readdir $path"); break }
            val bytes = entry.pointed.d_name.readBytes(strlen(entry.pointed.d_name).toInt())
            val name = bytes.decodeToString(throwOnInvalidSequence = true)
            if (name == "." || name == "..") continue
            require(result.size < limit) { "Directory entry limit exceeded: $path" }
            result += name to bytes
        }
        // Python Path sorting compares path components by Unicode code point, not UTF-16.
        // Retain the validated UTF-8 bytes from readdir rather than encoding both names
        // again for every comparison. Each directory still has the same entry bound.
        result.sortWith { left, right ->
            checkpoint()
            val a = left.second; val b = right.second
            var result = 0
            for (i in 0 until minOf(a.size, b.size)) {
                result = (a[i].toInt() and 255).compareTo(b[i].toInt() and 255)
                if (result != 0) break
            }
            if (result != 0) result else a.size.compareTo(b.size)
        }
        return result.map { checkpoint(); it.first }
    } finally { closedir(directory) }
}

internal data class CapturedInventory(val root: String, val summary: InventorySummary, val sdkSettings: ByteArray?)

/** Matches bootstrap.identity.inventory(root, True); symlink directories are recorded, never traversed. */
fun inventory(root: String, limits: InventoryLimits = InventoryLimits(), checkpoint: () -> Unit = {}): InventorySummary =
    captureInventory(root, limits, false, checkpoint).summary

internal fun captureInventory(suppliedRoot: String, limits: InventoryLimits, captureSdkSettings: Boolean, checkpoint: () -> Unit = {}): CapturedInventory {
    checkpoint()
    val root = physicalPath(suppliedRoot)
    require(root != "/") { "Filesystem root is not a tool installation" }
    memScoped {
        val status = alloc<stat>()
        if (lstat(root, status.ptr) != 0) ioFailure("lstat $root")
        require(status.st_mode.toInt() and S_IFMT == S_IFDIR) { "Tool root is not a directory: $root" }
    }
    val buffer = ByteArray(65536)
    var entries = 0; var files = 0L; var links = 0L; var fileBytes = 0L
    var settings: ByteArray? = null
    StreamHash(checkpoint).use { stream ->
        stream.text("{\"files\":[")
        var first = true
        fun emit(value: JsonValue) {
            if (!first) stream.text(",")
            first = false
            stream.text(value.canonical())
        }
        fun walk(directory: String, prefix: String, depth: Int) {
            checkpoint()
            require(depth <= limits.maxDepth) { "Inventory depth limit exceeded: $directory" }
            for (name in names(directory, limits.maxDirectoryEntries, checkpoint)) {
                checkpoint()
                require(++entries <= limits.maxEntries) { "Inventory entry limit exceeded" }
                val path = "$directory/$name"
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                memScoped {
                    val status = alloc<stat>()
                    if (lstat(path, status.ptr) != 0) ioFailure("lstat $path")
                    when (status.st_mode.toInt() and S_IFMT) {
                        S_IFDIR -> walk(path, relative, depth + 1)
                        S_IFLNK -> {
                            val target = physicalPath(path)
                            require(target == root || target.startsWith("$root/")) { "External tool symlink: $relative" }
                            val bytes = ByteArray(65536)
                            val length = bytes.usePinned { readlink(path, it.addressOf(0), bytes.size.convert()).toInt() }
                            require(length in 0 until bytes.size) { "Unsupported symlink length: $relative" }
                            emit(obj("path" to relative.json(), "symlink" to bytes.decodeToString(0, length, true).json()))
                            links++
                        }
                        S_IFREG -> {
                            val fd = open(path, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
                            if (fd < 0) ioFailure("open $path")
                            try {
                                val before = alloc<stat>(); val after = alloc<stat>()
                                if (fstat(fd, before.ptr) != 0) ioFailure("fstat $path")
                                require(before.st_mode.toInt() and S_IFMT == S_IFREG && before.st_ino == status.st_ino && before.st_dev == status.st_dev) { "File changed: $path" }
                                val capture = captureSdkSettings && relative == "SDKSettings.json"
                                if (capture) require(before.st_size in 0..1048576L) { "SDKSettings.json exceeds 1 MiB" }
                                val captured = if (capture) ByteArray(before.st_size.toInt()) else null
                                val digest = StreamHash(checkpoint).use { hash ->
                                    buffer.usePinned { pinned ->
                                        while (true) {
                                            checkpoint()
                                            val count = read(fd, pinned.addressOf(0), buffer.size.convert()).toInt()
                                            if (count < 0 && errno == EINTR) continue
                                            if (count < 0) ioFailure("read $path")
                                            if (count == 0) break
                                            if (captured != null) {
                                                require(hash.size + count <= captured.size) { "File grew: $path" }
                                                buffer.copyInto(captured, hash.size.toInt(), 0, count)
                                            }
                                            hash.update(buffer, count)
                                        }
                                    }
                                    hash.finish()
                                }
                                if (fstat(fd, after.ptr) != 0) ioFailure("fstat $path")
                                require(before.st_size == digest.sizeBytes && before.st_size == after.st_size && before.st_mode == after.st_mode &&
                                    kvik_files_mtime_seconds(before.ptr) == kvik_files_mtime_seconds(after.ptr) && kvik_files_mtime_nanoseconds(before.ptr) == kvik_files_mtime_nanoseconds(after.ptr) &&
                                    kvik_files_ctime_seconds(before.ptr) == kvik_files_ctime_seconds(after.ptr) && kvik_files_ctime_nanoseconds(before.ptr) == kvik_files_ctime_nanoseconds(after.ptr)) { "File changed during inventory: $path" }
                                emit(obj("path" to relative.json(), "digest" to digest.toJson(), "executable" to (before.st_mode.toInt() and 73 != 0).json()))
                                files++; fileBytes += digest.sizeBytes
                                if (capture) settings = captured
                            } finally { close(fd) }
                        }
                        else -> throw ToolchainVerificationFailure("Unsupported special file: $relative")
                    }
                }
            }
        }
        walk(root, "", 0)
        stream.text("],\"schemaVersion\":1}")
        checkpoint()
        return CapturedInventory(root, InventorySummary(stream.finish(), files, links, fileBytes), settings)
    }
}
