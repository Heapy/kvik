@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.core.*
import kotlin.system.exitProcess

class CliFailure(val code: String, val category: DiagnosticCategory, message: String, val exitStatus: ExitStatus = ExitStatus.FAILED) : Exception(message)
internal class RunStreamFailure(message: String, val exitStatus: ExitStatus = ExitStatus.FAILED) : Exception(message)

fun main(args: Array<String>) {
    try {
        command(args)
    } catch (failure: Exception) {
        if (failure is RunStreamFailure) {
            platform.posix.fputs(obj("schemaVersion" to CONTRACT_VERSION.json(), "diagnostic" to Diagnostic(
                "KVIK_RUN_EVIDENCE_UNAVAILABLE", DiagnosticCategory.INFRASTRUCTURE, failure.message ?: "Cannot persist run evidence").toJson()).canonical() + "\n", platform.posix.stderr)
            exitProcess(failure.exitStatus.code)
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
            Commands: runtime-info, inspect, resolve, check-lock, build, test, prepare-application, run-application, run-prepared, verification, dev, packages, dependencies, help
            Project options: --project PATH --pin GROUP:ARTIFACT:VERSION (repeatable)
            Scoped pins: --scope-pin MODULE:TARGET:main|test:USAGE:PURPOSE=GROUP:ARTIFACT:VERSION
            Metadata corrections: --exclude-metadata-edge JSON (repeatable; exact domain, parent, metadata digest, variant, dependency, reason)
            Lock options: --lock PATH --store PATH --offline
            Build/test: --module ID --target jvm|macosArm64 (repeatable) --tools PATH
            Execution: --runs PATH --jobs N --memory-mib N
            Applications: prepare-application or run-application plus build options; select exactly one module and target
            Prepared replay: run-prepared --run PATH --descriptor DIGEST_JSON (requires current successful preparation)
            Dev: dev [--control keys|jsonl|lines] (TTY defaults to immediate r/t/q/? keys) [--watch-ms 1000] [--initial build|test] plus build options
            Packages: packages versions GROUP:ARTIFACT --project PATH [--channel all|stable|prerelease] [--limit N] [--cursor TOKEN]
            Package graph: packages dependencies GROUP:ARTIFACT:VERSION --target jvm|macosArm64 --usage USAGE --project PATH
            Query cache: --query-cache PATH [--refresh|--offline] --json
            Project graph: dependencies --project PATH [--lock PATH] [--store PATH] [--module ID] [--target TARGET] [--compilation main|test] [--usage USAGE] --json
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
        "packages" -> exitProcess(printPackageQuery(packageCommand(args.drop(1))).code)
        "dependencies" -> exitProcess(printPackageQuery(projectDependenciesCommand(args.drop(1))).code)
        "inspect" -> {
            val options = projectOptions(args.drop(1), lockOptions = false)
            println(inspectProject(options.project, options.resolverPolicy()).canonical())
        }
        "resolve", "check-lock" -> println(dependencyCommand(args.drop(1), updateRequested = args[0] == "resolve").canonical())
        "build", "test" -> exitProcess(buildCommand(args.drop(1), test = args[0] == "test").code)
        "prepare-application" -> exitProcess(prepareApplicationCommand(args.drop(1)).code)
        "run-application" -> exitProcess(runApplicationCommand(args.drop(1)))
        "run-prepared" -> exitProcess(runPreparedApplicationCommand(args.drop(1)))
        "dev" -> exitProcess(devCommand(args.drop(1)))
        "snapshot-query" -> println(snapshotQueryCommand(args.drop(1)).canonical())
        "verification" -> exitProcess(verificationCommand(args.drop(1)).code)
        else -> {
            println(obj("schemaVersion" to CONTRACT_VERSION.json(), "diagnostic" to Diagnostic("KVIK_UNSUPPORTED_COMMAND", DiagnosticCategory.CONFIGURATION,
                "Command '${args[0]}' is not implemented; use help for current capabilities").toJson()).canonical())
            exitProcess(ExitStatus.INVALID_CONFIGURATION.code)
        }
    }
}
