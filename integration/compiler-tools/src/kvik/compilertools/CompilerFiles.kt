@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.compilertools

import kotlinx.cinterop.*
import platform.posix.*
import kvik.core.Digest
import kvik.toolchains.StreamHash

/** Source-composed Native boundary; no subprocess or JVM is involved in content verification. */
internal object CompilerFiles {
    fun names(directory: String): List<String> {
        val handle = opendir(directory) ?: error("Cannot open compiler tool directory: $directory (errno=$errno)")
        try {
            val names = mutableListOf<String>()
            while (true) {
                __error()!!.pointed.value = 0
                val entry = readdir(handle)
                if (entry == null) { check(errno == 0) { "Cannot read directory: $directory" }; break }
                val name = entry.pointed.d_name.readBytes(entry.pointed.d_namlen.toInt()).decodeToString(throwOnInvalidSequence = true)
                if (name == "." || name == "..") continue
                require(names.size < 8192) { "Compiler tool directory exceeds 8192 entries" }
                names += name
            }
            return names.sorted()
        } finally { closedir(handle) }
    }

    fun digest(path: String, maxBytes: Long): Digest = memScoped {
        val fd = open(path, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
        check(fd >= 0) { "Cannot open compiler tool file: $path (errno=$errno)" }
        try {
            val before = alloc<stat>(); val after = alloc<stat>()
            check(fstat(fd, before.ptr) == 0)
            require(before.st_mode.toInt() and S_IFMT == S_IFREG && before.st_size in 0..maxBytes) { "Unsupported compiler tool file type/size: $path" }
            val digest = StreamHash().use { hash ->
                ByteArray(65536).usePinned { buffer ->
                    while (true) {
                        val count = read(fd, buffer.addressOf(0), 65536u).toInt()
                        if (count < 0 && errno == EINTR) continue
                        check(count >= 0) { "Cannot read compiler tool file: $path (errno=$errno)" }
                        if (count == 0) break
                        require(hash.size + count <= maxBytes) { "Compiler tool file grew beyond limit: $path" }
                        hash.update(buffer.get(), count)
                    }
                }
                hash.finish()
            }
            check(fstat(fd, after.ptr) == 0)
            require(before.st_size == digest.sizeBytes && after.st_size == before.st_size && after.st_mode == before.st_mode &&
                before.st_mtimespec.tv_sec == after.st_mtimespec.tv_sec && before.st_mtimespec.tv_nsec == after.st_mtimespec.tv_nsec &&
                before.st_ctimespec.tv_sec == after.st_ctimespec.tv_sec && before.st_ctimespec.tv_nsec == after.st_ctimespec.tv_nsec) { "Compiler tool changed while hashing: $path" }
            digest
        } finally { close(fd) }
    }
}
