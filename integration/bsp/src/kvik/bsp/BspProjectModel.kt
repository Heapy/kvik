package kvik.bsp

import kvik.core.*

data class BspTarget(val uri: String, val module: String, val target: String, val compilation: String)

/** Encode an absolute POSIX path as a file URI, including UTF-8 bytes and URI delimiters. */
fun bspFileUri(absolutePath: String): String {
    require(absolutePath.startsWith('/') && '\u0000' !in absolutePath) { "BSP file URI requires an absolute path" }
    absolutePath.json().canonical() // Reject unpaired UTF-16 before encoding.
    return "file://" + buildString {
        for (byte in absolutePath.encodeToByteArray()) {
            val value = byte.toInt() and 0xff
            if (value in 65..90 || value in 97..122 || value in 48..57 || value.toChar() in "-._~/") append(value.toChar())
            else {
                append('%')
                append("0123456789ABCDEF"[value ushr 4])
                append("0123456789ABCDEF"[value and 15])
            }
        }
    }
}

/** BSP 2.2 import projection. [root] is the absolute directory containing the inspected configuration. */
class BspProjectModel(val inspection: ProjectInspection, val root: String, val configurationPath: LogicalPath) {
    init { require(root.startsWith('/')) { "BSP project root must be absolute" } }
    val rootUri: String = bspFileUri(root.trimEnd('/').ifEmpty { "/" }).let { if (it.endsWith('/')) it else "$it/" }
    private val configurationUri = fileUri(configurationPath)

    fun matchesRoot(uri: String): Boolean = canonicalFileUri(uri)?.trimEnd('/') == rootUri.trimEnd('/')

    private data class Compilation(val module: ModuleSpec, val target: TargetSpec, val scope: String,
                                   val uri: String, val sourceSets: List<SourceSet>) {
        val directories = sourceSets.flatMap { it.directories }.distinct()
        val resourceDirectories = sourceSets.flatMap { it.resources }.distinct()
        fun id(): JsonValue.Object = obj("uri" to uri.json())
    }

    private fun targetUri(module: String, target: String, scope: String): String = "$configurationUri#$module/$target/$scope"
    private fun fileUri(path: LogicalPath): String = bspFileUri("${root.trimEnd('/')}/${path.value}")
    private fun directoryUri(path: LogicalPath): String = "${fileUri(path)}/"
    private fun belongs(path: LogicalPath, directories: List<LogicalPath>): Boolean =
        directories.any { path.value.startsWith("${it.value}/") }

    private val compilations = inspection.project.project.modules.flatMap { module ->
        module.targets.flatMap { target ->
            listOfNotNull("main", target.test?.let { "test" }).map { scope ->
                val roots = if (scope == "main") listOf(target.main) else listOfNotNull(target.main, target.test)
                Compilation(module, target, scope, targetUri(module.id, target.name, scope), sourceSetClosure(module, roots))
            }
        }
    }
    private val byUri = compilations.associateBy { it.uri }
    private val filesByUri = inspection.files.associateBy { fileUri(it.path) }
    private val generated = mutableMapOf<String, List<BspGeneratedSources>>()

    private fun selected(targetUris: List<String>): List<Compilation> = targetUris.distinct().map { uri ->
        requireNotNull(byUri[uri]) { "Unknown BSP target: $uri" }
    }

    fun select(targetUris: List<String>): List<BspTarget> = selected(targetUris).map { BspTarget(it.uri, it.module.id, it.target.name, it.scope) }

    fun allTargets(): List<BspTarget> = select(byUri.keys.toList())

    /** Only adapters with an explicit source-output contract may contribute generated sources. */
    fun generatedSourceTarget(actionId: String): BspTarget? {
        val parts = actionId.split(':')
        if (parts.size != 3 || parts[2] != "ksp-test") return null
        val compilation = byUri[targetUri(parts[0], parts[1], "test")] ?: return null
        if (compilation.target.nativeTestSettings == null || NativeTarget.find(parts[1]) == null) return null
        return BspTarget(compilation.uri, parts[0], parts[1], "test")
    }

    fun replaceGeneratedSources(target: BspTarget, sources: List<BspGeneratedSources>): Boolean {
        require(select(listOf(target.uri)).single() == target) { "Generated-source owner differs from imported target" }
        require(sources.map { it.producer }.distinct().size == sources.size) { "Duplicate generated-source producer" }
        for (source in sources) {
            require(source.directory.startsWith("${root.trimEnd('/')}/.kvik/bsp/")) { "Generated sources must belong to a retained BSP run" }
            require(generatedSourceTarget(source.producer) == target) { "Unsupported generated-source producer for target" }
        }
        require(sources.flatMap { it.files.files }.map { it.path }.distinct().size == sources.sumOf { it.files.files.size }) {
            "Ambiguous generated-source logical paths"
        }
        if (generated[target.uri].orEmpty() == sources) return false
        if (sources.isEmpty()) generated.remove(target.uri) else generated[target.uri] = sources.toList()
        return true
    }

    fun isRunnable(target: BspTarget): Boolean = byUri[target.uri]?.let {
        it.module.id == target.module && it.target.name == target.target && it.scope == target.compilation &&
            it.scope == "main" && it.target.application != null
    } == true

    /** Compilation action identities are shared with the ordinary project graph. */
    fun actionTarget(actionId: String): BspTarget? {
        val parts = actionId.split(':')
        if (parts.size != 3) return null
        val scope = when (parts[2]) { "main" -> "main"; "test-compile", "test-link" -> "test"; else -> return null }
        return byUri[targetUri(parts[0], parts[1], scope)]?.let { BspTarget(it.uri, it.module.id, it.target.name, it.scope) }
    }

