package kvik.compiler.jvm

import kvik.core.*

/** A real Kotlin script and its explicitly captured importable Kotlin scripts. */
data class KtsCompilation(
    val name: String,
    val primary: LogicalPath,
    val sources: List<LogicalPath>,
    val classpath: List<LogicalPath>,
    val output: LogicalPath,
    val plugins: List<LogicalPath> = emptyList(),
    val jvmTarget: Int = 11,
    val optIns: List<String> = emptyList(),
    val explicitApi: Boolean = false,
    val warningsAsErrors: Boolean = false,
    val jdkRelease: Int? = null,
    val pluginApplications: List<JvmPluginApplication> = emptyList(),
    val declaredDependencies: List<String> = emptyList(),
    val declaredRepositories: List<String> = emptyList(),
) {
    init {
        require(name.length in 1..135 && name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")))
        require(primary.value.endsWith(".kts") && primary in sources)
        require(sources.size in 1..1024 && sources.all { it.value.endsWith(".kts") }) {
            "Script compilation requires an entry script and captured script imports"
        }
        for (paths in listOf(sources, classpath, plugins)) {
            require(paths.distinct().size == paths.size && paths.none { ':' in it.value || ',' in it.value || it.value.any(Char::isISOControl) })
        }
        require(plugins.size <= 1 && plugins.all { it.value.endsWith(".jar") && it !in classpath })
        require(pluginApplications.size <= 1 && (pluginApplications.isEmpty() || plugins.isEmpty()))
        require(pluginApplications.all { plugin -> plugin.classpath.none { it in classpath } && plugin.outputs.none { pathsOverlap(it, output) } })
        require(jvmTarget in setOf(11, 17, 21, 25))
        require(jdkRelease == null || jdkRelease == jvmTarget)
        require(optIns.distinct().size == optIns.size && optIns.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) })
        require(declaredDependencies.size <= 4096 && declaredDependencies.distinct().size == declaredDependencies.size)
        declaredDependencies.forEach(Coordinate::parse)
        require(declaredRepositories.size <= 128 && declaredRepositories.distinct().size == declaredRepositories.size &&
            declaredRepositories.all { it.length <= 4096 && it.startsWith("https://") && it.none(Char::isISOControl) })
    }
    fun options(): List<String> = buildList {
        addAll(listOf("-jvm-target", jvmTarget.toString(), "-module-name", name, "-language-version", "2.4", "-api-version", "2.4"))
        jdkRelease?.let { add("-Xjdk-release=$it") }
        optIns.forEach { add("-opt-in=$it") }
        if (explicitApi) add("-Xexplicit-api=strict")
        if (warningsAsErrors) add("-Werror")
    }
    fun toJson(): JsonValue.Object = obj("name" to name.json(), "primary" to primary.value.json(),
        "sources" to sources.ktsPaths(), "classpath" to classpath.ktsPaths(), "output" to output.value.json(),
        "plugins" to plugins.ktsPaths(), "jvmTarget" to jvmTarget.json(), "optIns" to optIns.map { it.json() }.json(),
        "explicitApi" to explicitApi.json(), "warningsAsErrors" to warningsAsErrors.json(),
        "jdkRelease" to (jdkRelease?.json() ?: JsonValue.Null),
        "pluginApplications" to pluginApplications.map { it.toJson() }.json(),
        "declaredDependencies" to declaredDependencies.map { it.json() }.json(),
        "declaredRepositories" to declaredRepositories.map { it.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): KtsCompilation {
            val fields = value.fields(setOf("name", "primary", "sources", "classpath", "output", "plugins", "jvmTarget",
                "optIns", "explicitApi", "warningsAsErrors", "jdkRelease", "pluginApplications", "declaredDependencies", "declaredRepositories"))
            fun strings(name: String) = fields.getValue(name).arrayValue().map(JsonValue::stringValue)
            return KtsCompilation(fields.getValue("name").stringValue(), LogicalPath(fields.getValue("primary").stringValue()),
                strings("sources").map(::LogicalPath), strings("classpath").map(::LogicalPath), LogicalPath(fields.getValue("output").stringValue()),
                strings("plugins").map(::LogicalPath), fields.getValue("jvmTarget").longValue().also { require(it in 1..25) }.toInt(),
                strings("optIns"), fields.getValue("explicitApi").booleanValue(), fields.getValue("warningsAsErrors").booleanValue(),
                fields.getValue("jdkRelease").takeUnless { it == JsonValue.Null }?.longValue()?.also { require(it in 1..25) }?.toInt(),
                fields.getValue("pluginApplications").arrayValue().map(JvmPluginApplication::fromJson),
                strings("declaredDependencies"), strings("declaredRepositories"))
        }
    }
}

