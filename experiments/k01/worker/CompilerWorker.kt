@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package kvik.spike.worker

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import org.jetbrains.kotlin.buildtools.api.BaseCompilationOperation
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain

/** One invocation, one in-process BTA session; never selects a daemon execution policy. */
fun main(args: Array<String>) {
    require(args.size >= 3) { "Usage: CompilerWorker <artifacts-directory> <destination> <source>..." }
    val artifacts = Path.of(args[0])
    val implementationClasspath = Files.list(artifacts).use { paths ->
        paths.filter { it.toString().endsWith(".jar") }.sorted().toList()
    }
    val toolchains = KotlinToolchains.loadImplementation(implementationClasspath)
    val jvm = toolchains.getToolchain(JvmPlatformToolchain::class.java)
    val destination = Path.of(args[1])
    Files.createDirectories(destination)
    val builder = jvm.jvmCompilationOperationBuilder(args.drop(2).map(Path::of), destination)
    builder.compilerArguments[JvmCompilerArguments.NO_REFLECT] = true
    builder.compilerArguments[JvmCompilerArguments.NO_STDLIB] = true
    builder.compilerArguments[JvmCompilerArguments.CLASSPATH] = listOf(artifacts.resolve("kotlin-stdlib-2.4.20.jar"))
    builder.compilerArguments[JvmCompilerArguments.MODULE_NAME] = "kvik-k01-fixture"
    builder[BaseCompilationOperation.COMPILER_MESSAGE_RENDERER] = object : CompilerMessageRenderer {
        override fun render(
            severity: CompilerMessageRenderer.Severity,
            message: String,
            location: CompilerMessageRenderer.SourceLocation?,
        ): String = "${severity.name} ${location?.path ?: ""}:${location?.line ?: 0}:${location?.column ?: 0} $message"
    }
    val logger = object : KotlinLogger {
        override val isDebugEnabled = false
        override fun error(msg: String, throwable: Throwable?) { System.err.println(msg); throwable?.printStackTrace() }
        override fun warn(msg: String, throwable: Throwable?) { System.err.println(msg) }
        override fun info(msg: String) { System.err.println(msg) }
        override fun debug(msg: String) = Unit
        override fun lifecycle(msg: String) { System.err.println(msg) }
    }
    val result = toolchains.createBuildSession().use { session ->
        session.executeOperation(builder.build(), toolchains.createInProcessExecutionPolicy(), logger)
    }
    println("{\"compiler\":\"${toolchains.getCompilerVersion()}\",\"policy\":\"in-process\",\"result\":\"${result.name}\"}")
    if (result != CompilationResult.COMPILATION_SUCCESS) exitProcess(1)
}
