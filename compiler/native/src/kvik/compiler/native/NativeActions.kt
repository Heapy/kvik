package kvik.compiler.native

import kvik.core.*

enum class NativeOutput { LIBRARY, PROGRAM, TEST, KOTEST_TEST, FRAMEWORK }

/** Closed source-language settings; paths and arbitrary compiler arguments are never accepted here. */
data class NativeLanguageOptions(val returnValueChecker: String? = null, val nameBasedDestructuring: String? = null) {
    init {
        require(returnValueChecker == null || returnValueChecker in setOf("check", "full")) { "Unsupported return-value checker mode" }
        require(nameBasedDestructuring == null || nameBasedDestructuring in setOf("only-syntax", "name-mismatch", "complete")) { "Unsupported destructuring mode" }
    }
    fun arguments(): List<String> = listOfNotNull(returnValueChecker?.let { "-Xreturn-value-checker=$it" },
        nameBasedDestructuring?.let { "-Xname-based-destructuring=$it" })
    fun toJson(): JsonValue.Object = obj("returnValueChecker" to (returnValueChecker?.json() ?: JsonValue.Null),
        "nameBasedDestructuring" to (nameBasedDestructuring?.json() ?: JsonValue.Null))
    companion object {
        fun fromJson(value: JsonValue): NativeLanguageOptions {
            val fields = value.fields(setOf("returnValueChecker", "nameBasedDestructuring"))
            fun mode(name: String) = fields.getValue(name).let { if (it == JsonValue.Null) null else it.stringValue() }
            return NativeLanguageOptions(mode("returnValueChecker"), mode("nameBasedDestructuring"))
        }
    }
}

/** Bind the verified dependency component inventories without leaking installation paths into keys. */
fun nativeDependenciesIdentity(llvm: Digest, libffi: Digest, xcodeToolchain: Digest): Digest = Sha256.digest(obj(
    "schemaVersion" to CONTRACT_VERSION.json(), "implementation" to "kvik-native-dependencies-v1".json(),
    "llvm" to llvm.toJson(), "libffi" to libffi.toJson(), "xcodeToolchain" to xcodeToolchain.toJson()))

fun linuxNativeDependenciesIdentity(llvm: Digest, libffi: Digest, nativeToolchain: Digest): Digest = Sha256.digest(obj(
    "schemaVersion" to CONTRACT_VERSION.json(), "implementation" to "kvik-native-linux-dependencies-v1".json(),
    "llvm" to llvm.toJson(), "libffi" to libffi.toJson(), "nativeToolchain" to nativeToolchain.toJson()))

