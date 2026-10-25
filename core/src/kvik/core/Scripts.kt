package kvik.core

const val DEFAULT_SCRIPT_TIMEOUT_MILLIS: Long = 60_000
private const val SCRIPT_LIMIT = 256

/** References retain author intent; catalog expansion never becomes an implicit project classpath. */
sealed interface ScriptDependencyReference {
    val scope: DependencyScopeKind
    data class External(val coordinate: Coordinate, override val scope: DependencyScopeKind = DependencyScopeKind.COMPILE) : ScriptDependencyReference
    data class Catalog(val alias: String, override val scope: DependencyScopeKind = DependencyScopeKind.COMPILE) : ScriptDependencyReference {
        init { scriptIdentifier(alias) }
    }
    data class Module(val module: String, override val scope: DependencyScopeKind = DependencyScopeKind.COMPILE) : ScriptDependencyReference {
        init { scriptIdentifier(module) }
    }
    fun toJson(): JsonValue {
        val reference = when (this) {
            is External -> "coordinate" to coordinate.toString().json()
            is Catalog -> "catalog" to alias.json()
            is Module -> "module" to module.json()
        }
        return obj(reference, "scope" to scope.name.lowercase().json())
    }
    companion object {
        fun fromJson(value: JsonValue): ScriptDependencyReference {
            val fields = value.fields(emptySet(), setOf("coordinate", "catalog", "module", "scope"))
            require(listOf("coordinate", "catalog", "module").count { it in fields } == 1) { "Script dependency requires exactly one coordinate, catalog or module" }
            val scope = when (fields["scope"]?.stringValue() ?: "compile") {
                "compile" -> DependencyScopeKind.COMPILE
                "runtime" -> DependencyScopeKind.RUNTIME
                else -> error("Unsupported script dependency scope")
            }
            return when {
                "coordinate" in fields -> External(Coordinate.parse(fields.getValue("coordinate").stringValue()), scope)
                "catalog" in fields -> Catalog(fields.getValue("catalog").stringValue(), scope)
                else -> Module(fields.getValue("module").stringValue(), scope)
            }
        }
    }
}

/** Ordered, nonrecursive script-only defaults; script declarations append rather than override. */
data class ScriptDependencyProfile(
    val dependencies: List<ScriptDependencyReference> = emptyList(),
    val compilerOptions: List<String> = emptyList(),
    val compilerPlugins: List<CompilerPluginSpec> = emptyList(),
) {
    init {
        validateScriptDeclarations(dependencies, compilerOptions, compilerPlugins)
        require(toJson().canonical().encodeToByteArray().size <= 256 * 1024) { "Script profile exceeds 256 KiB" }
    }
    fun toJson(): JsonValue.Object = obj("dependencies" to dependencies.map { it.toJson() }.json(),
        "compilerOptions" to compilerOptions.map { it.json() }.json(), "compilerPlugins" to compilerPlugins.map(::scriptPluginJson).json())
    companion object {
        fun fromJson(value: JsonValue): ScriptDependencyProfile {
            val fields = value.fields(emptySet(), setOf("dependencies", "compilerOptions", "compilerPlugins"))
            return ScriptDependencyProfile(scriptDependencies(fields), scriptOptions(fields), scriptPlugins(fields))
        }
    }
}

