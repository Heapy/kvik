package kvik.cache

import kvik.core.*

/** Capture requires a quiescent producer tree; restore requires a new destination. */
class NativeOutputTrees(private val content: ContentStore) {
    fun capture(root: String): TreeManifest {
        NativeFiles.ensureDirectory(root, create = false)
        val files = mutableListOf<FileEntry>()
        fun visit(directory: String, prefix: String) {
            for (name in NativeFiles.names(directory)) {
                val logical = LogicalPath(if (prefix.isEmpty()) name else "$prefix/$name")
                val path = "$directory/$name"
                val status = NativeFiles.info(path) ?: throw CacheFailure("Output disappeared: $path")
                when {
                    NativeFiles.directory(status) -> visit(path, logical.value)
                    NativeFiles.regular(status) -> {
                        val limit = (content as? NativeContentStore)?.maxBlobBytes ?: 64 * 1024 * 1024
                        files += FileEntry(logical, content.put(NativeFiles.read(path, limit)), status.mode and 73 != 0)
                    }
                    else -> throw CacheFailure("Symlink or special output rejected: $path")
                }
            }
        }
        visit(root, "")
        return TreeManifest(files.toList())
    }

    fun materialize(manifest: TreeManifest, destination: String) {
        val snapshot = TreeManifest(manifest.files.toList())
        NativeFiles.validateAbsolute(destination)
        val parent = destination.substringBeforeLast('/')
        NativeFiles.ensureDirectory(parent, create = false)
        if (NativeFiles.info(destination) != null) throw CacheFailure("Destination already exists: $destination")
        val stage = NativeFiles.tempDirectory(parent)
        try {
            // Track spelling of every directory. Exclusive mkdir catches aliases on this actual filesystem.
            val directories = mutableSetOf<String>()
            for (file in snapshot.files.sortedBy { it.path.value }) {
                val parts = file.path.value.split('/')
                for (length in 1 until parts.size) {
                    val relative = parts.take(length).joinToString("/")
                    if (directories.add(relative)) createDirectoryExclusive("$stage/$relative")
                }
                val bytes = content.get(file.digest) ?: throw CacheFailure("Cannot restore ${file.path.value}: blob unavailable")
                if (Sha256.digest(bytes) != file.digest) throw CacheFailure("Content store returned invalid bytes")
                NativeFiles.writeNew("$stage/${file.path.value}", bytes, file.executable)
            }
            for (directory in directories.sortedByDescending { it.length }) NativeFiles.syncDirectory("$stage/$directory")
            NativeFiles.syncDirectory(stage)
            NativeFiles.exposeDirectory(stage, destination)
        } finally { NativeFiles.removeTree(stage) }
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun createDirectoryExclusive(path: String) {
    if (platform.posix.mkdir(path, 448u) != 0) NativeFiles.fail("Directory collision $path")
}
