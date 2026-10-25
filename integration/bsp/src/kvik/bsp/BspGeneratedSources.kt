package kvik.bsp

import kvik.core.*

/** Materialized source files from one completed generator, owned by a retained BSP run. */
data class BspGeneratedSources(
    val directory: String,
    val producer: String,
    val actionKey: Digest,
    val files: TreeManifest,
    /** Exact adapter sources for diagnostics; excluded from the IDE's compilation source list. */
    val diagnosticFiles: TreeManifest = TreeManifest(emptyList()),
) {
    init {
        require(directory.startsWith('/') && !directory.endsWith('/'))
        bspFileUri(directory)
        require(directory.split('/').none { it == "." || it == ".." })
        require(producer.isNotEmpty() && producer.length <= 256)
        require(files.files.size in 1..10_000)
        val all = files.files + diagnosticFiles.files
        require(all.map { it.path }.distinct().size == all.size) { "Conflicting generated and diagnostic source paths" }
        require(all.size <= 10_000 && all.all { !it.executable && it.path.value.endsWith(".kt") }) { "Generated sources must contain Kotlin text files" }
        require(all.all { it.digest.sizeBytes <= 64L * 1024 * 1024 })
        require(all.sumOf { it.digest.sizeBytes } <= 64L * 1024 * 1024) { "Generated source manifest exceeds limit" }
    }

    fun uri(file: FileEntry): String = bspFileUri("$directory/${file.path.value}")
    val diagnosticDirectory: String get() = "$directory-diagnostics"
    fun diagnosticUri(file: FileEntry): String = bspFileUri("$diagnosticDirectory/${file.path.value}")
    fun documentUris(): List<String> = files.files.map(::uri) + diagnosticFiles.files.map(::diagnosticUri)

    fun sourceItems(): List<JsonValue> = files.files.sortedBy { it.path.value }.map { file ->
        obj("uri" to uri(file).json(), "kind" to 1.json(), "generated" to true.json(),
            "dataKind" to "kvik-generated-source".json(), "data" to obj(
                "schemaVersion" to CONTRACT_VERSION.json(), "producer" to producer.json(),
                "actionKey" to actionKey.toJson(), "logicalPath" to file.path.value.json(), "digest" to file.digest.toJson()).let { data ->
                    if (diagnosticFiles.files.isEmpty()) data else JsonValue.Object(data.values + ("diagnosticSources" to
                        diagnosticFiles.files.map { obj("uri" to diagnosticUri(it).json(), "logicalPath" to it.path.value.json(), "digest" to it.digest.toJson()) }.json()))
                })
    }
}
