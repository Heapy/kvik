package kvik.jvm

import kvik.core.*
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlin.system.exitProcess

/** Bootstrap validates the entire compiler distribution before loading the scripting implementation. */
object KotlinScriptCompileWorker {
    @JvmStatic fun main(args: Array<String>) = scriptWorker(args, "compile")
}

/** Evaluation loads only the scripting runtime APIs, not the Kotlin compiler. */
object KotlinScriptEvaluationWorker {
    @JvmStatic fun main(args: Array<String>) = scriptWorker(args, "evaluate")
}

private fun scriptWorker(args: Array<String>, operation: String) {
    require(args.size == 5) { "KotlinScriptWorker <workspace> <request> <result> <compiler-artifacts> <manifest>" }
    val workspace = Workspace(Path.of(args[0]))
    var requestId = "invalid-request"
    val result = try {
        val request = workspace.readJson(args[1])
        val fields = request.objectValue()
        requestId = fields.getValue("requestId").stringValue()
        val manifest = parseJson(Files.readString(Path.of(args[4])))
        val pinned = distribution(Path.of(args[3]), manifest, Digest.fromJson(fields.getValue("compiler")))
        val worker = Path.of(KotlinScriptCompileWorker::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()
        val artifacts = if (operation == "compile") pinned.classpath else {
            val names = setOf("kotlin-stdlib-${pinned.version}.jar", "kotlin-script-runtime-${pinned.version}.jar",
                "kotlin-scripting-common-${pinned.version}.jar", "kotlin-scripting-jvm-${pinned.version}.jar")
            pinned.classpath.filter { it.fileName.toString() in names }.also {
                require(it.map { path -> path.fileName.toString() }.toSet() == names) { "Missing pinned scripting runtime" }
            }
        }
        URLClassLoader((listOf(worker) + artifacts).map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val thread = Thread.currentThread()
            val previous = thread.contextClassLoader
            try {
                thread.contextClassLoader = loader
                if (operation == "compile") {
                    val version = loader.loadClass("org.jetbrains.kotlin.config.KotlinCompilerVersion").getField("VERSION").get(null)
                    if (version != pinned.version) reject("KVIK_COMPILER_IDENTITY", "Loaded script compiler version does not match manifest")
                }
                val session = loader.loadClass("kvik.jvm.KotlinScriptWorkerSession")
                val text = session.getMethod("execute", String::class.java, String::class.java, String::class.java,
                    String::class.java, String::class.java).invoke(null, operation, workspace.root.toString(), request.canonical(),
                        args[3], manifest.canonical()) as String
                WorkerResult.fromJson(parseJson(text))
            } finally { thread.contextClassLoader = previous }
        }
    } catch (failure: Exception) {
        val cause = if (failure is InvocationTargetException) failure.targetException else failure
        cause.printStackTrace(System.err)
        WorkerResult(requestId, WorkerStatus.INFRASTRUCTURE_ERROR, 1, listOf(Diagnostic(
            "KVIK_SCRIPT_WORKER", DiagnosticCategory.INFRASTRUCTURE, cause.message ?: "Script worker failed")))
    }
    workspace.publish(args[2], result.toJson())
    exitProcess(result.exitCode)
}

/** Called only inside the validated, isolated implementation loader; JSON is the loader boundary. */
object KotlinScriptWorkerSession {
    @JvmStatic fun execute(operation: String, root: String, requestJson: String, artifactRoot: String, manifestJson: String): String {
        var requestId = "invalid-request"
        val result = try {
            val request = parseJson(requestJson)
            requestId = request.objectValue().getValue("requestId").stringValue()
            executeRequest(operation, root, request, artifactRoot, manifestJson)
        } catch (failure: AdapterFailure) {
            // Serialize in the loader that owns AdapterFailure; its JVM type cannot cross this boundary.
            failure.printStackTrace(System.err)
            WorkerResult(requestId, WorkerStatus.INFRASTRUCTURE_ERROR, 1, listOf(Diagnostic(
                failure.code, DiagnosticCategory.INFRASTRUCTURE, failure.message ?: "Script adapter failed")))
        }
        return result.toJson().canonical()
    }

    private fun executeRequest(operation: String, root: String, request: JsonValue, artifactRoot: String, manifestJson: String): WorkerResult {
        val workspace = Workspace(Path.of(root))
        val fields = request.objectValue()
        val requestId = fields.getValue("requestId").stringValue()
        val pinned = distribution(Path.of(artifactRoot), parseJson(manifestJson), Digest.fromJson(fields.getValue("compiler")))
        fun runtimeArtifact(name: String) = pinned.classpath.single { it.fileName.toString() == "$name-${pinned.version}.jar" }
        val runtime = listOf(runtimeArtifact("kotlin-stdlib"), runtimeArtifact("kotlin-script-runtime"))
        fun strings(name: String) = fields.getValue(name).arrayValue().map(JsonValue::stringValue)
        val classpath = strings("classpath").map { logical ->
            require(logical.startsWith("input/")) { "Script dependencies must be declared inputs" }
            workspace.classpath(logical)
        }
        val result = if (operation == "evaluate") {
            request.fields(setOf("schemaVersion", "requestId", "compiler", "bundle", "classpath", "arguments")).requireVersion()
            val bundle = fields.getValue("bundle").stringValue()
            require(bundle.startsWith("input/")) { "Script bundle must be a declared input" }
            val evaluated = scriptBlocking { KotlinScriptHost.evaluate(workspace.classpath(bundle), classpath + runtime, strings("arguments")) }
            WorkerResult(requestId, if (evaluated.successful) WorkerStatus.SUCCESS else WorkerStatus.COMPILATION_ERROR,
                if (evaluated.successful) 0 else 1, evaluated.diagnostics.map { it.workerDiagnostic(evaluation = true) })
        } else {
            request.fields(setOf("schemaVersion", "requestId", "compiler", "primary", "sources", "classpath", "pluginClasspath",
                "options", "outputDirectory", "pluginApplications", "declaredDependencies", "declaredRepositories")).requireVersion()
            val primary = fields.getValue("primary").stringValue()
            val paths = strings("sources")
            require(paths.size == paths.distinct().size && primary in paths && paths.size in 1..1024) { "Invalid script source selection" }
            val sources = paths.associateWith { logical ->
                require(logical.startsWith("input/") && logical.endsWith(".kts")) { "Only captured .kts sources are supported" }
                val path = workspace.path(logical)
                require(Files.isRegularFile(path) && Files.size(path) <= 4 * 1024 * 1024) { "Missing or oversized script source" }
                Files.readString(path)
            }
            val applications = fields.getValue("pluginApplications").arrayValue().map(JvmPluginApplication::fromJson)
            val pluginPaths = strings("pluginClasspath").map { logical ->
                require(logical.startsWith("input/"))
                workspace.classpath(logical)
            }
            validateJvmPluginApplications(applications)
            require(pluginPaths.isEmpty() || applications.none { it.id == "org.jetbrains.kotlinx.serialization" }) { "Duplicate serialization plugin ID" }
            validateSerializationPlugin(pluginPaths, pinned)
            require(pluginPaths.none { plugin -> classpath.any { Files.isSameFile(plugin, it) } }) { "Compiler plugin leaks into consumer classpath" }
            val destination = fields.getValue("outputDirectory").stringValue()
            require(destination.startsWith("output/")) { "Script output must belong to output/" }
            val target = workspace.path(destination, false)
            require(!Files.exists(target)) { "Script output already exists" }
            Files.createDirectories(target.parent)
            val staging = Files.createTempDirectory(workspace.root, ".kvik-kts-")
            try {
                val outputRoots = prepareOutputRoots(staging, applications.flatMap { it.outputs })
                for (output in applications.flatMap { it.outputs }) require(!Files.exists(workspace.path(output.value, false))) { "Plugin output already exists" }
                val base = KotlinScriptCompileRequest(primary, sources, classpath = classpath + runtime, options = strings("options"),
                    pluginClasspath = pluginPaths, declaredDependencies = strings("declaredDependencies").toSet(),
                    declaredRepositories = strings("declaredRepositories").toSet(), output = staging.resolve("classes"))
                val compiled = if (applications.isEmpty()) scriptBlocking { KotlinScriptHost.compile(base) } else {
                    val loader = KotlinScriptWorkerSession::class.java.classLoader
                    val plugins = validatedWorkspacePlugins(applications, classpath, workspace, outputRoots, loader, pinned.classpath, pinned.version)
                    withTypedWorkspacePlugins(plugins, loader, workspace) { service ->
                        scriptBlocking { KotlinScriptHost.compile(base.copy(pluginClasspath = pluginPaths + listOf(service))) }
                    }
                }
                if (compiled.successful) {
                    val allowed = listOf(base.output) + outputRoots.values
                    Files.walk(staging).use { files ->
                        require(files.noneMatch { path -> Files.isSymbolicLink(path) || !Files.isDirectory(path) &&
                            (!Files.isRegularFile(path) || allowed.none { path.startsWith(it) }) }) { "Script plugin produced undeclared output" }
                    }
                    for ((logical, path) in outputRoots) {
                        Files.walk(path).use { files -> require(files.anyMatch { Files.isRegularFile(it) }) { "Script plugin output is empty" } }
                        val output = workspace.path(logical.value, false)
                        Files.createDirectories(output.parent)
                        Files.move(path, output, ATOMIC_MOVE)
                    }
                    Files.move(base.output, target, ATOMIC_MOVE)
                }
                WorkerResult(requestId, if (compiled.successful) WorkerStatus.SUCCESS else WorkerStatus.COMPILATION_ERROR,
                    if (compiled.successful) 0 else 1, compiled.diagnostics.map { it.workerDiagnostic(evaluation = false) })
            } finally { workspace.removeTree(staging) }
        }
        return result
    }
}

private fun KotlinScriptDiagnostic.workerDiagnostic(evaluation: Boolean): Diagnostic = Diagnostic(
    if (evaluation) "KVIK_SCRIPT_EXECUTION" else if (severity == "warning") "KVIK_COMPILATION_WARNING" else "KVIK_COMPILATION_ERROR",
    if (evaluation) DiagnosticCategory.INFRASTRUCTURE else DiagnosticCategory.COMPILATION, message,
    path = source, line = line?.takeIf { it > 0 }, column = column?.takeIf { it > 0 },
)

@Suppress("DEPRECATION_ERROR")
private fun <T> scriptBlocking(block: suspend () -> T): T = kotlin.script.experimental.impl.internalScriptingRunSuspend(block)
