@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.core.*
import kvik.locking.ResolverPolicy
import kotlin.system.exitProcess

class CliFailure(val code: String, val category: DiagnosticCategory, message: String, val exitStatus: ExitStatus = ExitStatus.FAILED) : Exception(message)
internal class RunStreamFailure(message: String) : Exception(message)

fun main(args: Array<String>) {
    try {
        command(args)
    } catch (failure: Exception) {
        if (failure is RunStreamFailure) {
            platform.posix.fputs(obj("schemaVersion" to CONTRACT_VERSION.json(), "diagnostic" to Diagnostic(
                "KVIK_RUN_EVIDENCE_UNAVAILABLE", DiagnosticCategory.INFRASTRUCTURE, failure.message ?: "Cannot persist run evidence").toJson()).canonical() + "\n", platform.posix.stderr)
            exitProcess(ExitStatus.FAILED.code)
        }
        val runtime = failure is RuntimeIntegrityFailure
        println(obj("schemaVersion" to CONTRACT_VERSION.json(), "diagnostic" to Diagnostic(
            if (failure is CliFailure) failure.code else if (runtime) "KVIK_RUNTIME_INTEGRITY" else "KVIK_INVALID_PROJECT",
            if (failure is CliFailure) failure.category else if (runtime) DiagnosticCategory.TOOLCHAIN else DiagnosticCategory.CONFIGURATION,
            failure.message ?: "Invalid project configuration").toJson()).canonical())
        exitProcess(if (failure is CliFailure) failure.exitStatus.code else ExitStatus.INVALID_CONFIGURATION.code)
    }
}

private fun command(args: Array<String>) {
    when (args.firstOrNull() ?: "help") {
        "help", "--help", "-h" -> println("""
            Kvik — Kotlin/Native source-built prototype
            Commands: runtime-info, inspect, resolve, check-lock, build, test, verification, help
            Project options: --project PATH --pin GROUP:ARTIFACT:VERSION (repeatable)
            Lock options: --lock PATH --store PATH --offline
            Build/test: --module ID --target jvm|macosArm64 (repeatable) --tools PATH
            Execution: --runs PATH --jobs N --memory-mib N
            Evidence: verification --run PATH
            Launcher options before --: --java-home PATH --konan-home PATH --cache-dir PATH --offline
        """.trimIndent())
        "runtime-info" -> {
            verifiedRuntimeGuardian()
            val worker = verifiedRuntimeJvmWorker()
            println(obj(
            "schemaVersion" to CONTRACT_VERSION.json(), "backend" to "kotlin-native".json(),
            "composition" to runtimeComposition.json(), "plugins" to buildPlugins.map { obj("id" to it.id.json(), "apiVersion" to it.apiVersion.json(), "schema" to it.configurationSchema) }.json(),
            "guardian" to Digest(runtimeGuardianHash, runtimeGuardianSize).toJson(),
            "jvmWorker" to worker.identity.toJson(), "jvmCompiler" to worker.compiler.toJson(),
            ).canonical())
        }
        "inspect" -> {
            val options = projectOptions(args.drop(1), lockOptions = false)
            println(inspectProject(options.project, ResolverPolicy(options.pins)).canonical())
        }
        "resolve", "check-lock" -> println(dependencyCommand(args.drop(1), updateRequested = args[0] == "resolve").canonical())
        "build", "test" -> exitProcess(buildCommand(args.drop(1), test = args[0] == "test").code)
        "verification" -> exitProcess(verificationCommand(args.drop(1)).code)
        else -> {
            println(obj("schemaVersion" to CONTRACT_VERSION.json(), "diagnostic" to Diagnostic("KVIK_UNSUPPORTED_COMMAND", DiagnosticCategory.CONFIGURATION,
                "Command '${args[0]}' is not implemented; use help for current capabilities").toJson()).canonical())
            exitProcess(ExitStatus.INVALID_CONFIGURATION.code)
        }
    }
}
