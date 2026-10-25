package kvik.projectgraph

import kvik.core.*
import kvik.compiler.jvm.*
import kvik.compiler.native.*
import kvik.compiler.js.*

/** Authored source directory, with both project and compiler-input paths. Empty roots are retained. */
data class CompilationSourceRoot(val sourceSet: String, val directory: LogicalPath, val inputPath: LogicalPath,
                                 val common: Boolean, val dependsOn: List<String>) {
    fun toJson(): JsonValue.Object = obj("sourceSet" to sourceSet.json(), "directory" to directory.value.json(),
        "inputPath" to inputPath.value.json(), "common" to common.json(), "dependsOn" to dependsOn.map(String::json).json())
}

/** Logical paths refer to [files], whose immutable bytes are available in CAS. Classpath order is significant. */
data class CompilationInputs(
    val files: TreeManifest,
    val sources: List<LogicalPath>,
    val commonSources: List<LogicalPath>,
    val classpath: List<LogicalPath>,
    val plugins: List<LogicalPath> = emptyList(),
    val pluginApplications: List<JvmPluginApplication> = emptyList(),
    val friendPaths: List<LogicalPath> = emptyList(),
    val sourceFragments: List<NativeSourceFragment> = emptyList(),
) {
    fun sourceFiles(): TreeManifest = TreeManifest(files.files.filter { it.path in sources })
    fun classpathFiles(): TreeManifest = TreeManifest(files.files.filter { file ->
        classpath.any { file.path == it || file.path.value.startsWith(it.value + "/") }
    })
}

/** A lazy input view. Consumers depend on [dependencies], never on compilation output. */
class ProjectCompilation(
    val module: String,
    val target: String,
    val compilation: String,
    val actionId: String,
    sourceRoots: List<CompilationSourceRoot>,
    dependencies: Set<String>,
    private val resolveInputs: (Map<String, ActionResult>) -> CompilationInputs,
) {
    val sourceRoots = sourceRoots.map { it.copy(dependsOn = it.dependsOn.toList()) }
    val dependencies = dependencies.toSet()
    init { require(listOf(module, target, compilation, actionId).all { it.isNotBlank() }) }

    /** Resolves the compiler's recipe after its prerequisites; does not execute the compiler or read host files. */
    fun resolve(results: Map<String, ActionResult>): CompilationInputs {
        val selected = dependencies.associateWith { id ->
            requireNotNull(results[id]) { "Missing compilation input producer: $id" }.also {
                require(it.actionId == id && it.successful) { "Unsuccessful or mismatched compilation input producer: $id" }
            }
        }
        return resolveInputs(selected)
    }

    fun toJson(): JsonValue.Object = obj("module" to module.json(), "target" to target.json(),
        "compilation" to compilation.json(), "action" to actionId.json(),
        "sourceRoots" to sourceRoots.map { it.toJson() }.json(),
        "dependencies" to dependencies.sorted().map(String::json).json())
}

fun ProjectActionGraph.requireCompilation(module: String, target: String, compilation: String): ProjectCompilation {
    val matches = compilations.filter { it.module == module && it.target == target && it.compilation == compilation }
    require(matches.size == 1) { "Expected one compilation for $module:$target:$compilation, found ${matches.size}" }
    return matches.single()
}

internal fun validateProjectCompilations(nodes: List<ActionNode>, compilations: List<ProjectCompilation>) {
    require(compilations.map { Triple(it.module, it.target, it.compilation) }.distinct().size == compilations.size) {
        "Duplicate project compilation"
    }
    val actions = nodes.associateBy { it.id }
    for (compilation in compilations) require(actions[compilation.actionId]?.dependencies == compilation.dependencies) {
        "Compilation must reference an action with the same prerequisites: ${compilation.actionId}"
    }
}

internal fun compilationInputs(action: ActionSpec): CompilationInputs = when (action.implementation) {
    "kvik-jvm-compiler-v1" -> JvmCompilation.fromJson(action.parameters).let {
        CompilationInputs(action.inputs, it.sources, it.commonSources, it.classpath, it.plugins, it.pluginApplications, it.friendPaths)
    }
    "kvik-native-compiler-v1" -> NativeCompilation.fromJson(action.parameters).let {
        CompilationInputs(action.inputs, it.sources, it.commonSources, it.libraries, it.plugins, it.pluginApplications,
            sourceFragments = it.sourceFragments)
    }
    "kvik-js-compiler-v1" -> JsCompilation.fromJson(action.parameters).let {
        CompilationInputs(action.inputs, it.sources, it.commonSources, it.libraries, it.plugins, it.pluginApplications)
    }
    "kvik-jvm-metadata-v1" -> JvmMetadataCompilation.fromJson(action.parameters).let {
        CompilationInputs(action.inputs, it.sources, it.sources, it.classpath, it.plugins, it.pluginApplications)
    }
    else -> error("Unsupported compilation input adapter: ${action.implementation}")
}
