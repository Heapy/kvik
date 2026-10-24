package kvik.core

private fun sourceIdentity(value: String) {
    require(value.length in 1..4096 && value.none { it.code < 32 || it.code == 127 }) { "Invalid source identity" }
    value.json().canonical()
}

/** Provider-native IDs are provenance, never Kvik content digests or portable action identity. */
data class SourceProvenance(val provider: String, val project: String, val revision: String, val tree: String) {
    init { listOf(provider, project, revision, tree).forEach(::sourceIdentity) }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "provider" to provider.json(),
        "project" to project.json(), "revision" to revision.json(), "tree" to tree.json())
    companion object {
        fun fromJson(value: JsonValue): SourceProvenance {
            val fields = value.fields(setOf("schemaVersion", "provider", "project", "revision", "tree")); fields.requireVersion()
            return SourceProvenance(fields.getValue("provider").stringValue(), fields.getValue("project").stringValue(),
                fields.getValue("revision").stringValue(), fields.getValue("tree").stringValue())
        }
    }
}

/** Immutable source context. Paths in diagnostics are relative to root inside this revision.
 * This context never participates in portable action identities. */
data class SourceReference(val provenance: SourceProvenance, val root: String, val manifest: Digest) {
    init { if (root.isNotEmpty()) LogicalPath(root) }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "provenance" to provenance.toJson(),
        "root" to root.json(), "manifest" to manifest.toJson())
    companion object {
        fun fromJson(value: JsonValue): SourceReference {
            val fields = value.fields(setOf("schemaVersion", "provenance", "root", "manifest")); fields.requireVersion()
            return SourceReference(SourceProvenance.fromJson(fields.getValue("provenance")), fields.getValue("root").stringValue(),
                Digest.fromJson(fields.getValue("manifest")))
        }
    }
}
