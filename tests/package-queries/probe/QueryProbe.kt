package kvik.cli

import kvik.core.*
import kotlin.system.exitProcess

// The probe invokes the production command boundaries without composing unrelated executors.
class CliFailure(val code: String, val category: DiagnosticCategory, message: String, val exitStatus: ExitStatus = ExitStatus.FAILED) : Exception(message)
fun main(args: Array<String>) {
    try {
        val result = when (args.firstOrNull()) {
            "packages" -> packageCommand(args.drop(1))
            "dependencies" -> projectDependenciesCommand(args.drop(1))
            else -> error("Unsupported query probe command")
        }
        exitProcess(printPackageQuery(result).code)
    } catch (failure: Exception) {
        println(obj("schemaVersion" to 1.json(), "error" to (failure.message ?: "Invalid query").json()).canonical())
        exitProcess(2)
    }
}
