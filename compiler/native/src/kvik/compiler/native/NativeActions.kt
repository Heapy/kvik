package kvik.compiler.native

import kvik.core.*

enum class NativeOutput { LIBRARY, PROGRAM, TEST, KOTEST_TEST }

/** Bind the verified dependency component inventories without leaking installation paths into keys. */
fun nativeDependenciesIdentity(llvm: Digest, libffi: Digest, xcodeToolchain: Digest): Digest = Sha256.digest(obj(
    "schemaVersion" to CONTRACT_VERSION.json(), "implementation" to "kvik-native-dependencies-v1".json(),
    "llvm" to llvm.toJson(), "libffi" to libffi.toJson(), "xcodeToolchain" to xcodeToolchain.toJson()))

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
) {
    init {
        require(module.matches(Regex("[A-Za-z0-9_.-]+")))
        require(moduleName.matches(Regex("[A-Za-z0-9_.:-]+")))
        require(sources.isNotEmpty() && sources.distinct().size == sources.size && sources.all { it.value.endsWith(".kt") })
        require((sources + libraries + plugins).none { "," in it.value }) { "Comma-separated compiler path syntax unsupported" }
        require(commonSources.distinct().size == commonSources.size && commonSources.all { it in sources })
        require(libraries.distinct().size == libraries.size && libraries.all { it.value.endsWith(".klib") })
        require(plugins.distinct().size == plugins.size && plugins.all { it.value.endsWith(".jar") })
        require(optIns.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) })
        require(output.value.endsWith(if (produce == NativeOutput.LIBRARY) ".klib" else ".kexe"))
        require((produce == NativeOutput.KOTEST_TEST) == (kotestLauncher != null)) { "Kotest output requires its generated launcher" }
        require(kotestLauncher == null || kotestLauncher in sources) { "Kotest launcher must be a declared source" }
        require(kotestLauncher !in commonSources) { "Kotest launcher is Native-specific" }
    }
    fun arguments(): List<String> = buildList {
        addAll(listOf("-target", "macos_arm64", "-produce", if (produce == NativeOutput.LIBRARY) "library" else "program", "-o", "output/${output.value}", "-module-name", moduleName))
        add("-Xoverride-konan-properties=airplaneMode=true")
        if (produce == NativeOutput.TEST) add("-generate-test-runner")
        if (produce == NativeOutput.KOTEST_TEST) addAll(listOf("-entry", "kvik.kotest.runtime.main"))
        if (commonSources.isNotEmpty()) {
            add("-Xmulti-platform")
            add("-Xcommon-sources=" + commonSources.joinToString(",") { "input/${it.value}" })
        }
        if (explicitApi) add("-Xexplicit-api=strict")
        if (warningsAsErrors) add("-Werror")
        optIns.forEach { add("-opt-in=$it") }
        libraries.forEach { addAll(listOf("-library", "input/${it.value}")) }
        plugins.forEach { add("-Xplugin=input/${it.value}") }
        sources.forEach { add(if (it == kotestLauncher) "prepared/kotest.kt" else "input/${it.value}") }
        if (produce == NativeOutput.KOTEST_TEST) add("prepared/KvikKotestRuntime.kt")
    }
    fun toJson(): JsonValue.Object = obj(
        "module" to module.json(), "sources" to sources.map { it.value.json() }.json(),
        "commonSources" to commonSources.map { it.value.json() }.json(), "libraries" to libraries.map { it.value.json() }.json(),
        "plugins" to plugins.map { it.value.json() }.json(), "output" to output.value.json(), "produce" to produce.name.json(),
        "moduleName" to moduleName.json(), "optIns" to optIns.map { it.json() }.json(), "explicitApi" to explicitApi.json(), "warningsAsErrors" to warningsAsErrors.json()).let {
            if (kotestLauncher == null) it else JsonValue.Object(it.objectValue() + ("kotestLauncher" to kotestLauncher.value.json()))
        }
    companion object {
        fun fromJson(value: JsonValue): NativeCompilation {
            val d = value.fields(setOf("module", "sources", "commonSources", "libraries", "plugins", "output", "produce", "moduleName", "optIns", "explicitApi", "warningsAsErrors"), setOf("kotestLauncher"))
            fun paths(name: String) = d.getValue(name).arrayValue().map { LogicalPath(it.stringValue()) }
            return NativeCompilation(d.getValue("module").stringValue(), paths("sources"), paths("commonSources"), paths("libraries"), paths("plugins"), LogicalPath(d.getValue("output").stringValue()), NativeOutput.valueOf(d.getValue("produce").stringValue()), d.getValue("moduleName").stringValue(), d.getValue("optIns").arrayValue().map { it.stringValue() }, d.getValue("explicitApi").booleanValue(), d.getValue("warningsAsErrors").booleanValue(), d["kotestLauncher"]?.stringValue()?.let(::LogicalPath))
        }
    }
}

