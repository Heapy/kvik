@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
package kvik.jvm

import kvik.core.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** One owned JVM, one Native compilation. Typed plugin arrays bypass the CLI comma splitter. */
object NativePluginWorker {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 6) { "NativePluginWorker <workspace> <request> <result> <compiler-artifacts> <manifest> <native-home>" }
        val workspace = Workspace(Path.of(args[0]))
        var requestId = "invalid-request"
        val result = try {
            val fields = workspace.readJson(args[1]).fields(setOf("schemaVersion", "requestId", "compiler", "arguments", "libraries", "pluginApplications"))
            fields.requireVersion()
            requestId = fields.getValue("requestId").stringValue()
            val pinned = distribution(Path.of(args[3]), parseJson(Files.readString(Path.of(args[4]))), Digest.fromJson(fields.getValue("compiler")))
            val applications = fields.getValue("pluginApplications").arrayValue().map(JvmPluginApplication::fromJson)
            require(applications.size == 1) { "Exactly one workspace plugin application is required" }
            val arguments = fields.getValue("arguments").arrayValue().map(JsonValue::stringValue)
            validateArguments(arguments, workspace)
            val libraries = fields.getValue("libraries").arrayValue().map { workspace.path(it.stringValue()) }
            val home = Path.of(args[5]).toRealPath()
            val nativeJar = home.resolve("konan/lib/kotlin-native-compiler-embeddable.jar")
            require(Files.isRegularFile(nativeJar)) { "Missing pinned Native compiler" }
            require(System.getProperty("konan.home") == home.toString()) { "Native home does not match process binding" }
            val bootstrap = listOf(Path.of(NativePluginWorker::class.java.protectionDomain.codeSource.location.toURI()).toRealPath(),
                Path.of(org.jetbrains.kotlin.buildtools.api.KotlinToolchains::class.java.protectionDomain.codeSource.location.toURI()).toRealPath(),
                Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toRealPath())
            require(System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Path.of(it).toRealPath() } == bootstrap) { "Unexpected Native worker bootstrap classpath" }
            URLClassLoader(arrayOf(nativeJar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val version = loader.loadClass("org.jetbrains.kotlin.config.KotlinCompilerVersion").getField("VERSION").get(null)
                require(version == "2.4.20") { "Unsupported Native compiler version: $version" }
                val outputs = prepareOutputRoots(workspace.root.resolve("output"), applications.flatMap { it.outputs })
                val plugins = validatedWorkspacePlugins(applications, libraries, workspace, outputs, loader, pinned.classpath, nativeJar)
                compile(requestId, arguments, plugins, loader, workspace)
            }
        } catch (failure: Exception) {
            val cause = if (failure is InvocationTargetException) failure.targetException else failure
            cause.printStackTrace(System.err)
            WorkerResult(requestId, WorkerStatus.INFRASTRUCTURE_ERROR, 1, listOf(Diagnostic(
                (cause as? AdapterFailure)?.code ?: "KVIK_NATIVE_WORKER", DiagnosticCategory.INFRASTRUCTURE,
                cause.message ?: "Native worker failed")))
        }
        workspace.publish(args[2], result.toJson())
        exitProcess(result.exitCode)
    }

    private fun validateArguments(arguments: List<String>, workspace: Workspace) {
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index++]
            fun value() = arguments.getOrNull(index++) ?: reject("KVIK_NATIVE_ARGUMENT", "Missing argument for $argument")
            when (argument) {
                "-target" -> require(value() == "macos_arm64")
                "-produce" -> require(value() in setOf("library", "program"))
                "-o" -> { val path = value(); require(path.startsWith("output/")); workspace.path(path, false) }
                "-module-name" -> require(value().matches(Regex("[A-Za-z0-9_.:-]+")))
                "-entry" -> require(value().matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")))
                "-library" -> { val path = value(); require(path.startsWith("input/") && path.endsWith(".klib")); workspace.path(path) }
                "-generate-test-runner", "-Xmulti-platform", "-Xexplicit-api=strict", "-Werror", "-Xoverride-konan-properties=airplaneMode=true" -> Unit
                else -> when {
                    argument.startsWith("-opt-in=") -> require(argument.substringAfter('=').matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")))
                    argument.startsWith("-Xcommon-sources=") -> argument.substringAfter('=').split(',').forEach { require(it.startsWith("input/") && it.endsWith(".kt")); workspace.path(it) }
                    argument.endsWith(".kt") && (argument.startsWith("input/") || argument.startsWith("prepared/")) -> workspace.path(argument)
                    else -> reject("KVIK_NATIVE_ARGUMENT", "Unsupported Native worker argument: $argument")
                }
            }
        }
    }

    private fun compile(requestId: String, arguments: List<String>, plugins: List<PreparedWorkspacePlugin>, loader: ClassLoader, workspace: Workspace): WorkerResult {
        val compilerType = loader.loadClass("org.jetbrains.kotlin.cli.bc.K2Native")
        val compiler = compilerType.getDeclaredConstructor().newInstance()
        val argumentObject = compilerType.methods.single { it.name == "createArguments" && it.returnType.name.endsWith("K2NativeCompilerArguments") }.invoke(compiler)
        compilerType.methods.single { it.name == "parseArguments" && it.parameterCount == 2 }.invoke(compiler, arguments.toTypedArray(), argumentObject)
        argumentObject.javaClass.getMethod("setPluginClasspaths", Array<String>::class.java).invoke(argumentObject,
            plugins.flatMap { it.classpath }.map(Path::toString).toTypedArray() as Any)
        argumentObject.javaClass.getMethod("setPluginOptions", Array<String>::class.java).invoke(argumentObject,
            plugins.flatMap { plugin -> plugin.options.map { "plugin:${plugin.id}:$it" } }.toTypedArray() as Any)
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
                    diagnostics += Diagnostic(if (error) "KVIK_NATIVE_ERROR" else "KVIK_NATIVE_WARNING", DiagnosticCategory.COMPILATION, message,
                        path = path?.let { if (it.startsWith(workspace.root)) workspace.root.relativize(it).toString().removePrefix("input/") else it.toString() },
                        line = (locationValue("getLine") as? Int)?.takeIf { it > 0 }, column = (locationValue("getColumn") as? Int)?.takeIf { it > 0 })
                }
                System.err.println("${severity.lowercase()}: $message")
                null
            }
            "toString" -> "KvikNativeMessageCollector"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === values?.get(0)
            else -> error("Unsupported MessageCollector method: ${method.name}")
        } }
        val services = loader.loadClass("org.jetbrains.kotlin.config.Services").getField("EMPTY").get(null)
        val result = compilerType.methods.single { it.name == "exec" && it.parameterCount == 3 && it.parameterTypes[0] == collectorType }
            .invoke(compiler, collector, services, argumentObject)
        val code = result.javaClass.getMethod("getCode").invoke(result) as Int
        if (code == 0 && hasErrors) reject("KVIK_NATIVE_RESULT", "Native compiler returned success with errors")
        return WorkerResult(requestId, if (code == 0) WorkerStatus.SUCCESS else WorkerStatus.COMPILATION_ERROR, code, diagnostics)
    }
}
