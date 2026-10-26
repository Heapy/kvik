@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class, org.jetbrains.kotlin.buildtools.api.arguments.ExperimentalCompilerArgument::class)

package kvik.jvm

import kvik.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.Collections
import kotlin.system.exitProcess
import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.arguments.*
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain

private const val SERIALIZATION_ID = "org.jetbrains.kotlinx.serialization"

internal data class Distribution(val version: String, val classpath: List<Path>, val serialization: Digest?)

internal fun distribution(root: Path, manifest: JsonValue, expected: Digest): Distribution {
    if (Sha256.digest(manifest) != expected) reject("KVIK_COMPILER_IDENTITY", "Compiler distribution identity does not match the request")
    val fields = manifest.fields(setOf("schemaVersion", "version", "artifacts", "serializationPlugin"))
    fields.requireVersion()
    val version = fields.getValue("version").stringValue()
    if (!isSupportedJvmCompilerVersion(version)) reject("KVIK_UNSUPPORTED_COMPILER", "Only Kotlin 2.4.10, 2.4.20 and 2.4.21 are supported")
    val workspace = Workspace(root)
    val paths = fields.getValue("artifacts").arrayValue().map { item ->
        val entry = item.fields(setOf("path", "digest"))
        val path = workspace.path(entry.getValue("path").stringValue())
        if (!Files.isRegularFile(path) || fileDigest(path) != Digest.fromJson(entry.getValue("digest")))
            reject("KVIK_COMPILER_IDENTITY", "Compiler artifact digest mismatch: ${path.fileName}")
        path
    }
    if (paths.isEmpty() || paths.distinct().size != paths.size) reject("KVIK_COMPILER_IDENTITY", "Compiler classpath must be nonempty and unique")
    val plugin = fields.getValue("serializationPlugin").let { if (it == JsonValue.Null) null else Digest.fromJson(it) }
    return Distribution(version, paths, plugin)
}

