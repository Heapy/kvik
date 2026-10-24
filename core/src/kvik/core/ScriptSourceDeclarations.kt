package kvik.core

/** Normalized literals only: body edits, comments and source line numbers do not change a lock. */
@ConsistentCopyVisibility
data class ScriptFileDeclarations internal constructor(
    val dependencies: List<Coordinate>,
    val repositories: List<String>,
    val imports: List<LogicalPath>,
) {
    fun toJson(): JsonValue = obj("dependencies" to dependencies.map { it.toString().json() }.json(),
        "repositories" to repositories.map { it.json() }.json(), "imports" to imports.map { it.value.json() }.json())
}

@ConsistentCopyVisibility
data class ScriptSourceDeclarations internal constructor(val primary: LogicalPath, val files: Map<LogicalPath, ScriptFileDeclarations>) {
    val empty: Boolean get() = files.values.all { it.dependencies.isEmpty() && it.repositories.isEmpty() && it.imports.isEmpty() }
    fun toJson(): JsonValue = obj("implementation" to "kvik-script-declarations-v1".json(), "primary" to primary.value.json(),
        "files" to JsonValue.Object(files.entries.sortedBy { it.key.value }.associate { it.key.value to it.value.toJson() }))
}

/** Reads only the entry and its imported helpers, all within explicitly captured source roots. */
fun readScriptSourceDeclarations(script: ScriptSpec, read: (LogicalPath) -> ByteArray): ScriptSourceDeclarations? {
    val primary = script.entryScript ?: return null
    val files = linkedMapOf<LogicalPath, ScriptFileDeclarations>()
    val active = mutableSetOf<LogicalPath>()
    var totalBytes = 0L
    fun visit(path: LogicalPath) {
        require(path !in active) { "Script import cycle at ${path.value}" }
        if (path in files) return
        require(active.size < 128) { "Script import depth exceeds 128" }
        require(path.value.endsWith(".kts") && script.sources.any { path == it || path.value.startsWith("${it.value}/") }) {
            "@Import is outside declared script sources: ${path.value}"
        }
        require(files.size < 1024) { "Too many imported scripts" }
        active += path
        val bytes = read(path)
        totalBytes += bytes.size
        require(bytes.size <= 4 * 1024 * 1024 && totalBytes <= 16 * 1024 * 1024) { "Script declaration sources exceed byte limit" }
        val declarations = try { parseScriptDeclarations(bytes.decodeToString(throwOnInvalidSequence = true)) }
            catch (failure: IllegalArgumentException) { throw IllegalArgumentException("${path.value}: ${failure.message}", failure) }
        val dependencies = mutableListOf<Coordinate>()
        val repositories = mutableListOf<String>()
        val imports = mutableListOf<LogicalPath>()
        for (declaration in declarations) try {
            when (declaration.annotation) {
                "DependsOn" -> dependencies += declaration.values.map(Coordinate::parse)
                "Repository" -> repositories += declaration.values.map(::scriptRepositoryUrl)
                "Import" -> imports += declaration.values.map { scriptImportPath(path, it) }
                else -> error("Unknown script declaration")
            }
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("${path.value}:${declaration.line}:${declaration.column}: ${failure.message}", failure)
        }
        files[path] = ScriptFileDeclarations(dependencies.distinct().sortedBy(Coordinate::toString), repositories.distinct(), imports.distinct())
        imports.distinct().forEach(::visit)
        active -= path
    }
    visit(primary)
    return ScriptSourceDeclarations(primary, files.toMap()).takeUnless { it.empty }
}

fun scriptImportPath(source: LogicalPath, reference: String): LogicalPath {
    require(reference.isNotEmpty() && !reference.startsWith('/') && '\\' !in reference && ':' !in reference) { "@Import requires a relative captured path" }
    val parts = source.value.split('/').dropLast(1).toMutableList()
    for (part in reference.split('/')) when (part) {
        "" -> throw IllegalArgumentException("Empty script import path component")
        "." -> Unit
        ".." -> { require(parts.isNotEmpty()) { "Script import escapes captured root" }; parts.removeAt(parts.lastIndex) }
        else -> parts += part
    }
    return LogicalPath(parts.joinToString("/"))
}

private fun scriptRepositoryUrl(value: String): String {
    val normalized = value.trimEnd('/')
    val base = "$normalized/"
    require(base.matches(Regex("https://[A-Za-z0-9.-]+(?::[0-9]+)?/[A-Za-z0-9_./-]*")) &&
        base.substringAfter("https://").split('/').none { part -> part == "." || part == ".." }) {
        "@Repository requires an HTTPS repository URL without credentials, query or path traversal"
    }
    return normalized
}

/** The authoring document and model remain unchanged; source declarations have a separate origin. */
fun ProjectConfiguration.withScriptDeclarations(read: (LogicalPath) -> ByteArray): ProjectConfiguration = copy(
    scriptDeclarations = project.scripts.mapNotNull { (name, script) -> readScriptSourceDeclarations(script, read)?.let { name to it } }.toMap())

fun StandaloneScriptConfiguration.withScriptDeclarations(read: (LogicalPath) -> ByteArray): StandaloneScriptConfiguration =
    copy(sourceDeclarations = readScriptSourceDeclarations(script, read))

fun expandScript(configuration: ProjectConfiguration, name: String): ExpandedScript =
    expandScript(configuration.project, name).withSourceDeclarations(configuration.scriptDeclarations[name])

internal fun ExpandedScript.withSourceDeclarations(declarations: ScriptSourceDeclarations?): ExpandedScript {
    if (declarations == null) return this
    require(declarations.primary == declaration.entryScript) { "Script declarations refer to a different entry script" }
    val result = dependencies.toMutableList()
    for ((path, file) in declarations.files.entries.sortedBy { it.key.value }) for ((index, coordinate) in file.dependencies.withIndex()) {
        val dependency = Dependency.External(coordinate)
        if (result.none { it.value == dependency }) {
            val escaped = path.value.replace("~", "~0").replace("/", "~1")
            result += ScriptValue(dependency, ScriptOrigin("/scriptDeclarations/$name/files/$escaped/dependencies/$index"))
        }
    }
    require(result.size <= 1024) { "Too many script dependencies" }
    return copy(dependencies = result.toList())
}

/** Additional repositories apply to this script's dependency domains, never its tools or modules. */
fun scriptRepositories(base: List<Repository>, declarations: ScriptSourceDeclarations?): List<Repository> {
    val repositories = base.toMutableList()
    declarations?.files?.entries?.sortedBy { it.key.value }?.flatMap { it.value.repositories }?.distinct()?.forEach { url ->
        val normalized = scriptRepositoryUrl(url)
        if (repositories.none { it.url.trimEnd('/') == normalized }) {
            val id = "script-${Sha256.digest(normalized.encodeToByteArray()).hash}"
            require(repositories.none { it.id == id }) { "Conflicting script repository id: $id" }
            repositories += Repository(id, normalized)
        }
    }
    require(repositories.size <= 32) { "Too many script repositories (maximum 32)" }
    return repositories.toList()
}