/** One Kotlin source-set fragment; file ownership and refinement are explicit. */
data class NativeSourceFragment(val name: String, val sources: List<LogicalPath>, val dependsOn: List<String> = emptyList()) {
    init {
        require(name.length in 1..128 && name.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*"))) { "Invalid Native fragment name" }
        require(sources.distinct().size == sources.size && sources.all { it.value.endsWith(".kt") && ':' !in it.value && ',' !in it.value })
        require(dependsOn.distinct().size == dependsOn.size)
    }
    fun toJson(): JsonValue.Object = obj("name" to name.json(), "sources" to sources.map { it.value.json() }.json(),
        "dependsOn" to dependsOn.map { it.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): NativeSourceFragment {
            val fields = value.fields(setOf("name", "sources", "dependsOn"))
            return NativeSourceFragment(fields.getValue("name").stringValue(), fields.getValue("sources").arrayValue().map { LogicalPath(it.stringValue()) },
                fields.getValue("dependsOn").arrayValue().map(JsonValue::stringValue))
        }
    }
}

data class NativeCompilation(
    val module: String,
    val sources: List<LogicalPath>,
    val commonSources: List<LogicalPath> = emptyList(),
    val libraries: List<LogicalPath> = emptyList(),
    val plugins: List<LogicalPath> = emptyList(),
    val output: LogicalPath,
    val produce: NativeOutput = NativeOutput.LIBRARY,
    val moduleName: String = module,
    val optIns: List<String> = emptyList(),
    val explicitApi: Boolean = false,
    val warningsAsErrors: Boolean = false,
    val kotestLauncher: LogicalPath? = null,
    val includedLibrary: LogicalPath? = null,
    val entryPoint: String? = null,
    val pluginApplications: List<JvmPluginApplication> = emptyList(),
    val target: NativeTarget = NativeTarget.MACOS_ARM64,
    val isStaticFramework: Boolean = false,
    val sourceFragments: List<NativeSourceFragment> = emptyList(),
    val languageOptions: NativeLanguageOptions = NativeLanguageOptions(),
) {
    init {
        require(!isStaticFramework || produce == NativeOutput.FRAMEWORK) { "Static framework requires FRAMEWORK output" }
        require(produce != NativeOutput.FRAMEWORK || target.isIos && includedLibrary != null &&
            output.value.substringAfterLast('/').removeSuffix(".framework").matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "Framework output requires an iOS source-free KLIB link and a valid framework base name"
        }
        require(produce !in setOf(NativeOutput.TEST, NativeOutput.KOTEST_TEST) || target.supportsHostExecution) { "iOS test execution is unsupported" }
        require(entryPoint == null || produce == NativeOutput.PROGRAM && entryPoint.length in 1..1024 &&
            entryPoint.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) { "Explicit Native entry point requires a PROGRAM and a qualified function name" }
        require(pluginApplications.isEmpty() || produce == NativeOutput.LIBRARY) { "Workspace Native plugins require LIBRARY compilation; compile a KLIB then use a source-free link" }
        require(pluginApplications.size <= 1 && (pluginApplications.isEmpty() || plugins.isEmpty())) { "Only one plugin application/representation is supported" }
        require(pluginApplications.all { plugin ->
            plugin.classpath.none { it in libraries } && plugin.outputs.all { root ->
                root.value.startsWith("plugins/${plugin.id}/") && !pathsOverlap(root, output) &&
                    (!target.isApple || !pathsOverlap(root, LogicalPath(output.value + ".dSYM")))
            }
        }) { "Plugin paths overlap compilation inputs/outputs or leave the plugin namespace" }
        require(module.matches(Regex("[A-Za-z0-9_.-]+")))
        require(moduleName.matches(Regex("[A-Za-z0-9_.:-]+")))
        require(sources.distinct().size == sources.size && sources.all { it.value.endsWith(".kt") })
        require((sources + libraries + plugins + listOfNotNull(includedLibrary)).none { "," in it.value }) { "Comma-separated compiler path syntax unsupported" }
        if (includedLibrary == null) require(sources.isNotEmpty()) else {
            require(languageOptions == NativeLanguageOptions()) { "Source-language options are invalid for source-free linking" }
            require(sources.isEmpty() && produce != NativeOutput.LIBRARY && includedLibrary.value.endsWith(".klib") && includedLibrary !in libraries) { "Included klib requires a distinct source-free link input" }
            require(commonSources.isEmpty() && plugins.isEmpty() && pluginApplications.isEmpty() && optIns.isEmpty() && !explicitApi && kotestLauncher == null) { "Source compilation options are invalid for source-free linking" }
        }
        if (sourceFragments.isNotEmpty()) {
            require(includedLibrary == null && sourceFragments.size <= 1024 && sourceFragments.map { it.name }.distinct().size == sourceFragments.size)
            validateGraph(sourceFragments.associate { it.name to it.dependsOn.toSet() })
            val owned = sourceFragments.flatMap { it.sources }
            require(owned.size == owned.distinct().size && owned.toSet() == sources.toSet()) { "Every Native source must belong to exactly one fragment" }
            val parents = sourceFragments.flatMap { it.dependsOn }.toSet()
            val terminal = sourceFragments.filter { it.name !in parents }.singleOrNull()
                ?: error("Native compilation requires one terminal fragment")
            require(kotestLauncher == null || kotestLauncher in terminal.sources) { "Kotest launcher must belong to the terminal Native fragment" }
        }
        require(commonSources.distinct().size == commonSources.size && commonSources.all { it in sources })
        require(libraries.distinct().size == libraries.size && libraries.all { it.value.endsWith(".klib") })
        require(plugins.distinct().size == plugins.size && plugins.all { it.value.endsWith(".jar") })
        require(optIns.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) })
        require(output.value.endsWith(when (produce) { NativeOutput.LIBRARY -> ".klib"; NativeOutput.FRAMEWORK -> ".framework"; else -> ".kexe" }))
        require(kotestLauncher == null || produce in setOf(NativeOutput.LIBRARY, NativeOutput.KOTEST_TEST)) { "Kotest launcher requires library or Kotest compilation" }
        require(produce != NativeOutput.KOTEST_TEST || kotestLauncher != null || includedLibrary != null) { "Kotest output requires its generated launcher or compiled klib" }
        require(kotestLauncher == null || kotestLauncher in sources) { "Kotest launcher must be a declared source" }
        require(kotestLauncher !in commonSources) { "Kotest launcher is Native-specific" }
    }
    fun arguments(): List<String> = buildList {
        addAll(listOf("-target", target.compilerName, "-produce", when (produce) { NativeOutput.LIBRARY -> "library"; NativeOutput.FRAMEWORK -> "framework"; else -> "program" }, "-o", "output/${output.value}", "-module-name", moduleName))
        add("-Xoverride-konan-properties=airplaneMode=true")
        if (isStaticFramework) add("-Xstatic-framework")
        entryPoint?.let { addAll(listOf("-entry", it)) }
        if (produce == NativeOutput.TEST) add("-generate-test-runner")
        if (produce == NativeOutput.KOTEST_TEST) addAll(listOf("-entry", "kvik.kotest.runtime.main"))
        if (sourceFragments.isNotEmpty()) {
            add("-Xmulti-platform")
            val parents = sourceFragments.flatMap { it.dependsOn }.toSet()
            sourceFragments.forEach { fragment ->
                add("-Xfragments=${fragment.name}")
                fragment.dependsOn.forEach { add("-Xfragment-refines=${fragment.name}:$it") }
                fragment.sources.forEach { path -> add("-Xfragment-sources=${fragment.name}:" + if (path == kotestLauncher) "prepared/kotest.kt" else "input/${path.value}") }
                if (kotestLauncher != null && fragment.name !in parents) add("-Xfragment-sources=${fragment.name}:prepared/KvikKotestRuntime.kt")
            }
        } else if (commonSources.isNotEmpty()) {
            add("-Xmulti-platform")
            add("-Xcommon-sources=" + commonSources.joinToString(",") { "input/${it.value}" })
        }
        if (explicitApi) add("-Xexplicit-api=strict")
        if (warningsAsErrors) add("-Werror")
        optIns.forEach { add("-opt-in=$it") }
        addAll(languageOptions.arguments())
        libraries.forEach { addAll(listOf("-library", "input/${it.value}")) }
        includedLibrary?.let {
            add("-Xinclude=input/${it.value}")
            add("-Xpartial-linkage-loglevel=error")
        }
        plugins.forEach { add("-Xplugin=input/${it.value}") }
        sources.forEach { add(if (it == kotestLauncher) "prepared/kotest.kt" else "input/${it.value}") }
        if (kotestLauncher != null) add("prepared/KvikKotestRuntime.kt")
    }
    fun toJson(): JsonValue.Object = obj(
        "module" to module.json(), "sources" to sources.map { it.value.json() }.json(),
        "commonSources" to commonSources.map { it.value.json() }.json(), "libraries" to libraries.map { it.value.json() }.json(),
        "plugins" to plugins.map { it.value.json() }.json(), "output" to output.value.json(), "produce" to produce.name.json(),
        "moduleName" to moduleName.json(), "optIns" to optIns.map { it.json() }.json(), "explicitApi" to explicitApi.json(), "warningsAsErrors" to warningsAsErrors.json()).let {
            JsonValue.Object(it.objectValue() + listOfNotNull(languageOptions.takeIf { it != NativeLanguageOptions() }?.let { "languageOptions" to it.toJson() }, sourceFragments.takeIf { it.isNotEmpty() }?.let { "sourceFragments" to it.map(NativeSourceFragment::toJson).json() }, if (isStaticFramework) "isStaticFramework" to true.json() else null, kotestLauncher?.let { "kotestLauncher" to it.value.json() }, includedLibrary?.let { "includedLibrary" to it.value.json() }, entryPoint?.let { "entryPoint" to it.json() }, target.takeIf { it != NativeTarget.MACOS_ARM64 }?.let { "target" to it.projectName.json() }, pluginApplications.takeIf { it.isNotEmpty() }?.let { "pluginApplications" to it.map(JvmPluginApplication::toJson).json() }).toMap())
        }
    companion object {
        fun fromJson(value: JsonValue): NativeCompilation {
            val d = value.fields(setOf("module", "sources", "commonSources", "libraries", "plugins", "output", "produce", "moduleName", "optIns", "explicitApi", "warningsAsErrors"), setOf("kotestLauncher", "includedLibrary", "entryPoint", "pluginApplications", "target", "isStaticFramework", "sourceFragments", "languageOptions"))
            fun paths(name: String) = d.getValue(name).arrayValue().map { LogicalPath(it.stringValue()) }
            return NativeCompilation(d.getValue("module").stringValue(), paths("sources"), paths("commonSources"), paths("libraries"), paths("plugins"), LogicalPath(d.getValue("output").stringValue()), NativeOutput.valueOf(d.getValue("produce").stringValue()), d.getValue("moduleName").stringValue(), d.getValue("optIns").arrayValue().map { it.stringValue() }, d.getValue("explicitApi").booleanValue(), d.getValue("warningsAsErrors").booleanValue(), d["kotestLauncher"]?.stringValue()?.let(::LogicalPath), d["includedLibrary"]?.stringValue()?.let(::LogicalPath), d["entryPoint"]?.stringValue(), d["pluginApplications"]?.arrayValue()?.map(JvmPluginApplication::fromJson) ?: emptyList(), d["target"]?.stringValue()?.let(NativeTarget::require) ?: NativeTarget.MACOS_ARM64, d["isStaticFramework"]?.booleanValue() ?: false, d["sourceFragments"]?.arrayValue()?.map(NativeSourceFragment::fromJson) ?: emptyList(), d["languageOptions"]?.let(NativeLanguageOptions::fromJson) ?: NativeLanguageOptions())
        }
    }
}

