package kvik.compiler.jvm

import kvik.core.*

internal val jvmIdentities = setOf("jdk", "jvm-compiler", "jvm-worker", "junit")
/** Bundle identity shared with K14; workerTree is SHA-256 of canonical core TreeManifest JSON. */
fun jvmWorkerIdentity(workerTree: Digest, api: Digest, stdlib: Digest, junit: Digest,
    jacocoAgent: Digest? = null, jacocoCli: Digest? = null, jacocoVersion: String? = null,
    documentationManifest: Digest? = null): Digest {
    require(listOf(jacocoAgent, jacocoCli, jacocoVersion).all { it == null } ||
        jacocoAgent != null && jacocoCli != null && jacocoVersion == "0.8.14") { "JaCoCo identity requires the pinned version and both artifacts" }
    val identity = obj("schemaVersion" to CONTRACT_VERSION.json(), "implementation" to "kvik-jvm-worker-bundle-v1".json(),
        "workerTree" to workerTree.toJson(), "api" to api.toJson(), "stdlib" to stdlib.toJson(), "junit" to junit.toJson())
    val measured = if (jacocoAgent == null) identity else JsonValue.Object(identity.values + mapOf(
        "jacocoAgent" to jacocoAgent.toJson(), "jacocoCli" to jacocoCli!!.toJson(), "jacocoVersion" to jacocoVersion!!.json()))
    return Sha256.digest(if (documentationManifest == null) measured else JsonValue.Object(measured.values +
        ("documentationManifest" to documentationManifest.toJson())))
}
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun jvmHostPlatform(): Map<String, String> = when (kotlin.native.Platform.osFamily to kotlin.native.Platform.cpuArchitecture) {
    kotlin.native.OsFamily.MACOSX to kotlin.native.CpuArchitecture.ARM64 -> mapOf("os" to "macos", "arch" to "arm64")
    kotlin.native.OsFamily.LINUX to kotlin.native.CpuArchitecture.X64 -> mapOf("os" to "linux", "arch" to "x64")
    else -> error("Unsupported JVM worker host")
}

private val hostPlatform = jvmHostPlatform()
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
    val pluginApplications: List<JvmPluginApplication> = emptyList(),
    val includeStandardLibrary: Boolean = false,
) {
    init {
        require(module.matches(Regex("[A-Za-z0-9_.-]+")))
        require(sources.isNotEmpty() && sources.all { it.value.endsWith(".kt") })
        for (paths in listOf(sources, classpath, plugins, commonSources, friendPaths)) {
            require(paths.distinct().size == paths.size && paths.none { ',' in it.value })
        }
        require(commonSources.all { it in sources } && friendPaths.all { it in classpath })
        require(plugins.size <= 1 && plugins.all { it.value.endsWith(".jar") && it !in classpath })
        require(pluginApplications.size <= 1) { "At most one workspace plugin is supported" }
        require(plugins.isEmpty() || pluginApplications.none { it.id == "org.jetbrains.kotlinx.serialization" }) { "Duplicate serialization plugin ID" }
        require(pluginApplications.all { plugin -> plugin.classpath.none { it in classpath } && plugin.outputs.none { pathsOverlap(it, output) } }) { "Plugin paths overlap compilation classpath/output" }
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
            val release = if (jdkRelease == null) it else JsonValue.Object(it.objectValue() + ("jdkRelease" to jdkRelease.json()))
            val plugins = if (pluginApplications.isEmpty()) release else JsonValue.Object(release.values + ("pluginApplications" to pluginApplications.map { p -> p.toJson() }.json()))
            if (!includeStandardLibrary) plugins else JsonValue.Object(plugins.values + ("includeStandardLibrary" to true.json()))
        }
    companion object {
        fun fromJson(value: JsonValue): JvmCompilation {
            val d = value.fields(setOf("module", "sources", "classpath", "output", "plugins", "commonSources", "friendPaths", "jvmTarget", "optIns", "explicitApi", "warningsAsErrors"), setOf("jdkRelease", "pluginApplications", "includeStandardLibrary"))
            return JvmCompilation(d.getValue("module").stringValue(), d.paths("sources"), d.paths("classpath"), LogicalPath(d.getValue("output").stringValue()),
                d.paths("plugins"), d.paths("commonSources"), d.paths("friendPaths"), d.getValue("jvmTarget").longValue().also { require(it in 1..25) }.toInt(),
                d.getValue("optIns").arrayValue().map(JsonValue::stringValue), d.getValue("explicitApi").booleanValue(), d.getValue("warningsAsErrors").booleanValue(),
                d["jdkRelease"]?.longValue()?.also { require(it in 1..25) }?.toInt(),
                d["pluginApplications"]?.arrayValue()?.map(JvmPluginApplication::fromJson) ?: emptyList(),
                d["includeStandardLibrary"]?.booleanValue() ?: false)
        }
    }
}