/** Declarative compilation and optional generator attachment; no host paths or inferred main class. */
data class ScriptSpec(
    val sources: List<LogicalPath>,
    val resources: List<LogicalPath> = emptyList(),
    val profiles: List<String> = emptyList(),
    val dependencies: List<ScriptDependencyReference> = emptyList(),
    val compilerOptions: List<String> = emptyList(),
    val compilerPlugins: List<CompilerPluginSpec> = emptyList(),
    val mainClass: String? = null,
    val timeoutMillis: Long = DEFAULT_SCRIPT_TIMEOUT_MILLIS,
    val environment: Map<String, String> = emptyMap(),
    val entryScript: LogicalPath? = null,
    val generator: ScriptGeneratorSpec? = null,
) {
    init {
        require(sources.isNotEmpty()) { "Script sources must be nonempty" }
        if (entryScript == null) {
            require(sources.none { it.value.endsWith(".kts", ignoreCase = true) }) { ".kts sources require entryScript" }
        } else {
            require(entryScript.value.endsWith(".kts") && entryScript.value.length <= 1024) { "entryScript must name a .kts file" }
            require(mainClass == null) { "entryScript and mainClass are mutually exclusive" }
            require(sources.any { it == entryScript || entryScript.value.startsWith(it.value + "/") }) { "entryScript must be covered by declared sources" }
            require(sources.none { it.value.endsWith(".kt", ignoreCase = true) }) { ".kts helpers must be scripts; use a workspace module for .kt helpers" }
        }
        require(sources.size <= SCRIPT_LIMIT && resources.size <= SCRIPT_LIMIT) { "Too many script source/resource roots" }
        validateScriptRoots(sources + resources)
        require(profiles.size <= 32 && profiles.distinct().size == profiles.size) { "Duplicate or too many script profiles" }
        profiles.forEach(::scriptIdentifier)
        validateScriptDeclarations(dependencies, compilerOptions, compilerPlugins)
        require(mainClass == null || mainClass.length <= 1024 && mainClass.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) { "Invalid script mainClass" }
        require(timeoutMillis in 1L..3_600_000L) { "Script timeoutMillis must be in 1..3600000" }
        require(environment.size <= 128 && environment.all { (name, value) ->
            name.length <= 128 && name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) &&
                !name.startsWith("KVIK_") && name !in SCRIPT_RESERVED_ENV && value.length <= 4096 && '\u0000' !in value
        }) { "Invalid or reserved script environment variable" }
        require(toJson().canonical().encodeToByteArray().size <= 256 * 1024) { "Script declaration exceeds 256 KiB" }
    }
    fun toJson(): JsonValue.Object = JsonValue.Object(linkedMapOf(
        "sources" to sources.map { it.value.json() }.json(), "resources" to resources.map { it.value.json() }.json(),
        "profiles" to profiles.map { it.json() }.json(), "dependencies" to dependencies.map { it.toJson() }.json(),
        "compilerOptions" to compilerOptions.map { it.json() }.json(), "compilerPlugins" to compilerPlugins.map(::scriptPluginJson).json(),
        "timeoutMillis" to timeoutMillis.json(), "environment" to JsonValue.Object(environment.mapValues { it.value.json() }),
    ) + (mainClass?.let { mapOf("mainClass" to it.json()) } ?: emptyMap()) +
        (entryScript?.let { mapOf("entryScript" to it.value.json()) } ?: emptyMap()) +
        (generator?.let { mapOf("generator" to it.toJson()) } ?: emptyMap()))
    companion object {
        fun fromJson(value: JsonValue): ScriptSpec {
            val fields = value.fields(setOf("sources"), setOf("resources", "profiles", "dependencies", "compilerOptions", "compilerPlugins", "mainClass", "timeoutMillis", "environment", "entryScript", "generator"))
            return ScriptSpec(fields.getValue("sources").arrayValue().map { LogicalPath(it.stringValue()) },
                fields["resources"]?.arrayValue()?.map { LogicalPath(it.stringValue()) } ?: emptyList(),
                fields["profiles"]?.arrayValue()?.map(JsonValue::stringValue) ?: emptyList(),
                scriptDependencies(fields), scriptOptions(fields), scriptPlugins(fields), fields["mainClass"]?.stringValue(),
                fields["timeoutMillis"]?.longValue() ?: DEFAULT_SCRIPT_TIMEOUT_MILLIS,
                fields["environment"]?.objectValue()?.mapValues { it.value.stringValue() } ?: emptyMap(),
                fields["entryScript"]?.stringValue()?.let(::LogicalPath), fields["generator"]?.let(ScriptGeneratorSpec::fromJson))
        }
    }
}

// Runtime launcher controls these values; explicit application data cannot inject JVM/loader flags.
private val SCRIPT_RESERVED_ENV = setOf("PATH", "JAVA_HOME", "JAVACMD", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "DYLD_FRAMEWORK_PATH")

