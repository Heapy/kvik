package kvik.core

data class Repository(val id: String, val url: String) {
    init { require(id.isNotBlank() && url.startsWith("https://")) { "Repository requires an id and HTTPS URL" } }
}

data class Coordinate(val group: String, val name: String, val version: String) {
    init {
        require(listOf(group, name, version).all { it.matches(Regex("[A-Za-z0-9_.-]+")) }) { "Only exact Maven coordinates are supported" }
        require(group.split('.').all { it.isNotEmpty() } && name !in setOf(".", "..") && version !in setOf(".", "..")) { "Invalid Maven path segment" }
        require(!version.endsWith("-SNAPSHOT") && version !in setOf("LATEST", "RELEASE")) { "Mutable Maven version unsupported" }
    }
    override fun toString(): String = "$group:$name:$version"
    companion object {
        fun parse(value: String): Coordinate {
            val pieces = value.split(':')
            require(pieces.size == 3) { "Expected group:name:version: $value" }
            return Coordinate(pieces[0], pieces[1], pieces[2])
        }
    }
}

enum class DependencyScopeKind { COMPILE, RUNTIME }

sealed interface Dependency {
    val scope: DependencyScopeKind
    val exported: Boolean
    data class External(val coordinate: Coordinate, override val scope: DependencyScopeKind = DependencyScopeKind.COMPILE,
                        override val exported: Boolean = false) : Dependency {
        init { require(scope != DependencyScopeKind.RUNTIME || !exported) { "Runtime-only dependency cannot be exported to compilation" } }
    }
    data class Module(val module: String, override val scope: DependencyScopeKind = DependencyScopeKind.COMPILE,
                      override val exported: Boolean = false) : Dependency {
        init { require(scope != DependencyScopeKind.RUNTIME || !exported) { "Runtime-only dependency cannot be exported to compilation" } }
    }
}

data class SourceSet(
    val name: String,
    val directories: List<LogicalPath>,
    val resources: List<LogicalPath>,
    val dependsOn: List<String>,
    val dependencies: List<Dependency>,
)

/** Omitted classes discover compiled tests; an explicit empty selection still selects nothing. */
data class JvmTestSettings(val classes: List<String>, val engines: List<String> = listOf("junit"), val parallelism: Int = 1, val maxRetries: Int = 0, val discover: Boolean = false, val tags: JvmTestTags = JvmTestTags()) {
    init {
        require(!tags.enabled || engines == listOf("junit")) { "JVM tag filters currently require only the junit engine" }
        require(!discover || classes.isEmpty()) { "JVM test discovery cannot be combined with explicit classes" }
        require(maxRetries in 0..9) { "JVM test maxRetries must be an integer in 0..9" }
        require(parallelism in 1..1024) { "JVM test parallelism must be an integer in 1..1024" }
        require(parallelism == 1 || engines == listOf("junit")) { "Parallel JVM tests currently require only the junit engine" }
        require(classes.distinct().size == classes.size && classes.all {
            it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
        }) { "JVM test classes must be unique qualified binary names" }
        require(engines.isNotEmpty() && engines.distinct().size == engines.size && engines.all { it in setOf("junit", "kotest") }) {
            "JVM test engines must be a nonempty unique selection of junit and/or kotest"
        }
    }
}

/** Built-in Native tests need no generator; Kotest pins its generator/runtime protocol explicitly. */
data class NativeTestSettings(val engine: String, val kspVersion: String? = null, val processor: Coordinate? = null, val maxRetries: Int = 0, val filter: String? = null) {
    init {
        require(filter == null || engine == "kotlin" && filter.matches(Regex("[A-Za-z0-9_.*]+"))) {
            "Native test filters require the kotlin engine and a pattern containing only letters, digits, underscores, dots and *"
        }
        require(maxRetries in 0..9) { "Native test maxRetries must be an integer in 0..9" }
        require(engine == "kotlin" && kspVersion == null && processor == null ||
            engine == "kotest" && kspVersion == "2.3.12" &&
            processor == Coordinate("io.kotest", "kotest-framework-symbol-processor", "6.2.5")) {
            "Native tests require kotlin without generator settings, or Kotest 6.2.5 with KSP 2.3.12"
        }
    }
}

