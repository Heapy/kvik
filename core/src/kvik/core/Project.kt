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

sealed interface Dependency {
    data class External(val coordinate: Coordinate) : Dependency
    data class Module(val module: String) : Dependency
}

data class SourceSet(
    val name: String,
    val directories: List<LogicalPath>,
    val resources: List<LogicalPath>,
    val dependsOn: List<String>,
    val dependencies: List<Dependency>,
)

/** Exact JVM binary class names; an empty selection deliberately requests no discovery. */
data class JvmTestSettings(val classes: List<String>, val engines: List<String> = listOf("junit")) {
    init {
        require(classes.distinct().size == classes.size && classes.all {
            it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
        }) { "JVM test classes must be unique qualified binary names" }
        require(engines.isNotEmpty() && engines.distinct().size == engines.size && engines.all { it in setOf("junit", "kotest") }) {
            "JVM test engines must be a nonempty unique selection of junit and/or kotest"
        }
    }
}

data class TargetSpec(
    val name: String,
    val main: String,
    val test: String?,
    val compilerOptions: List<String>,
    val testSettings: JvmTestSettings? = null,
) {
    init {
        require(testSettings == null || name == "jvm" && test != null) { "testSettings requires a JVM target with a test source set" }
    }
}
data class CompilerPluginSpec(val module: String?, val coordinate: Coordinate?, val targets: Set<String>, val scopes: Set<String>, val options: List<String>, val inputs: List<LogicalPath>) {
    init {
        require((module == null) != (coordinate == null)) { "Compiler plugin needs exactly one module or coordinate" }
        require(targets.isNotEmpty() && scopes.isNotEmpty() && scopes.all { it in setOf("main", "test", "script") })
    }
}
data class ModuleSpec(val id: String, val sourceSets: List<SourceSet>, val targets: List<TargetSpec>, val compilerPlugins: List<CompilerPluginSpec>)
data class ProjectSpec(val name: String, val repositories: List<Repository>, val modules: List<ModuleSpec>)

data class VariantRequest(val platform: String, val usage: String, val attributes: Map<String, String>) {
    init { require(platform in setOf("jvm", "macosArm64", "common") && usage.isNotBlank()) }
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
