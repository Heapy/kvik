@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
package kvik.jvm

import kvik.core.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** One owned JVM, one JS/Wasm KLIB compilation, with plugin values passed as typed options. */
object JsPluginWorker {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 5) { "JsPluginWorker <workspace> <request> <result> <compiler-artifacts> <manifest>" }
        val workspace = Workspace(Path.of(args[0]))
        var requestId = "invalid-request"
        val result = try {
            val fields = workspace.readJson(args[1]).fields(setOf("schemaVersion", "requestId", "compiler", "target", "arguments", "libraries", "pluginApplications"))
            fields.requireVersion()
            requestId = fields.getValue("requestId").stringValue()
            val target = KotlinJsTarget.require(fields.getValue("target").stringValue())
            val pinned = distribution(Path.of(args[3]), parseJson(Files.readString(Path.of(args[4]))), Digest.fromJson(fields.getValue("compiler")))
            val applications = fields.getValue("pluginApplications").arrayValue().map(JvmPluginApplication::fromJson)
            require(applications.size == 1) { "Exactly one workspace plugin application is required" }
            val arguments = fields.getValue("arguments").arrayValue().map(JsonValue::stringValue)
            val libraries = fields.getValue("libraries").arrayValue().map(JsonValue::stringValue)
            validateJsPluginArguments(arguments, target, libraries, workspace)
            val bootstrap = listOf(Path.of(JsPluginWorker::class.java.protectionDomain.codeSource.location.toURI()).toRealPath(),
                Path.of(org.jetbrains.kotlin.buildtools.api.KotlinToolchains::class.java.protectionDomain.codeSource.location.toURI()).toRealPath(),
                Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toRealPath())
            require(System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Path.of(it).toRealPath() } == bootstrap) {
                "Unexpected JS/Wasm worker bootstrap classpath"
            }
            URLClassLoader((pinned.classpath + listOf(bootstrap.first())).map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                val version = loader.loadClass("org.jetbrains.kotlin.config.KotlinCompilerVersion").getField("VERSION").get(null)
                require(version == "2.4.20") { "Unsupported JS/Wasm compiler version: $version" }
                val outputs = prepareOutputRoots(workspace.root.resolve("output"), applications.flatMap { it.outputs })
                val plugins = validatedWorkspacePlugins(applications, libraries.map { workspace.path(it) }, workspace, outputs, loader, pinned.classpath)
                compile(requestId, target, arguments, plugins.single(), loader, workspace)
            }
        } catch (failure: Exception) {
            val cause = if (failure is InvocationTargetException) failure.targetException else failure
            cause.printStackTrace(System.err)
            WorkerResult(requestId, WorkerStatus.INFRASTRUCTURE_ERROR, 1, listOf(Diagnostic(
                (cause as? AdapterFailure)?.code ?: "KVIK_JS_WORKER", DiagnosticCategory.INFRASTRUCTURE,
                cause.message ?: "JS/Wasm worker failed")))
        }
        workspace.publish(args[2], result.toJson())
        exitProcess(result.exitCode)
    }

    private fun compile(requestId: String, target: KotlinJsTarget, arguments: List<String>, plugin: PreparedWorkspacePlugin,
        loader: ClassLoader, workspace: Workspace): WorkerResult {
        val compilerType = loader.loadClass("org.jetbrains.kotlin.cli.js." + if (target == KotlinJsTarget.JS) "K2JSCompiler" else "KotlinWasmCompiler")
        val compiler = compilerType.getDeclaredConstructor().newInstance()
        val argumentObject = compilerType.methods.single { it.name == "createArguments" && !it.isBridge }.invoke(compiler)
        compilerType.methods.single { it.name == "parseArguments" && it.parameterCount == 2 }.invoke(compiler, arguments.toTypedArray(), argumentObject)
        val collectorType = loader.loadClass("org.jetbrains.kotlin.cli.common.messages.MessageCollector")
        val diagnostics = mutableListOf<Diagnostic>()
        var hasErrors = false
        val collector = Proxy.newProxyInstance(loader, arrayOf(collectorType)) { proxy, method, values -> when (method.name) {
            "clear" -> { diagnostics.clear(); hasErrors = false; null }
            "hasErrors" -> hasErrors
            "report" -> {
                val severity = values!![0].toString()
                val message = values[1].toString()
                val location = values[2]
                if (severity in setOf("ERROR", "EXCEPTION", "WARNING", "STRONG_WARNING")) {
                    val error = severity in setOf("ERROR", "EXCEPTION")
                    hasErrors = hasErrors || error
                    fun locationValue(name: String) = location?.javaClass?.getMethod(name)?.invoke(location)
                    val path = (locationValue("getPath") as? String)?.let { Path.of(it).toAbsolutePath().normalize() }
                    diagnostics += Diagnostic(if (error) "KVIK_JS_ERROR" else "KVIK_JS_WARNING", DiagnosticCategory.COMPILATION, message,
                        path = path?.let { if (it.startsWith(workspace.root)) workspace.root.relativize(it).toString().removePrefix("input/") else it.toString() },
                        line = (locationValue("getLine") as? Int)?.takeIf { it > 0 }, column = (locationValue("getColumn") as? Int)?.takeIf { it > 0 })
                }
                System.err.println("${severity.lowercase()}: $message")
                null
            }
            "toString" -> "KvikJsMessageCollector"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === values?.get(0)
            else -> error("Unsupported MessageCollector method: ${method.name}")
        } }
        val result = withTypedWorkspacePlugin(plugin, loader, workspace) { serviceDirectory ->
            argumentObject.javaClass.getMethod("setPluginClasspaths", Array<String>::class.java).invoke(argumentObject, arrayOf(serviceDirectory.toString()) as Any)
            argumentObject.javaClass.getMethod("setPluginOptions", Array<String>::class.java).invoke(argumentObject, emptyArray<String>() as Any)
            val services = loader.loadClass("org.jetbrains.kotlin.config.Services").getField("EMPTY").get(null)
            compilerType.methods.single { it.name == "exec" && it.parameterCount == 3 && it.parameterTypes[0] == collectorType }
                .invoke(compiler, collector, services, argumentObject)
        }
        val code = result.javaClass.getMethod("getCode").invoke(result) as Int
        if (code == 0 && hasErrors) reject("KVIK_JS_RESULT", "JS/Wasm compiler returned success with errors")
        return WorkerResult(requestId, if (code == 0) WorkerStatus.SUCCESS else WorkerStatus.COMPILATION_ERROR, code, diagnostics)
    }
}

