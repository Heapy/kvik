package kvik.library

import kvik.core.*

/** A published shared source set whose metadata is supplied by a compiler action. */
data class KmpSourceSetMetadata(val name: String, val dependsOn: List<String>, val dependencies: List<Coordinate>) {
    init {
        require(validSourceSet(name) && name.lowercase() != "meta-inf") { "Invalid shared metadata source set" }
        require(dependsOn.size <= 128 && dependsOn.distinct().size == dependsOn.size && dependsOn.all(::validSourceSet))
        require(name !in dependsOn)
        require(dependencies.size <= 1000 && dependencies.map { it.group to it.name }.distinct().size == dependencies.size) {
            "Duplicate or conflicting shared dependency"
        }
        require(dependencies.all { it.toString().length <= 1024 })
    }
    fun toJson(): JsonValue = obj("name" to name.json(), "dependsOn" to dependsOn.sorted().map { it.json() }.json(),
        "dependencies" to dependencies.sortedBy { it.toString() }.map { it.toString().json() }.json())
    companion object {
        fun fromJson(value: JsonValue): KmpSourceSetMetadata = value.fields(setOf("name", "dependsOn", "dependencies")).let {
            KmpSourceSetMetadata(it.getValue("name").stringValue(), it.getValue("dependsOn").arrayValue().map { item -> item.stringValue() },
                it.getValue("dependencies").arrayValue().map { item -> Coordinate.parse(item.stringValue()) })
        }
    }
}
private fun validSourceSet(value: String) = value.length <= 128 && value.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))

