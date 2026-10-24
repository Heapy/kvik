package kvik.core

val SOURCE_ROLE_CAPABILITIES: Set<String> = setOf("task-create", "task-update", "ref-create", "ref-update",
    "ref-delete", "project-add", "merge")

/** An invocation policy exported by a trusted caller; not an authentication or sandbox boundary. */
data class SourceRolePolicy(
    val name: String,
    val view: SourceView,
    val tagRules: ProjectFileTagRules,
    val editable: FileTagPredicate?,
    val capabilities: Set<String> = emptySet(),
) {
    init {
        require(name.length in 1..128 && name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid source role policy name" }
        require(capabilities.all { it in SOURCE_ROLE_CAPABILITIES }) { "Unknown source role capability" }
        require(view.files.all { file -> file.tags == tagRules.tags(relativePath(file.path)) }) {
            "Source role view tags do not match classification rules"
        }
        requirePolicySize(toJson())
    }

    private fun relativePath(path: LogicalPath): LogicalPath {
        val root = view.source.root
        require(root.isEmpty() || path.value.startsWith("$root/")) { "Source role path is outside source root" }
        return LogicalPath(if (root.isEmpty()) path.value else path.value.removePrefix("$root/"))
    }

    fun canEdit(path: LogicalPath): Boolean {
        val root = view.source.root
        if (root.isNotEmpty() && !path.value.startsWith("$root/")) return false
        return editable?.matches(tagRules.tags(relativePath(path))) ?: false
    }

    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "source-role-policy".json(),
        "name" to name.json(), "view" to view.toJson(), "tagRules" to tagRules.toJson(),
        "editable" to (editable?.toJson() ?: JsonValue.Null), "capabilities" to capabilities.sorted().map { it.json() }.json())

    companion object {
        fun fromJson(value: JsonValue): SourceRolePolicy {
            requirePolicySize(value)
            val fields = value.fields(setOf("schemaVersion", "kind", "name", "view", "tagRules", "editable", "capabilities"))
            fields.requireVersion()
            require(fields.getValue("kind").stringValue() == "source-role-policy") { "Expected source-role-policy kind" }
            val capabilities = fields.getValue("capabilities").arrayValue().map(JsonValue::stringValue)
            require(capabilities.toSet().size == capabilities.size) { "Duplicate source role capability" }
            return SourceRolePolicy(fields.getValue("name").stringValue(), SourceView.fromJson(fields.getValue("view")),
                ProjectFileTagRules.fromJson(fields.getValue("tagRules")),
                fields.getValue("editable").let { if (it == JsonValue.Null) null else FileTagPredicate.fromJson(it) },
                capabilities.toSet())
        }
    }
}

private fun requirePolicySize(value: JsonValue) {
    val text = value.canonical()
    require(text.length <= 16 * 1024 * 1024 && text.encodeToByteArray().size <= 16 * 1024 * 1024) {
        "Source role policy exceeds 16 MiB"
    }
}
