package kvik.core

private const val PROJECT_FILE_LIMIT = 100_000
private const val SOURCE_VIEW_MAX_BYTES = 16 * 1024 * 1024
private val FILE_TAG_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.-]*:[A-Za-z0-9_.-][A-Za-z0-9_.:-]*")

private fun requireFileTag(tag: String) {
    require(FILE_TAG_PATTERN.matches(tag)) { "Invalid project file tag: $tag" }
}

/** Declarative ownership and purpose; tags do not grant access to the file. */
data class TaggedProjectFile(val path: LogicalPath, val tags: Set<String>) {
    init {
        require(tags.isNotEmpty()) { "Project file tags must be nonempty" }
        tags.forEach(::requireFileTag)
    }

    fun toJson(): JsonValue.Object = obj("path" to path.value.json(), "tags" to tags.sorted().map { it.json() }.json())

    companion object {
        fun fromJson(value: JsonValue): TaggedProjectFile {
            val fields = value.fields(setOf("path", "tags"))
            val tags = fields.getValue("tags").arrayValue().map(JsonValue::stringValue)
            require(tags.distinct().size == tags.size) { "Duplicate project file tag" }
            return TaggedProjectFile(LogicalPath(fields.getValue("path").stringValue()), tags.toSet())
        }
    }
}

/** Classifies only the supplied existing inventory, relative to the configuration directory.
 * Directory topology is checked by the caller. No file visibility or generated status is inferred. */
fun tagProjectFiles(
    configuration: ProjectConfiguration,
    configurationPath: LogicalPath,
    lockPath: LogicalPath?,
    files: List<LogicalPath>,
    checkpoint: () -> Unit = {},
): List<TaggedProjectFile> {
    require(lockPath == null || configurationPath != lockPath) { "Configuration and lock paths must differ" }
    require(files.size <= PROJECT_FILE_LIMIT) { "Project inventory exceeds 100000 files" }
    require(files.toSet().size == files.size) { "Duplicate project file path" }
    val exact = mutableMapOf<String, MutableSet<String>>()
    val descendants = mutableMapOf<String, MutableSet<String>>()
    fun add(path: LogicalPath, tags: Set<String>, directory: Boolean = false, inclusive: Boolean = false) {
        checkpoint()
        if (!directory || inclusive) exact.getOrPut(path.value) { mutableSetOf() }.addAll(tags)
        if (directory) descendants.getOrPut(path.value) { mutableSetOf() }.addAll(tags)
    }
    add(configurationPath, setOf("kind:configuration"))
    lockPath?.let { add(it, setOf("kind:lock")) }
    val project = configuration.project
    for (module in project.modules) {
        checkpoint()
        val moduleTag = "module:${module.id}"
        val compilationTags = mutableMapOf<String, MutableSet<String>>()
        for (target in module.targets) {
            checkpoint()
            val production = sourceSetClosure(module, listOf(target.main)).map { it.name }.toSet()
            val tests = target.test?.let { sourceSetClosure(module, listOf(it)).map { source -> source.name }.toSet() }
                ?: emptySet()
            for (name in production) compilationTags.getOrPut(name) { mutableSetOf() }
                .addAll(setOf("target:${target.name}", "scope:production"))
            for (name in tests - production) compilationTags.getOrPut(name) { mutableSetOf() }
                .addAll(setOf("target:${target.name}", "scope:test"))
            target.abi?.let {
                add(it.baseline, setOf(moduleTag, "target:${target.name}", "scope:production", "kind:abi-baseline", "api:public"))
            }
        }
        for (sourceSet in module.sourceSets) {
            val tags = setOf(moduleTag, "source-set:${sourceSet.name}") + compilationTags[sourceSet.name].orEmpty()
            sourceSet.directories.forEach { add(it, tags + "kind:source", directory = true) }
            sourceSet.resources.forEach { add(it, tags + "kind:resource", directory = true) }
        }
        for (plugin in module.compilerPlugins) {
            val tags = setOf(moduleTag, "kind:plugin-input") + plugin.targets.map { "target:$it" } +
                plugin.scopes.map { "scope:${if (it == "main") "production" else it}" }
            plugin.inputs.forEach { add(it, tags, directory = true, inclusive = true) }
        }
        module.documentation?.includes?.forEach {
            add(it, setOf(moduleTag, "kind:documentation"), directory = true, inclusive = true)
        }
    }
    for ((name, script) in project.scripts) {
        checkpoint()
        val tags = setOf("script:$name", "target:jvm", "scope:script")
        script.sources.forEach { add(it, tags + setOf("kind:source", "kind:script"), directory = true, inclusive = true) }
        script.resources.forEach { add(it, tags + "kind:resource", directory = true, inclusive = true) }
        expandScript(project, name).compilerPlugins.flatMap { it.value.inputs }.forEach {
            add(it, tags + "kind:plugin-input", directory = true, inclusive = true)
        }
    }
    return files.sortedBy { it.value }.map { path ->
        checkpoint()
        val tags = exact[path.value]?.toMutableSet() ?: mutableSetOf()
        var slash = path.value.indexOf('/')
        while (slash >= 0) {
            descendants[path.value.substring(0, slash)]?.let(tags::addAll)
            slash = path.value.indexOf('/', slash + 1)
        }
        TaggedProjectFile(path, tags.ifEmpty { setOf("kind:unclassified") })
    }
}

