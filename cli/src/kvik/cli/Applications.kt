@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.application.*
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeEventJournal
import kvik.execution.NativeRunDirectory
import kvik.execution.platform.*
import kvik.foreground.*
import kvik.projectgraph.ProjectMode
import kvik.runevidence.verificationRecordFromJson
import platform.posix.usleep
import platform.posix.access
import platform.posix.X_OK
import kotlin.time.TimeSource

internal fun prepareApplicationCommand(args: List<String>): ExitStatus = executeBuild(args, ProjectMode.APPLICATION).status

internal fun runApplicationCommand(args: List<String>): Int {
    if (args.firstOrNull() != "--run-options") return buildAndRunApplication(args)
    require(args.size >= 3) { "Missing application run options or build options" }
    val options = readApplicationRunOptions(args[1])
    return buildAndRunApplication(args.drop(2), runOptions = options)
}

/** Internal supervisor boundary; the foreground owner creates a dedicated empty gate directory. */
internal fun stageApplicationCommand(args: List<String>): Int {
    require(args.size > 4 && args[0] == "--activation" && args[2] == "--token") {
        "Use stage-application --activation DIR --token DIGEST_JSON [build options]"
    }
    val gate = activationGate(args)
    return buildAndRunApplication(args.drop(4), gate)
}

internal fun stagePreparedApplicationCommand(args: List<String>): Int {
    require(args.size == 8 && args[0] == "--activation" && args[2] == "--token" && args[4] == "--run" && args[6] == "--descriptor") {
        "Use stage-prepared --activation DIR --token DIGEST_JSON --run PATH --descriptor DIGEST_JSON"
    }
    val gate = activationGate(args)
    require(args[7].encodeToByteArray().size <= 256) { "Prepared descriptor digest exceeds bound" }
    return executePreparedApplication(PreparedRun(projectPath(currentDirectory(), args[5]), Digest.fromJson(parseJson(args[7]))), gate = gate)
}

private fun activationGate(args: List<String>): ApplicationGate {
    require(args[3].encodeToByteArray().size <= 256) { "Activation token exceeds bound" }
    val directory = args[1]
    NativeFiles.ensureDirectory(directory, create = false)
    require(NativeFiles.names(directory).isEmpty()) { "Activation directory must be fresh and empty" }
    val token = Digest.fromJson(parseJson(args[3]))
    require(token.toJson().canonical() == args[3]) { "Activation token must be canonical" }
    return ApplicationGate(directory, token)
}