/** Genuine common metadata compilation; dependencies are explicit metadata KLIBs/directories. */
data class JvmMetadataCompilation(
    val module: String,
    val sources: List<LogicalPath>,
    val classpath: List<LogicalPath>,
    val output: LogicalPath,
    val optIns: List<String> = emptyList(),
    val explicitApi: Boolean = false,
    val warningsAsErrors: Boolean = false,
    val targets: Set<String> = setOf("jvm", "macosArm64"),
    val plugins: List<LogicalPath> = emptyList(),
    val pluginApplications: List<JvmPluginApplication> = emptyList(),
) {
    init {
        require(targets.size >= 2 && targets.all { it == "jvm" || NativeTarget.find(it) != null || KotlinJsTarget.find(it) != null }) { "Metadata compilation requires at least two supported targets" }
        require(module.matches(Regex("[A-Za-z0-9_.:-]+"))) { "Invalid metadata module name" }
        require(sources.isNotEmpty() && sources.all { it.value.endsWith(".kt") })
        for (paths in listOf(sources, classpath, plugins)) require(paths.distinct().size == paths.size && paths.none { ':' in it.value })
        require(plugins.size <= 1 && plugins.all { it.value.endsWith(".jar") && it !in classpath })
        require(pluginApplications.size <= 1) { "At most one workspace plugin is supported" }
        require(plugins.isEmpty() || pluginApplications.none { it.id == "org.jetbrains.kotlinx.serialization" }) { "Duplicate serialization plugin ID" }
        require(pluginApplications.all { plugin -> plugin.classpath.none { it in classpath } && plugin.outputs.none { pathsOverlap(it, output) } }) {
            "Plugin paths overlap metadata classpath/output"
        }
        require(optIns.distinct().size == optIns.size && optIns.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) })
    }
    fun options(prefix: String = ""): List<String> = buildList {
        // Kept parallel to JvmCompilation.options(prefix); metadata has no path-valued flags.
        require(prefix.none { it == '\u0000' })
        addAll(listOf("-module-name", module, "-language-version", "2.4", "-api-version", "2.4", "-Xmulti-platform"))
        optIns.forEach { add("-opt-in=$it") }
        if (explicitApi) add("-Xexplicit-api=strict")
        if (warningsAsErrors) add("-Werror")
    }
    fun toJson(): JsonValue.Object = obj("module" to module.json(), "sources" to sources.jsonPaths(),
        "classpath" to classpath.jsonPaths(), "output" to output.value.json(), "optIns" to optIns.map { it.json() }.json(),
        "explicitApi" to explicitApi.json(), "warningsAsErrors" to warningsAsErrors.json(), "targets" to targets.sorted().map { it.json() }.json()).let {
            val published = if (plugins.isEmpty()) it else JsonValue.Object(it.values + ("plugins" to plugins.jsonPaths()))
            if (pluginApplications.isEmpty()) published else JsonValue.Object(published.values +
                ("pluginApplications" to pluginApplications.map { plugin -> plugin.toJson() }.json()))
        }
    companion object {
        fun fromJson(value: JsonValue): JvmMetadataCompilation {
            val d = value.fields(setOf("module", "sources", "classpath", "output", "optIns", "explicitApi", "warningsAsErrors", "targets"), setOf("plugins", "pluginApplications"))
            return JvmMetadataCompilation(d.getValue("module").stringValue(), d.paths("sources"), d.paths("classpath"),
                LogicalPath(d.getValue("output").stringValue()), d.getValue("optIns").arrayValue().map(JsonValue::stringValue),
                d.getValue("explicitApi").booleanValue(), d.getValue("warningsAsErrors").booleanValue(),
                d.getValue("targets").arrayValue().map(JsonValue::stringValue).also { require(it.distinct().size == it.size) }.toSet(),
                d["plugins"]?.arrayValue()?.map { LogicalPath(it.stringValue()) } ?: emptyList(),
                d["pluginApplications"]?.arrayValue()?.map(JvmPluginApplication::fromJson) ?: emptyList())
        }
    }
}