fun ktsCompileAction(compilation: KtsCompilation, inputs: TreeManifest, composition: Digest,
                     toolchains: Map<String, Digest>, timeoutMillis: Long = 120_000): ActionSpec {
    require(toolchains.keys == jvmIdentities)
    require((compilation.sources + compilation.plugins).all { path -> inputs.files.any { it.path == path } }) { "Undeclared script source/plugin" }
    ktsDeclaredClasspath(compilation.classpath, inputs)
    compilation.pluginApplications.forEach { plugin ->
        ktsDeclaredClasspath(plugin.classpath, inputs)
        require(plugin.inputs.all { input -> inputs.files.any { it.path == input } }) { "Undeclared script compiler plugin input" }
    }
    return ActionSpec("kvik-kotlin-script-compiler-v1", composition, ActionKind.COMPILE, jvmHostPlatform(), "jvm", toolchains.toMap(),
        TreeManifest(inputs.files.toList()), listOf(compilation.output) + compilation.pluginApplications.flatMap { it.outputs },
        listOf("kvik.jvm.KotlinScriptCompileWorker") + compilation.options(), mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin"),
        compilation.toJson(), timeoutMillis, CachePolicy.LOCAL)
}

/** The bundle includes its script descriptor and compiled classes; arguments remain execution-only. */
data class KtsLaunch(
    val name: String,
    val bundle: LogicalPath,
    val classpath: List<LogicalPath>,
    val arguments: List<String> = emptyList(),
) {
    init {
        require(name.length in 1..128 && name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")))
        require(classpath.isNotEmpty() && classpath.first() == bundle && classpath.distinct().size == classpath.size)
        require(classpath.none { ':' in it.value || it.value.any(Char::isISOControl) })
        require(arguments.size <= 4096 && arguments.all { '\u0000' !in it } && arguments.sumOf { it.encodeToByteArray().size.toLong() } <= 1024 * 1024)
    }
    fun toJson(): JsonValue.Object = obj("name" to name.json(), "bundle" to bundle.value.json(),
        "classpath" to classpath.ktsPaths(), "arguments" to arguments.map { it.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): KtsLaunch {
            val fields = value.fields(setOf("name", "bundle", "classpath", "arguments"))
            return KtsLaunch(fields.getValue("name").stringValue(), LogicalPath(fields.getValue("bundle").stringValue()),
                fields.getValue("classpath").arrayValue().map { LogicalPath(it.stringValue()) },
                fields.getValue("arguments").arrayValue().map(JsonValue::stringValue))
        }
    }
}

fun ktsScriptAction(launch: KtsLaunch, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>,
                    environment: Map<String, String>, timeoutMillis: Long): ActionSpec {
    require(toolchains.keys == jvmIdentities && timeoutMillis in 1L..3_600_000L)
    validateScriptEnvironment(environment)
    ktsDeclaredClasspath(launch.classpath, inputs)
    require(inputs.files.any { it.path.value == "${launch.bundle.value}/kvik-script.json" }) { "Missing compiled script descriptor" }
    require(inputs.files.any { it.path.value.startsWith(launch.bundle.value + "/") && it.path.value.endsWith(".class") }) { "Missing compiled script classes" }
    return ActionSpec("kvik-kotlin-script-v1", composition, ActionKind.SCRIPT, jvmHostPlatform(), "jvm", toolchains.toMap(),
        TreeManifest(inputs.files.toList()), emptyList(), launch.arguments.toList(), environment.toMap(), launch.toJson(), timeoutMillis, CachePolicy.NEVER)
}

/** The compiled script runs freshly; only validated generated Kotlin files become outputs. */
fun ktsScriptGeneratorAction(launch: KtsLaunch, inputs: TreeManifest, composition: Digest,
                            toolchains: Map<String, Digest>, environment: Map<String, String>, timeoutMillis: Long,
                            resourceOutputs: List<LogicalPath> = emptyList()): ActionSpec {
    validateScriptGeneratorLayout(launch.arguments, inputs)
    return ktsScriptAction(launch, inputs, composition, toolchains, environment, timeoutMillis).copy(
        implementation = "kvik-kotlin-script-generator-v1", kind = ActionKind.GENERATE,
        outputs = listOf(LogicalPath("generated")), parameters = generatorParameters(launch.toJson(), resourceOutputs))
}

private fun List<LogicalPath>.ktsPaths() = map { it.value.json() }.json()
private fun ktsDeclaredClasspath(paths: List<LogicalPath>, inputs: TreeManifest) {
    for (path in paths) require(inputs.files.any { it.path == path && path.value.endsWith(".jar") || it.path.value.startsWith(path.value + "/") }) {
        "Undeclared script classpath: ${path.value}"
    }
}