private fun buildAndRunApplication(args: List<String>, gate: ApplicationGate? = null, runOptions: ApplicationRunOptions? = null): Int {
    check(kvik_interrupt_install() == 0) { "Cannot install application interruption handlers" }
    try {
        val outcome = executeBuild(args, ProjectMode.APPLICATION, ownInterrupts = false)
        if (kvik_interrupt_requested() != 0) return ExitStatus.CANCELLED.code
        if (outcome.status != ExitStatus.SUCCESS) return outcome.status.code
        // Keep the same interruption latch across preparation and execution.
        try {
            return executePreparedApplication(checkNotNull(outcome.preparation), ownInterrupts = false, gate = gate, runOptions = runOptions)
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

private fun executePreparedApplication(prepared: PreparedRun, ownInterrupts: Boolean = true, gate: ApplicationGate? = null, runOptions: ApplicationRunOptions? = null): Int {
    if (ownInterrupts) check(kvik_interrupt_install() == 0) { "Cannot install application interruption handlers" }
    var streamStarted = false
    try {
        if (kvik_interrupt_requested() != 0) return ExitStatus.CANCELLED.code
        var plan = validateApplicationPlan(prepared)
        runOptions?.requirePrepared(plan.record)
        val ready = gate?.let {
            val value = ApplicationReady(it.token, prepared.directory, prepared.descriptor, plan.descriptor.verification,
                plan.record.composition, plan.launch.module, plan.launch.target)
            if (!it.awaitActivation(value)) return ExitStatus.CANCELLED.code
            val current = validateApplicationPlan(prepared)
            require(current == plan) { "Validated application launch changed after readiness" }
            plan = current
            value
        }
        if (kvik_interrupt_requested() != 0) return ExitStatus.CANCELLED.code
        val directory = prepared.directory
        val descriptor = plan.descriptor
        val record = plan.record
        val launch = plan.launch
        val arguments = runOptions?.arguments?.let { plan.arguments.dropLast(launch.application.arguments.size) + it } ?: plan.arguments
        val environment = plan.environment + runOptions?.environmentVariables.orEmpty()
        val workingDirectory = runOptions?.workingDirectory ?: plan.workingDirectory
        NativeFiles.ensureDirectory("$directory/application-runs")
        val execution = NativeRunDirectory("$directory/application-runs")
        NativeFiles.syncDirectory("$directory/application-runs")
        val stdout = "${execution.path}/application.stdout"
        val stderr = "${execution.path}/application.stderr"
        val request = ProcessRequest(arguments, workingDirectory, environment, stdout, stderr, 0)
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
                    if (gate != null) gate.started(checkNotNull(ready), execution.path)
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

private data class ApplicationPlan(
    val descriptor: PreparedApplicationDescriptor,
    val record: VerificationRecord,
    val launch: ApplicationLaunch,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String,
)

private fun validateApplicationPlan(prepared: PreparedRun): ApplicationPlan {
    // Readiness must include the ownership helper required to start the application.
    require(access(verifiedRuntimeGuardian(), X_OK) == 0) { "Native ownership helper is not executable" }
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
    val inputs = "$directory/prepared/application/input"
    val arguments = if (launch.target == "jvm") {
        require(':' !in inputs) { "JVM bundle directory cannot contain the classpath separator ':'" }
        val tools = BuildTools(checked.metadata.getValue("toolsPath").stringValue())
        val java = tools.verifiedJavaExecutable(record.toolchains.getValue("jvm:jdk")) {
            if (kvik_interrupt_requested() != 0) throw CliFailure("KVIK_APPLICATION_CANCELLED",
                DiagnosticCategory.CANCELLATION, "Application JDK verification cancelled", ExitStatus.CANCELLED)
        }
        // JDK hashing can be lengthy: source/config/lock and desired pins must still match after it.
        val after = checkRun(directory)
        require(Sha256.digest(after.record.toJson()) == descriptor.verification) { "Preparation verification changed during JDK validation" }
        if (after.exitStatus != ExitStatus.SUCCESS) throw CliFailure("KVIK_APPLICATION_PREPARATION_STALE", DiagnosticCategory.CONFIGURATION,
            after.reason ?: "Application preparation changed during JDK validation", after.exitStatus)
        listOf(java, "--disable-@files", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp",
            launch.classpath.joinToString(":") { "$inputs/${it.value}" }, launch.application.entryPoint) + launch.application.arguments
    } else if (KotlinJsTarget.find(launch.target) != null) {
        val tools = BuildTools(checked.metadata.getValue("toolsPath").stringValue())
        val node = tools.nodeProvider().verified(record.toolchains.getValue("${launch.target}:node")) {
            if (kvik_interrupt_requested() != 0) throw CliFailure("KVIK_APPLICATION_CANCELLED",
                DiagnosticCategory.CANCELLATION, "Application Node verification cancelled", ExitStatus.CANCELLED)
        }
        val after = checkRun(directory)
        require(Sha256.digest(after.record.toJson()) == descriptor.verification) { "Preparation verification changed during Node validation" }
        if (after.exitStatus != ExitStatus.SUCCESS) throw CliFailure("KVIK_APPLICATION_PREPARATION_STALE", DiagnosticCategory.CONFIGURATION,
            after.reason ?: "Application preparation changed during Node validation", after.exitStatus)
        listOf(node, "--unhandled-rejections=strict", "$inputs/${checkNotNull(launch.moduleFile).value}") + launch.application.arguments
    } else listOf("$inputs/${checkNotNull(launch.executable).value}") + launch.application.arguments
    require(access(arguments.first(), X_OK) == 0) { "Application executable is not executable" }
    require("KVIK_APPLICATION_INPUTS" !in launch.application.environment) { "KVIK_APPLICATION_INPUTS is supplied by the application runner" }
    val environment = mapOf("PATH" to "/usr/bin:/bin:/usr/sbin:/sbin", "LANG" to "C", "LC_ALL" to "C", "TZ" to "UTC") +
        launch.application.environment + ("KVIK_APPLICATION_INPUTS" to inputs)
    return ApplicationPlan(descriptor, record, launch, arguments, environment, checked.metadata.getValue("projectRoot").stringValue())
}

private class ApplicationGate(val directory: String, val token: Digest) {
    fun awaitActivation(ready: ApplicationReady): Boolean {
        if (kvik_interrupt_requested() != 0) return false
        require(NativeFiles.names(directory).isEmpty()) { "Activation files appeared before readiness" }
        val bytes = ready.toJson().canonical().encodeToByteArray()
        NativeFiles.publish("$directory/ready.json", bytes, APPLICATION_ACTIVATION_MAX_BYTES)
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            if (kvik_interrupt_requested() != 0) return false
            require(start.elapsedNow().inWholeMilliseconds < APPLICATION_ACTIVATION_WAIT_MILLIS) { "Application activation timed out after 60 seconds" }
            if (NativeFiles.info("$directory/activate.json") != null) {
                ApplicationActivation.parse(NativeFiles.read("$directory/activate.json", APPLICATION_ACTIVATION_MAX_BYTES)).requireReady(ready)
                require(NativeFiles.read("$directory/ready.json", APPLICATION_ACTIVATION_MAX_BYTES).contentEquals(bytes)) { "Application readiness changed" }
                require(NativeFiles.info("$directory/started.json") == null) { "Application started record appeared before activation" }
                return kvik_interrupt_requested() == 0
            }
            usleep(10_000u)
        }
    }
    fun started(ready: ApplicationReady, executionDirectory: String) {
        val value = ApplicationStarted(token, Sha256.digest(ready.toJson()), executionDirectory)
        NativeFiles.publish("$directory/started.json", value.toJson().canonical().encodeToByteArray(), APPLICATION_ACTIVATION_MAX_BYTES)
    }
}
