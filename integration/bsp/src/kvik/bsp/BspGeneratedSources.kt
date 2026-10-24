package kvik.bsp

import kvik.core.*

/** Materialized source files from one completed generator, owned by a retained BSP run. */
data class BspGeneratedSources(
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
        require(files.files.size in 1..10_000)
        require(files.files.all { !it.executable && it.path.value.endsWith(".kt") }) { "Generated sources must contain Kotlin text files" }
        require(files.files.all { it.digest.sizeBytes <= 64L * 1024 * 1024 })
        require(files.files.sumOf { it.digest.sizeBytes } <= 64L * 1024 * 1024) { "Generated source manifest exceeds limit" }
    }

    fun uri(file: FileEntry): String = bspFileUri("$directory/${file.path.value}")

    fun sourceItems(): List<JsonValue> = files.files.sortedBy { it.path.value }.map { file ->
        obj("uri" to uri(file).json(), "kind" to 1.json(), "generated" to true.json(),
            "dataKind" to "kvik-generated-source".json(), "data" to obj(
                "schemaVersion" to CONTRACT_VERSION.json(), "producer" to producer.json(),
                "actionKey" to actionKey.toJson(), "logicalPath" to file.path.value.json(), "digest" to file.digest.toJson()))
    }
}