/** Deliberately small option surface. Never forward arbitrary output/classpath/daemon/plugin flags. */
private fun safeOptions(request: WorkerRequest, workspace: Workspace): List<String> {
    val metadata = request.operation == "metadata-compile"
    val result = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var index = 0
    while (index < request.options.size) {
        val raw = request.options[index++]
        val name = raw.substringBefore('=')
        if (metadata && name !in setOf("-module-name", "-language-version", "-api-version", "-opt-in", "-Xexplicit-api", "-Xmulti-platform", "-Werror"))
            reject("KVIK_UNSUPPORTED_OPTION", "Unsupported metadata compiler option: $name")
        fun value(): String = if ('=' in raw) raw.substringAfter('=') else request.options.getOrNull(index++)
            ?: reject("KVIK_UNSUPPORTED_OPTION", "Missing value for $name")
        if (name != "-opt-in" && !seen.add(name)) reject("KVIK_UNSUPPORTED_OPTION", "Repeated compiler option: $name")
        when (name) {
            "-jvm-target" -> {
                val target = value()
                if (target !in setOf("11", "17", "21", "25")) reject("KVIK_UNSUPPORTED_OPTION", "Unsupported JVM target: $target")
                result += listOf(name, target)
            }
            "-Xjdk-release" -> {
                val release = value()
                if (release !in setOf("11", "17", "21", "25")) reject("KVIK_UNSUPPORTED_OPTION", "Unsupported JDK release: $release")
                result += "-Xjdk-release=$release"
            }
            "-module-name" -> {
                val module = value()
                if (!module.matches(Regex(if (metadata) "[A-Za-z0-9_.:-]+" else "[A-Za-z0-9_.-]+"))) reject("KVIK_UNSUPPORTED_OPTION", "Invalid module name")
                result += listOf(name, module)
            }
            "-language-version", "-api-version" -> {
                if (value() != "2.4") reject("KVIK_UNSUPPORTED_OPTION", "Only Kotlin language/API version 2.4 is supported")
                result += listOf(name, "2.4")
            }
            "-opt-in" -> {
                val annotation = value()
                if (!annotation.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*"))) reject("KVIK_UNSUPPORTED_OPTION", "Invalid opt-in annotation")
                result += "-opt-in=$annotation"
            }
            "-Xexplicit-api" -> {
                if (value() != "strict") reject("KVIK_UNSUPPORTED_OPTION", "Only explicit-api=strict is supported")
                result += "-Xexplicit-api=strict"
            }
            "-Xmulti-platform", "-Xexpect-actual-classes", "-Werror" -> {
                if ('=' in raw) reject("KVIK_UNSUPPORTED_OPTION", "Unexpected flag value")
                result += name
            }
            "-Xcommon-sources", "-Xfriend-paths" -> {
                val paths = value().split(',')
                val declared = if (name == "-Xcommon-sources") request.sources else request.classpath
                if (paths.any { it !in declared } || paths.distinct().size != paths.size)
                    reject("KVIK_UNSUPPORTED_OPTION", "$name must refer to unique declared inputs")
                result += "$name=${paths.joinToString(",") { workspace.path(it).toString() }}"
            }
            else -> reject("KVIK_UNSUPPORTED_OPTION", "Unsupported compiler option: $name")
        }
    }
    try { if (!metadata) jvmTargetSettings(result) }
    catch (failure: Exception) { reject("KVIK_UNSUPPORTED_OPTION", failure.message ?: "Invalid JVM target/release settings") }
    return result
}

internal fun compile(workspace: Workspace, request: WorkerRequest, distribution: Distribution,
    reusableToolchains: KotlinToolchains? = null): WorkerResult {
    require(reusableToolchains == null || request.operation == "jvm-compile" && request.pluginClasspath.isEmpty() && request.pluginApplications.isEmpty())
    val metadata = request.operation == "metadata-compile"
    if ((metadata || request.operation == "metadata-prepare") && distribution.version != "2.4.20")
        reject("KVIK_UNSUPPORTED_COMPILER", "Metadata compilation requires Kotlin 2.4.20")
    if (request.operation == "metadata-prepare") return prepareMetadata(workspace, request)
    val options = safeOptions(request, workspace)
    val sources = request.sources.map { logical ->
        val path = workspace.path(logical)
        if (!logical.endsWith(".kt") || !Files.isRegularFile(path)) reject("KVIK_UNSUPPORTED_SOURCE", "Only declared Kotlin .kt files are supported")
        path
    }
    if (sources.distinct().size != sources.size) reject("KVIK_INVALID_REQUEST", "Duplicate source input")
    if (request.classpath.distinct().size != request.classpath.size) reject("KVIK_INVALID_REQUEST", "Duplicate classpath input")
    val plugins = request.pluginClasspath.map(workspace::path)
    validateSerializationPlugin(plugins, distribution)
    if (request.pluginClasspath.any { it in request.classpath }) reject("KVIK_UNSUPPORTED_PLUGIN", "Compiler plugins must be separate from the consumer classpath")
    val destination = workspace.path(request.outputDirectory, false)
    if (Files.exists(destination)) reject("KVIK_OUTPUT_EXISTS", "Output directory must be new")
    val staging = Files.createTempDirectory(destination.parent, ".kvik-compile-")
    val bundle = if (request.pluginApplications.isEmpty()) null else Files.createTempDirectory(workspace.root, ".kvik-plugin-outputs-")
    val metadataScratch = if (metadata) Files.createTempDirectory(workspace.root, ".kvik-metadata-inputs-") else null
    val diagnostics = Collections.synchronizedList(mutableListOf<Diagnostic>())
    try {
        val classpath = if (metadata) metadataClasspath(workspace, request.classpath, metadataScratch!!, request.metadataTargets,
            // Only this graph-owned captured directory may supply Native builtins. Root metadata
            // archives and arbitrary platform KLIBs retain strict common-manifest validation.
            nativeStdlibPath = request.classpath.singleOrNull { it == "input/native-stdlib/shared" })
            else request.classpath.map(workspace::classpath)
        val outputRoots = if (bundle == null) emptyMap() else prepareOutputRoots(bundle,
            listOf(LogicalPath(request.outputDirectory)) + request.pluginApplications.flatMap { it.outputs })
        for (output in request.pluginApplications.flatMap { it.outputs }) if (Files.exists(workspace.path(output.value, false)))
            reject("KVIK_OUTPUT_EXISTS", "Plugin output already exists: ${output.value}")
        val typedWorkspacePlugin = request.pluginApplications.isNotEmpty()
        // The compiler parent must share the bound registrar class with plugin discovery. The
        // worker location is already part of the action's verified worker identity.
        val implementation = distribution.classpath + if (typedWorkspacePlugin)
            listOf(Path.of(PreparedWorkspacePlugin::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()) else emptyList()
        val toolchains = reusableToolchains ?: KotlinToolchains.loadImplementation(implementation)
        if (toolchains.getCompilerVersion() != distribution.version) reject("KVIK_COMPILER_IDENTITY", "Loaded compiler version does not match manifest")
        val builder = if (metadata) {
            metadataCompilationBuilder(toolchains, sources, staging, options, classpath, request.metadataTargets)
        } else {
            val jvm = toolchains.getToolchain(JvmPlatformToolchain::class.java)
            val operation = jvm.jvmCompilationOperationBuilder(sources, staging)
            operation.compilerArguments.applyArgumentStrings(options)
            operation.compilerArguments[JvmCompilerArguments.NO_REFLECT] = true
            operation.compilerArguments[JvmCompilerArguments.NO_STDLIB] = true
            operation.compilerArguments[JvmCompilerArguments.CLASSPATH] = classpath
            CompilerOperationBuilder(operation, operation.compilerArguments) { operation.build() }
        }
        val publishedPlugins = if (plugins.isEmpty()) emptyList() else
            listOf(CompilerPlugin(SERIALIZATION_ID, plugins, emptyList(), emptySet()))
        if (publishedPlugins.isNotEmpty()) builder.compilerArguments[CommonCompilerArguments.COMPILER_PLUGINS] = publishedPlugins
        builder.configuration[BaseCompilationOperation.COMPILER_MESSAGE_RENDERER] = object : CompilerMessageRenderer {
            override fun render(severity: CompilerMessageRenderer.Severity, message: String, location: CompilerMessageRenderer.SourceLocation?): String {
                if (severity == CompilerMessageRenderer.Severity.ERROR || severity == CompilerMessageRenderer.Severity.WARNING) {
                    val path = location?.path?.let { Path.of(it).toAbsolutePath().normalize() }
                    diagnostics += Diagnostic(if (severity == CompilerMessageRenderer.Severity.ERROR) "KVIK_COMPILATION_ERROR" else "KVIK_COMPILATION_WARNING",
                        DiagnosticCategory.COMPILATION, message,
                        path = path?.let { if (it.startsWith(workspace.root)) workspace.root.relativize(it).toString() else it.toString() },
                        line = location?.line?.takeIf { it > 0 }, column = location?.column?.takeIf { it > 0 })
                }
                return "$severity: $message"
            }
        }
        val logger = object : KotlinLogger {
            override val isDebugEnabled = false
            override fun error(msg: String, throwable: Throwable?) { System.err.println(msg); throwable?.printStackTrace(System.err) }
            override fun warn(msg: String, throwable: Throwable?) { System.err.println(msg) }
            override fun info(msg: String) { System.err.println(msg) }
            override fun debug(msg: String) = Unit
            override fun lifecycle(msg: String) { System.err.println(msg) }
        }
        fun execute() = toolchains.createBuildSession().use { session ->
            session.executeOperation(builder.build(), toolchains.createInProcessExecutionPolicy(), logger)
        }
        val result = if (typedWorkspacePlugin) {
            val plugins = validatedWorkspacePlugins(request.pluginApplications,
                // Keep original archive paths in alias checks after metadata extraction.
                classpath + request.classpath.map(workspace::path), workspace, outputRoots,
                toolchains.javaClass.classLoader, distribution.classpath, distribution.version)
            withTypedWorkspacePlugins(plugins, toolchains.javaClass.classLoader, workspace) { service ->
                builder.compilerArguments[CommonCompilerArguments.COMPILER_PLUGINS] =
                    publishedPlugins + CompilerPlugin(workspacePluginBridgeId(plugins), listOf(service), emptyList(), emptySet())
                execute()
            }
        } else execute()
        if (result == CompilationResult.COMPILATION_SUCCESS) {
            if (bundle != null) Files.walk(bundle).use { paths ->
                val declared = request.pluginApplications.flatMap { it.outputs }.map(outputRoots::getValue)
                if (paths.anyMatch { path -> Files.isSymbolicLink(path) || !Files.isDirectory(path) &&
                    (!Files.isRegularFile(path) || declared.none { path.startsWith(it) }) })
                    reject("KVIK_PLUGIN_OUTPUT", "Plugin wrote an undeclared output, symlink or special file")
            }
            for (output in request.pluginApplications.flatMap { it.outputs }) {
                val target = workspace.path(output.value, false)
                Files.walk(outputRoots.getValue(output)).use { paths ->
                    if (paths.noneMatch { Files.isRegularFile(it) }) reject("KVIK_PLUGIN_OUTPUT", "Plugin produced no files under ${output.value}")
                }
                if (Files.exists(target)) reject("KVIK_OUTPUT_EXISTS", "Plugin output already exists: ${output.value}")
                Files.createDirectories(target.parent)
                Files.move(outputRoots.getValue(output), target, ATOMIC_MOVE)
            }
            Files.move(staging, destination, ATOMIC_MOVE)
            return WorkerResult(request.requestId, WorkerStatus.SUCCESS, 0, diagnostics.toList())
        }
        val status = if (result == CompilationResult.COMPILATION_ERROR) WorkerStatus.COMPILATION_ERROR else WorkerStatus.INFRASTRUCTURE_ERROR
        if (diagnostics.isEmpty()) diagnostics += Diagnostic("KVIK_COMPILER_FAILURE", DiagnosticCategory.INFRASTRUCTURE, "BTA returned $result")
        return WorkerResult(request.requestId, status, 1, diagnostics.toList())
    } finally { workspace.removeTree(staging); bundle?.let(workspace::removeTree); metadataScratch?.let(workspace::removeTree) }
}

/** One process, one request, one in-process BTA session. The native process host owns cancellation. */
fun main(args: Array<String>) {
    require(args.size == 5) { "CompilerWorker <workspace> <request-relative> <result-relative> <compiler-artifacts> <manifest>" }
    val workspace = Workspace(Path.of(args[0]))
    var requestId = "invalid-request"
    val result = try {
        val request = WorkerRequest.fromJson(workspace.readJson(args[1]))
        requestId = request.requestId
        val manifest = parseJson(Files.readString(Path.of(args[4])))
        compile(workspace, request, distribution(Path.of(args[3]), manifest, request.compiler))
    } catch (failure: Exception) {
        failure.printStackTrace(System.err)
        WorkerResult(requestId, WorkerStatus.INFRASTRUCTURE_ERROR, 1, listOf(Diagnostic(
            (failure as? AdapterFailure)?.code ?: "KVIK_WORKER_INFRASTRUCTURE", DiagnosticCategory.INFRASTRUCTURE,
            failure.message ?: failure::class.simpleName ?: "Worker failed")))
    }
    workspace.publish(args[2], result.toJson())
    exitProcess(result.exitCode)
}
