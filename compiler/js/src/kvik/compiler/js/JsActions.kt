package kvik.compiler.js

import kvik.core.*
import kvik.compiler.jvm.jvmHostPlatform

private val compilerIdentities = setOf("jdk", "jvm-compiler", "jvm-worker", "junit")
private val environment = mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin")

/** A compilation produces a packed KLIB; a source-free link produces an ES module directory. */
data class JsCompilation(
    val module: String,
    val target: KotlinJsTarget,
    val sources: List<LogicalPath>,
    val libraries: List<LogicalPath>,
    val output: LogicalPath,
    val commonSources: List<LogicalPath> = emptyList(),
    val includedLibrary: LogicalPath? = null,
    val moduleName: String = module,
    val optIns: List<String> = emptyList(),
    val explicitApi: Boolean = false,
    val warningsAsErrors: Boolean = false,
    val plugins: List<LogicalPath> = emptyList(),
    val pluginApplications: List<JvmPluginApplication> = emptyList(),
    val applicationEntryPoint: String? = null,
) {
    init {
        applicationEntryPoint?.let {
            ApplicationSpec(it).requireTarget(target.projectName)
            require(sources.isEmpty() && commonSources.isEmpty() && plugins.isEmpty() && pluginApplications.isEmpty() && !explicitApi)
        }
        require(pluginApplications.size <= 1 && (pluginApplications.isEmpty() || plugins.isEmpty())) { "Only one plugin application/representation is supported" }
        require(pluginApplications.all { plugin ->
            plugin.classpath.none { it in libraries } && plugin.outputs.all { root ->
                root.value.startsWith("plugins/${plugin.id}/") && !pathsOverlap(root, output)
            }
        }) { "Plugin paths overlap compilation inputs/outputs or leave the plugin namespace" }
        require(module.matches(Regex("[A-Za-z0-9_.-]+")))
        require(moduleName.matches(Regex(if (includedLibrary == null) "[A-Za-z0-9_][A-Za-z0-9_.:-]*" else "[A-Za-z_][A-Za-z0-9_.-]*")))
        for (paths in listOf(sources, libraries, commonSources, plugins)) {
            require(paths.distinct().size == paths.size && paths.none { ':' in it.value || ',' in it.value })
        }
        require(sources.all { it.value.endsWith(".kt") } && commonSources.all { it in sources })
        require(libraries.all { it.value.endsWith(".klib") })
        require(plugins.size <= 1 && plugins.all { it.value.endsWith(".jar") })
        require(optIns.distinct().size == optIns.size && optIns.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) })
        if (includedLibrary == null) {
            require((sources.isNotEmpty() || applicationEntryPoint != null) && output.value.endsWith(".klib"))
        } else {
            require(includedLibrary.value.endsWith(".klib") && ':' !in includedLibrary.value && ',' !in includedLibrary.value)
            require(includedLibrary !in libraries && sources.isEmpty() && commonSources.isEmpty() && plugins.isEmpty() && pluginApplications.isEmpty() && optIns.isEmpty() && !explicitApi)
        }
    }
    val compilerClass: String get() = "org.jetbrains.kotlin.cli.js." + if (target == KotlinJsTarget.JS) "K2JSCompiler" else "KotlinWasmCompiler"
    fun arguments(): List<String> = buildList {
        if (target == KotlinJsTarget.WASM_JS) add("-Xwasm-target=wasm-js")
        if (includedLibrary == null) {
            addAll(listOf("-ir-output-dir", "output/" + output.value.substringBeforeLast('/', "").ifEmpty { "." },
                "-ir-output-name", output.value.substringAfterLast('/').removeSuffix(".klib")))
            add("-Xir-module-name=$moduleName")
            addAll(listOf("-language-version", "2.4", "-api-version", "2.4"))
            if (commonSources.isNotEmpty()) {
                add("-Xmulti-platform")
                add("-Xcommon-sources=" + commonSources.joinToString(",") { "input/${it.value}" })
            }
            if (explicitApi) add("-Xexplicit-api=strict")
            optIns.forEach { add("-opt-in=$it") }
            plugins.forEach { add("-Xplugin=input/${it.value}") }
        } else {
            addAll(listOf("-Xir-produce-js", "-Xinclude=input/${includedLibrary.value}", "-ir-output-dir", "output/${output.value}", "-ir-output-name", moduleName, "-main", "noCall"))
            if (target == KotlinJsTarget.JS) addAll(listOf("-module-kind", "es"))
        }
        if (warningsAsErrors) add("-Werror")
        if (libraries.isNotEmpty()) addAll(listOf("-libraries", libraries.joinToString(":") { "input/${it.value}" }))
        addAll(sources.map { "input/${it.value}" })
        if (includedLibrary == null && applicationEntryPoint != null) add("prepared/Application.kt")
    }
    fun toJson(): JsonValue.Object = obj("module" to module.json(), "target" to target.projectName.json(),
        "sources" to sources.map { it.value.json() }.json(), "libraries" to libraries.map { it.value.json() }.json(),
        "output" to output.value.json(), "commonSources" to commonSources.map { it.value.json() }.json(),
        "includedLibrary" to (includedLibrary?.value?.json() ?: JsonValue.Null), "moduleName" to moduleName.json(),
        "optIns" to optIns.map { it.json() }.json(), "explicitApi" to explicitApi.json(),
        "warningsAsErrors" to warningsAsErrors.json(), "plugins" to plugins.map { it.value.json() }.json()).let {
            if (pluginApplications.isEmpty()) it else JsonValue.Object(it.values + ("pluginApplications" to pluginApplications.map(JvmPluginApplication::toJson).json()))
        }.let(::withApplication)
    private fun withApplication(value: JsonValue.Object): JsonValue.Object =
        if (applicationEntryPoint == null) value else JsonValue.Object(value.values + ("applicationEntryPoint" to applicationEntryPoint.json()))
    companion object {
        fun fromJson(value: JsonValue): JsCompilation {
            val d = value.fields(setOf("module", "target", "sources", "libraries", "output", "commonSources", "includedLibrary", "moduleName", "optIns", "explicitApi", "warningsAsErrors", "plugins"), setOf("pluginApplications", "applicationEntryPoint"))
            fun paths(key: String) = d.getValue(key).arrayValue().map { LogicalPath(it.stringValue()) }
            return JsCompilation(d.getValue("module").stringValue(), KotlinJsTarget.require(d.getValue("target").stringValue()), paths("sources"), paths("libraries"), LogicalPath(d.getValue("output").stringValue()), paths("commonSources"),
                d.getValue("includedLibrary").let { if (it == JsonValue.Null) null else LogicalPath(it.stringValue()) }, d.getValue("moduleName").stringValue(), d.getValue("optIns").arrayValue().map { it.stringValue() }, d.getValue("explicitApi").booleanValue(), d.getValue("warningsAsErrors").booleanValue(), paths("plugins"), d["pluginApplications"]?.arrayValue()?.map(JvmPluginApplication::fromJson) ?: emptyList(), d["applicationEntryPoint"]?.stringValue())
        }
    }
}

