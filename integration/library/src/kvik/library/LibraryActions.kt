package kvik.library

import kvik.core.*

/** [api] is emitted API visibility: Native compile dependencies are visible, runtime-only ones are not. */
data class LibraryDependency(val coordinate: Coordinate, val api: Boolean) {
    fun toJson(): JsonValue = obj("coordinate" to coordinate.toString().json(), "api" to api.json())
    companion object {
        fun fromJson(value: JsonValue): LibraryDependency = value.fields(setOf("coordinate", "api")).let {
            LibraryDependency(Coordinate.parse(it.getValue("coordinate").stringValue()), it.getValue("api").booleanValue())
        }
    }
}

/** Coordinate is the final target coordinate, not a multiplatform root publication. */
data class LibraryPackage(
    val module: String, val target: String, val coordinate: Coordinate,
    val dependencies: List<LibraryDependency>, val jvmTarget: Int? = null,
) {
    init {
        require(module.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) && module.length <= 256)
        require(target in setOf("jvm", "macosArm64")) { "Unsupported library target" }
        if (target == "jvm") supportedJvmTarget(requireNotNull(jvmTarget) { "JVM library target is required" }.toString())
        else require(jvmTarget == null) { "Native library cannot declare a JVM target" }
        require(dependencies.size <= 1000 && dependencies.map { it.coordinate.group to it.coordinate.name }.distinct().size == dependencies.size) {
            "Duplicate or conflicting library dependency"
        }
        require(dependencies.none { it.coordinate.group == coordinate.group && it.coordinate.name == coordinate.name }) { "Library cannot depend on itself" }
        require((dependencies.map { it.coordinate } + coordinate).all { it.toString().length <= 1024 }) { "Library coordinate exceeds bound" }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "module" to module.json(),
        "target" to target.json(), "coordinate" to coordinate.toString().json(),
        "dependencies" to dependencies.sortedBy { it.coordinate.toString() }.map { it.toJson() }.json(),
        "jvmTarget" to (jvmTarget?.json() ?: JsonValue.Null))
    companion object {
        fun fromJson(value: JsonValue): LibraryPackage = value.fields(setOf("schemaVersion", "module", "target", "coordinate", "dependencies", "jvmTarget")).let {
            it.requireVersion()
            val jvm = it.getValue("jvmTarget")
            LibraryPackage(it.getValue("module").stringValue(), it.getValue("target").stringValue(),
                Coordinate.parse(it.getValue("coordinate").stringValue()), it.getValue("dependencies").arrayValue().map(LibraryDependency::fromJson),
                if (jvm == JsonValue.Null) null else jvm.longValue().also { number -> require(number in 1..Int.MAX_VALUE) }.toInt())
        }
    }
}

internal const val LIBRARY_BYTE_LIMIT = 64 * 1024 * 1024
internal const val LIBRARY_METADATA_LIMIT = 1024 * 1024
internal val jarManifest = "Manifest-Version: 1.0\r\n\r\n".encodeToByteArray()

fun libraryPackageAction(selection: LibraryPackage, inputs: TreeManifest, composition: Digest): ActionSpec {
    val selected = LibraryPackage.fromJson(parseJson(selection.toJson().canonical()))
    val declared = TreeManifest(inputs.files.sortedBy { it.path.value })
    require(declared.files.size <= 10000) { "Library input file count exceeds 10000" }
    var total = 0L
    declared.files.forEach { file ->
        require(file.digest.sizeBytes <= LIBRARY_BYTE_LIMIT) { "Library input exceeds 64 MiB" }
        total += file.digest.sizeBytes
        require(total <= LIBRARY_BYTE_LIMIT) { "Library inputs exceed 64 MiB" }
    }
    if (selected.target == "jvm") {
        require(declared.files.all { it.path.value.startsWith("classes/") }) { "JVM library inputs must be under classes/" }
        val entries = declared.files.map { it.copy(path = LogicalPath(it.path.value.removePrefix("classes/"))) }
        validateJarEntries(entries)
        storedZipSize(entries + FileEntry(LogicalPath("META-INF/MANIFEST.MF"), Sha256.digest(jarManifest)))
    } else {
        require(declared.files.size == 1 && declared.files.single().path.value == "module.klib" && !declared.files.single().executable) {
            "Native library inputs must contain only nonexecutable module.klib"
        }
    }
    require(selected.toJson().canonical().encodeToByteArray().size <= LIBRARY_METADATA_LIMIT) { "Library metadata exceeds 1 MiB" }
    return ActionSpec("kvik-library-package-v1", composition, ActionKind.PACKAGE,
        mapOf("format" to "maven-library-v1"), selected.target, mapOf("kvik" to composition), declared,
        listOf(LogicalPath("library")), emptyList(), emptyMap(), selected.toJson(), 120_000, CachePolicy.LOCAL)
}

private fun validateJarEntries(entries: List<FileEntry>) {
    val all = entries + FileEntry(LogicalPath("META-INF/MANIFEST.MF"), Sha256.digest(jarManifest))
    TreeManifest(all) // Exact duplicates and file/directory collisions, including the reserved manifest.
    val spellings = mutableMapOf<String, String>()
    val files = all.map { it.path.value.lowercase() }.toSet()
    for (entry in all) {
        val parts = entry.path.value.split('/')
        for (size in 1..parts.size) {
            val path = parts.take(size).joinToString("/")
            val folded = path.lowercase()
            val prior = spellings.put(folded, path)
            require(prior == null || prior == path) { "JAR path case collision: $path" }
            require(size == parts.size || folded !in files) { "JAR file/directory case collision: $path" }
        }
    }
}