    fun sourceUri(target: BspTarget, path: String): String? = try {
        val logical = LogicalPath(path)
        if (belongs(logical, byUri.getValue(target.uri).directories)) fileUri(logical)
        else generated[target.uri].orEmpty().firstNotNullOfOrNull { source ->
            source.files.files.singleOrNull { it.path == logical }?.let(source::uri)
        }
    } catch (_: IllegalArgumentException) { null }

    fun containsSource(target: BspTarget, path: String): Boolean = sourceUri(target, path) != null

    fun targets(compileTargets: Set<String> = emptySet(), testTargets: Set<String> = emptySet(), runTargets: Set<String> = emptySet()): JsonValue.Object = obj("targets" to compilations.map { compilation ->
        val (module, target, scope) = compilation
        val dependencies = compilation.sourceSets.flatMap { it.dependencies }.filterIsInstance<Dependency.Module>()
            .map { targetUri(it.module, target.name, "main") }.toMutableList()
        if (scope == "test") dependencies += targetUri(module.id, target.name, "main")
        val moduleDocument = inspection.project.document.values.getValue("modules").arrayValue()
            .single { it.objectValue().getValue("id").stringValue() == module.id }.objectValue()
        val targetDocument = moduleDocument.getValue("targets").arrayValue()
            .single { it.objectValue().getValue("name").stringValue() == target.name }
        obj("id" to compilation.id(), "displayName" to "${module.id}:${target.name}:$scope".json(),
            "baseDirectory" to rootUri.json(),
            "tags" to listOf((if (scope == "test") "test" else if (target.application != null) "application" else "library").json()).json(),
            "languageIds" to listOf("kotlin".json()).json(),
            "dependencies" to dependencies.distinct().map { obj("uri" to it.json()) }.json(),
            "capabilities" to obj("canCompile" to (target.name in compileTargets).json(), "canTest" to (scope == "test" && target.name in testTargets).json(), "canRun" to (scope == "main" && target.application != null && target.name in runTargets).json(), "canDebug" to false.json()),
            "dataKind" to "kvik-kotlin".json(),
            "data" to obj("schemaVersion" to CONTRACT_VERSION.json(), "module" to module.id.json(), "target" to target.name.json(),
                "compilation" to scope.json(), "configurationUri" to configurationUri.json(),
                "configuration" to inspection.configuration.toJson(), "composition" to inspection.composition.toJson(),
                "sourceSets" to compilation.sourceSets.map { it.name.json() }.json(),
                "compilerOptions" to target.optionsFor(scope).map { it.json() }.json(), "settings" to targetDocument))
    }.json())

    fun sources(targetUris: List<String>): JsonValue.Object = obj("items" to selected(targetUris).map { compilation ->
        obj("target" to compilation.id(), "sources" to compilation.directories.map {
            obj("uri" to directoryUri(it).json(), "kind" to 2.json(), "generated" to false.json())
        }.let { it + generated[compilation.uri].orEmpty().flatMap(BspGeneratedSources::sourceItems) }.json(),
            "roots" to (compilation.directories.map { directoryUri(it) } +
                generated[compilation.uri].orEmpty().map { bspFileUri(it.directory) + "/" }).distinct().map { it.json() }.json())
    }.json())

    fun resources(targetUris: List<String>): JsonValue.Object = obj("items" to selected(targetUris).map { compilation ->
        obj("target" to compilation.id(), "resources" to inspection.files.filter {
            belongs(it.path, compilation.resourceDirectories)
        }.map { fileUri(it.path) }.distinct().sorted().map { it.json() }.json())
    }.json())

    fun inverseSources(documentUri: String): JsonValue.Object {
        val uri = canonicalFileUri(documentUri)
        val file = uri?.let(filesByUri::get)
        return obj("targets" to compilations.filter {
            file != null && belongs(file.path, it.directories) || uri != null && generated[it.uri].orEmpty().any { source ->
                source.files.files.any { generatedFile -> source.uri(generatedFile) == uri }
            }
        }.map { it.id() }.json())
    }
}

/** Accept equivalent percent escapes from clients without assigning nonlocal URI authorities to local files. */
private fun canonicalFileUri(uri: String): String? = bspFilePath(uri)?.let(::bspFileUri)

/** Decode a local file URI for an explicitly requested application working directory. */
fun bspFilePath(uri: String): String? {
    val path = when {
        uri.startsWith("file:///") -> uri.removePrefix("file://")
        uri.startsWith("file://localhost/") -> uri.removePrefix("file://localhost")
        else -> return null
    }
    if ('?' in path || '#' in path) return null
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < path.length) {
        if (path[index] == '%') {
            if (index + 2 >= path.length) return null
            val high = path[index + 1].digitToIntOrNull(16) ?: return null
            val low = path[index + 2].digitToIntOrNull(16) ?: return null
            bytes += ((high shl 4) or low).toByte()
            index += 3
        } else {
            val end = path.indexOf('%', index).let { if (it < 0) path.length else it }
            bytes.addAll(path.substring(index, end).encodeToByteArray().toList())
            index = end
        }
    }
    return try { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true).also { bspFileUri(it) } }
    catch (_: IllegalArgumentException) { null }
    catch (_: CharacterCodingException) { null }
}
