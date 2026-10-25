package kvik.core

/** Pure model inspection. Compiler distribution defaults and invocation-only launch overrides are separate. */
fun projectSettingsInspection(configuration: ProjectConfiguration, file: LogicalPath): Map<String, InspectedSetting> {
    val result = linkedMapOf<String, InspectedSetting>()
    fun authored(value: JsonValue, pointer: String, inherited: Boolean = false) =
        InspectedSetting(value, if (inherited) "inherited" else "configuration", listOf(ConfigurationOrigin(file, pointer)))
    fun tree(key: String, normalized: JsonValue.Object, declaration: JsonValue.Object?, pointer: String) {
        for ((name, value) in normalized.values) {
            val original = declaration?.values?.get(name)
            if (value is JsonValue.Object && value.values.isNotEmpty()) tree("$key:$name", value, original as? JsonValue.Object, "$pointer/$name")
            else result["$key:$name"] = if (original == null) InspectedSetting(value, "default") else authored(value, "$pointer/$name")
        }
    }
    val modules = configuration.document.values.getValue("modules").arrayValue()
    for ((moduleIndex, module) in configuration.project.modules.withIndex()) {
        val modulePointer = "/modules/$moduleIndex"
        val moduleDocument = modules[moduleIndex].objectValue()
        for ((index, source) in module.sourceSets.withIndex()) {
            val dependencies = moduleDocument.getValue("sourceSets").arrayValue()[index].objectValue().getValue("dependencies").arrayValue()
            for ((dependencyIndex, dependency) in source.dependencies.withIndex()) {
                val pointer = "$modulePointer/sourceSets/$index/dependencies/$dependencyIndex"
                tree("sourceSet:${module.id}:${source.name}:dependency:$dependencyIndex", obj(
                    "scope" to dependency.scope.name.lowercase().json(), "exported" to dependency.exported.json()),
                    dependencies[dependencyIndex] as JsonValue.Object, pointer)
            }
        }
        for ((index, target) in module.targets.withIndex()) {
            val pointer = "$modulePointer/targets/$index"
            val declaration = moduleDocument.getValue("targets").arrayValue()[index] as JsonValue.Object
            val key = "target:${module.id}:${target.name}"
            for (compilation in listOfNotNull("main", target.test?.let { "test" })) {
                val inherited = compilation == "test" && target.testCompilerOptions == null
                val optionField = if (compilation == "test" && !inherited) "testCompilerOptions" else "compilerOptions"
                result["$key:$compilation:compilerOptions"] = authored(target.optionsFor(compilation).map { it.json() }.json(),
                    "$pointer/$optionField", inherited)
                val plugins = module.compilerPlugins.withIndex().filter { it.value.appliesTo(target.name, compilation) }
                result["$key:$compilation:compilerPlugins"] = InspectedSetting(plugins.map {
                    moduleDocument.getValue("compilerPlugins").arrayValue()[it.index]
                }.json(), "derived", plugins.map { ConfigurationOrigin(file, "$modulePointer/compilerPlugins/${it.index}") })
            }
            fun settings(name: String, value: JsonValue.Object) = tree("$key:$name", value,
                declaration.values[name] as? JsonValue.Object, "$pointer/$name")
            target.testSettings?.let { settings("testSettings", obj("classes" to it.classes.map(String::json).json(),
                "engines" to it.engines.map(String::json).json(), "parallelism" to it.parallelism.json(), "maxRetries" to it.maxRetries.json())) }
            if (target.test != null && NativeTarget.find(target.name) != null) {
                val native = target.nativeTestSettings ?: NativeTestSettings("kotlin")
                settings("nativeTestSettings", JsonValue.Object(obj("engine" to native.engine.json(), "maxRetries" to native.maxRetries.json()).values +
                    listOfNotNull(native.kspVersion?.let { "kspVersion" to it.json() }, native.processor?.let { "processor" to it.toString().json() }).toMap()))
            }
            if (target.test != null) (target.testResources ?: TestResources()).let {
                settings("testResources", JsonValue.Object(it.toJson().values + ("cpuSlots" to it.cpuSlots.json())))
            }
            target.application?.let { settings("application", it.toJson()) }
            target.nativeFramework?.let { settings("nativeFramework", it.toJson()) }
            if (target.test != null && KotlinJsTarget.find(target.name) != null)
                settings("jsTestSettings", (target.jsTestSettings ?: JsTestSettings()).toJson())
            target.benchmarkSettings?.let { settings("benchmarkSettings", it.toJson()) }
        }
    }
    for ((id, settings) in configuration.pluginSettings) result["buildPlugin:$id:settings"] = authored(settings, "/pluginSettings/$id")
    return result
}
