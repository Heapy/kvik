package kvik.core

/** Validated declarative configuration; retain the document for origins and identity. */
data class ProjectConfiguration(val project: ProjectSpec, val document: JsonValue.Object,
                                val scriptDeclarations: Map<String, ScriptSourceDeclarations> = emptyMap(),
                                val buildPluginInputs: Map<String, BuildPluginInputs> = emptyMap()) {
    val digest: Digest get() = Sha256.digest(document.canonical().encodeToByteArray())
}

private fun JsonValue.identifier(): String = stringValue().also {
    require(it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid project identifier: $it" }
}
private fun JsonValue.strings(): List<String> = arrayValue().map(JsonValue::stringValue)
private fun JsonValue.identifiers(): List<String> = arrayValue().map { it.identifier() }
private fun JsonValue.paths(): List<LogicalPath> = strings().map(::LogicalPath)
private fun <T> List<T>.unique(label: String): List<T> = also {
    require(distinct().size == size) { "Duplicate $label" }
}

/** Reject unsupported authoring features rather than silently dropping their dependencies. */
fun parseProjectConfiguration(document: JsonValue): ProjectConfiguration {
    val fields = document.fields(setOf("schemaVersion", "name", "repositories", "modules"), setOf("dependencyCatalog", "dependencyProfiles", "scripts", "fileTags", "pluginSettings"))
    fields.requireVersion()
    parsePluginSettings(fields["pluginSettings"])
    val repositories = fields.getValue("repositories").arrayValue().map { value ->
        val entry = value.fields(setOf("id", "url"))
        Repository(entry.getValue("id").identifier(), entry.getValue("url").stringValue())
    }
    require(repositories.isNotEmpty()) { "At least one repository must be declared" }
    repositories.map { it.id }.unique("repository id")
    val modules = fields.getValue("modules").arrayValue().map(::parseModule)
    modules.map { it.id }.unique("module id")
    val project = ProjectSpec(fields.getValue("name").identifier(), repositories, modules,
        parseScriptCatalog(fields["dependencyCatalog"]), parseScriptProfiles(fields["dependencyProfiles"]),
        parseNamedScripts(fields["scripts"]),
        fields["fileTags"]?.objectValue()?.map { (path, tags) ->
            LogicalPath(path) to tags.strings().unique("manual file tag for $path").toSet()
        }?.toMap() ?: emptyMap())
    val byId = modules.associateBy { it.id }
    val dependencies = modules.associate { module ->
        module.id to (module.sourceSets.flatMap { sourceSet -> sourceSet.dependencies.filterIsInstance<Dependency.Module>().map { it.module } } +
            module.compilerPlugins.mapNotNull { it.module }).toSet()
    }
    validateGraph(dependencies)
    for (module in modules) {
        for (target in module.targets) {
            val selected = sourceSetClosure(module, listOfNotNull(target.main, target.test))
            for (dependency in selected.flatMap { it.dependencies }.filterIsInstance<Dependency.Module>()) {
                require(byId.getValue(dependency.module).targets.any { it.name == target.name }) {
                    "Module ${module.id}:${target.name} requires missing target ${dependency.module}:${target.name}"
                }
            }
        }
        for (plugin in module.compilerPlugins) {
            require("script" !in plugin.scopes) { "Script compiler plugins must be declared on a script or script profile" }
            require(plugin.targets.all { requested -> module.targets.any { it.name == requested } }) { "Compiler plugin selects an undeclared target in ${module.id}" }
            plugin.module?.let { pluginModule ->
                require(byId.getValue(pluginModule).targets.any { it.name == "jvm" }) { "Compiler plugin module $pluginModule requires a JVM target" }
                require("test" !in plugin.scopes || plugin.targets.all { selected ->
                    module.targets.single { it.name == selected }.test != null
                }) { "Workspace plugin selects missing test compilation" }
            }
        }
    }
    validateProjectScripts(project)
    return ProjectConfiguration(project, document as JsonValue.Object)
}

private fun parseModule(value: JsonValue): ModuleSpec {
    val fields = value.fields(setOf("id", "sourceSets", "targets", "compilerPlugins"), setOf("publication", "documentation", "sqlDelight"))
    val id = fields.getValue("id").identifier()
    val sourceSets = fields.getValue("sourceSets").arrayValue().map { source ->
        val entry = source.fields(setOf("name", "directories", "resources", "dependsOn", "dependencies"))
        SourceSet(entry.getValue("name").identifier(), entry.getValue("directories").paths().unique("source directory"),
            entry.getValue("resources").paths().unique("resource directory"), entry.getValue("dependsOn").identifiers().unique("source-set edge"),
            entry.getValue("dependencies").arrayValue().map(::parseDependency).unique("dependency"))
    }
    require(sourceSets.isNotEmpty()) { "Module $id requires source sets" }
    sourceSets.map { it.name }.unique("source-set name in $id")
    validateGraph(sourceSets.associate { it.name to it.dependsOn.toSet() })
    val names = sourceSets.map { it.name }.toSet()
    val targets = fields.getValue("targets").arrayValue().map { target ->
        val entry = target.fields(setOf("name", "main", "test", "compilerOptions"), setOf("testSettings", "testCompilerOptions", "nativeTestSettings", "nativeTimeouts", "application", "nativeFramework", "abi", "jsTestSettings", "benchmarkSettings", "testResources"))
        val name = entry.getValue("name").stringValue()
        require(name == "jvm" || NativeTarget.find(name) != null || KotlinJsTarget.find(name) != null) { "Unsupported target: $name" }
        val main = entry.getValue("main").identifier()
        val test = entry.getValue("test").let { if (it == JsonValue.Null) null else it.identifier() }
        require(main in names && (test == null || test in names)) { "Unknown compilation source set in $id:$name" }
        require(test != main) { "Main and test source sets must differ in $id:$name" }
        val settings = entry["testSettings"]?.let { value ->
            val selection = value.fields(emptySet(), setOf("classes", "engines", "parallelism", "maxRetries", "includeTags", "excludeTags"))
            JvmTestSettings(selection["classes"]?.strings().orEmpty(), selection["engines"]?.strings() ?: listOf("junit"),
                (selection["parallelism"]?.longValue() ?: 1).also {
                    require(it in 1L..1024L) { "JVM test parallelism must be an integer in 1..1024" }
                }.toInt(),
                (selection["maxRetries"]?.longValue() ?: 0).also {
                    require(it in 0L..9L) { "JVM test maxRetries must be an integer in 0..9" }
                }.toInt(), discover = "classes" !in selection, tags = JvmTestTags.fromFields(selection))
        }
        val nativeSettings = entry["nativeTestSettings"]?.let { value ->
            val selection = value.fields(setOf("engine"), setOf("kspVersion", "processor", "maxRetries"))
            NativeTestSettings(selection.getValue("engine").stringValue(), selection["kspVersion"]?.stringValue(),
                selection["processor"]?.stringValue()?.let(Coordinate::parse),
                (selection["maxRetries"]?.longValue() ?: 0).also {
                    require(it in 0L..9L) { "Native test maxRetries must be an integer in 0..9" }
                }.toInt())
        }
        val nativeTimeouts = entry["nativeTimeouts"]?.let { value ->
            val timeouts = value.fields(emptySet(), setOf("compileMillis", "linkMillis"))
            NativeTimeouts(timeouts["compileMillis"]?.longValue(), timeouts["linkMillis"]?.longValue())
        }
        TargetSpec(name, main, test, entry.getValue("compilerOptions").strings(), settings, entry["testCompilerOptions"]?.strings(), nativeSettings, nativeTimeouts,
            entry["application"]?.let(ApplicationSpec::fromJson), entry["nativeFramework"]?.let(NativeFrameworkSpec::fromJson),
            entry["abi"]?.let(AbiSettings::fromJson), entry["jsTestSettings"]?.let(JsTestSettings::fromJson), entry["benchmarkSettings"]?.let(JvmBenchmarkSettings::fromJson),
            entry["testResources"]?.let(TestResources::fromJson))
    }
    require(targets.isNotEmpty()) { "Module $id requires targets" }
    targets.map { it.name }.unique("target in $id")
    val publication = fields["publication"]?.let { value ->
        val declaration = value.fields(setOf("coordinate"))
        LibraryPublicationSpec(Coordinate.parse(declaration.getValue("coordinate").stringValue()))
    }
    return ModuleSpec(id, sourceSets, targets, fields.getValue("compilerPlugins").arrayValue().map(::parseCompilerPlugin), publication,
        fields["documentation"]?.let(DocumentationSettings::fromJson), fields["sqlDelight"]?.let(SqlDelightSpec::fromJson))
}

private fun parseDependency(value: JsonValue): Dependency {
    val fields = value.fields(emptySet(), setOf("module", "coordinate", "scope", "exported"))
    require(("module" in fields) != ("coordinate" in fields)) { "Dependency requires exactly one module or coordinate" }
    val scope = when (fields["scope"]?.stringValue() ?: "compile") {
        "compile" -> DependencyScopeKind.COMPILE
        "runtime" -> DependencyScopeKind.RUNTIME
        else -> error("Unsupported dependency scope")
    }
    val exported = fields["exported"]?.booleanValue() ?: false
    return fields["module"]?.let { Dependency.Module(it.identifier(), scope, exported) }
        ?: Dependency.External(Coordinate.parse(fields.getValue("coordinate").stringValue()), scope, exported)
}

internal fun parseCompilerPlugin(value: JsonValue): CompilerPluginSpec {
    val fields = value.fields(setOf("targets", "scopes", "options", "inputs"), setOf("module", "coordinate", "id", "compilerVersion", "pathOptions", "outputs"))
    val targets = fields.getValue("targets").strings().unique("plugin target")
    require(targets.all { it == "jvm" || NativeTarget.find(it) != null || KotlinJsTarget.find(it) != null }) { "Unsupported compiler-plugin target" }
    return CompilerPluginSpec(fields["module"]?.identifier(), fields["coordinate"]?.stringValue()?.let(Coordinate::parse),
        targets.toSet(), fields.getValue("scopes").strings().unique("plugin scope").toSet(),
        fields.getValue("options").strings(), fields.getValue("inputs").paths().unique("plugin input"),
        fields["id"]?.identifier(), fields["compilerVersion"]?.stringValue(),
        fields["pathOptions"]?.arrayValue()?.map(PluginPathOption::fromJson) ?: emptyList(), fields["outputs"]?.paths() ?: emptyList())
}

/** Dependency-first source sets, with shared ancestors included exactly once. */
fun sourceSetClosure(module: ModuleSpec, roots: List<String>): List<SourceSet> {
    val byName = module.sourceSets.associateBy { it.name }
    val seen = mutableSetOf<String>()
    val ordered = mutableListOf<SourceSet>()
    fun visit(name: String) {
        if (!seen.add(name)) return
        val source = byName[name] ?: error("Unknown source set: ${module.id}:$name")
        source.dependsOn.forEach(::visit)
        ordered += source
    }
    roots.forEach(::visit)
    return ordered
}