fun jsCompileAction(compilation: JsCompilation, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>, timeoutMillis: Long = 180_000): ActionSpec {
    val declared = inputs.files.map { it.path }.toSet()
    require((compilation.sources + compilation.libraries + compilation.plugins + listOfNotNull(compilation.includedLibrary)).all { it in declared }) { "Undeclared JS/Wasm compiler input" }
    compilation.pluginApplications.forEach { plugin ->
        require(plugin.inputs.all { it in declared }) { "Undeclared compiler plugin input" }
        plugin.classpath.forEach { path -> require(inputs.files.any {
            it.path == path && path.value.endsWith(".jar") || it.path.value.startsWith(path.value + "/")
        }) { "Undeclared/unsupported plugin classpath: ${path.value}" } }
    }
    require(toolchains.keys == compilerIdentities) { "Complete JVM compiler identities required" }
    return ActionSpec("kvik-js-compiler-v1", composition, if (compilation.includedLibrary == null) ActionKind.COMPILE else ActionKind.LINK,
        jvmHostPlatform(), compilation.target.projectName, toolchains.toMap(), TreeManifest(inputs.files.toList()), listOf(compilation.output) + compilation.pluginApplications.flatMap { it.outputs }, compilation.arguments(), environment,
        compilation.toJson(), timeoutMillis, CachePolicy.LOCAL)
}

data class JsTestSelection(val module: String, val target: KotlinJsTarget, val entryPoint: LogicalPath, val filter: String? = null, val resources: TestResources = TestResources(), val maxRetries: Int = 0) {
    init {
        require(module.matches(Regex("[A-Za-z0-9_.-]+")))
        require(entryPoint.value.endsWith(".mjs"))
        require(maxRetries in 0..9) { "JS/Wasm test maxRetries must be an integer in 0..9" }
        require(filter == null || filter.length in 1..4096 && filter.none { it.isISOControl() }) { "Test filter must be a nonempty glob (* matches any characters)" }
    }
    fun toJson(): JsonValue.Object = obj("module" to module.json(), "target" to target.projectName.json(), "entryPoint" to entryPoint.value.json(), "filter" to (filter?.json() ?: JsonValue.Null)).let {
        if (resources == TestResources()) it else JsonValue.Object(it.values + ("resources" to resources.toJson()))
    }.let {
        if (maxRetries == 0) it else JsonValue.Object(it.values + ("maxRetries" to maxRetries.json()))
    }
    companion object {
        fun fromJson(value: JsonValue): JsTestSelection = value.fields(setOf("module", "target", "entryPoint", "filter"), setOf("resources", "maxRetries")).let {
            JsTestSelection(it.getValue("module").stringValue(), KotlinJsTarget.require(it.getValue("target").stringValue()), LogicalPath(it.getValue("entryPoint").stringValue()),
                it.getValue("filter").let { v -> if (v == JsonValue.Null) null else v.stringValue() }, it["resources"]?.let(TestResources::fromJson) ?: TestResources(),
                (it["maxRetries"]?.longValue() ?: 0).also { count -> require(count in 0L..9L) }.toInt())
        }
    }
}

fun jsTestAction(selection: JsTestSelection, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>, timeoutMillis: Long = 60_000): ActionSpec {
    require(inputs.files.any { it.path == selection.entryPoint }) { "Undeclared Node test entry point" }
    require(toolchains.keys == setOf("node")) { "An explicit verified Node identity is required" }
    return ActionSpec("kvik-js-tests-v1", composition, ActionKind.TEST, jvmHostPlatform(), selection.target.projectName,
        toolchains.toMap(), TreeManifest(inputs.files.toList()), listOf(LogicalPath("test-results.json")), emptyList(), environment, selection.toJson(), timeoutMillis, CachePolicy.NEVER)
}
