package kvik.compiler.jvm

import kvik.core.*

internal val jvmIdentities = setOf("jdk", "jvm-compiler", "jvm-worker", "junit")
/** Bundle identity shared with K14; workerTree is SHA-256 of canonical core TreeManifest JSON. */
fun jvmWorkerIdentity(workerTree: Digest, api: Digest, stdlib: Digest, junit: Digest): Digest = Sha256.digest(obj(
    "schemaVersion" to CONTRACT_VERSION.json(), "implementation" to "kvik-jvm-worker-bundle-v1".json(),
    "workerTree" to workerTree.toJson(), "api" to api.toJson(), "stdlib" to stdlib.toJson(), "junit" to junit.toJson()))
private val hostPlatform = mapOf("os" to "macos", "arch" to "arm64")
private val workerEnvironment = mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin")

/** Supported compiler settings, deliberately without a free-form flag escape hatch. */
data class JvmCompilation(
    val module: String,
    val sources: List<LogicalPath>,
    val classpath: List<LogicalPath>,
    val output: LogicalPath,
    val plugins: List<LogicalPath> = emptyList(),
    val commonSources: List<LogicalPath> = emptyList(),
    val friendPaths: List<LogicalPath> = emptyList(),
    val jvmTarget: Int = 11,
    val optIns: List<String> = emptyList(),
    val explicitApi: Boolean = false,
    val warningsAsErrors: Boolean = false,
    val jdkRelease: Int? = null,
) {
    init {
        require(module.matches(Regex("[A-Za-z0-9_.-]+")))
        require(sources.isNotEmpty() && sources.all { it.value.endsWith(".kt") })
        for (paths in listOf(sources, classpath, plugins, commonSources, friendPaths)) {
            require(paths.distinct().size == paths.size && paths.none { ',' in it.value })
        }
        require(commonSources.all { it in sources } && friendPaths.all { it in classpath })
        require(plugins.size <= 1 && plugins.all { it.value.endsWith(".jar") && it !in classpath })
        require(jvmTarget in setOf(11, 17, 21, 25))
        require(jdkRelease == null || jdkRelease == jvmTarget) { "JDK release must equal JVM target" }
        require(optIns.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) })
    }
    fun options(prefix: String = ""): List<String> = buildList {
        addAll(listOf("-jvm-target", jvmTarget.toString(), "-module-name", module, "-language-version", "2.4", "-api-version", "2.4"))
        jdkRelease?.let { add("-Xjdk-release=$it") }
        if (commonSources.isNotEmpty()) {
            add("-Xmulti-platform")
            add("-Xcommon-sources=" + commonSources.joinToString(",") { prefix + it.value })
        }
        if (friendPaths.isNotEmpty()) add("-Xfriend-paths=" + friendPaths.joinToString(",") { prefix + it.value })
        optIns.forEach { add("-opt-in=$it") }
        if (explicitApi) add("-Xexplicit-api=strict")
        if (warningsAsErrors) add("-Werror")
    }
    fun toJson(): JsonValue.Object = obj("module" to module.json(), "sources" to sources.jsonPaths(),
        "classpath" to classpath.jsonPaths(), "output" to output.value.json(), "plugins" to plugins.jsonPaths(),
        "commonSources" to commonSources.jsonPaths(), "friendPaths" to friendPaths.jsonPaths(), "jvmTarget" to jvmTarget.json(),
        "optIns" to optIns.map { it.json() }.json(), "explicitApi" to explicitApi.json(), "warningsAsErrors" to warningsAsErrors.json()).let {
            if (jdkRelease == null) it else JsonValue.Object(it.objectValue() + ("jdkRelease" to jdkRelease.json()))
        }
    companion object {
        fun fromJson(value: JsonValue): JvmCompilation {
            val d = value.fields(setOf("module", "sources", "classpath", "output", "plugins", "commonSources", "friendPaths", "jvmTarget", "optIns", "explicitApi", "warningsAsErrors"), setOf("jdkRelease"))
            return JvmCompilation(d.getValue("module").stringValue(), d.paths("sources"), d.paths("classpath"), LogicalPath(d.getValue("output").stringValue()),
                d.paths("plugins"), d.paths("commonSources"), d.paths("friendPaths"), d.getValue("jvmTarget").longValue().also { require(it in 1..25) }.toInt(),
                d.getValue("optIns").arrayValue().map(JsonValue::stringValue), d.getValue("explicitApi").booleanValue(), d.getValue("warningsAsErrors").booleanValue(),
                d["jdkRelease"]?.longValue()?.also { require(it in 1..25) }?.toInt())
        }
    }
}

data class JvmTestSelection(val module: String, val classpath: List<LogicalPath>, val classes: List<String>, val engines: List<String> = listOf("junit-jupiter")) {
    init {
        require(module.matches(Regex("[A-Za-z0-9_.-]+")))
        require(classpath.isNotEmpty()) { "Test runtime classpath must be nonempty" }
        require(classpath.distinct().size == classpath.size && classes.distinct().size == classes.size)
        require(classes.all { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_.$]*")) })
        require(engines.isNotEmpty() && engines.distinct().size == engines.size && engines.all { it in setOf("junit-jupiter", "kotest") })
    }
    fun toJson(): JsonValue.Object = obj("module" to module.json(), "classpath" to classpath.jsonPaths(),
        "classes" to classes.map { it.json() }.json(), "engines" to engines.map { it.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): JvmTestSelection {
            val d = value.fields(setOf("module", "classpath", "classes", "engines"))
            return JvmTestSelection(d.getValue("module").stringValue(), d.paths("classpath"), d.getValue("classes").arrayValue().map(JsonValue::stringValue), d.getValue("engines").arrayValue().map(JsonValue::stringValue))
        }
    }
}

private fun List<LogicalPath>.jsonPaths() = map { it.value.json() }.json()
private fun Map<String, JsonValue>.paths(name: String) = getValue(name).arrayValue().map { LogicalPath(it.stringValue()) }
private fun declaredClasspath(paths: List<LogicalPath>, inputs: TreeManifest) {
    for (path in paths) require(inputs.files.any { it.path == path && path.value.endsWith(".jar") || it.path.value.startsWith(path.value + "/") }) { "Undeclared/unsupported classpath: ${path.value}" }
}

fun jvmCompileAction(compilation: JvmCompilation, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>, timeoutMillis: Long = 120_000): ActionSpec {
    require(toolchains.keys == jvmIdentities)
    require((compilation.sources + compilation.plugins).all { path -> inputs.files.any { it.path == path } }) { "Undeclared source/plugin input" }
    declaredClasspath(compilation.classpath, inputs)
    return ActionSpec("kvik-jvm-compiler-v1", composition, ActionKind.COMPILE, hostPlatform, "jvm", toolchains.toMap(), TreeManifest(inputs.files.toList()),
        listOf(compilation.output), listOf("kvik.jvm.CompilerWorkerKt") + compilation.options(), workerEnvironment, compilation.toJson(), timeoutMillis, CachePolicy.LOCAL)
}

fun jvmTestAction(selection: JvmTestSelection, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>, timeoutMillis: Long = 60_000): ActionSpec {
    require(toolchains.keys == jvmIdentities)
    declaredClasspath(selection.classpath, inputs)
    return ActionSpec("kvik-jvm-tests-v1", composition, ActionKind.TEST, hostPlatform, "jvm", toolchains.toMap(), TreeManifest(inputs.files.toList()),
        listOf(LogicalPath("test-results.json")), listOf("kvik.jvm.TestWorker") + selection.classes, workerEnvironment, selection.toJson(), timeoutMillis, CachePolicy.NEVER)
}
