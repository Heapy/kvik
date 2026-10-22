package kvik.core

/** Explicit correction of one dependency edge in exact immutable producer metadata. */
data class MetadataEdgeExclusion(
    val parent: Coordinate,
    val metadata: Digest,
    val variant: String,
    val dependency: Coordinate,
    val reason: String,
) {
    init {
        require(parent.toString().length <= 1024 && dependency.toString().length <= 1024)
        require(metadata.sizeBytes in 1..1024 * 1024)
        require(variant.isNotBlank() && variant.length <= 256 && variant.none { it.code < 32 })
        require(reason.isNotBlank() && reason.length <= 1024 && reason.none { it.code < 32 })
    }
    fun toJson(): JsonValue.Object = obj("parent" to parent.toString().json(), "metadata" to metadata.toJson(),
        "variant" to variant.json(), "dependency" to dependency.toString().json(), "reason" to reason.json())
    val digest: Digest get() = Sha256.digest(toJson())
    companion object {
        fun fromJson(value: JsonValue): MetadataEdgeExclusion {
            val d = value.fields(setOf("parent", "metadata", "variant", "dependency", "reason"))
            return MetadataEdgeExclusion(Coordinate.parse(d.getValue("parent").stringValue()), Digest.fromJson(d.getValue("metadata")),
                d.getValue("variant").stringValue(), Coordinate.parse(d.getValue("dependency").stringValue()), d.getValue("reason").stringValue())
        }
    }
}
