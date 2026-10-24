package kvik.kodesource

import kvik.core.*

/** A source pin acquired for a run. This is not a claim that the pin or external tools still exist. */
data class KodeRetentionReference(val project: String, val name: String, val record: String, val revision: String, val tree: String) {
    init {
        require(project.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")) && name.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
            "Invalid Kode retention identity"
        }
        require(record.length in setOf(40, 64) && listOf(record, revision, tree).all { value ->
            value.length == record.length && value.any { it != '0' } && value.all { it in '0'..'9' || it in 'a'..'f' }
        }) { "Invalid Kode retention object identity" }
    }

    fun requireSource(source: SourceProvenance) {
        require(source.provider == "kode" && source.project == project && source.revision == revision && source.tree == tree) {
            "Retained Kode source differs from selected source"
        }
    }

    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "project" to project.json(),
        "name" to name.json(), "record" to record.json(), "revision" to revision.json(), "tree" to tree.json())

    companion object {
        fun fromJson(value: JsonValue): KodeRetentionReference {
            val fields = value.fields(setOf("schemaVersion", "project", "name", "record", "revision", "tree"))
            fields.requireVersion()
            return KodeRetentionReference(fields.getValue("project").stringValue(), fields.getValue("name").stringValue(),
                fields.getValue("record").stringValue(), fields.getValue("revision").stringValue(), fields.getValue("tree").stringValue())
        }
    }
}