/** Per-action Native compiler budgets; omitted phases retain the adapter defaults. */
data class NativeTimeouts(val compileMillis: Long? = null, val linkMillis: Long? = null) {
    init {
        require(compileMillis != null || linkMillis != null) { "nativeTimeouts requires compileMillis or linkMillis" }
        require(listOfNotNull(compileMillis, linkMillis).all { it in 1L..3_600_000L }) {
            "Native timeouts must be integer milliseconds in 1..3600000"
        }
    }
}

data class TargetSpec(
    val name: String,
    val main: String,
    val test: String?,
    val compilerOptions: List<String>,
    val testSettings: JvmTestSettings? = null,
    val testCompilerOptions: List<String>? = null,
    val nativeTestSettings: NativeTestSettings? = null,
    val nativeTimeouts: NativeTimeouts? = null,
    val application: ApplicationSpec? = null,
    val nativeFramework: NativeFrameworkSpec? = null,
    val abi: AbiSettings? = null,
    val jsTestSettings: JsTestSettings? = null,
    val benchmarkSettings: JvmBenchmarkSettings? = null,
    val testResources: TestResources? = null,
) {
    init {
        require(NativeTarget.find(name)?.isIos != true || test == null) { "iOS test execution requires simulator/device support; only compilation and linking are supported" }
        require(nativeFramework == null || NativeTarget.find(name)?.isIos == true && application == null) {
            "nativeFramework requires an iOS library target without application settings"
        }
        require(testSettings == null || name == "jvm" && test != null) { "testSettings requires a JVM target with a test source set" }
        require((testSettings?.parallelism ?: 1) <= (testResources?.cpuSlots ?: 1)) {
            "JVM test parallelism must not exceed testResources.cpuSlots"
        }
        require(testResources == null || test != null) { "testResources requires a test source set" }
        require(testCompilerOptions == null || test != null) { "testCompilerOptions requires a test source set" }
        require(nativeTestSettings == null || NativeTarget.find(name) != null && test != null) { "nativeTestSettings requires a supported Native target with a test source set" }
        require(nativeTimeouts == null || NativeTarget.find(name) != null) { "nativeTimeouts requires a supported Native target" }
        require(jsTestSettings == null || KotlinJsTarget.find(name) != null && test != null) { "jsTestSettings requires a JS/Wasm target with tests" }
        require(benchmarkSettings == null || name == "jvm") { "benchmarkSettings requires a JVM target" }
        application?.requireTarget(name)
    }
    fun optionsFor(compilation: String): List<String> = when (compilation) {
        "main" -> compilerOptions
        "test" -> testCompilerOptions ?: compilerOptions
        else -> error("Unknown compilation: $compilation")
    }
}
data class CompilerPluginSpec(val module: String?, val coordinate: Coordinate?, val targets: Set<String>, val scopes: Set<String>, val options: List<String>, val inputs: List<LogicalPath>,
                              val id: String? = null, val compilerVersion: String? = null,
                              val pathOptions: List<PluginPathOption> = emptyList(), val outputs: List<LogicalPath> = emptyList()) {
    init {
        require((module == null) != (coordinate == null)) { "Compiler plugin needs exactly one module or coordinate" }
        require(targets.isNotEmpty() && scopes.isNotEmpty() && scopes.all { it in setOf("main", "test", "script") })
        if (module != null || id != null) {
            require(id != null && id.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) &&
                compilerVersion != null && isSupportedJvmCompilerVersion(compilerVersion)) { "Compiler plugin requires an id and a supported compilerVersion" }
            require(targets.all { it == "jvm" } || compilerVersion == "2.4.20") { "Native/JS/Wasm compiler plugins require Kotlin 2.4.20" }
            require(if ("script" in scopes) targets == setOf("jvm") && scopes == setOf("script")
                else targets.all { it == "jvm" || NativeTarget.find(it) != null || KotlinJsTarget.find(it) != null }) {
                "Compiler plugins require JVM/Native/JS/Wasm main/test or a separate JVM script declaration"
            }
            validatePluginOptions(options, inputs, pathOptions, outputs)
        } else require(compilerVersion == null && pathOptions.isEmpty() && outputs.isEmpty()) { "Published plugin extensions require an id and compilerVersion" }
    }

    /** JS/Wasm tests recompile production sources, so their main plugins must also apply. */
    fun appliesTo(target: String, scope: String): Boolean = target in targets &&
        (scope in scopes || scope == "test" && "main" in scopes &&
            (KotlinJsTarget.find(target) != null || id == null && NativeTarget.find(target) != null))
}
/** Base coordinate for a root KMP publication and its target packages. */
data class LibraryPublicationSpec(val coordinate: Coordinate) {
    fun coordinateFor(target: String): Coordinate {
        val suffix = when (target) {
            "jvm" -> "jvm"
            else -> KotlinJsTarget.find(target)?.publicationSuffix ?: NativeTarget.require(target).publicationSuffix
        }
        return coordinate.copy(name = "${coordinate.name}-$suffix")
    }
}

