@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

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

private data class Distribution(val classpath: List<Path>, val serialization: Digest?)

private fun distribution(root: Path, manifest: JsonValue, expected: Digest): Distribution {
    if (Sha256.digest(manifest) != expected) reject("KVIK_COMPILER_IDENTITY", "Compiler distribution identity does not match the request")
    val fields = manifest.fields(setOf("schemaVersion", "version", "artifacts", "serializationPlugin"))
    fields.requireVersion()
    if (fields.getValue("version").stringValue() != "2.4.20") reject("KVIK_UNSUPPORTED_COMPILER", "Only Kotlin 2.4.20 is supported")
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
    return Distribution(paths, plugin)
}

/** Deliberately small option surface. Never forward arbitrary output/classpath/daemon/plugin flags. */
private fun safeOptions(request: WorkerRequest, workspace: Workspace): List<String> {
    val result = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var index = 0
    while (index < request.options.size) {
        val raw = request.options[index++]
        val name = raw.substringBefore('=')
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
                if (!module.matches(Regex("[A-Za-z0-9_.-]+"))) reject("KVIK_UNSUPPORTED_OPTION", "Invalid module name")
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
    try { jvmTargetSettings(result) }
    catch (failure: Exception) { reject("KVIK_UNSUPPORTED_OPTION", failure.message ?: "Invalid JVM target/release settings") }
    return result
}

private fun compile(workspace: Workspace, request: WorkerRequest, distribution: Distribution): WorkerResult {
    val options = safeOptions(request, workspace)
    val sources = request.sources.map { logical ->
        val path = workspace.path(logical)
        if (!logical.endsWith(".kt") || !Files.isRegularFile(path)) reject("KVIK_UNSUPPORTED_SOURCE", "Only declared Kotlin .kt files are supported")
        path
    }
    if (sources.distinct().size != sources.size) reject("KVIK_INVALID_REQUEST", "Duplicate source input")
    val classpath = request.classpath.map(workspace::classpath)
    if (classpath.distinct().size != classpath.size) reject("KVIK_INVALID_REQUEST", "Duplicate classpath input")
    val plugins = request.pluginClasspath.map(workspace::path)
    if (plugins.isNotEmpty() && (plugins.size != 1 || distribution.serialization == null || fileDigest(plugins.single()) != distribution.serialization))
        reject("KVIK_UNSUPPORTED_PLUGIN", "Only the manifest-pinned Kotlin serialization plugin is supported")
    if (request.pluginClasspath.any { it in request.classpath }) reject("KVIK_UNSUPPORTED_PLUGIN", "Compiler plugins must be separate from the consumer classpath")
    val destination = workspace.path(request.outputDirectory, false)
    if (Files.exists(destination)) reject("KVIK_OUTPUT_EXISTS", "Output directory must be new")
    val staging = Files.createTempDirectory(destination.parent, ".kvik-compile-")
    val diagnostics = Collections.synchronizedList(mutableListOf<Diagnostic>())
    try {
        val toolchains = KotlinToolchains.loadImplementation(distribution.classpath)
        if (toolchains.getCompilerVersion() != "2.4.20") reject("KVIK_COMPILER_IDENTITY", "Loaded compiler version does not match manifest")
        val jvm = toolchains.getToolchain(JvmPlatformToolchain::class.java)
        val builder = jvm.jvmCompilationOperationBuilder(sources, staging)
        builder.compilerArguments.applyArgumentStrings(options)
        builder.compilerArguments[JvmCompilerArguments.NO_REFLECT] = true
        builder.compilerArguments[JvmCompilerArguments.NO_STDLIB] = true
        builder.compilerArguments[JvmCompilerArguments.CLASSPATH] = classpath
        if (plugins.isNotEmpty()) builder.compilerArguments[CommonCompilerArguments.COMPILER_PLUGINS] =
            listOf(CompilerPlugin(SERIALIZATION_ID, plugins, emptyList(), emptySet()))
        builder[BaseCompilationOperation.COMPILER_MESSAGE_RENDERER] = object : CompilerMessageRenderer {
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
        val result = toolchains.createBuildSession().use { session ->
            session.executeOperation(builder.build(), toolchains.createInProcessExecutionPolicy(), logger)
        }
        if (result == CompilationResult.COMPILATION_SUCCESS) {
            Files.move(staging, destination, ATOMIC_MOVE)
            return WorkerResult(request.requestId, WorkerStatus.SUCCESS, 0, diagnostics.toList())
        }
        val status = if (result == CompilationResult.COMPILATION_ERROR) WorkerStatus.COMPILATION_ERROR else WorkerStatus.INFRASTRUCTURE_ERROR
        if (diagnostics.isEmpty()) diagnostics += Diagnostic("KVIK_COMPILER_FAILURE", DiagnosticCategory.INFRASTRUCTURE, "BTA returned $result")
        return WorkerResult(request.requestId, status, 1, diagnostics.toList())
    } finally { workspace.removeTree(staging) }
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
