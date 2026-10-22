@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
package kvik.jvm

import kvik.core.*
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin
import org.jetbrains.kotlin.buildtools.api.arguments.CompilerPluginOption

/** Check on the real action filesystem, including case and Unicode aliases, before plugin code loads. */
internal fun prepareOutputRoots(staging: Path, logical: List<LogicalPath>): Map<LogicalPath, Path> {
    val roots = logical.associateWith { staging.resolve(it.value.removePrefix("output/")) }
    for ((name, path) in roots) {
        if (!name.value.startsWith("output/")) reject("KVIK_PLUGIN_OUTPUT", "Outputs must belong to output/: ${name.value}")
        Files.createDirectories(path)
    }
    for ((a, first) in roots) for ((b, second) in roots) if (a != b) {
        var ancestor: Path? = second
        while (ancestor != null && ancestor != staging) {
            if (Files.isSameFile(first, ancestor)) reject("KVIK_PLUGIN_OUTPUT", "Physical output roots overlap: ${a.value}, ${b.value}")
            ancestor = ancestor.parent
        }
    }
    return roots
}

internal data class PreparedWorkspacePlugin(val id: String, val classpath: List<Path>, val options: List<String>)

internal fun workspacePlugins(request: WorkerRequest, workspace: Workspace, outputs: Map<LogicalPath, Path>, compilerLoader: ClassLoader,
                              compilerArtifacts: List<Path>): List<CompilerPlugin> =
    validatedWorkspacePlugins(request.pluginApplications, request.classpath.map(workspace::classpath), workspace, outputs,
        compilerLoader, compilerArtifacts).map { plugin -> CompilerPlugin(plugin.id, plugin.classpath,
            plugin.options.map { CompilerPluginOption(it.substringBefore('='), it.substringAfter('=')) }, emptySet()) }