fun nativeCompileAction(compilation: NativeCompilation, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>, timeoutMillis: Long = 180_000): ActionSpec {
    val declared = inputs.files.map { it.path }.toSet()
    require((compilation.sources + compilation.libraries + compilation.plugins).all { it in declared }) { "Undeclared compiler input" }
    require(toolchains.keys == setOf("native", "jdk", "sdk", "native-dependencies")) { "Complete Native toolchain identities required" }
    return ActionSpec("kvik-native-compiler-v1", composition, if (compilation.produce == NativeOutput.LIBRARY) ActionKind.COMPILE else ActionKind.LINK,
        mapOf("os" to "macos", "arch" to "arm64"), "macosArm64", toolchains.toMap(), TreeManifest(inputs.files.toList()),
        listOf(compilation.output) + if (compilation.produce == NativeOutput.LIBRARY) emptyList() else listOf(LogicalPath(compilation.output.value + ".dSYM")), compilation.arguments(), mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin", "KONAN_USE_INTERNAL_SERVER" to "0"), compilation.toJson(), timeoutMillis, CachePolicy.LOCAL)
}

fun nativeTestAction(module: String, executable: LogicalPath, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>, filter: String? = null, timeoutMillis: Long = 60_000): ActionSpec {
    require(module.matches(Regex("[A-Za-z0-9_.-]+")))
    require(inputs.files.singleOrNull { it.path == executable }?.executable == true) { "Test executable must be a declared executable input" }
    require(toolchains.keys == setOf("native", "jdk", "sdk", "native-dependencies")) { "Complete Native toolchain identities required" }
    require(filter == null || filter.matches(Regex("[A-Za-z0-9_.*]+"))) { "Unsupported test filter" }
    return ActionSpec("kvik-native-tests-v1", composition, ActionKind.TEST, mapOf("os" to "macos", "arch" to "arm64"), "macosArm64", toolchains.toMap(), TreeManifest(inputs.files.toList()),
        listOf(LogicalPath("test-results.json")), listOf("--ktest_logger=TEAMCITY") + listOfNotNull(filter?.let { "--ktest_filter=$it" }),
        mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin", "KONAN_USE_INTERNAL_SERVER" to "0"), obj("module" to module.json(), "executable" to executable.value.json(), "filter" to (filter?.json() ?: JsonValue.Null)), timeoutMillis, CachePolicy.NEVER)
}

fun kotestNativeTestAction(module: String, executable: LogicalPath, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>, timeoutMillis: Long = 60_000): ActionSpec {
    require(module.matches(Regex("[A-Za-z0-9_.-]+")))
    require(inputs.files.singleOrNull { it.path == executable }?.executable == true) { "Kotest executable must be a declared executable input" }
    require(toolchains.keys == setOf("native", "jdk", "sdk", "native-dependencies")) { "Complete Native toolchain identities required" }
    return ActionSpec("kvik-kotest-native-tests-v1", composition, ActionKind.TEST, mapOf("os" to "macos", "arch" to "arm64"), "macosArm64", toolchains.toMap(), TreeManifest(inputs.files.toList()),
        listOf(LogicalPath("test-results.json")), emptyList(), mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin", "KONAN_USE_INTERNAL_SERVER" to "0"),
        obj("module" to module.json(), "executable" to executable.value.json()), timeoutMillis, CachePolicy.NEVER)
}
