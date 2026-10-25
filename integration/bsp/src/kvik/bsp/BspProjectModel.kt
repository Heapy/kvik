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
    private val generatedResources = mutableMapOf<String, List<BspGeneratedResources>>()
    private var pluginRoots = emptyList<BspPluginGeneratedRoot>()

    /** Declarations are supplied by the composed plugins using the retained build snapshot. */
    fun registerPluginGeneratedRoots(roots: List<BspPluginGeneratedRoot>) {
        require(roots.size <= 1024 && roots.distinct().size == roots.size)
        require(roots.all { root -> inspection.project.project.modules.any { module ->
            module.id == root.module && module.sourceSets.any { it.name == root.sourceSet }
        } }) { "Unknown plugin generated source set" }
        pluginRoots = roots.map { it.copy(resources = it.resources?.toList()) }
    }

    fun pluginGeneratedRoots(producer: String): List<BspPluginGeneratedRoot> = pluginRoots.filter { it.producer == producer }

    fun pluginGeneratedTargets(root: BspPluginGeneratedRoot): List<BspTarget> = compilations.filter { compilation ->
        compilation.module.id == root.module && compilation.sourceSets.any { it.name == root.sourceSet } &&
            (root.resources != null || compilation.scope == "main" || sourceSetClosure(compilation.module, listOf(compilation.target.main))
                .none { it.name == root.sourceSet })
    }.map { BspTarget(it.uri, it.module.id, it.target.name, it.scope) }

    private fun selected(targetUris: List<String>): List<Compilation> = targetUris.distinct().map { uri ->
        requireNotNull(byUri[uri]) { "Unknown BSP target: $uri" }
    }

    fun select(targetUris: List<String>): List<BspTarget> = selected(targetUris).map { BspTarget(it.uri, it.module.id, it.target.name, it.scope) }

    fun allTargets(): List<BspTarget> = select(byUri.keys.toList())

    fun scriptGenerator(actionId: String): ExpandedScript? {
        val parts = actionId.split(':')
        if (parts.size != 4 || parts[0] != "script" || parts[2] != "jvm" || parts[3] != "generate") return null
        val project = inspection.project.project
        if (project.scripts[parts[1]]?.generator == null) return null
        return expandScript(project, parts[1])
    }

    private fun scriptGeneratorTargets(script: ExpandedScript, resources: Boolean): List<BspTarget> {
        val generator = checkNotNull(script.declaration.generator)
        if (generator.resources.isNotEmpty() != resources) return emptyList()
        return compilations.filter { compilation ->
            compilation.module.id == generator.module && compilation.sourceSets.any { it.name == generator.sourceSet } &&
                (resources || compilation.scope == "main" || sourceSetClosure(compilation.module, listOf(compilation.target.main))
                    .none { it.name == generator.sourceSet })
        }.map { BspTarget(it.uri, it.module.id, it.target.name, it.scope) }
    }

    fun generatedResourceTargets(actionId: String): List<BspTarget> =
        scriptGenerator(actionId)?.let { scriptGeneratorTargets(it, resources = true) }
            ?: pluginGeneratedRoots(actionId).filter { it.resources != null }.flatMap(::pluginGeneratedTargets).distinct()

    /** A host generator may serve several target compilations without exposing a host BSP target. */
    fun generatedSourceTargets(actionId: String): List<BspTarget> {
        if (pluginGeneratedRoots(actionId).isNotEmpty()) return pluginGeneratedRoots(actionId).filter { it.resources == null }.flatMap(::pluginGeneratedTargets).distinct()
        scriptGenerator(actionId)?.let { return scriptGeneratorTargets(it, resources = false) }
        val parts = actionId.split(':')
        if (parts.size != 3) return emptyList()
        val owners = when (parts[2]) {
            "ksp-test" -> listOfNotNull(byUri[targetUri(parts[0], parts[1], "test")]).filter {
                it.target.nativeTestSettings != null && NativeTarget.find(parts[1]) != null
            }
            "sqldelight" -> {
                if (parts[1] != "jvm") return emptyList()
                compilations.filter { compilation ->
                    val settings = compilation.module.sqlDelight
                    compilation.module.id == parts[0] && compilation.scope == "main" && settings != null &&
                        compilation.sourceSets.any { it.name == settings.sourceSet }
                }
            }
            else -> emptyList()
        }
        return owners.map { BspTarget(it.uri, it.module.id, it.target.name, it.scope) }
    }

    /** Source and resource generator owners in the build's prerequisites, including host compiler plugins.
     * Classpath queries build their producers, but do not compile the requested compilation itself. */
    fun generatedSourceOwners(target: BspTarget, runtime: Boolean = false, classpath: Boolean = false): List<BspTarget> {
        require(select(listOf(target.uri)).single() == target) { "Generated-source owner differs from imported target" }
        require(!classpath || target.target == "jvm" && !runtime) { "Classpath preparation requires JVM compile dependencies" }
        val project = inspection.project.project
        val visited = mutableSetOf<Pair<String, Boolean>>()
        val owners = linkedSetOf<BspTarget>()
        // Plugin declaration callbacks can depend on any captured input. Until the new
        // snapshot is available, invalidate all previously imported plugin outputs.
        pluginRoots.flatMap(::pluginGeneratedTargets).forEach(owners::add)
        fun visit(module: String, platform: String, scope: String, includeRuntime: Boolean) {
            val compilation = byUri.getValue(targetUri(module, platform, scope))
            if (!visited.add(compilation.uri to includeRuntime)) return
            if (scope == "main") {
                val shared = generatedSourceTargets("$module:jvm:sqldelight")
                if (shared.any { it.uri == compilation.uri }) owners += shared
            } else owners += generatedSourceTargets("$module:$platform:ksp-test")
            for (script in scriptGenerators(project, module, compilation.sourceSets.map { it.name }.toSet())) {
                val id = "script:${script.name}:jvm:generate"
                owners += generatedSourceTargets(id)
                owners += generatedResourceTargets(id)
                script.dependencies.mapNotNull { (it.value as? Dependency.Module)?.module }.forEach {
                    visit(it, "jvm", "main", true)
                }
                script.compilerPlugins.mapNotNull { it.value.module }.forEach { visit(it, "jvm", "main", true) }
            }
            dependencyClosure(project, module, platform, scope,
                if (includeRuntime) DependencyUsage.RUNTIME else DependencyUsage.COMPILE).modules.forEach {
                visit(it, platform, "main", false)
            }
            if (scope == "test") visit(module, platform, "main", false)
            compilation.module.compilerPlugins.filter {
                it.module != null && platform in it.targets && scope in it.scopes
            }.forEach { visit(it.module!!, "jvm", "main", true) }
        }
        if (classpath) {
            dependencyClosure(project, target.module, target.target, target.compilation, DependencyUsage.COMPILE).modules.forEach {
                visit(it, target.target, "main", false)
            }
            if (target.compilation == "test") visit(target.module, target.target, "main", false)
        } else {
            val compilation = byUri.getValue(target.uri)
            visit(target.module, target.target, target.compilation, runtime ||
                target.compilation == "test" && target.target != "jvm" ||
                target.compilation == "main" && compilation.target.nativeFramework != null)
        }
        return owners.toList()
    }

    fun replaceGeneratedSources(target: BspTarget, sources: List<BspGeneratedSources>): Boolean {
        require(select(listOf(target.uri)).single() == target) { "Generated-source owner differs from imported target" }
        require(sources.map { it.producer }.distinct().size == sources.size) { "Duplicate generated-source producer" }
        for (source in sources) {
            require(source.directory.startsWith("${root.trimEnd('/')}/.kvik/bsp/")) { "Generated sources must belong to a retained BSP run" }
            require(target in generatedSourceTargets(source.producer)) { "Unsupported generated-source producer for target" }
            if (pluginGeneratedRoots(source.producer).isNotEmpty()) {
                val roots = pluginGeneratedRoots(source.producer).filter { it.resources == null && target in pluginGeneratedTargets(it) }
                require(source.files.files.all { file -> roots.any { file.path.value.startsWith(it.prefix) } }) {
                    "Generated plugin sources must use consuming compiler paths"
                }
            }
            scriptGenerator(source.producer)?.let { script ->
                require(source.files.files.all { it.path.value.startsWith("generated/scripts/${script.name}/") }) {
                    "Generated script sources must use consuming compiler paths"
                }
            }
        }
        require(sources.flatMap { it.files.files }.map { it.path }.distinct().size == sources.sumOf { it.files.files.size }) {
            "Ambiguous generated-source logical paths"
        }
        if (generated[target.uri].orEmpty() == sources) return false
        if (sources.isEmpty()) generated.remove(target.uri) else generated[target.uri] = sources.toList()
        return true
    }

    fun replaceGeneratedResources(target: BspTarget, resources: List<BspGeneratedResources>): Boolean {
        require(select(listOf(target.uri)).single() == target) { "Generated-resource owner differs from imported target" }
        require(resources.map { it.producer }.distinct().size == resources.size) { "Duplicate generated-resource producer" }
        for (resource in resources) {
            require(resource.directory.startsWith("${root.trimEnd('/')}/.kvik/bsp/")) { "Generated resources must belong to a retained BSP run" }
            require(target in generatedResourceTargets(resource.producer)) { "Unsupported generated-resource producer for target" }
            val expected = scriptGenerator(resource.producer)?.declaration?.generator?.resources?.map { LogicalPath("generated/${it.value}") }
                ?: pluginGeneratedRoots(resource.producer).filter { it.resources != null && target in pluginGeneratedTargets(it) }
                    .flatMap { root -> root.resources.orEmpty().map { LogicalPath(root.prefix + it.value) } }
            require(resource.files.files.map { it.path }.toSet() == expected.toSet()) {
                "Generated resources differ from declared outputs"
            }
        }
        if (generatedResources[target.uri].orEmpty() == resources) return false
        if (resources.isEmpty()) generatedResources.remove(target.uri) else generatedResources[target.uri] = resources.toList()
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

    /** Attribute source diagnostics without treating generator inputs as compiler source roots. */
    fun diagnosticTargets(actionId: String): List<BspTarget> = actionTarget(actionId)?.let(::listOf)
        ?: if (actionId.endsWith(":sqldelight")) generatedSourceTargets(actionId) else emptyList()

    fun diagnosticUri(actionId: String, path: String): String? {
        val target = diagnosticTargets(actionId).firstOrNull() ?: return null
        if (actionTarget(actionId) != null) {
            if (!path.endsWith(".kt")) return null
            // Native and web tests compile main Kotlin again; source ownership remains with main.
            if (target.compilation == "test" && recompilesMainSources(target.target)) {
                val logical = try { LogicalPath(path) } catch (_: IllegalArgumentException) { return null }
                return generatedSourceUri(target.uri, logical)
                    ?: generatedSourceUri(targetUri(target.module, target.target, "main"), logical)
                    ?: sourceUri(target, path)
            }
            return sourceUri(target, path)
        }
        return try {
            val logical = LogicalPath(path)
            val settings = byUri.getValue(target.uri).module.sqlDelight ?: return null
            if (path.endsWith(".sq") && belongs(logical, listOf(settings.sourceDirectory))) fileUri(logical) else null
        } catch (_: IllegalArgumentException) { null }
    }

    private fun recompilesMainSources(target: String): Boolean =
        NativeTarget.find(target) != null || KotlinJsTarget.find(target) != null

    /** Generated main files can have diagnostics from both main and a recompiling test compiler. */
    fun generatedDiagnosticTargets(owner: BspTarget): List<BspTarget> {
        require(select(listOf(owner.uri)).single() == owner) { "Generated-source owner differs from imported target" }
        if (owner.compilation != "main" || !recompilesMainSources(owner.target)) return listOf(owner)
        val test = byUri[targetUri(owner.module, owner.target, "test")]
            ?.let { BspTarget(it.uri, it.module.id, it.target.name, it.scope) }
        return listOfNotNull(owner, test)
    }

    private fun generatedSourceUri(uri: String, path: LogicalPath): String? =
        generated[uri].orEmpty().firstNotNullOfOrNull { source ->
            source.files.files.singleOrNull { it.path == path }?.let(source::uri)
        }

    fun sourceUri(target: BspTarget, path: String): String? = try {
        val logical = LogicalPath(path)
        generatedSourceUri(target.uri, logical)
            ?: if (belongs(logical, byUri.getValue(target.uri).directories)) fileUri(logical) else null
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
        }.map { fileUri(it.path) }.let { static ->
            static + generatedResources[compilation.uri].orEmpty().flatMap { resource -> resource.files.files.map(resource::uri) }
        }.distinct().sorted().map { it.json() }.json())
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
