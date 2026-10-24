package kvik.foreground

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory

/** Owns supervisors, not application PIDs. The loop reads only two bounded canonical records. */
internal class ApplicationLifecycle(
    private val host: ProcessHost,
    private val directory: String,
    private val workingDirectory: String,
    private val arguments: List<String>,
    private val module: String,
    private val target: String,
    private val startProcess: (ProcessRequest) -> OwnedProcess = host::start,
    private val event: (String, JsonValue.Object) -> Unit,
) {
    private data class Child(
        val process: OwnedProcess, val path: String, val gate: String, val token: Digest,
        val revision: Long, val generation: Long, val composition: Digest, val replacement: Boolean,
        val prepared: ApplicationReady?, var ready: ApplicationReady? = null,
        var activated: Boolean = false, var started: ApplicationStarted? = null,
        var cancelled: Boolean = false, var stale: Boolean = false, var cancelledByRequest: Boolean = false,
    )
    private var active: Child? = null
    private var candidate: Child? = null
    private var handoff: ApplicationReady? = null
    private var handoffRevision: Long? = null
    var retryPreparation: Boolean = false
        private set
    fun consumeRetry(): Boolean = retryPreparation.also { retryPreparation = false }
    val busy: Boolean get() = active != null || candidate != null
    val preparing: Boolean get() = candidate != null
    val hasActive: Boolean get() = active != null
    val preparedForHandoff: ApplicationReady? get() = handoff.takeIf { !busy }
    val candidateComposition: Digest? get() = candidate?.composition

    fun status(): JsonValue.Object = obj(
        "active" to (active?.let { JsonValue.Object(identity(it).values + ("stale" to it.stale.json())) } ?: JsonValue.Null),
        "candidate" to (candidate?.let { child -> JsonValue.Object(identity(child).values + ("phase" to when {
            child.cancelled -> "cancelling"
            child.activated -> "starting"
            child.ready != null && active != null -> "waiting-for-old-cleanup"
            child.ready != null -> "ready"
            else -> "preparing"
        }.json())) } ?: JsonValue.Null),
        "handoffReady" to (preparedForHandoff != null).json())

    private fun cancel(child: Child?) { child?.let { it.cancelled = true; it.process.cancel() } }
    fun cancelAll() { handoff = null; handoffRevision = null; cancel(candidate); cancel(active) }
    fun cancelCandidate() { handoff = null; handoffRevision = null; cancel(candidate) }
    /** Identity belongs to one immutable supervisor directory, never to a later replacement. */
    fun cancelOperation(id: String): Boolean {
        val child = candidate?.takeIf { it.path.substringAfterLast('/') == id }
            ?: active?.takeIf { it.path.substringAfterLast('/') == id } ?: return false
        child.cancelledByRequest = true
        if (child === candidate) { handoff = null; handoffRevision = null; retryPreparation = false }
        cancel(child)
        return true
    }
    fun markStale() {
        active?.takeUnless { it.stale }?.let {
            it.stale = true
            event("application-stale", identity(it))
        }
    }
    fun observeRevision(revision: Long) {
        if (active?.revision != revision) markStale()
        candidate?.takeIf { it.revision != revision }?.let(::invalidateCandidate)
        if (handoffRevision != null && handoffRevision != revision) invalidateHandoff()
    }
    private fun invalidateCandidate(child: Child) {
        // The consumed reload intent survives a transient observation, including after old-owner reap.
        if (!child.cancelled) retryPreparation = true
        cancel(child)
    }
    private fun invalidateHandoff() {
        if (handoff != null) retryPreparation = true
        handoff = null; handoffRevision = null
    }
    private fun identity(child: Child) = obj("operationId" to child.path.substringAfterLast('/').json(), "generation" to child.generation.json(), "revision" to child.revision.json(),
        "composition" to child.composition.toJson(), "module" to module.json(), "target" to target.json(),
        "evidenceDirectory" to child.path.json())

    fun start(runtime: String, composition: Digest, revision: Long, generation: Long,
        replacement: Boolean = false, prepared: ApplicationReady? = null) {
        check(candidate == null && handoff == null) { "Application candidate still owned" }
        prepared?.let { require(it.composition == composition && it.module == module && it.target == target) { "Prepared application scope/composition mismatch" } }
        val path = NativeRunDirectory(directory).path
        val gate = "$path/activation"
        NativeFiles.ensureDirectory(gate)
        val token = Sha256.digest(obj("directory" to path.json(), "revision" to revision.json(), "generation" to generation.json(),
            "composition" to composition.toJson(), "module" to module.json(), "target" to target.json()))
        val command = listOf(runtime, if (prepared == null) "stage-application" else "stage-prepared", "--activation", gate,
            "--token", token.toJson().canonical()) + if (prepared == null) arguments + listOf("--runs", "$path/runs") +
                (if ("--remote-cache" in arguments || "--offline" in arguments) emptyList() else listOf("--offline"))
            else listOf("--run", prepared.preparationDirectory, "--descriptor", prepared.descriptor.toJson().canonical())
        NativeFiles.writeNew("$path/command.json", command.map { it.json() }.json().canonical().encodeToByteArray())
        val process = startProcess(ProcessRequest(command, workingDirectory,
            mapOf("LANG" to "C", "LC_ALL" to "C", "TZ" to "UTC", "KVIK_BOOTSTRAP_OFFLINE" to "1"),
            "$path/stdout.jsonl", "$path/stderr.log", 0))
        candidate = Child(process, path, gate, token, revision, generation, composition, replacement, prepared)
        event("application-preparing", identity(candidate!!))
    }

    private fun readReady(child: Child) {
        if (child.ready != null || NativeFiles.info("${child.gate}/ready.json") == null) return
        val ready = ApplicationReady.parse(NativeFiles.read("${child.gate}/ready.json", APPLICATION_ACTIVATION_MAX_BYTES))
        require(ready.token == child.token && ready.composition == child.composition && ready.module == module && ready.target == target) { "Application readiness identity mismatch" }
        val prepared = child.prepared
        if (prepared != null) require(ready.copy(token = prepared.token) == prepared) { "Prepared application identity changed" }
        else require(ready.preparationDirectory.substringBeforeLast('/') == "${child.path}/runs") { "Preparation escaped owned run directory" }
        child.ready = ready
        event("application-ready", JsonValue.Object(identity(child).values + ("ready" to ready.toJson())))
    }
    private fun readStarted(child: Child) {
        if (child.started != null || NativeFiles.info("${child.gate}/started.json") == null) return
        check(child.activated) { "Application started without authorization" }
        val ready = checkNotNull(child.ready)
        val started = ApplicationStarted.parse(NativeFiles.read("${child.gate}/started.json", APPLICATION_ACTIVATION_MAX_BYTES))
        require(started.token == child.token && started.ready == Sha256.digest(ready.toJson()) &&
            started.executionDirectory.substringBeforeLast('/') == "${ready.preparationDirectory}/application-runs") { "Application start identity mismatch" }
        child.started = started
        event("application-started", JsonValue.Object(identity(child).values + ("started" to started.toJson())))
    }
    private fun poll(child: Child): Boolean {
        val result = child.process.poll() ?: return false
        child.process.close()
        event("application-finished", JsonValue.Object(identity(child).values + mapOf("exitCode" to result.exitCode.json(),
            "cancelled" to result.cancelled.json(), "timedOut" to result.timedOut.json(), "started" to (child.started != null).json(),
            "stale" to child.stale.json())))
        return true
    }
    private fun records(child: Child) {
        try { readReady(child); readStarted(child) }
        catch (failure: Exception) {
            cancel(child)
            event("diagnostic", obj("code" to "KVIK_SESSION_APPLICATION".json(), "message" to (failure.message ?: "Invalid application record").take(512).json()))
        }
    }

    /** Cancellation and terminal polls always precede readiness authorization. */
    fun tick(revision: Long, composition: Digest?, activationAllowed: Boolean, closing: Boolean) {
        observeRevision(revision)
        if (closing) cancelAll()
        if (handoff != null && handoff!!.composition != composition) invalidateHandoff()
        candidate?.takeIf { it.composition != composition }?.let(::invalidateCandidate)
        active?.let { records(it); if (poll(it)) active = null }
        val child = candidate ?: return
        records(child)
        if (poll(child)) {
            if (child.prepared != null && child.started == null && !closing && !child.cancelledByRequest) retryPreparation = true
            candidate = null; return
        }
        if (child.cancelled || closing || child.revision != revision || child.composition != composition) return
        if (child.started != null) {
            check(active == null)
            active = child; candidate = null
            return
        }
        val ready = child.ready ?: return
        if (!activationAllowed || child.activated) return
        if (child.replacement) {
            // Both supervisors are reaped before exec. The replacement revalidates this pointer.
            handoff = ready; handoffRevision = revision
            cancel(active); cancel(child)
            return
        }
        if (active != null) { cancel(active); return }
        try {
            NativeFiles.publish("${child.gate}/activate.json", ApplicationActivation(child.token, Sha256.digest(ready.toJson()))
                .toJson().canonical().encodeToByteArray(), APPLICATION_ACTIVATION_MAX_BYTES)
            child.activated = true
            event("application-activating", identity(child))
        } catch (failure: Exception) {
            cancel(child)
            event("diagnostic", obj("code" to "KVIK_SESSION_APPLICATION".json(), "message" to (failure.message ?: "Cannot authorize application").take(512).json()))
        }
    }
}