/** Only source compilation options are accepted; plugin paths and values never enter this parser. */
internal fun validateJsPluginArguments(arguments: List<String>, target: KotlinJsTarget, libraries: List<String>, workspace: Workspace) {
    fun input(path: String, suffix: String) {
        require(path.startsWith("input/") && path.endsWith(suffix) && ':' !in path && ',' !in path) { "Invalid JS/Wasm input path" }
        require(Files.isRegularFile(workspace.path(path))) { "JS/Wasm input must be a regular file" }
    }
    require(libraries.distinct().size == libraries.size) { "Duplicate JS/Wasm libraries" }
    libraries.forEach { input(it, ".klib") }
    val seen = mutableSetOf<String>()
    val sources = mutableListOf<String>()
    val common = mutableListOf<String>()
    val optIns = mutableSetOf<String>()
    var declaredLibraries = emptyList<String>()
    var index = 0
    while (index < arguments.size) {
        val argument = arguments[index++]
        val name = argument.substringBefore('=')
        if (argument.startsWith('-') && name != "-opt-in") require(seen.add(name)) { "Repeated JS/Wasm argument: $name" }
        fun value() = arguments.getOrNull(index++) ?: reject("KVIK_JS_ARGUMENT", "Missing argument for $argument")
        when (argument) {
            "-ir-output-dir" -> {
                val path = value()
                require(path == "output/." || path.startsWith("output/")) { "JS/Wasm output must belong to output/" }
                workspace.path(if (path == "output/.") "output" else path, false)
            }
            "-ir-output-name" -> require(value().matches(Regex("[A-Za-z0-9_.-]+")))
            "-language-version", "-api-version" -> require(value() == "2.4")
            "-libraries" -> { declaredLibraries = value().split(':'); require(declaredLibraries == libraries) { "JS/Wasm libraries differ from declared inputs" } }
            "-Xwasm-target=wasm-js" -> require(target == KotlinJsTarget.WASM_JS)
            "-Xmulti-platform", "-Xexplicit-api=strict", "-Werror" -> Unit
            else -> when {
                argument.startsWith("-Xir-module-name=") -> require(argument.substringAfter('=').matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.:-]*")))
                argument.startsWith("-opt-in=") -> {
                    val annotation = argument.substringAfter('=')
                    require(annotation.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) && optIns.add(annotation))
                }
                argument.startsWith("-Xcommon-sources=") -> common += argument.substringAfter('=').split(',')
                argument.startsWith("input/") && argument.endsWith(".kt") -> { input(argument, ".kt"); sources += argument }
                else -> reject("KVIK_JS_ARGUMENT", "Unsupported JS/Wasm worker argument: $argument")
            }
        }
    }
    require(sources.isNotEmpty() && sources.distinct().size == sources.size) { "JS/Wasm worker requires unique source inputs" }
    require(common.distinct().size == common.size && common.all { it in sources }) { "Common sources must be unique declared sources" }
    require(common.isEmpty() || "-Xmulti-platform" in seen) { "Common sources require multiplatform compilation" }
    require(declaredLibraries == libraries) { "Missing JS/Wasm libraries argument" }
    require(setOf("-ir-output-dir", "-ir-output-name", "-Xir-module-name", "-language-version", "-api-version").all { it in seen }) { "Missing JS/Wasm compilation argument" }
    require(("-Xwasm-target" in seen) == (target == KotlinJsTarget.WASM_JS)) { "JS/Wasm target does not match the compiler arguments" }
}