data class ModuleSpec(val id: String, val sourceSets: List<SourceSet>, val targets: List<TargetSpec>, val compilerPlugins: List<CompilerPluginSpec>,
                      val publication: LibraryPublicationSpec? = null, val documentation: DocumentationSettings? = null,
                      val sqlDelight: SqlDelightSpec? = null) {
    init {
        sqlDelight?.let { spec ->
            require(sourceSets.any { it.name == spec.sourceSet }) { "Unknown SQLDelight source set in $id" }
            val consumers = targets.filter { target -> sourceSetClosure(this, listOf(target.main)).any { it.name == spec.sourceSet } }
            val unsupportedTests = targets.filter { it !in consumers && it.test != null }.any { target ->
                sourceSetClosure(this, listOfNotNull(target.test)).any { it.name == spec.sourceSet }
            }
            require(consumers.isNotEmpty() && !unsupportedTests) {
                "SQLDelight requires a main source-set consumer and cannot be attached only to tests in $id"
            }
        }
        publication?.let { spec ->
            require(targets.none { it.application != null }) { "Published library module $id cannot declare application settings" }
            targets.forEach { spec.coordinateFor(it.name) }
        }
    }
}
data class ProjectSpec(val name: String, val repositories: List<Repository>, val modules: List<ModuleSpec>,
                       val dependencyCatalog: Map<String, Coordinate> = emptyMap(),
                       val dependencyProfiles: Map<String, ScriptDependencyProfile> = emptyMap(),
                       val scripts: Map<String, ScriptSpec> = emptyMap(),
                       val fileTags: Map<LogicalPath, Set<String>> = emptyMap()) {
    init {
        validateManualFileTags(fileTags)
        val coordinates = modules.flatMap { module ->
            module.publication?.let { publication ->
                (if (module.targets.size > 1) listOf(publication.coordinate) else emptyList()) + module.targets.map { publication.coordinateFor(it.name) }
            } ?: emptyList()
        }
        require(coordinates.distinct().size == coordinates.size) { "Duplicate root or target library publication coordinate" }
    }
}

data class VariantRequest(val platform: String, val usage: String, val attributes: Map<String, String>) {
    init { require((platform in setOf("jvm", "common") || NativeTarget.find(platform) != null || KotlinJsTarget.find(platform) != null) && usage.isNotBlank()) }
}
data class LockedArtifact(val coordinate: Coordinate, val variant: String, val repository: String, val url: String, val digest: Digest)
data class MetadataIdentity(val url: String, val digest: Digest)
data class LockedSelection(
    val requested: Coordinate,
    val variant: VariantRequest,
    val artifacts: List<LockedArtifact>,
    val metadata: List<MetadataIdentity>,
)

interface ArtifactTransport { fun fetch(url: String): ByteArray }
interface DependencyResolver {
    fun resolve(coordinate: Coordinate, variant: VariantRequest, repositories: List<Repository>): LockedSelection
}

/** Declared dependencies are a graph, including ordinary module and compiler-plugin edges. */
fun validateGraph(dependencies: Map<String, Set<String>>) {
    val complete = mutableSetOf<String>()
    val visiting = mutableListOf<String>()
    fun visit(id: String) {
        require(id in dependencies) { "Unknown dependency: $id" }
        if (id in complete) return
        require(id !in visiting) { "Dependency cycle: ${(visiting.dropWhile { it != id } + id).joinToString(" -> ")}" }
        visiting += id
        dependencies.getValue(id).sorted().forEach(::visit)
        visiting.removeAt(visiting.lastIndex)
        complete += id
    }
    dependencies.keys.sorted().forEach(::visit)
}
