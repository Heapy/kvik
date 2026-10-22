package kvik.compiler.ksp

import kvik.core.*

/** Pinned nonincremental Native analysis with separate, ordered JVM runtime and processor closures. */
data class KspNativeCompilation(
    val module: String,
    val moduleName: String,
    val sources: List<LogicalPath>,
    val commonSources: List<LogicalPath>,
    val libraries: List<LogicalPath>,
    val runtimeClasspath: List<LogicalPath>,
    val processorClasspath: List<LogicalPath>,
    val languageVersion: String = "2.4",
    val apiVersion: String = "2.4",
) {
    init {
        require(module.matches(Regex("[A-Za-z0-9_.-]+")) && moduleName.matches(Regex("[A-Za-z0-9_.:-]+")))
        require(sources.isNotEmpty() && sources.distinct().size == sources.size && sources.all { it.value.endsWith(".kt") })
        require(commonSources.distinct().size == commonSources.size && commonSources.all { it in sources })
        require(libraries.distinct().size == libraries.size && libraries.all { it.value.endsWith(".klib") })
        for (classpath in listOf(runtimeClasspath, processorClasspath)) require(classpath.isNotEmpty() && classpath.distinct().size == classpath.size && classpath.all { it.value.endsWith(".jar") })
        require(languageVersion == "2.4" && apiVersion == "2.4") { "KSP Native currently uses the validated Kotlin 2.4 language/API" }
    }
    fun arguments(directory: String = ".", nativeLibraries: List<String> = listOf("@verified-native-stdlib-and-platform")): List<String> {
        fun paths(values: List<LogicalPath>) = values.joinToString(":") { "$directory/input/${it.value}" }
        return buildList {
            addAll(listOf("-Xmx1024m", "-XX:MaxMetaspaceSize=512m", "-XX:ActiveProcessorCount=2", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
                "-Djava.io.tmpdir=$directory/tmp", "-Duser.home=$directory/home", "-Duser.language=en", "-Duser.country=US", "-Duser.timezone=UTC",
                "-classpath", paths(runtimeClasspath), "com.google.devtools.ksp.cmdline.KSPNativeMain",
                "-module-name=$moduleName", "-source-roots=" + paths(sources)))
            if (commonSources.isNotEmpty()) add("-common-source-roots=" + paths(commonSources))
            add("-libraries=" + (libraries.map { "$directory/input/${it.value}" } + nativeLibraries).joinToString(":"))
            addAll(listOf("-language-version=$languageVersion", "-api-version=$apiVersion", "-all-warnings-as-errors=false", "-map-annotation-arguments-in-java=false",
                "-incremental=false", "-incremental-log=false", "-project-base-dir=$directory/input", "-output-base-dir=$directory/output/generated",
                "-caches-dir=$directory/caches", "-class-output-dir=$directory/output/generated/classes", "-kotlin-output-dir=$directory/output/generated/kotlin",
                "-resource-output-dir=$directory/output/generated/resources", "-target=macosArm64", paths(processorClasspath)))
        }
    }
    fun toJson(): JsonValue.Object = obj("module" to module.json(), "moduleName" to moduleName.json(),
        "sources" to sources.map { it.value.json() }.json(), "commonSources" to commonSources.map { it.value.json() }.json(),
        "libraries" to libraries.map { it.value.json() }.json(), "runtimeClasspath" to runtimeClasspath.map { it.value.json() }.json(),
        "processorClasspath" to processorClasspath.map { it.value.json() }.json(), "languageVersion" to languageVersion.json(), "apiVersion" to apiVersion.json(),
        "kspVersion" to "2.3.12".json(), "processor" to "io.kotest:kotest-framework-symbol-processor:6.2.5".json())
    companion object {
        fun fromJson(value: JsonValue): KspNativeCompilation {
            val d = value.fields(setOf("module", "moduleName", "sources", "commonSources", "libraries", "runtimeClasspath", "processorClasspath", "languageVersion", "apiVersion", "kspVersion", "processor"))
            require(d.getValue("kspVersion").stringValue() == "2.3.12" && d.getValue("processor").stringValue() == "io.kotest:kotest-framework-symbol-processor:6.2.5")
            fun paths(name: String) = d.getValue(name).arrayValue().map { LogicalPath(it.stringValue()) }
            return KspNativeCompilation(d.getValue("module").stringValue(), d.getValue("moduleName").stringValue(), paths("sources"), paths("commonSources"), paths("libraries"),
                paths("runtimeClasspath"), paths("processorClasspath"), d.getValue("languageVersion").stringValue(), d.getValue("apiVersion").stringValue())
        }
    }
}

val kotestGeneratedLauncher = LogicalPath("generated/kotlin/io/kotest/framework/runtime/kotest.kt")

fun kspNativeAction(compilation: KspNativeCompilation, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>, timeoutMillis: Long = 120_000): ActionSpec {
    val paths = inputs.files.map { it.path }.toSet()
    require((compilation.sources + compilation.libraries + compilation.runtimeClasspath + compilation.processorClasspath).all { it in paths }) { "Undeclared KSP input" }
    require(toolchains.keys == setOf("native", "jdk", "sdk", "native-dependencies")) { "Complete Native/JDK tool identities required for KSP" }
    return ActionSpec("kvik-ksp-native-v1", composition, ActionKind.GENERATE, mapOf("os" to "macos", "arch" to "arm64"), "macosArm64", toolchains.toMap(), TreeManifest(inputs.files.toList()),
        listOf(LogicalPath("generated")), compilation.arguments(), mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin", "KONAN_USE_INTERNAL_SERVER" to "0", "TZ" to "UTC"), compilation.toJson(), timeoutMillis, CachePolicy.LOCAL)
}