/** JSON pointers address authoring declarations or the effective configuration's scriptDeclarations. */
data class ScriptOrigin(val declaration: String, val profileSelection: String? = null, val catalog: String? = null) {
    fun toJson(): JsonValue = obj("declaration" to declaration.json(),
        "profileSelection" to (profileSelection?.json() ?: JsonValue.Null), "catalog" to (catalog?.json() ?: JsonValue.Null))
}
data class ScriptValue<T>(val value: T, val origin: ScriptOrigin)
data class ExpandedScript(
    val name: String,
    val declaration: ScriptSpec,
    val dependencies: List<ScriptValue<Dependency>>,
    val compilerOptions: List<ScriptValue<String>>,
    val compilerPlugins: List<ScriptValue<CompilerPluginSpec>>,
)

fun expandScript(project: ProjectSpec, name: String): ExpandedScript {
    scriptIdentifier(name)
    return expandScriptValues(name, project.scripts[name] ?: error("Unknown script: $name"), project.dependencyCatalog,
        project.dependencyProfiles, project.modules, "/scripts/$name")
}

/** Same declarations, no implicit workspace lookup. The caller binds the selected file and durable lock. */
data class StandaloneScriptConfiguration(
    val name: String,
    val repositories: List<Repository>,
    val dependencyCatalog: Map<String, Coordinate>,
    val dependencyProfiles: Map<String, ScriptDependencyProfile>,
    val script: ScriptSpec,
    val document: JsonValue.Object,
    val sourceDeclarations: ScriptSourceDeclarations? = null,
) {
    val digest: Digest get() = Sha256.digest(document.canonical().encodeToByteArray())
    fun expanded(): ExpandedScript = expandScriptValues(name, script, dependencyCatalog, dependencyProfiles, emptyList(), "/script")
        .withSourceDeclarations(sourceDeclarations)
    /** CLI must call this with the selected file relative to the companion directory, before effects. */
    fun requirePrimarySource(source: LogicalPath) {
        require(source in script.sources && if (script.entryScript == null) source.value.endsWith(".kt") else source == script.entryScript) {
            "Standalone selected file must be an explicit source and match entryScript for .kts"
        }
    }
}

fun parseStandaloneScriptConfiguration(document: JsonValue): StandaloneScriptConfiguration {
    val fields = document.fields(setOf("schemaVersion", "name", "repositories", "script"), setOf("dependencyCatalog", "dependencyProfiles"))
    fields.requireVersion()
    val name = fields.getValue("name").stringValue().also(::scriptIdentifier)
    val repositories = fields.getValue("repositories").arrayValue().map { value ->
        val entry = value.fields(setOf("id", "url"))
        Repository(entry.getValue("id").stringValue().also(::scriptIdentifier), entry.getValue("url").stringValue())
    }
    require(repositories.isNotEmpty() && repositories.map { it.id }.distinct().size == repositories.size) { "Standalone repositories must be nonempty and unique" }
    val catalog = parseScriptCatalog(fields["dependencyCatalog"])
    val profiles = parseScriptProfiles(fields["dependencyProfiles"])
    val script = ScriptSpec.fromJson(fields.getValue("script"))
    require(script.generator == null) { "Standalone scripts cannot attach a source generator to a workspace module" }
    val result = StandaloneScriptConfiguration(name, repositories, catalog, profiles, script, document as JsonValue.Object)
    validateProjectScripts(ProjectSpec(name, repositories, emptyList(), catalog, profiles))
    result.expanded()
    return result
}

internal fun parseScriptCatalog(value: JsonValue?): Map<String, Coordinate> = scriptMap(value, 1024).mapValues { (_, coordinate) -> Coordinate.parse(coordinate.stringValue()) }
internal fun parseScriptProfiles(value: JsonValue?): Map<String, ScriptDependencyProfile> = scriptMap(value, SCRIPT_LIMIT).mapValues { (_, profile) -> ScriptDependencyProfile.fromJson(profile) }
internal fun parseNamedScripts(value: JsonValue?): Map<String, ScriptSpec> = scriptMap(value, SCRIPT_LIMIT).mapValues { (_, script) -> ScriptSpec.fromJson(script) }