fun jvmMetadataCompileAction(compilation: JvmMetadataCompilation, inputs: TreeManifest, composition: Digest,
                            toolchains: Map<String, Digest>, timeoutMillis: Long = 120_000): ActionSpec {
    require(toolchains.keys == jvmIdentities)
    require(compilation.sources.all { source -> inputs.files.any { it.path == source } }) { "Undeclared metadata source" }
    for (path in compilation.classpath) require(inputs.files.any {
        it.path == path && (path.value.endsWith(".klib") || path.value.endsWith(".jar")) || it.path.value.startsWith(path.value + "/")
    }) { "Undeclared metadata KLIB/directory: ${path.value}" }
    require(compilation.plugins.all { path -> inputs.files.any { it.path == path } }) { "Undeclared metadata plugin" }
    compilation.pluginApplications.forEach { plugin ->
        declaredClasspath(plugin.classpath, inputs)
        require(plugin.inputs.all { input -> inputs.files.any { it.path == input } }) { "Undeclared compiler plugin input" }
    }
    return ActionSpec("kvik-jvm-metadata-v1", composition, ActionKind.COMPILE, hostPlatform, "common", toolchains.toMap(),
        TreeManifest(inputs.files.toList()), listOf(compilation.output) + compilation.pluginApplications.flatMap { it.outputs }, listOf("kvik.jvm.CompilerWorkerKt") + compilation.options(),
        workerEnvironment, compilation.toJson(), timeoutMillis, CachePolicy.LOCAL)
}