/** Native hosts authorize the pinned JVM API dependency while loading their own verified parent API. */
internal fun validatedWorkspacePlugins(applications: List<JvmPluginApplication>, consumerClasspath: List<Path>, workspace: Workspace,
    outputs: Map<LogicalPath, Path>, compilerLoader: ClassLoader, compilerArtifacts: List<Path>,
    nativeParentApi: Path? = null): List<PreparedWorkspacePlugin> = applications.map { application ->
    if (application.classpath.any { !it.value.startsWith("input/") } || application.inputs.any { !it.value.startsWith("input/") })
        reject("KVIK_UNSUPPORTED_PLUGIN", "Plugin classpath/inputs must be separate declared input paths")
    if (application.outputs.any { !it.value.startsWith("output/plugins/${application.id}/") })
        reject("KVIK_PLUGIN_OUTPUT", "Plugin outputs must belong to output/plugins/${application.id}/")
    val classpath = application.classpath.map { workspace.classpath(it.value) }
    if (classpath.indices.any { i -> classpath.drop(i + 1).any { Files.isSameFile(classpath[i], it) } } ||
        classpath.any { plugin -> consumerClasspath.any { Files.isSameFile(plugin, it) } })
        reject("KVIK_UNSUPPORTED_PLUGIN", "Plugin classpath aliases itself or the consumer classpath")
    for (input in application.inputs) if (!Files.isRegularFile(workspace.path(input.value)))
        reject("KVIK_MISSING_INPUT", "Plugin input must be a regular file: ${input.value}")
    val registrar = "org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
    val processor = "org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor"
    fun resource(path: Path, name: String): ByteArray? = if (Files.isDirectory(path)) {
        val file = path.resolve(name)
        if (Files.exists(file)) {
            if (!Files.isRegularFile(file) || Files.size(file) > 65536) reject("KVIK_PLUGIN_SERVICE", "Invalid plugin service resource")
            Files.readAllBytes(file)
        } else null
    } else ZipFile(path.toFile()).use { zip -> zip.getEntry(name)?.let { entry ->
        if (entry.size !in 0..65536) reject("KVIK_PLUGIN_SERVICE", "Invalid plugin service resource size")
        zip.getInputStream(entry).use { input -> input.readNBytes(65537).also { if (it.size > 65536) reject("KVIK_PLUGIN_SERVICE", "Plugin service resource too large") } }
    } }
    fun services(name: String) = classpath.flatMap { path -> resource(path, "META-INF/services/$name")?.decodeToString(throwOnInvalidSequence = true)
        ?.lineSequence()?.map { it.substringBefore('#').trim() }?.filter { it.isNotEmpty() }?.toList() ?: emptyList() }
    val registrars = services(registrar)
    val processors = services(processor)
    if (registrars.size != 1 || processors.size > 1 || services("org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar").isNotEmpty())
        reject("KVIK_PLUGIN_SERVICE", "Exactly one K2 registrar and at most one command-line processor are supported")
    if ((registrars + processors).any { !it.matches(Regex("[A-Za-z_$][A-Za-z0-9_.$]*")) }) reject("KVIK_PLUGIN_SERVICE", "Invalid plugin provider class name")
    // A compiler API jar may be an explicit dependency, but cannot replace the pinned host API.
    val apiClass = registrar.replace('.', '/') + ".class"
    val hostApi = compilerArtifacts.singleOrNull { resource(it, apiClass) != null }
        ?: reject("KVIK_COMPILER_IDENTITY", "Pinned compiler API artifact is missing or ambiguous")
    for (path in classpath) if (resource(path, apiClass) != null && (!Files.isRegularFile(path) || fileDigest(path) != fileDigest(hostApi)))
        reject("KVIK_PLUGIN_INCOMPATIBLE", "Plugin classpath contains a different compiler API")
    if (nativeParentApi != null) for (name in listOf(registrar, processor, "org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension")) {
        val origin = compilerLoader.loadClass(name).protectionDomain?.codeSource?.location?.toURI()?.let(Path::of)?.toRealPath()
        if (origin != nativeParentApi.toRealPath()) reject("KVIK_COMPILER_IDENTITY", "Native parent API origin mismatch: $name")
    }
    // Validation runs inside the owned worker, never inside the Native orchestrator. BTA subsequently
    // creates its own loader; plugin constructors must tolerate normal repeated service instantiation.
    try {
        URLClassLoader(classpath.map { it.toUri().toURL() }.toTypedArray(), compilerLoader).use { loader ->
            for ((service, names) in listOf(registrar to registrars, processor to processors)) for (name in names) {
                val type = compilerLoader.loadClass(service)
                val implementation = loader.loadClass(name)
                if (!type.isAssignableFrom(implementation)) reject("KVIK_PLUGIN_INCOMPATIBLE", "Invalid $service provider: $name")
                val instance = implementation.getDeclaredConstructor().newInstance()
                if (type.getMethod("getPluginId").invoke(instance) != application.id)
                    reject("KVIK_PLUGIN_ID", "Plugin service ID does not match ${application.id}: $name")
                if (service == registrar && type.getMethod("getSupportsK2").invoke(instance) != true)
                    reject("KVIK_PLUGIN_INCOMPATIBLE", "Plugin must support K2")
            }
        }
    } catch (failure: LinkageError) {
        reject("KVIK_PLUGIN_INCOMPATIBLE", failure.message ?: "Plugin/compiler API linkage failed")
    }
    if ((application.options.isNotEmpty() || application.pathOptions.isNotEmpty()) && processors.isEmpty())
        reject("KVIK_PLUGIN_SERVICE", "Plugin options require a command-line processor")
    val options = application.options + application.pathOptions.map {
        it.name + "=" + (if (it.kind == PluginPathKind.INPUT_FILE) workspace.path(it.path.value) else outputs.getValue(it.path)).toString()
    }
    PreparedWorkspacePlugin(application.id, classpath, options)
}
