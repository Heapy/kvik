@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.application.*
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeEventJournal
import kvik.execution.NativeRunDirectory
import kvik.execution.platform.*
import kvik.projectgraph.ProjectMode
import kvik.runevidence.verificationRecordFromJson
import platform.posix.usleep

internal fun prepareApplicationCommand(args: List<String>): ExitStatus = executeBuild(args, ProjectMode.APPLICATION).status

internal fun runApplicationCommand(args: List<String>): Int {
    check(kvik_interrupt_install() == 0) { "Cannot install application interruption handlers" }
    try {
        val outcome = executeBuild(args, ProjectMode.APPLICATION, ownInterrupts = false)
        if (kvik_interrupt_requested() != 0) return ExitStatus.CANCELLED.code
        if (outcome.status != ExitStatus.SUCCESS) return outcome.status.code
        // Keep the same interruption latch across preparation and execution.
        try {
            return executePreparedApplication(checkNotNull(outcome.preparation), ownInterrupts = false)
        } catch (failure: RunStreamFailure) {
            throw failure
        } catch (failure: Exception) {
            throw RunStreamFailure("Prepared application execution failed: ${failure.message}",
                if (failure is CliFailure) failure.exitStatus else ExitStatus.FAILED)
        }
    } finally {
        kvik_interrupt_restore()
    }
}

/** Explicit replay still requires current source/tool evidence; it never trusts a previous run. */
internal fun runPreparedApplicationCommand(args: List<String>): Int {
    require(args.size == 4 && args[0] == "--run" && args[2] == "--descriptor") {
        "Use run-prepared --run PATH --descriptor DIGEST_JSON"
    }
    return executePreparedApplication(PreparedRun(projectPath(currentDirectory(), args[1]), Digest.fromJson(parseJson(args[3]))))
}

private fun executePreparedApplication(prepared: PreparedRun, ownInterrupts: Boolean = true): Int {
    if (ownInterrupts) check(kvik_interrupt_install() == 0) { "Cannot install application interruption handlers" }
    var streamStarted = false
    try {
        if (kvik_interrupt_requested() != 0) return ExitStatus.CANCELLED.code
        val directory = prepared.directory
        val descriptorBytes = NativeFiles.read("$directory/prepared.json", 8192)
        require(Sha256.digest(descriptorBytes) == prepared.descriptor) { "Prepared application descriptor changed" }
        val descriptor = PreparedApplicationDescriptor.fromJson(parseJson(descriptorBytes.decodeToString(throwOnInvalidSequence = true)))
        val record = verificationRecordFromJson(parseJson(NativeFiles.read("$directory/verification.json", 16 * 1024 * 1024)
            .decodeToString(throwOnInvalidSequence = true)))
        // Hash the complete materialized bundle before the final source/tool observation.
        val launch = validatePreparedApplication(record, descriptor, directory)
        val checked = checkRun(directory)
        require(Sha256.digest(checked.record.toJson()) == descriptor.verification) { "Preparation verification changed during validation" }
        if (checked.exitStatus != ExitStatus.SUCCESS) throw CliFailure("KVIK_APPLICATION_PREPARATION_STALE", DiagnosticCategory.CONFIGURATION,
            checked.reason ?: "Application preparation is not current and successful", checked.exitStatus)
        if (kvik_interrupt_requested() != 0) return ExitStatus.CANCELLED.code
        val inputs = "$directory/prepared/application/input"
        val arguments = if (launch.target == "jvm") {
            require(':' !in inputs) { "JVM bundle directory cannot contain the classpath separator ':'" }
            listOf(checkNotNull(checked.javaExecutable), "--disable-@files", "-cp",
                launch.classpath.joinToString(":") { "$inputs/${it.value}" }, launch.application.entryPoint) + launch.application.arguments
        } else listOf("$inputs/${checkNotNull(launch.executable).value}") + launch.application.arguments
        require("KVIK_APPLICATION_INPUTS" !in launch.application.environment) { "KVIK_APPLICATION_INPUTS is supplied by the application runner" }
        val environment = mapOf("PATH" to "/usr/bin:/bin:/usr/sbin:/sbin", "LANG" to "C", "LC_ALL" to "C", "TZ" to "UTC") +
            launch.application.environment + ("KVIK_APPLICATION_INPUTS" to inputs)
        NativeFiles.ensureDirectory("$directory/application-runs")
        val execution = NativeRunDirectory("$directory/application-runs")
        NativeFiles.syncDirectory("$directory/application-runs")
        val stdout = "${execution.path}/application.stdout"
        val stderr = "${execution.path}/application.stderr"
        val request = ProcessRequest(arguments, checked.metadata.getValue("projectRoot").stringValue(), environment, stdout, stderr, 0)
        NativeFiles.writeNew("${execution.path}/request.json", obj("schemaVersion" to CONTRACT_VERSION.json(),
            "preparation" to prepared.descriptor.toJson(), "verification" to descriptor.verification.toJson(),
            "arguments" to arguments.map { it.json() }.json(), "workingDirectory" to request.workingDirectory.json(),
            "environment" to JsonValue.Object(environment.mapValues { it.value.json() }), "timeoutMillis" to 0.json()).canonical().encodeToByteArray())
        NativeFiles.syncDirectory(execution.path)
        NativeEventJournal("${execution.path}/events.jsonl").use { journal ->
            var sequence = 0L
            fun emit(type: String, payload: JsonValue.Object) {
                val event = RunEvent(execution.runId, ++sequence, type, payload)
                streamStarted = true
                journal.append(event)
                println(event.toJson().canonical())
            }
            runtimeProcessHost().use { host ->
                if (kvik_interrupt_requested() != 0) return ExitStatus.CANCELLED.code
                host.start(request).use { process ->
                    emit("application-started", obj("preparationRun" to record.runId.json(), "descriptor" to prepared.descriptor.toJson(),
                        "module" to launch.module.json(), "target" to launch.target.json(), "stdout" to stdout.json(), "stderr" to stderr.json()))
                    var result: ProcessResult? = null
                    while (result == null) {
                        if (kvik_interrupt_requested() != 0) process.cancel()
                        result = process.poll()
                        if (result == null) usleep(10_000u)
                    }
                    emit("application-finished", obj("preparationRun" to record.runId.json(), "exitCode" to result.exitCode.json(),
                        "cancelled" to result.cancelled.json(), "timedOut" to result.timedOut.json(),
                        "stdout" to stdout.json(), "stderr" to stderr.json()))
                    return if (result.cancelled || kvik_interrupt_requested() != 0) ExitStatus.CANCELLED.code else result.exitCode
                }
            }
        }
    } catch (failure: RunStreamFailure) {
        throw failure
    } catch (failure: CliFailure) {
        if (streamStarted) throw RunStreamFailure(failure.message ?: "Application execution failed", failure.exitStatus)
        throw failure
    } catch (failure: Exception) {
        if (streamStarted) throw RunStreamFailure("Application execution has incomplete retained evidence: ${failure.message}")
        throw CliFailure("KVIK_APPLICATION_RUN_FAILED", DiagnosticCategory.INFRASTRUCTURE,
            failure.message ?: "Cannot run prepared application")
    } finally {
        if (ownInterrupts) kvik_interrupt_restore()
    }
}