data class JvmTestSelection(val module: String, val classpath: List<LogicalPath>, val classes: List<String>, val engines: List<String> = listOf("junit-jupiter"), val coverage: JvmCoverageSpec? = null, val resources: TestResources = TestResources(), val agents: List<JvmTestAgent> = emptyList(), val parallelism: Int = 1, val maxRetries: Int = 0, val discoveryRoots: List<LogicalPath> = emptyList()) {
    init {
        require(discoveryRoots.distinct().size == discoveryRoots.size && discoveryRoots.all { it in classpath && !it.value.endsWith(".jar") }) {
            "Test discovery roots must be unique runtime class directories"
        }
        require(discoveryRoots.isEmpty() || classes.isEmpty()) { "Test discovery cannot be combined with explicit classes" }
        require(module.matches(Regex("[A-Za-z0-9_.-]+")))
        require(classpath.isNotEmpty()) { "Test runtime classpath must be nonempty" }
        require(classpath.distinct().size == classpath.size && classes.distinct().size == classes.size)
        require(classes.all { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_.$]*")) })
        require(engines.isNotEmpty() && engines.distinct().size == engines.size && engines.all { it in setOf("junit-jupiter", "kotest") })
        require(maxRetries in 0..9) { "JVM test maxRetries must be an integer in 0..9" }
        require(parallelism in 1..1024 && parallelism <= resources.cpuSlots) { "JVM test parallelism must be in 1..1024 and not exceed reserved CPU slots" }
        require(parallelism == 1 || engines == listOf("junit-jupiter")) { "Parallel JVM tests currently require only the junit-jupiter engine" }
        require(agents.size <= 16 && agents.map { it.id }.distinct().size == agents.size) { "Duplicate or too many test agents" }
        require(agents.flatMap { it.outputs }.size <= 256) { "Too many test instrumentation outputs" }
    }
    fun toJson(): JsonValue.Object = obj("module" to module.json(), "classpath" to classpath.jsonPaths(),
        "classes" to classes.map { it.json() }.json(), "engines" to engines.map { it.json() }.json()).let {
            val measured = if (coverage == null) it else JsonValue.Object(it.values + ("coverage" to coverage.toJson()))
            val scheduled = if (resources == TestResources()) measured else JsonValue.Object(measured.values + ("resources" to resources.toJson()))
            val instrumented = if (agents.isEmpty()) scheduled else JsonValue.Object(scheduled.values + ("agents" to agents.map { it.toJson() }.json()))
            val scheduledThreads = if (parallelism == 1) instrumented else JsonValue.Object(instrumented.values + ("parallelism" to parallelism.json()))
            val retried = if (maxRetries == 0) scheduledThreads else JsonValue.Object(scheduledThreads.values + ("maxRetries" to maxRetries.json()))
            if (discoveryRoots.isEmpty()) retried else JsonValue.Object(retried.values + ("discoveryRoots" to discoveryRoots.jsonPaths()))
        }
    companion object {
        fun fromJson(value: JsonValue): JvmTestSelection {
            val d = value.fields(setOf("module", "classpath", "classes", "engines"), setOf("coverage", "resources", "agents", "parallelism", "maxRetries", "discoveryRoots"))
            return JvmTestSelection(d.getValue("module").stringValue(), d.paths("classpath"), d.getValue("classes").arrayValue().map(JsonValue::stringValue), d.getValue("engines").arrayValue().map(JsonValue::stringValue), d["coverage"]?.let(JvmCoverageSpec::fromJson), d["resources"]?.let(TestResources::fromJson) ?: TestResources(),
                d["agents"]?.arrayValue()?.map(JvmTestAgent::fromJson).orEmpty(),
                (d["parallelism"]?.longValue() ?: 1).also { require(it in 1L..1024L) { "Invalid JVM test parallelism" } }.toInt(),
                (d["maxRetries"]?.longValue() ?: 0).also { require(it in 0L..9L) { "Invalid JVM test maxRetries" } }.toInt(),
                if ("discoveryRoots" in d) d.paths("discoveryRoots") else emptyList())
        }
    }
}

private fun List<LogicalPath>.jsonPaths() = map { it.value.json() }.json()
private fun Map<String, JsonValue>.paths(name: String) = getValue(name).arrayValue().map { LogicalPath(it.stringValue()) }
private fun declaredClasspath(paths: List<LogicalPath>, inputs: TreeManifest) {
    for (path in paths) require(inputs.files.any { it.path == path && path.value.endsWith(".jar") || it.path.value.startsWith(path.value + "/") }) { "Undeclared/unsupported classpath: ${path.value}" }
}

/** Package classpath bytes without invoking a compiler or inspecting the requested compilation's sources. */
fun jvmClasspathAction(metadata: JvmClasspath, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>): ActionSpec {
    require(toolchains.keys == jvmIdentities)
    metadata.requireInputs(inputs)
    return ActionSpec("kvik-jvm-classpath-v1", composition, ActionKind.PACKAGE, hostPlatform, "jvm", toolchains.toMap(),
        TreeManifest(inputs.files.toList()), listOf(LogicalPath("classpath")), emptyList(), emptyMap(), metadata.toJson(), cachePolicy = CachePolicy.LOCAL)
}

fun jvmCompileAction(compilation: JvmCompilation, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>, timeoutMillis: Long = 120_000, cachePolicy: CachePolicy = CachePolicy.LOCAL): ActionSpec {
    require(cachePolicy == CachePolicy.LOCAL || cachePolicy == CachePolicy.REMOTE) { "Unsupported JVM compilation cache policy" }
    require(cachePolicy != CachePolicy.REMOTE || compilation.plugins.isEmpty() && compilation.pluginApplications.isEmpty()) {
        "Compiler plugin actions require a separate remote portability approval"
    }
    require(toolchains.keys == jvmIdentities)
    require((compilation.sources + compilation.plugins).all { path -> inputs.files.any { it.path == path } }) { "Undeclared source/plugin input" }
    declaredClasspath(compilation.classpath, inputs)
    compilation.pluginApplications.forEach { plugin ->
        declaredClasspath(plugin.classpath, inputs)
        require(plugin.inputs.all { input -> inputs.files.any { it.path == input } }) { "Undeclared compiler plugin input" }
    }
    return ActionSpec("kvik-jvm-compiler-v1", composition, ActionKind.COMPILE, hostPlatform, "jvm", toolchains.toMap(), TreeManifest(inputs.files.toList()),
        listOf(compilation.output) + compilation.pluginApplications.flatMap { it.outputs }, listOf("kvik.jvm.CompilerWorkerKt") + compilation.options(), workerEnvironment, compilation.toJson(), timeoutMillis, cachePolicy)
}

/** The approved portable slice excludes all compiler plugins, including workspace plugins. */
fun portableJvmCompilation(action: ActionSpec): Boolean {
    if (action.implementation != "kvik-jvm-compiler-v1" || action.target != "jvm" || action.kind != ActionKind.COMPILE) return false
    return try {
        val compilation = JvmCompilation.fromJson(action.parameters)
        compilation.plugins.isEmpty() && compilation.pluginApplications.isEmpty() &&
            action == jvmCompileAction(compilation, action.inputs, action.composition, action.toolchains, action.timeoutMillis, action.cachePolicy)
    } catch (_: Exception) { false }
}

fun jvmTestAction(selection: JvmTestSelection, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>, timeoutMillis: Long = 60_000): ActionSpec {
    require(toolchains.keys == jvmIdentities)
    declaredClasspath(selection.classpath, inputs)
    selection.agents.forEach { it.requireInputs(inputs) }
    selection.coverage?.let { coverage ->
        require(coverage.classDirectories.all { it.path in selection.classpath && !it.path.value.endsWith(".jar") }) { "Coverage class roots must be declared runtime directories" }
        require(coverage.sources.all { source -> inputs.files.any { it.path == source.inputPath && it.digest == source.digest } }) { "Coverage source inputs differ from their declared digests" }
        val names = coverageClassNames(coverage, inputs).toSet()
        val otherDirectories = selection.classpath.filter { path -> !path.value.endsWith(".jar") && coverage.classDirectories.none { it.path == path } }
        require(otherDirectories.none { root -> inputs.files.any { file ->
            file.path.value.startsWith(root.value + "/") && file.path.value.endsWith(".class") &&
                file.path.value.removePrefix(root.value + "/").removeSuffix(".class").replace('/', '.') in names
        } }) { "Test/runtime directory shadows a production coverage class" }
    }
    return ActionSpec("kvik-jvm-tests-v1", composition, ActionKind.TEST, hostPlatform, "jvm", toolchains.toMap(), TreeManifest(inputs.files.toList()),
        listOf(LogicalPath("test-results.json")) + (if (selection.coverage == null) emptyList() else listOf(LogicalPath("coverage.exec"), LogicalPath("coverage-result.json"))) + selection.agents.flatMap { it.outputs },
        listOf("kvik.jvm.TestWorker") + selection.classes, workerEnvironment, selection.toJson(), timeoutMillis, CachePolicy.NEVER)
}

/** Exact classes avoid instrumenting test/framework/dependency JAR classes. Bound the agent option
 * before launching (64 KiB), rather than silently broadening to a package wildcard.
 */
internal fun coverageClassNames(coverage: JvmCoverageSpec, inputs: TreeManifest): List<String> {
    val names = coverage.classDirectories.flatMap { root ->
        inputs.files.filter { it.path.value.startsWith(root.path.value + "/") && it.path.value.endsWith(".class") }.map {
            it.path.value.removePrefix(root.path.value + "/").removeSuffix(".class").replace('/', '.')
        }.also { require(it.isNotEmpty()) { "Coverage class directory contains no classes: ${root.path.value}" } }
    }
    require(names.distinct().size == names.size) { "Coverage class names overlap across production modules" }
    require(names.all { name -> name.isNotEmpty() && name.none { it.isISOControl() || it in "*,?:=" } }) { "Coverage class names contain unsupported JaCoCo option/pattern delimiters" }
    val sorted = names.sorted()
    require(sorted.joinToString(":").encodeToByteArray().size <= 64 * 1024) { "Coverage class inclusion list exceeds 64 KiB; select fewer production modules" }
    return sorted
}
