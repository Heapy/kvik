package kvik.core

/** A location in the retained configuration document. Pointer text is preserved as supplied. */
data class ConfigurationOrigin(val file: LogicalPath, val pointer: String) {
    fun toJson(): JsonValue.Object = obj("file" to file.value.json(), "pointer" to pointer.json())

    companion object {
        fun fromJson(value: JsonValue): ConfigurationOrigin {
            val fields = value.fields(setOf("file", "pointer"))
            return ConfigurationOrigin(LogicalPath(fields.getValue("file").stringValue()), fields.getValue("pointer").stringValue())
        }
    }
}

/** Configuration-relative ownership. Null tags retain the older v1 representation without tags. */
data class FileInspection(
    val path: LogicalPath,
    val modules: List<String>,
    val sourceSets: List<String>,
    val targets: List<String>,
    val tags: List<String>? = null,
) {
    init {
        require((modules + sourceSets).all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) }) {
            "Invalid inspection ownership identifier"
        }
        if (tags != null) {
            require(tags.distinct().size == tags.size) { "Duplicate inspection file tag" }
            TaggedProjectFile(path, tags.toSet())
        }
    }

    fun toJson(): JsonValue.Object = JsonValue.Object(buildMap {
        put("path", path.value.json())
        put("modules", modules.map { it.json() }.json())
        put("sourceSets", sourceSets.map { it.json() }.json())
        put("targets", targets.map { it.json() }.json())
        tags?.let { put("tags", it.map { tag -> tag.json() }.json()) }
    })

    companion object {
        fun fromJson(value: JsonValue): FileInspection {
            val fields = value.fields(setOf("path", "modules", "sourceSets", "targets"), setOf("tags"))
            return FileInspection(LogicalPath(fields.getValue("path").stringValue()),
                fields.getValue("modules").arrayValue().map(JsonValue::stringValue),
                fields.getValue("sourceSets").arrayValue().map(JsonValue::stringValue),
                fields.getValue("targets").arrayValue().map(JsonValue::stringValue),
                fields["tags"]?.arrayValue()?.map(JsonValue::stringValue))
        }
    }
}

/**
 * Read-only inspection contract for consumers of local or provider-backed projects.
 * The project retains the authored document; configuration identifies effective resolver settings.
 * Source context is a single optional value so its three wire fields cannot be partially populated.
 */
data class ProjectInspection(
    val project: ProjectConfiguration,
    val configuration: Digest,
    val composition: Digest,
    val files: List<FileInspection>,
    val origins: Map<String, ConfigurationOrigin>,
    val capabilities: List<String>,
    val source: SourceReference? = null,
) {
    fun toJson(): JsonValue.Object = JsonValue.Object(buildMap {
        put("schemaVersion", CONTRACT_VERSION.json())
        put("project", project.document)
        put("configuration", configuration.toJson())
        put("composition", composition.toJson())
        put("files", files.map { it.toJson() }.json())
        put("origins", JsonValue.Object(origins.mapValues { it.value.toJson() }))
        put("capabilities", capabilities.map { it.json() }.json())
        source?.let {
            put("source", it.provenance.toJson())
            put("sourceRoot", it.root.json())
            put("sourceManifest", it.manifest.toJson())
        }
    })

    companion object {
        fun fromJson(value: JsonValue): ProjectInspection {
            val sourceFields = setOf("source", "sourceRoot", "sourceManifest")
            val fields = value.fields(setOf("schemaVersion", "project", "configuration", "composition", "files", "origins", "capabilities"), sourceFields)
            fields.requireVersion()
            val present = fields.keys.intersect(sourceFields)
            require(present.isEmpty() || present == sourceFields) { "Inspection source context requires source, sourceRoot and sourceManifest" }
            val source = if (present.isEmpty()) null else SourceReference(
                SourceProvenance.fromJson(fields.getValue("source")), fields.getValue("sourceRoot").stringValue(),
                Digest.fromJson(fields.getValue("sourceManifest")))
            return ProjectInspection(parseProjectConfiguration(fields.getValue("project")),
                Digest.fromJson(fields.getValue("configuration")), Digest.fromJson(fields.getValue("composition")),
                fields.getValue("files").arrayValue().map(FileInspection::fromJson),
                fields.getValue("origins").objectValue().mapValues { ConfigurationOrigin.fromJson(it.value) },
                fields.getValue("capabilities").arrayValue().map(JsonValue::stringValue), source)
        }
    }
}