internal fun validateProjectScripts(project: ProjectSpec) {
    require(project.dependencyCatalog.size <= 1024 && project.dependencyProfiles.size <= SCRIPT_LIMIT && project.scripts.size <= SCRIPT_LIMIT) { "Too many script declarations" }
    (project.dependencyCatalog.keys + project.dependencyProfiles.keys + project.scripts.keys).forEach(::scriptIdentifier)
    // Even unused profiles must not hide broken references or invalid workspace prerequisites.
    project.dependencyProfiles.forEach { (name, profile) ->
        expandScriptValues(name, ScriptSpec(listOf(LogicalPath("__profile_validation__.kt")), dependencies = profile.dependencies,
            compilerOptions = profile.compilerOptions, compilerPlugins = profile.compilerPlugins), project.dependencyCatalog, emptyMap(), project.modules,
            "/dependencyProfiles/$name", checkInputOverlap = false)
    }
    project.scripts.keys.forEach { expandScript(project, it) }
    validateScriptGenerators(project)
}

private fun expandScriptValues(name: String, script: ScriptSpec, catalog: Map<String, Coordinate>, profiles: Map<String, ScriptDependencyProfile>,
                               modules: List<ModuleSpec>, pointer: String, checkInputOverlap: Boolean = true): ExpandedScript {
    val dependencies = mutableListOf<ScriptValue<Dependency>>()
    val options = mutableListOf<ScriptValue<String>>()
    val plugins = mutableListOf<ScriptValue<CompilerPluginSpec>>()
    fun append(refs: List<ScriptDependencyReference>, compilerOptions: List<String>, compilerPlugins: List<CompilerPluginSpec>, root: String, selection: String?) {
        refs.forEachIndexed { index, reference ->
            val value = when (reference) {
                is ScriptDependencyReference.External -> Dependency.External(reference.coordinate, reference.scope)
                is ScriptDependencyReference.Catalog -> Dependency.External(catalog[reference.alias] ?: error("Unknown script catalog alias '${reference.alias}' at $root/dependencies/$index"), reference.scope)
                is ScriptDependencyReference.Module -> Dependency.Module(reference.module, reference.scope)
            }
            dependencies += ScriptValue(value, ScriptOrigin("$root/dependencies/$index", selection,
                (reference as? ScriptDependencyReference.Catalog)?.let { "/dependencyCatalog/${it.alias}" }))
        }
        compilerOptions.forEachIndexed { index, value -> options += ScriptValue(value, ScriptOrigin("$root/compilerOptions/$index", selection)) }
        compilerPlugins.forEachIndexed { index, value -> plugins += ScriptValue(value, ScriptOrigin("$root/compilerPlugins/$index", selection)) }
    }
    script.profiles.forEachIndexed { index, name ->
        val profile = profiles[name] ?: error("Unknown script profile: $name")
        append(profile.dependencies, profile.compilerOptions, profile.compilerPlugins, "/dependencyProfiles/$name", "$pointer/profiles/$index")
    }
    append(script.dependencies, script.compilerOptions, script.compilerPlugins, pointer, null)
    require(dependencies.size <= SCRIPT_LIMIT && options.size <= SCRIPT_LIMIT && plugins.size <= 16) { "Expanded script exceeds declaration bounds" }
    val seenDependencies = mutableMapOf<String, ScriptOrigin>()
    for ((dependency, origin) in dependencies) {
        val key = when (dependency) {
            is Dependency.External -> "coordinate:${dependency.coordinate}:${dependency.scope}"
            is Dependency.Module -> "module:${dependency.module}"
        }
        require(key !in seenDependencies) { "Duplicate/conflicting script dependency $key at ${seenDependencies[key]?.declaration} and ${origin.declaration}" }
        seenDependencies[key] = origin
    }
    val seenPlugins = mutableSetOf<String>()
    val seenPluginIds = mutableSetOf<String>()
    for ((plugin, origin) in plugins) {
        val key = plugin.module?.let { "module:$it" } ?: plugin.coordinate!!.let { "coordinate:${it.group}:${it.name}" }
        require(seenPlugins.add(key) && (plugin.id == null || seenPluginIds.add(plugin.id))) { "Duplicate/conflicting script compiler plugin at ${origin.declaration}" }
    }
    jvmTargetSettings(options.map { it.value })
    val prerequisites = dependencies.mapNotNull { (it.value as? Dependency.Module)?.module } + plugins.mapNotNull { it.value.module }
    for (module in prerequisites) require(modules.singleOrNull { it.id == module }?.targets?.any { it.name == "jvm" } == true) {
        "Script requires an existing JVM workspace module: $module (standalone scripts cannot reference workspace modules)"
    }
    val outputRoots = plugins.flatMap { it.value.outputs }
    validateScriptRoots(outputRoots)
    val inputRoots = (if (checkInputOverlap) script.sources + script.resources + script.generator?.inputs.orEmpty() else emptyList()) + plugins.flatMap { it.value.inputs }
    require(outputRoots.none { output -> inputRoots.any { scriptPathsOverlap(output, it) } }) { "Script plugin outputs overlap declared inputs" }
    return ExpandedScript(name, script, dependencies.toList(), options.toList(), plugins.toList())
}

