package kvik.foreground

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory

/** All source inventories and compiler work occur in these invocation-owned children. */
class Recomposition(
    val launch: BootstrapLaunch,
    private val host: ProcessHost,
    private val directory: String,
    private val environment: Map<String, String>,
    private val interval: Long,
    private val event: (String, JsonValue.Object) -> Unit,
    private val terminalLeaseBytes: Int? = null,
    private val applicationMode: Boolean = false,
    private val sourceRevisions: Boolean = false,
) {
    private data class Child(val process: OwnedProcess, val directory: String, val composition: Digest?, val epoch: Int)
    private var epoch = 0
    private var query: Child? = null
    private var build: Child? = null
    private var compatibility: Child? = null
    private var compatible = terminalLeaseBytes == null && !applicationMode && !sourceRevisions
    private var nextQuery = 0L
    private var candidate: Digest? = null
    private var observed = false
    private var repeats = 0
    private var attempted: Digest? = null
    private var replacement: Pair<BootstrapLaunch, ByteArray>? = null
    private var confirmed = false
    var allowWork = false
        private set
    val busy: Boolean get() = query != null || build != null || compatibility != null
    val ready: Pair<BootstrapLaunch, ByteArray>? get() = replacement.takeIf { confirmed && compatible && !busy }
    val selected: Pair<BootstrapLaunch, ByteArray>? get() = replacement.takeIf { confirmed && compatible }
    fun retry() { attempted = null; nextQuery = 0 }
    fun replacementFailed() { replacement = null; confirmed = false; compatible = terminalLeaseBytes == null && !applicationMode && !sourceRevisions }
    fun cancel() { query?.process?.cancel(); build?.process?.cancel(); compatibility?.process?.cancel() }

    private fun start(kind: BootstrapQuery, composition: Digest?): Child = startCommand(launch.command(kind), composition,
        if (kind == BootstrapQuery.COMPOSITION) 60_000 else launch.rebuildTimeoutMillis)
    private fun startCommand(command: List<String>, composition: Digest?, timeout: Long): Child {
        val path = NativeRunDirectory(directory).path
        NativeFiles.writeNew("$path/command.json", command.map { it.json() }.json().canonical().encodeToByteArray())
        val process = host.start(ProcessRequest(command, launch.sourceRoot, environment,
            "$path/stdout.jsonl", "$path/stderr.log", timeout))
        return Child(process, path, composition, epoch)
    }
    private fun completed(child: Child): ProcessResult? = child.process.poll()?.also { child.process.close() }

    fun tick(now: Long, consumersIdle: Boolean, stopping: Boolean) {
        query?.let { child -> completed(child)?.let { result ->
            query = null; nextQuery = now + interval
            if (child.epoch != epoch) { NativeFiles.removeTree(child.directory); nextQuery = 0 } else {
            val digest = if (result.exitCode == 0 && !result.cancelled && !result.timedOut) try {
                BootstrapLaunch.parseComposition(NativeFiles.read("${child.directory}/stdout.jsonl", 1024))
            } catch (_: Exception) { null } else null
            val changed = !observed || candidate != digest
            repeats = if (changed) 1 else minOf(2, repeats + 1)
            candidate = digest; observed = true
            if (changed) event("composition-observed", obj("composition" to (digest?.toJson() ?: JsonValue.Null),
                "state" to (if (digest == null) "unavailable" else if (digest == launch.composition) "current" else "stale").json(),
                "evidenceDirectory" to child.directory.json()))
            else NativeFiles.removeTree(child.directory)
            allowWork = digest == launch.composition && (repeats >= 2 || !changed && allowWork)
            if (digest != build?.composition) build?.process?.cancel()
            if (digest != compatibility?.composition) compatibility?.process?.cancel()
            replacement?.let { (selected, _) ->
                if (digest == selected.composition) confirmed = true
                else { replacementFailed() }
            }
            }
        } }
        build?.let { child -> completed(child)?.let { result ->
            build = null
            if (result.cancelled && !stopping) attempted = null
            var error: String? = null
            var selected: Pair<BootstrapLaunch, ByteArray>? = null
            if (result.exitCode == 0 && !result.cancelled && !result.timedOut) try {
                val bytes = NativeFiles.read("${child.directory}/stdout.jsonl", 32 * 1024)
                val value = BootstrapLaunch.parse(bytes)
                value.requireReplacement(launch, child.composition!!)
                if (applicationMode) value.requireApplicationOwnership()
                if (candidate == value.composition) selected = value to bytes
                else error = "Source composition changed during rebuild"
            } catch (failure: Exception) { error = failure.message ?: "Invalid replacement descriptor" }
            else error = "Bootstrap did not produce a replacement"
            replacement = selected; confirmed = false; compatible = terminalLeaseBytes == null && !applicationMode && !sourceRevisions; nextQuery = 0
            epoch++; query?.process?.cancel()
            event("rebuild-finished", obj("composition" to child.composition!!.toJson(), "exitCode" to result.exitCode.json(),
                "cancelled" to result.cancelled.json(), "timedOut" to result.timedOut.json(), "accepted" to (selected != null).json(),
                "error" to (error?.take(512)?.json() ?: JsonValue.Null), "evidenceDirectory" to child.directory.json()))
        } }
        compatibility?.let { child -> completed(child)?.let { result ->
            compatibility = null
            var error: String? = null
            val selected = replacement?.first
            val accepted = if (result.exitCode == 0 && !result.cancelled && !result.timedOut && selected != null && selected.composition == child.composition) try {
                requireSessionCapabilities(NativeFiles.read("${child.directory}/stdout.jsonl", 8192), selected,
                    if (applicationMode) 3 else if (terminalLeaseBytes != null) 2 else 1, terminalLeaseBytes, sourceRevisions)
                true
            } catch (failure: Exception) { error = failure.message ?: "Invalid session capabilities"; false }
            else { error = "Replacement did not accept terminal handoff"; false }
            compatible = accepted
            if (!accepted) replacementFailed()
            event("replacement-checked", obj("composition" to child.composition!!.toJson(), "compatible" to accepted.json(),
                "error" to (error?.take(512)?.json() ?: JsonValue.Null), "evidenceDirectory" to child.directory.json()))
        } }
        if (stopping) { cancel(); return }
        if (ready != null && !applicationMode) return
        if (confirmed && !compatible && compatibility == null) replacement?.first?.let { selected ->
            compatibility = startCommand(listOf(selected.runtime, "dev", "--session-capabilities") +
                (if (sourceRevisions) listOf("--source-revisions") else emptyList()), selected.composition, 60_000)
        }
        if (query == null && now >= nextQuery) query = start(BootstrapQuery.COMPOSITION, null)
        val desired = candidate
        if (!allowWork && consumersIdle && build == null && replacement == null && repeats >= 2 && desired != null &&
            desired != launch.composition && desired != attempted) {
            attempted = desired
            build = start(BootstrapQuery.LAUNCH, desired)
            event("rebuild-started", obj("composition" to desired.toJson(), "evidenceDirectory" to build!!.directory.json()))
        }
    }
}