data class KmpLibraryPackage(
    val module: String, val coordinate: Coordinate, val targets: List<LibraryPackage>,
    val sourceSets: List<KmpSourceSetMetadata>, val sourceSetsByTarget: Map<String, List<String>>,
) {
    init {
        require(module.length <= 256 && module.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")))
        require(coordinate.toString().length <= 1024)
        require(targets.size in 1..2 && targets.map { it.target }.distinct().size == targets.size)
        require(targets.all { it.module == module }) { "Target publication belongs to a different module" }
        require(targets.all { it.rootCoordinate == coordinate }) { "Target publication must identify its root owner" }
        require((targets.map { it.coordinate } + coordinate).map { it.group to it.name }.distinct().size == targets.size + 1) {
            "Root and target publication coordinates must be distinct"
        }
        require(sourceSets.size in 1..128 && sourceSets.map { it.name.lowercase() }.distinct().size == sourceSets.size)
        require(sourceSets.sumOf { it.dependencies.size } <= 1000) { "Shared dependency references exceed 1000" }
        val byName = sourceSets.associateBy { it.name }
        require(sourceSets.all { byName.keys.containsAll(it.dependsOn) }) { "Unknown shared source-set dependency" }
        val visited = mutableSetOf<String>()
        val active = mutableSetOf<String>()
        fun visit(name: String) {
            if (name in visited) return
            require(active.add(name)) { "Shared source-set cycle" }
            byName.getValue(name).dependsOn.forEach(::visit)
            active.remove(name); visited.add(name)
        }
        byName.keys.forEach(::visit)
        require(sourceSetsByTarget.keys == targets.map { it.target }.toSet()) { "Shared source mappings must cover exactly the declared targets" }
        for (names in sourceSetsByTarget.values) {
            require(names.isNotEmpty() && names.size <= 128 && names.distinct().size == names.size && byName.keys.containsAll(names))
            require(names.all { names.containsAll(byName.getValue(it).dependsOn) }) { "Target mapping omits shared dependsOn closure" }
        }
        require(sourceSetsByTarget.values.flatten().toSet() == byName.keys) { "Unmapped shared source set" }
        val coordinates = sourceSets.flatMap { it.dependencies }
        require(coordinates.groupBy { it.group to it.name }.all { (_, values) -> values.distinct().size == 1 }) {
            "Conflicting shared metadata dependency versions"
        }
        require(coordinates.none { it.group == coordinate.group && it.name == coordinate.name }) { "Root metadata cannot depend on itself" }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "module" to module.json(),
        "coordinate" to coordinate.toString().json(), "targets" to targets.sortedBy { it.target }.map { it.toJson() }.json(),
        "sourceSets" to sourceSets.sortedBy { it.name }.map { it.toJson() }.json(),
        "sourceSetsByTarget" to JsonValue.Object(sourceSetsByTarget.mapValues { (_, names) -> names.sorted().map { it.json() }.json() }))
    companion object {
        fun fromJson(value: JsonValue): KmpLibraryPackage = value.fields(setOf("schemaVersion", "module", "coordinate", "targets", "sourceSets", "sourceSetsByTarget")).let {
            it.requireVersion()
            KmpLibraryPackage(it.getValue("module").stringValue(), Coordinate.parse(it.getValue("coordinate").stringValue()),
                it.getValue("targets").arrayValue().map(LibraryPackage::fromJson),
                it.getValue("sourceSets").arrayValue().map(KmpSourceSetMetadata::fromJson),
                it.getValue("sourceSetsByTarget").objectValue().mapValues { (_, names) -> names.arrayValue().map { item -> item.stringValue() } })
        }
    }
}

internal const val PROJECT_STRUCTURE_PATH = "META-INF/kotlin-project-structure-metadata.json"

fun kmpLibraryPackageAction(selection: KmpLibraryPackage, inputs: TreeManifest, composition: Digest): ActionSpec {
    val selected = KmpLibraryPackage.fromJson(parseJson(selection.toJson().canonical()))
    val declared = TreeManifest(inputs.files.sortedBy { it.path.value })
    require(declared.files.size <= 10000) { "KMP input file count exceeds 10000" }
    var total = 0L
    declared.files.forEach {
        require(!it.executable && it.digest.sizeBytes <= LIBRARY_BYTE_LIMIT) { "KMP inputs must be bounded nonexecutable files" }
        total += it.digest.sizeBytes
        require(total <= LIBRARY_BYTE_LIMIT) { "KMP inputs exceed 64 MiB" }
    }
    val expectedTargets = selected.targets.flatMap { target ->
        val base = "${target.directory()}/${target.basename()}"
        val artifact = declared.files.singleOrNull { it.path.value == "$base.${target.extension()}" }
            ?: error("Missing declared target artifact: ${target.target}")
        listOf(artifact, FileEntry(LogicalPath("$base.pom"), Sha256.digest(target.pom())),
            FileEntry(LogicalPath("$base.module"), Sha256.digest(target.moduleMetadata(artifact.digest))))
    }.sortedBy { it.path.value }
    require(declared.files.filter { it.path.value.startsWith("library/") } == expectedTargets) {
        "Target publication inputs differ from selected coordinates, dependencies, attributes or artifact digest"
    }
    val sourceNames = selected.sourceSets.map { it.name }.toSet()
    val metadata = declared.files.filter { it.path.value.startsWith("metadata/") }
    require(metadata.size + expectedTargets.size == declared.files.size && metadata.all {
        val pieces = it.path.value.split('/')
        pieces.size >= 4 && pieces[1] in sourceNames
    }) { "Unexpected KMP package input" }
    for (source in sourceNames) {
        require(metadata.any { it.path.value == "metadata/$source/default/manifest" } &&
            metadata.any { it.path.value == "metadata/$source/default/linkdata/module" }) {
            "Missing compiler-produced KLIB metadata for $source"
        }
    }
    val psm = selected.projectStructure()
    val entries = metadata.map { it.copy(path = LogicalPath(it.path.value.removePrefix("metadata/"))) } +
        FileEntry(LogicalPath(PROJECT_STRUCTURE_PATH), Sha256.digest(psm))
    validateJarEntries(entries)
    storedZipSize((entries + FileEntry(LogicalPath("META-INF/MANIFEST.MF"), Sha256.digest(jarManifest))).sortedBy { it.path.value }, includeDirectories = true)
    require(selected.toJson().canonical().encodeToByteArray().size <= LIBRARY_METADATA_LIMIT) { "KMP package selection exceeds 1 MiB" }
    return ActionSpec("kvik-kmp-library-package-v1", composition, ActionKind.PACKAGE, mapOf("format" to "maven-kmp-library-v1"),
        "common", mapOf("kvik" to composition), declared, listOf(LogicalPath("library")), emptyList(), emptyMap(),
        selected.toJson(), 120_000, CachePolicy.LOCAL)
}