private fun scriptIdentifier(value: String) {
    require(value.length in 1..128 && value.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid script identifier: $value" }
}
private fun scriptMap(value: JsonValue?, max: Int): Map<String, JsonValue> = (value?.objectValue() ?: emptyMap()).also {
    require(it.size <= max) { "Too many script declarations" }; it.keys.forEach(::scriptIdentifier)
}
private fun scriptDependencies(fields: Map<String, JsonValue>) = fields["dependencies"]?.arrayValue()?.map(ScriptDependencyReference::fromJson) ?: emptyList()
private fun scriptOptions(fields: Map<String, JsonValue>) = fields["compilerOptions"]?.arrayValue()?.map(JsonValue::stringValue) ?: emptyList()
private fun scriptPlugins(fields: Map<String, JsonValue>) = fields["compilerPlugins"]?.arrayValue()?.map(::parseCompilerPlugin) ?: emptyList()
private fun scriptPathsOverlap(a: LogicalPath, b: LogicalPath) = pathsOverlap(LogicalPath(a.value.lowercase()), LogicalPath(b.value.lowercase()))
private fun validateScriptRoots(paths: List<LogicalPath>) {
    require(paths.all { it.value.length <= 1024 }) { "Script path exceeds 1024 UTF-16 units" }
    require(paths.indices.all { i -> paths.drop(i + 1).none { scriptPathsOverlap(paths[i], it) } }) { "Duplicate/overlapping script paths" }
}
private fun validateScriptDeclarations(dependencies: List<ScriptDependencyReference>, options: List<String>, plugins: List<CompilerPluginSpec>) {
    require(dependencies.size <= SCRIPT_LIMIT && dependencies.distinct().size == dependencies.size) { "Duplicate or too many script dependencies" }
    require(options.size <= SCRIPT_LIMIT && options.all { it.length in 1..4096 && it.none { char -> char.code < 32 } }) { "Invalid script compiler options" }
    require(plugins.size <= 16 && plugins.all { it.targets == setOf("jvm") && it.scopes == setOf("script") }) { "Script plugins require exactly JVM target and script scope" }
    require(plugins.all { it.options.size <= SCRIPT_LIMIT && it.options.all { option -> option.length <= 4096 && '\u0000' !in option } &&
        it.inputs.size <= SCRIPT_LIMIT && it.pathOptions.size <= SCRIPT_LIMIT && it.outputs.size <= SCRIPT_LIMIT }) { "Script plugin declaration exceeds bounds" }
    require(plugins.all { plugin -> (plugin.inputs + plugin.outputs).all { it.value.length <= 1024 } }) { "Script plugin path exceeds bounds" }
}
private fun scriptPluginJson(plugin: CompilerPluginSpec): JsonValue = JsonValue.Object(linkedMapOf(
    "targets" to plugin.targets.sorted().map { it.json() }.json(), "scopes" to plugin.scopes.sorted().map { it.json() }.json(),
    "options" to plugin.options.map { it.json() }.json(), "inputs" to plugin.inputs.map { it.value.json() }.json(),
) + (plugin.module?.let { mapOf("module" to it.json()) } ?: mapOf("coordinate" to plugin.coordinate!!.toString().json())) +
    (plugin.id?.let { mapOf("id" to it.json(), "compilerVersion" to plugin.compilerVersion!!.json(),
    "pathOptions" to plugin.pathOptions.map { option -> option.toJson() }.json(), "outputs" to plugin.outputs.map { path -> path.value.json() }.json()) }
    ?: emptyMap()))