fun nativeCompileAction(compilation: NativeCompilation, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>, timeoutMillis: Long = 180_000): ActionSpec {
    val declared = inputs.files.map { it.path }.toSet()
    require((compilation.sources + compilation.libraries + compilation.plugins + listOfNotNull(compilation.includedLibrary)).all { it in declared }) { "Undeclared compiler input" }
    compilation.pluginApplications.forEach { plugin ->
        require(plugin.inputs.all { it in declared }) { "Undeclared compiler plugin input" }
        plugin.classpath.forEach { path -> require(inputs.files.any {
            it.path == path && path.value.endsWith(".jar") || it.path.value.startsWith(path.value + "/")
        }) { "Undeclared/unsupported plugin classpath: ${path.value}" } }
    }
    val identities = compilation.target.toolIdentityNames +
        if (compilation.pluginApplications.isEmpty()) emptySet() else setOf("jvm-compiler", "jvm-worker", "junit")
    require(toolchains.keys == identities) { "Complete Native toolchain identities required" }
    return ActionSpec("kvik-native-compiler-v1", composition, if (compilation.produce == NativeOutput.LIBRARY) ActionKind.COMPILE else ActionKind.LINK,
        compilation.target.executionPlatform, compilation.target.projectName, toolchains.toMap(), TreeManifest(inputs.files.toList()),
        listOf(compilation.output) + compilation.pluginApplications.flatMap { it.outputs } + (if (compilation.produce == NativeOutput.LIBRARY || !compilation.target.isApple || compilation.isStaticFramework) emptyList() else listOf(LogicalPath(compilation.output.value + ".dSYM"))), compilation.arguments(), mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin", "KONAN_USE_INTERNAL_SERVER" to "0"), compilation.toJson(), timeoutMillis, CachePolicy.LOCAL)
}

fun nativeTestAction(module: String, executable: LogicalPath, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>, filter: String? = null, timeoutMillis: Long = 60_000, target: NativeTarget = NativeTarget.MACOS_ARM64, resources: TestResources = TestResources(), maxRetries: Int = 0): ActionSpec {
    require(target.supportsHostExecution) { "iOS test execution is unsupported" }
    require(module.matches(Regex("[A-Za-z0-9_.-]+")))
    require(inputs.files.singleOrNull { it.path == executable }?.executable == true) { "Test executable must be a declared executable input" }
    require(toolchains.keys == target.toolIdentityNames) { "Complete Native toolchain identities required" }
    require(filter == null || filter.matches(Regex("[A-Za-z0-9_.*]+"))) { "Unsupported test filter" }
    return ActionSpec("kvik-native-tests-v1", composition, ActionKind.TEST, target.platform, target.projectName, toolchains.toMap(), TreeManifest(inputs.files.toList()),
        listOf(LogicalPath("test-results.json")), listOf("--ktest_logger=TEAMCITY") + listOfNotNull(filter?.let { "--ktest_filter=$it" }),
        mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin"), obj("module" to module.json(), "executable" to executable.value.json(), "filter" to (filter?.json() ?: JsonValue.Null)).withTestResources(resources).withNativeRetries(maxRetries), timeoutMillis, CachePolicy.NEVER)
}

fun kotestNativeTestAction(module: String, executable: LogicalPath, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>, timeoutMillis: Long = 60_000, target: NativeTarget = NativeTarget.MACOS_ARM64, resources: TestResources = TestResources(), maxRetries: Int = 0): ActionSpec {
    require(target.supportsHostExecution) { "iOS test execution is unsupported" }
    require(module.matches(Regex("[A-Za-z0-9_.-]+")))
    require(inputs.files.singleOrNull { it.path == executable }?.executable == true) { "Kotest executable must be a declared executable input" }
    require(toolchains.keys == target.toolIdentityNames) { "Complete Native toolchain identities required" }
    return ActionSpec("kvik-kotest-native-tests-v1", composition, ActionKind.TEST, target.platform, target.projectName, toolchains.toMap(), TreeManifest(inputs.files.toList()),
        listOf(LogicalPath("test-results.json")), emptyList(), mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin"),
        obj("module" to module.json(), "executable" to executable.value.json()).withTestResources(resources).withNativeRetries(maxRetries), timeoutMillis, CachePolicy.NEVER)
}

private fun JsonValue.Object.withTestResources(resources: TestResources): JsonValue.Object =
    if (resources == TestResources()) this else JsonValue.Object(values + ("resources" to resources.toJson()))

internal fun nativeTestResources(action: ActionSpec): TestResources =
    action.parameters.objectValue()["resources"]?.let(TestResources::fromJson) ?: TestResources()

private fun JsonValue.Object.withNativeRetries(maxRetries: Int): JsonValue.Object {
    require(maxRetries in 0..9) { "Native test maxRetries must be an integer in 0..9" }
    return if (maxRetries == 0) this else JsonValue.Object(values + ("maxRetries" to maxRetries.json()))
}

internal fun nativeMaxRetries(action: ActionSpec): Int =
    (action.parameters.objectValue()["maxRetries"]?.longValue() ?: 0).also {
        require(it in 0L..9L) { "Native test maxRetries must be an integer in 0..9" }
    }.toInt()
