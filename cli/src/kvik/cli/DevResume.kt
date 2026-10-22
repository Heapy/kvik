@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.platform.*
import kvik.foreground.*
import kotlinx.cinterop.*
import platform.posix.*

internal fun boundedEnvironment(name: String, max: Int): String? {
    val value = getenv(name) ?: return null
    val length = strnlen(value, (max + 1).convert()).toInt()
    require(length <= max) { "Environment entry exceeds bound: $name" }
    return value.readBytes(length).decodeToString(throwOnInvalidSequence = true)
}
internal fun bootstrapEnvironment(): Map<String, String> = buildMap {
    put("LANG", "C"); put("LC_ALL", "C"); put("TZ", "UTC"); put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin")
    for (name in listOf("HOME", "TMPDIR")) boundedEnvironment(name, 4096)?.let { put(name, it) }
}
internal fun bootstrapContext(): BootstrapLaunch? = boundedEnvironment("KVIK_BOOTSTRAP_LAUNCH", 32 * 1024)?.let {
    BootstrapLaunch.parse(it.encodeToByteArray()).also { launch ->
        launch.requireCurrent(Digest(runtimeComposition, runtimeCompositionSize), "${runtimeDirectory()}/kvik.kexe")
    }
}

internal fun replaceSession(state: SessionResume) {
    val bytes = state.toJson().canonical().encodeToByteArray()
    SessionResume.parse(bytes) // Bound and validate our own continuation before publishing it.
    val path = "${state.directory}/handoff.json"
    NativeFiles.writeNew(path, bytes)
    NativeFiles.syncDirectory(state.directory)
    val launch = BootstrapLaunch.parse(state.launchBytes)
    val arguments = listOf(launch.runtime, "dev", "--resume", path, "--resume-hash", Sha256.digest(bytes).hash)
    val environment = bootstrapEnvironment() + ("KVIK_BOOTSTRAP_LAUNCH" to state.launchBytes.decodeToString(throwOnInvalidSequence = true))
    memScoped {
        fun strings(values: List<String>) = allocArray<CPointerVar<ByteVar>>(values.size + 1).also { array ->
            values.forEachIndexed { index, value -> array[index] = value.cstr.ptr }; array[values.size] = null
        }
        val argv = strings(arguments)
        val envp = strings(environment.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" })
        if (state.terminalLease == null) kvik_foreground_exec(launch.runtime, argv, envp)
        else kvik_foreground_exec_terminal(launch.runtime, argv, envp, state.terminalLease)
        error("Cannot replace foreground runtime: errno=$errno")
    }
}

internal fun resumeDev(args: List<String>): Int {
    require(args.size == 4 && args[0] == "--resume" && args[2] == "--resume-hash" && args[3].matches(Regex("[0-9a-f]{64}"))) { "Invalid session resume arguments" }
    NativeFiles.validateAbsolute(args[1])
    val bytes = NativeFiles.read(args[1], SessionResume.MAX_BYTES)
    require(Sha256.digest(bytes).hash == args[3]) { "Session handoff digest mismatch" }
    val state = SessionResume.parse(bytes)
    require(args[1] == "${state.directory}/handoff.json" && state.directory != state.previousDirectory &&
        state.directory.substringBeforeLast('/') == "${state.workingDirectory}/.kvik/sessions" &&
        state.previousDirectory.substringBeforeLast('/') == state.directory.substringBeforeLast('/')) { "Invalid linked session directories" }
    NativeFiles.ensureDirectory(state.directory, create = false)
    NativeFiles.ensureDirectory(state.workingDirectory, create = false)
    val options = buildOptions(state.arguments)
    val paths = listOf(options.project.project, options.project.lock, options.project.store, options.tools)
    paths.forEach(NativeFiles::validateAbsolute)
    require(options.project.project.substringBeforeLast('/') == state.workingDirectory) { "Resume project binding changed" }
    val launch = BootstrapLaunch.parse(state.launchBytes)
    launch.requireCurrent(Digest(runtimeComposition, runtimeCompositionSize), "${runtimeDirectory()}/kvik.kexe")
    checkNotNull(bootstrapContext()) { "Missing resumed bootstrap context" }.requireReplacement(launch, launch.composition)
    // Deliberately do not parse the mutable project here. Every owned observer/build child
    // performs the current configuration/store preflight, so broken configuration is recoverable.
    return runtimeProcessHost().use { host ->
        try { ForegroundSession(host, launch.runtime, state.directory, state.workingDirectory, state.arguments,
            listOf("--project", paths[0], "--lock", paths[1], "--tools", paths[3], "--store", paths[2]), state.jsonControls,
            state.watchMillis, state.pending, launch, bootstrapEnvironment(), state.watched, state.partialInput,
            state.previousDirectory, resumed = true, restart = ::replaceSession, keys = state.terminalLease != null, inheritedTerminalLease = state.terminalLease).run() }
        catch (failure: Exception) { throw RunStreamFailure("Resumed foreground session failed: ${failure.message}") }
    }
}
