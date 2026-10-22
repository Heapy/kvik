package kvik.jvm

import kvik.core.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

internal class AdapterFailure(val code: String, message: String) : IllegalArgumentException(message)
internal fun reject(code: String, message: String): Nothing = throw AdapterFailure(code, message)

internal fun fileDigest(path: Path): Digest {
    val hash = MessageDigest.getInstance("SHA-256")
    var size = 0L
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count); size += count
        }
    }
    return Digest(hash.digest().toHexString(), size)
}

/** Request paths are logical paths inside an orchestrator-owned action workspace. */
internal class Workspace(path: Path) {
    val root: Path = path.toRealPath()
    fun path(logical: String, mustExist: Boolean = true): Path {
        LogicalPath(logical)
        var current = root
        for (part in logical.split('/')) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) reject("KVIK_UNSAFE_PATH", "Symlink in action path: $logical")
        }
        if (mustExist && !Files.exists(current, NOFOLLOW_LINKS)) reject("KVIK_MISSING_INPUT", "Missing action input: $logical")
        return current
    }
    fun readJson(logical: String): JsonValue {
        val path = path(logical)
        if (Files.size(path) > 4 * 1024 * 1024) reject("KVIK_INVALID_REQUEST", "Request exceeds 4 MiB")
        return parseJson(Files.readString(path))
    }
    fun classpath(logical: String): Path {
        val path = path(logical)
        if (Files.isDirectory(path)) {
            Files.walk(path).use { files ->
                if (files.anyMatch { Files.isSymbolicLink(it) || (!Files.isDirectory(it) && !Files.isRegularFile(it)) })
                    reject("KVIK_UNSAFE_PATH", "Classpath tree contains a symlink or special file: $logical")
            }
        } else if (!Files.isRegularFile(path) || !logical.endsWith(".jar")) {
            reject("KVIK_UNSUPPORTED_CLASSPATH", "Classpath entries must be directories or jars")
        }
        return path.toRealPath()
    }
    fun publish(logical: String, value: JsonValue) {
        val destination = path(logical, false)
        check(!Files.exists(destination, NOFOLLOW_LINKS)) { "Result already exists" }
        val temporary = Files.createTempFile(destination.parent, ".kvik-result-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val data = ByteBuffer.wrap((value.canonical() + "\n").encodeToByteArray())
                while (data.hasRemaining()) channel.write(data)
                channel.force(true)
            }
            Files.move(temporary, destination, ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }
    fun removeTree(path: Path) {
        if (Files.exists(path)) Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
