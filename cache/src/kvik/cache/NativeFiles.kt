@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.cache

import kotlinx.cinterop.*
import platform.posix.*

class CacheFailure(message: String) : Exception(message)

data class CacheDiagnostic(val code: String, val message: String)

/** Native filesystem boundary. Roots must be owned by the caller and not concurrently renamed. */
internal object NativeFiles {
    data class Info(val size: Long, val mode: Int)
    fun fail(operation: String): Nothing = throw CacheFailure("$operation: errno=$errno")
    fun info(path: String): Info? = memScoped {
        val status = alloc<stat>()
        if (lstat(path, status.ptr) != 0) {
            if (errno == ENOENT) return null
            fail("lstat $path")
        }
        Info(status.st_size, status.st_mode.toInt())
    }
    fun directory(info: Info) = info.mode and S_IFMT == S_IFDIR
    fun regular(info: Info) = info.mode and S_IFMT == S_IFREG
    fun validateAbsolute(path: String) {
        require(path.startsWith('/') && path != "/" && '\u0000' !in path)
        require(path.drop(1).split('/').none { it.isEmpty() || it == "." || it == ".." })
    }
    fun ensureDirectory(path: String, create: Boolean = true) {
        validateAbsolute(path)
        var current = ""
        for (segment in path.drop(1).split('/')) {
            current += "/$segment"
            var status = info(current)
            if (status == null && create) {
                if (mkdir(current, 448u) != 0 && errno != EEXIST) fail("mkdir $current")
                syncDirectory(current.substringBeforeLast('/').ifEmpty { "/" })
                status = info(current)
            }
            if (status == null || !directory(status)) throw CacheFailure("Not a real directory: $current")
        }
    }
    fun syncDirectory(path: String) {
        val fd = open(path, O_RDONLY or O_DIRECTORY or O_NOFOLLOW)
        if (fd < 0) fail("open directory $path")
        try { if (fsync(fd) != 0) fail("fsync directory $path") } finally { close(fd) }
    }
    fun read(path: String, limit: Int): ByteArray = memScoped {
        val fd = open(path, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
        if (fd < 0) fail("open $path")
        try {
            val status = alloc<stat>()
            if (fstat(fd, status.ptr) != 0) fail("fstat $path")
            if (status.st_mode.toInt() and S_IFMT != S_IFREG) throw CacheFailure("Not a regular file: $path")
            val size = status.st_size
            if (size < 0 || size > limit) throw CacheFailure("File exceeds $limit byte limit: $path")
            val bytes = ByteArray(size.toInt())
            bytes.usePinned { pinned ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = read(fd, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
                    if (count < 0 && errno == EINTR) continue
                    if (count <= 0) throw CacheFailure("Short read: $path")
                    offset += count
                }
            }
            val extra = alloc<ByteVar>()
            if (read(fd, extra.ptr, 1u) != 0L) throw CacheFailure("File changed while reading: $path")
            bytes
        } finally { close(fd) }
    }
    fun writeNew(path: String, bytes: ByteArray, executable: Boolean = false) {
        val fd = open(path, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, 384)
        if (fd < 0) fail("create $path")
        try {
            bytes.usePinned { pinned ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
                    if (count < 0 && errno == EINTR) continue
                    if (count <= 0) fail("write $path")
                    offset += count
                }
            }
            if (fchmod(fd, (if (executable) 365 else 292).convert()) != 0) fail("chmod $path")
            if (fsync(fd) != 0) fail("fsync $path")
        } finally { close(fd) }
    }
    fun <T> withLock(path: String, block: () -> T): T {
        // Persistent lock inodes must never be unlinked: all cooperating producers lock the same inode.
        val fd = open(path, O_RDWR or O_CREAT or O_NOFOLLOW, 384)
        if (fd < 0) fail("open lock $path")
        try {
            while (flock(fd, LOCK_EX) != 0) { if (errno != EINTR) fail("lock $path") }
            return block()
        } finally { flock(fd, LOCK_UN); close(fd) }
    }
    fun removeEntry(path: String) {
        if (unlink(path) != 0) fail("remove corrupt entry $path")
    }
    fun tempDirectory(parent: String): String = memScoped {
        val template = "$parent/.kvik-stage-XXXXXX".cstr.getPointer(this)
        mkdtemp(template)?.toKString() ?: fail("mkdtemp $parent")
    }
    fun names(directory: String): List<String> {
        val handle = opendir(directory) ?: fail("opendir $directory")
        try {
            val names = mutableListOf<String>()
            while (true) {
                __error()!!.pointed.value = 0
                val entry = readdir(handle)
                if (entry == null) {
                    if (errno != 0) fail("readdir $directory")
                    break
                }
                val raw = entry.pointed.d_name
                val name = raw.readBytes(entry.pointed.d_namlen.toInt()).decodeToString(throwOnInvalidSequence = true)
                if (name != "." && name != "..") names += name
            }
            return names.sorted()
        } finally { closedir(handle) }
    }
    fun removeTree(path: String) {
        val status = info(path) ?: return
        if (directory(status)) {
            names(path).forEach { removeTree("$path/$it") }
            if (rmdir(path) != 0) fail("rmdir $path")
        } else if (unlink(path) != 0) fail("unlink $path")
    }
    /** Hard-link publication never replaces a live inode; concurrent identical writers converge. */
    fun publish(path: String, bytes: ByteArray, limit: Int) {
        val parent = path.substringBeforeLast('/')
        val stage = tempDirectory(parent)
        try {
            val temporary = "$stage/content"
            writeNew(temporary, bytes)
            if (link(temporary, path) != 0) {
                if (errno != EEXIST) fail("publish $path")
                if (!read(path, limit).contentEquals(bytes)) throw CacheFailure("Immutable publication conflict: $path")
            }
            syncDirectory(parent)
        } finally { removeTree(stage) }
    }
    fun exposeDirectory(stage: String, destination: String) {
        // macOS exclusive rename gives atomic visibility without overwriting even an empty directory.
        if (renamex_np(stage, destination, RENAME_EXCL.toUInt()) != 0) fail("expose $destination")
        syncDirectory(destination.substringBeforeLast('/'))
    }
}
