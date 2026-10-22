@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.platform.kvik_foreground_terminal_lease_bytes
import kvik.execution.platform.kvik_process_ownership_version
import kvik.execution.NativeRunDirectory
import kvik.foreground.*
import platform.posix.*

internal fun devCommand(args: List<String>): Int {
    if (args.firstOrNull() == "--resume") return resumeDev(args)
    if (args == listOf("--session-capabilities")) {
        println(obj("schemaVersion" to 1.json(), "composition" to Digest(runtimeComposition, runtimeCompositionSize).toJson(),
            "runtime" to "${runtimeDirectory()}/kvik.kexe".json(), "processOwnershipVersion" to kvik_process_ownership_version().json(), "resumeVersions" to listOf(1.json(), 2.json(), 3.json()).json(),
            "terminalLease" to obj("schemaVersion" to 1.json(), "platform" to "macos-arm64".json(),
                "sizeBytes" to kvik_foreground_terminal_lease_bytes().json())).canonical())
        return 0
    }
    val bootstrap = bootstrapContext()
    val session = mutableMapOf<String, String>()
    val build = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        if (flag in setOf("--control", "--watch-ms", "--initial", "--mode")) {
            require(index < args.size && session.put(flag, args[index++]) == null) { "Missing/duplicate session option: $flag" }
        } else {
            require(flag != "--runs") { "dev reserves .kvik/sessions for invocation-owned evidence" }
            build += flag
            if (flag != "--offline") { require(index < args.size) { "Missing $flag value" }; build += args[index++] }
        }
    }
    val control = session["--control"] ?: if (isatty(0) == 1) "keys" else error("Non-TTY dev requires --control jsonl or --control lines")
    require(control in setOf("jsonl", "lines", "keys")) { "Unsupported control mode" }
    require(control != "keys" || isatty(0) == 1) { "Key controls require a foreground controlling TTY; use --control jsonl or lines for non-TTY input" }
    val interval = session["--watch-ms"]?.toLongOrNull() ?: if ("--watch-ms" in session) error("Invalid watch interval") else 1000L
    require(interval in 100..60_000) { "Watch interval must be 100..60000 milliseconds" }
    val mode = when (session["--mode"] ?: "library") {
        "library" -> SessionMode.LIBRARY; "application" -> SessionMode.APPLICATION; else -> error("Invalid session mode")
    }
    require(mode != SessionMode.APPLICATION || kvik_process_ownership_version() == 1) { "Application sessions require recursive process ownership version 1" }
    if (mode == SessionMode.APPLICATION) bootstrap?.requireApplicationOwnership()
    val initial = when (session["--initial"] ?: "build") { "build" -> if (mode == SessionMode.APPLICATION) Operation.APPLICATION else Operation.BUILD; "test" -> Operation.TEST; else -> error("Invalid initial operation") }
    val options = buildOptions(build)
    val project = readProject(options.project.project)
    if (mode == SessionMode.APPLICATION) {
        require(options.modules.size == 1 && options.targets.size == 1) { "Application sessions require exactly one --module and --target" }
        val selected = project.configuration.project.modules.singleOrNull { it.id == options.modules.single() }
            ?.targets?.singleOrNull { it.name == options.targets.single() } ?: error("Unknown application module/target")
        require(selected.application != null) { "Selected target has no application settings" }
    }
    // Validate arguments before any session output writes; actual lock/tool validation stays in children.
    kvik.locking.effectiveConfiguration(project.configuration, options.project.resolverPolicy())
    val tools = projectPath(project.root, options.tools)
    val lock = projectPath(project.root, options.project.lock)
    val store = projectPath(project.root, options.project.store)
    requireDisjointStore(project, store, lock, tools)
    val arguments = listOf("--project", project.path, "--lock", lock, "--store", store, "--tools", tools,
        "--jobs", options.budget.maxParallel.toString(), "--memory-mib", options.budget.memoryMiB.toString()) +
        (options.remoteCache?.let { listOf("--remote-cache", projectPath(project.root, it)) } ?: emptyList()) +
        (if (options.project.offline) listOf("--offline") else emptyList()) +
        options.project.pins.flatMap { listOf("--pin", it.toString()) } +
        options.project.scopedPins.flatMap { listOf("--scope-pin", it.argument()) } +
        options.project.metadataExclusions.flatMap { listOf("--exclude-metadata-edge", it.toJson().canonical()) } +
        options.modules.sorted().flatMap { listOf("--module", it) } + options.targets.sorted().flatMap { listOf("--target", it) }
    val parent = "${project.root}/.kvik/sessions"
    NativeFiles.ensureDirectory(parent)
    val directory = NativeRunDirectory(parent).path
    return runtimeProcessHost().use { host ->
        ForegroundSession(host, "${runtimeDirectory()}/kvik.kexe", directory, project.root, arguments,
            listOf("--project", project.path, "--lock", lock, "--tools", tools, "--store", store), control == "jsonl", interval, initial, bootstrap, bootstrapEnvironment(), restart = ::replaceSession, keys = control == "keys", mode = mode,
            currentComposition = Digest(runtimeComposition, runtimeCompositionSize),
            applicationModule = if (mode == SessionMode.APPLICATION) options.modules.single() else null,
            applicationTarget = if (mode == SessionMode.APPLICATION) options.targets.single() else null,
            initialWatched = if (mode == SessionMode.APPLICATION) Operation.APPLICATION else initial,
            initialIntents = if (mode == SessionMode.APPLICATION && initial == Operation.TEST) listOf(Operation.TEST, Operation.APPLICATION) else null,
            operations = OwnedSessionOperations(host, directory, project.root) { remoteOperationEnvironment(project.root, it) }).let { foreground ->
                try { foreground.run() }
                catch (failure: Exception) { throw RunStreamFailure("Foreground session failed: ${failure.message}") }
            }
    }
}