/** Conjunction of all tags, an optional disjunction, and exclusions. */
data class FileTagPredicate(
    val all: Set<String> = emptySet(),
    val any: Set<String> = emptySet(),
    val none: Set<String> = emptySet(),
) {
    init {
        for (tags in listOf(all, any, none)) {
            require(tags.size <= 64) { "File tag predicate exceeds 64 tags per clause" }
            tags.forEach(::requireFileTag)
        }
    }

    fun matches(tags: Set<String>): Boolean = tags.containsAll(all) && (any.isEmpty() || any.any { it in tags }) && none.none { it in tags }

    fun toJson(): JsonValue.Object = obj("all" to all.sorted().map { it.json() }.json(),
        "any" to any.sorted().map { it.json() }.json(), "none" to none.sorted().map { it.json() }.json())

    companion object {
        fun fromJson(value: JsonValue): FileTagPredicate {
            val fields = value.fields(setOf("all", "any", "none"))
            fun clause(name: String): Set<String> {
                val values = fields.getValue(name).arrayValue()
                require(values.size <= 64) { "File tag predicate exceeds 64 tags per clause" }
                val tags = values.map(JsonValue::stringValue)
                require(tags.distinct().size == tags.size) { "Duplicate file tag predicate entry" }
                return tags.toSet()
            }
            return FileTagPredicate(clause("all"), clause("any"), clause("none"))
        }
    }
}

fun roleFilePredicate(role: String, all: Set<String> = emptySet(), none: Set<String> = emptySet()): FileTagPredicate =
    FileTagPredicate(all, when (role) {
        "production" -> setOf("scope:production", "kind:configuration", "kind:lock", "kind:abi-baseline")
        "tests" -> setOf("scope:test", "kind:configuration", "kind:lock")
        "all" -> emptySet()
        else -> throw IllegalArgumentException("Unknown source view role: $role")
    }, none)

/** Display projection pinned to a complete source manifest, never an authorization boundary.
 * File paths are revision-relative, including the source root prefix when nonempty. */
data class SourceView(
    val name: String,
    val source: SourceReference,
    val predicate: FileTagPredicate,
    val files: List<TaggedProjectFile>,
) {
    init {
        require(name in setOf("production", "tests", "all")) { "Unknown source view role: $name" }
        require(files.size <= PROJECT_FILE_LIMIT) { "Source view exceeds 100000 files" }
        require(files.map { it.path }.toSet().size == files.size) { "Duplicate source view file path" }
        require(files.all { source.root.isEmpty() || it.path.value.startsWith("${source.root}/") }) { "Source view file is outside source root" }
        require(files.all { predicate.matches(it.tags) }) { "Source view file does not match predicate" }
        requireSourceViewSize(toJson())
    }

    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "source-view".json(),
        "name" to name.json(), "source" to source.toJson(), "predicate" to predicate.toJson(),
        "files" to files.sortedBy { it.path.value }.map { it.toJson() }.json())

    companion object {
        fun fromJson(value: JsonValue): SourceView {
            requireSourceViewSize(value)
            val fields = value.fields(setOf("schemaVersion", "kind", "name", "source", "predicate", "files"))
            fields.requireVersion()
            require(fields.getValue("kind").stringValue() == "source-view") { "Expected source-view kind" }
            val files = fields.getValue("files").arrayValue()
            require(files.size <= PROJECT_FILE_LIMIT) { "Source view exceeds 100000 files" }
            return SourceView(fields.getValue("name").stringValue(), SourceReference.fromJson(fields.getValue("source")),
                FileTagPredicate.fromJson(fields.getValue("predicate")), files.map(TaggedProjectFile::fromJson))
        }
    }
}

private fun requireSourceViewSize(value: JsonValue) {
    val text = value.canonical()
    require(text.length <= SOURCE_VIEW_MAX_BYTES && text.encodeToByteArray().size <= SOURCE_VIEW_MAX_BYTES) {
        "Source view metadata exceeds 16 MiB"
    }
}
