package kvik.bsp

import kvik.core.*

/** Binary or text resource files from a completed generator in a retained BSP run. */
data class BspGeneratedResources(
    val directory: String,
    val producer: String,
    val actionKey: Digest,
    val files: TreeManifest,
) {
    init {
        require(directory.startsWith('/') && !directory.endsWith('/'))
        bspFileUri(directory)
        require(directory.split('/').none { it == "." || it == ".." })
        require(producer.isNotEmpty() && producer.length <= 256)
        require(files.files.size in 1..10_000 && files.files.all {
            !it.executable && it.path.value.startsWith("generated/") && it.digest.sizeBytes <= 8L * 1024 * 1024
        }) { "Unsupported generated resource files" }
        require(files.files.sumOf { it.digest.sizeBytes } <= 64L * 1024 * 1024) { "Generated resource manifest exceeds limit" }
    }

    fun uri(file: FileEntry): String = bspFileUri("$directory/${file.path.value}")
}
