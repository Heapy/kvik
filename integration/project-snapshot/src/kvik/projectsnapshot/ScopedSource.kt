package kvik.projectsnapshot

import kvik.core.*

/** A directory view of an already resolved revision. Scoping changes paths, never blob identities or provenance. */
fun ResolvedSourceSnapshot.scopedTo(root: String, checkpoint: () -> Unit = {}): ResolvedSourceSnapshot {
    checkpoint()
    val directory = root.takeIf(String::isNotEmpty)?.let(::LogicalPath)
    val source = this
    val provenance = source.provenance.copy()
    val original = source.manifest
    val directories = original.directories
    require(directory == null || directory in directories) { "Source project root is not a declared directory: $root" }
    val prefix = directory?.let { "${it.value}/" }.orEmpty()
    val files = original.files.mapNotNull { entry ->
        checkpoint()
        if (entry.path.value.startsWith(prefix)) entry.copy(path = LogicalPath(entry.path.value.removePrefix(prefix))) else null
    }
    val folders = directories.mapNotNull { path ->
        checkpoint()
        if (path.value.startsWith(prefix)) LogicalPath(path.value.removePrefix(prefix)) else null
    }
    val manifest = SourceManifest(files, folders, checkpoint)
    val blobs = files.map { checkpoint(); it.blob }.toSet()
    checkpoint()
    return object : ResolvedSourceSnapshot {
        override val provenance = provenance
        override val manifest = manifest

        private fun requireDeclared(blob: SourceBlob) {
            require(blob in blobs) { "Blob does not belong to the scoped source inventory" }
        }

        override fun knownDigest(blob: SourceBlob): Digest? {
            requireDeclared(blob)
            return source.knownDigest(blob)
        }

        override fun fetch(blob: SourceBlob, maxBytes: Int, checkpoint: () -> Unit): ByteArray? {
            checkpoint(); requireDeclared(blob)
            return source.fetch(blob, maxBytes, checkpoint).also { checkpoint() }
        }

        override fun verify(blob: SourceBlob, bytes: ByteArray, checkpoint: () -> Unit) {
            checkpoint(); requireDeclared(blob)
            source.verify(blob, bytes, checkpoint)
            checkpoint()
        }
    }
}
