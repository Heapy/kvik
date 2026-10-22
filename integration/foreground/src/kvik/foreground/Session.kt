@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.foreground

import kotlinx.cinterop.*
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kvik.execution.platform.*
import platform.posix.*
import kotlin.time.TimeSource

/** Each cleanup boundary runs even when another fails; the original session failure remains primary. */
internal fun closeSessionResources(primary: Throwable?, closeOwned: () -> Unit, closeJournal: () -> Unit, restoreTerminal: () -> Unit) {
    var cleanup: Throwable? = null
    for (close in listOf(closeOwned, closeJournal, restoreTerminal)) try { close() } catch (failure: Throwable) {
        if (primary != null) primary.addSuppressed(failure)
        else if (cleanup == null) cleanup = failure else cleanup.addSuppressed(failure)
    }
    if (primary == null) cleanup?.let { throw it }
}

/** No compiler, source hashing, tool verification, or dependency resolution runs on this loop. */
class ForegroundSession(
    private val host: ProcessHost,
    private val executable: String,
    private val directory: String,
    private val workingDirectory: String,
    private val arguments: List<String>,
    private val snapshotArguments: List<String>,
    private val jsonControls: Boolean,
    private val watchMillis: Long,
    initial: Operation,
    private val bootstrap: BootstrapLaunch? = null,
    bootstrapEnvironment: Map<String, String> = emptyMap(),
    initialWatched: Operation = initial,
    initialPartialInput: List<Byte> = emptyList(),
    private val previousDirectory: String? = null,
    private val resumed: Boolean = false,
    private val restart: ((SessionResume) -> Unit)? = null,
    private val keys: Boolean = false,
    private val inheritedTerminalLease: String? = null,
    private val terminalGuardian: String = "${executable.substringBeforeLast('/')}/guardian",
    private val mode: SessionMode = SessionMode.LIBRARY,
    private val currentComposition: Digest? = bootstrap?.composition,
    private val applicationModule: String? = null,
    private val applicationTarget: String? = null,
    initialIntents: List<Operation>? = null,
    preparedApplication: ApplicationReady? = null,
) {
    init {
        require(!keys || !jsonControls); require(inheritedTerminalLease == null || keys)
        if (mode == SessionMode.APPLICATION) bootstrap?.requireApplicationOwnership()
        require(mode != SessionMode.APPLICATION || kvik_process_ownership_version() == 1) { "Application sessions require recursive process ownership version 1" }
    }
    private val pending = PendingOperation(initial, mode, initialIntents)
    private val applications = if (mode == SessionMode.APPLICATION) ApplicationLifecycle(host, directory, workingDirectory, arguments,
        checkNotNull(applicationModule), checkNotNull(applicationTarget), ::event) else null
    private var resumePrepared = preparedApplication
    private var replacementAttempt: Pair<Digest, Long>? = null
    private var observedRuntime: String? = null
    private var handoffApplication: ApplicationReady? = null
    private var watchedOperation = initialWatched
    private var sequence = 0L
    private var revision = 0L
    private var generation = 0L
    private var observed: String? = null
    private var observedOnce = false
    private var stableObserved: String? = null
    private var watchPending = false
    private var scanner: Child? = null
    private var operation: Child? = null
    private var nextScan = 0L
    private var closing: String? = null
    private var closingAt = 0L
    private val origin = TimeSource.Monotonic.markNow()
    private val output = ArrayDeque<ByteArray>()
    private var outputBytes = 0
    private var outputOffset = 0
    private var line = initialPartialInput.toMutableList()
    private val sources = bootstrap?.let { Recomposition(it, host, directory, bootstrapEnvironment, watchMillis, ::event, if (keys) kvik_foreground_terminal_lease_bytes() else null, mode == SessionMode.APPLICATION) }
    private var sourceGated = false
    private var nextSessionDirectory: String? = null
    private var handoffStarted = 0L
    private var journal = -1
    private var boundary: COpaquePointer? = null
    private data class Child(val process: OwnedProcess, val path: String, val revision: Long, val operation: Operation?, val runtime: String)
    private fun now() = origin.elapsedNow().inWholeMilliseconds

    private fun event(type: String, payload: JsonValue.Object) {
        val bytes = (obj("schemaVersion" to 1.json(), "sessionId" to directory.substringAfterLast('/').json(),
            "sequence" to (++sequence).json(), "type" to type.json(), "payload" to payload).canonical() + "\n").encodeToByteArray()
        // Local session journal; authoritative run journals stay with the child coordinator.
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val count = write(journal, pinned.addressOf(offset), (bytes.size - offset).convert()).toInt()
                if (count < 0 && errno == EINTR) continue
                check(count > 0) { "Cannot retain session event" }
                offset += count
            }
        }
        if (outputBytes + bytes.size > 256 * 1024) {
            if (closing == null) { closing = "output-backpressure"; closingAt = now() }
            return
        }
        output.addLast(bytes); outputBytes += bytes.size
    }
    private fun stop(reason: String) {
        if (closing != null) return
        closing = reason; closingAt = now()
        event("closing", obj("reason" to reason.json()))
    }
    private fun launch(kind: Operation?, runtime: String = executable): Child {
        val path = NativeRunDirectory(directory).path
        val command = if (kind == null) listOf(runtime, "snapshot-query") + snapshotArguments
            else listOf(executable, kind.command) + arguments + listOf("--runs", "$path/runs", "--offline")
        NativeFiles.writeNew("$path/command.json", command.map { it.json() }.json().canonical().encodeToByteArray())
        val process = host.start(ProcessRequest(command, workingDirectory,
            mapOf("LANG" to "C", "LC_ALL" to "C", "TZ" to "UTC", "KVIK_BOOTSTRAP_OFFLINE" to "1"),
            "$path/stdout.jsonl", "$path/stderr.log", if (kind == null) 60_000 else 30 * 60_000))
        return Child(process, path, revision, kind, runtime)
    }
    private fun control(value: Control) {
        if (closing != null) return
        when (value.command) {
            "r", "t" -> {
                val requested = if (value.command == "t") Operation.TEST else if (mode == SessionMode.APPLICATION) Operation.APPLICATION else Operation.BUILD
                if (mode == SessionMode.LIBRARY) watchedOperation = requested
                pending.request(requested)
                if (requested == Operation.APPLICATION) { replacementAttempt = null; applications?.cancelCandidate() }
                watchPending = false
                stableObserved = observed
                sources?.retry()
            }
            "q" -> stop("quit")
        }
        val payload = obj("requestId" to (value.requestId?.json() ?: JsonValue.Null), "command" to value.command.json(),
            "help" to (if (value.command == "?") "r: queue ${if (mode == SessionMode.APPLICATION) "application reload" else "build"}; t: queue fresh tests; q: close; ?: help/status; ${if (keys) "keys are immediate; Ctrl-D: close" else "controls require newline"}; input watching never asserts tool freshness".json() else JsonValue.Null),
            "compositionReady" to (sources?.allowWork ?: true).json(),
            "active" to (operation?.operation?.command?.json() ?: JsonValue.Null), "pending" to (pending.pending?.command?.json() ?: JsonValue.Null))
        event("control", if (applications == null) payload else JsonValue.Object(payload.values + mapOf(
            "application" to applications.status(), "pendingIntents" to pending.snapshot().map { it.command.json() }.json())))
    }
    private fun pollInput() = memScoped {
        if (closing != null) return@memScoped
        val buffer = allocArray<ByteVar>(4096)
        // Leave bytes after a raw quit/EOF key in the terminal for its next owner.
        when (val count = kvik_foreground_read(boundary, buffer, if (keys) 1 else 4096)) {
            -4 -> stop("terminal-failure")
            -3 -> stop("parent-exit")
            -2 -> stop("end-of-input")
            -1 -> stop("input-error")
            else -> if (count > 0) for (index in 0 until count) {
                if (closing != null) break
                val byte = buffer[index]
                if (keys) {
                    if (byte.toInt() == 4) stop("end-of-input")
                    else try { keyControl(byte)?.let(::control) }
                    catch (failure: Exception) { event("diagnostic", obj("code" to "KVIK_SESSION_CONTROL".json(), "message" to (failure.message ?: "Invalid key").json())) }
                    continue
                }
                if (byte.toInt() == 10) {
                    try { control(parseControl(line.toByteArray().decodeToString(throwOnInvalidSequence = true), jsonControls)) }
                    catch (failure: Exception) { event("diagnostic", obj("code" to "KVIK_SESSION_CONTROL".json(), "message" to (failure.message ?: "Invalid control").take(512).json())) }
                    line.clear()
                } else {
                    line.add(byte)
                    if (line.size > 4096) stop("input-limit")
                }
            }
        }
    }
    private fun flushOutput() {
        var remaining = 16 * 1024
        while (output.isNotEmpty() && remaining > 0) {
            val bytes = output.first()
            val count = bytes.usePinned { kvik_foreground_write(boundary, it.addressOf(outputOffset), minOf(bytes.size - outputOffset, remaining)) }
            if (count < 0) { stop("output-error"); return }
            if (count == 0) return
            remaining -= count; outputBytes -= count; outputOffset += count
            if (outputOffset == bytes.size) { output.removeFirst(); outputOffset = 0 }
        }
    }
    private fun completed(child: Child): ProcessResult? {
        val result = child.process.poll() ?: return null
        child.process.close()
        return result
    }
    private fun pollChildren() {
        scanner?.let { child -> completed(child)?.let scannerResult@{ result ->
            scanner = null; nextScan = now() + watchMillis
            if (mode == SessionMode.APPLICATION && result.cancelled) return@scannerResult
            observedRuntime = child.runtime
            var fingerprint: String? = null
            var observationError: String? = if (result.exitCode != 0 || result.timedOut) "Snapshot worker did not complete successfully" else null
            if (result.exitCode == 0 && !result.cancelled && !result.timedOut) {
                try {
                    val fields = parseJson(NativeFiles.read("${child.path}/stdout.jsonl", 8192).decodeToString()).fields(setOf("schemaVersion", "fingerprint"))
                    fields.requireVersion()
                    fingerprint = fields.getValue("fingerprint").let { if (it == JsonValue.Null) null else Digest.fromJson(it).toJson().canonical() }
                } catch (failure: Exception) { observationError = failure.message ?: "Invalid snapshot observation" }
            }
            val firstObservation = !observedOnce
            val changed = observedOnce && fingerprint != observed
            if ((firstObservation || changed) && fingerprint == null) {
                val retained = obj("schemaVersion" to 1.json(), "exitCode" to result.exitCode.json(),
                    "cancelled" to result.cancelled.json(), "timedOut" to result.timedOut.json(),
                    "error" to (observationError?.take(512)?.json() ?: JsonValue.Null))
                NativeFiles.writeNew("${child.path}/observation-result.json", retained.canonical().encodeToByteArray())
                event("diagnostic", obj("code" to "KVIK_SESSION_OBSERVATION".json(),
                    "message" to "Snapshot unavailable: exit=${result.exitCode}, timedOut=${result.timedOut}; evidence=${child.path}".take(512).json()))
            }
            if (!observedOnce || changed) {
                observed = fingerprint; observedOnce = true; revision++
                applications?.observeRevision(revision)
                event("inputs-observed", obj("revision" to revision.json(), "available" to (fingerprint != null).json(),
                    "fingerprint" to (fingerprint?.let(::parseJson) ?: JsonValue.Null), "scope" to "project-inputs-and-tools-document-only".json()))
                if (firstObservation) stableObserved = fingerprint
                if (changed && closing == null) watchPending = true
            } else if (watchPending && closing == null) {
                // A second equal content observation debounces a burst before enqueueing.
                if (fingerprint != stableObserved) pending.request(watchedOperation)
                stableObserved = fingerprint; watchPending = false
            }
            // Retain initial/changed observations; bound disk growth from unchanged polling.
            if (!changed && !firstObservation) NativeFiles.removeTree(child.path)
        } }
        operation?.let { child -> completed(child)?.let { result ->
            operation = null
            event("operation-finished", obj("generation" to generation.json(), "operation" to child.operation!!.command.json(),
                "exitCode" to result.exitCode.json(), "cancelled" to result.cancelled.json(), "timedOut" to result.timedOut.json(),
                "inputsChangedSinceStart" to (child.revision != revision).json(), "evidenceDirectory" to child.path.json()))
            // Trigger a fresh observation; do not label tool inventories/current verification from a source scan.
            nextScan = 0
        } }
    }
    private fun startOperation(kind: Operation) {
        check(kind != Operation.APPLICATION)
        operation = launch(kind); generation++
        event("operation-started", obj("generation" to generation.json(), "operation" to kind.command.json(), "evidenceDirectory" to operation!!.path.json()))
    }

    private inline fun prepareApplication(start: () -> Unit): Boolean = try { start(); true } catch (failure: Exception) {
        event("diagnostic", obj("code" to "KVIK_SESSION_APPLICATION".json(), "message" to (failure.message ?: "Cannot prepare application").take(512).json()))
        false
    }

    private fun tickApplications(gate: Boolean) {
        val apps = checkNotNull(applications)
        val selected = if (gate) null else sources?.selected
        val runtime = if (gate) executable else selected?.first?.runtime
        val composition = if (gate) checkNotNull(currentComposition) else selected?.first?.composition
        // Continue cheap observation under the selected replacement runtime while the old app lives.
        if (closing == null && runtime != null && handoffApplication == null && scanner == null && now() >= nextScan) scanner = launch(null, runtime)
        apps.tick(revision, composition, closing == null && observed != null && observedRuntime == runtime, closing != null)
        if (apps.consumeRetry() && closing == null) {
            pending.request(Operation.APPLICATION); replacementAttempt = null
            event("diagnostic", obj("code" to "KVIK_SESSION_APPLICATION".json(), "message" to "Application preparation became unavailable; queued one fresh preparation".json()))
        }
        handoffApplication = apps.preparedForHandoff
        if (handoffApplication != null && (handoffApplication!!.composition != composition || closing != null)) {
            apps.cancelAll(); handoffApplication = null
        }
        if (handoffApplication != null) { scanner?.process?.cancel(); return }
        if (closing != null || runtime == null || composition == null || observedRuntime != runtime || !observedOnce || operation != null || apps.preparing) return
        if (!gate) {
            val attempt = composition to revision
            if (observed != null && !watchPending && attempt != replacementAttempt) {
                replacementAttempt = attempt
                pending.remove(Operation.APPLICATION)
                generation++
                prepareApplication { apps.start(runtime, composition, revision, generation, replacement = true) }
            }
            return
        }
        resumePrepared?.let { prepared ->
            resumePrepared = null; generation++
            if (!prepareApplication { apps.start(runtime, composition, revision, generation, prepared = prepared) }) pending.request(Operation.APPLICATION)
            return
        }
        // Do not consume the retained reload while a transient observation is unavailable or debouncing.
        if (pending.pending == Operation.APPLICATION && (observed == null || watchPending)) return
        pending.take()?.let { kind ->
            if (kind == Operation.APPLICATION) {
                generation++; prepareApplication { apps.start(runtime, composition, revision, generation) }
            } else startOperation(kind)
        }
    }

    fun run(): Int {
        check(kvik_interrupt_install() == 0) { "Cannot install session interrupt handler" }
        val previousPipe = signal(SIGPIPE, SIG_IGN)
        if (resumed) kvik_foreground_resume_signals()
        var primaryFailure: Throwable? = null
        try {
            boundary = if (keys) {
                if (inheritedTerminalLease != null) kvik_foreground_resume_keys(inheritedTerminalLease) else kvik_foreground_open_keys(terminalGuardian)
            } else kvik_foreground_open()
            if (mode == SessionMode.APPLICATION) {
                check(currentComposition != null && watchedOperation == Operation.APPLICATION)
            }
            check(boundary != null) { "Cannot establish foreground terminal/stream ownership (errno=$errno)" }
            journal = open("$directory/session.jsonl", O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW or O_CLOEXEC, 384)
            check(journal >= 0) { "Cannot create session journal" }
            event("session-started", obj("control" to (if (keys) "keys" else if (jsonControls) "jsonl" else "lines").json(),
                "directory" to directory.json(), "previousSessionDirectory" to (previousDirectory?.json() ?: JsonValue.Null),
                "composition" to (bootstrap?.composition?.toJson() ?: JsonValue.Null),
                "capabilities" to (listOf("library-build", "library-test", "input-watch") + (if (mode == SessionMode.APPLICATION) listOf("application-reload") else emptyList()) + if (bootstrap != null) listOf("source-recomposition") else emptyList()).map { it.json() }.json(),
                "limitations" to ((if (mode == SessionMode.LIBRARY) listOf("no-application-restart") else emptyList()) + listOf("no-continuous-tool-verification") + if (bootstrap == null) listOf("source-recomposition-unsupported-without-bootstrap-context") else emptyList()).map { it.json() }.json()))
            while (true) {
                pollInput()
                if (kvik_interrupt_requested() != 0) stop("signal")
                if (closing != null) { scanner?.process?.cancel(); operation?.process?.cancel(); sources?.cancel(); applications?.cancelAll() }
                sources?.tick(now(), operation == null && scanner == null, closing != null)
                val gate = sources?.allowWork ?: true
                if (!gate) {
                    if (!sourceGated && resumePrepared == null) pending.request(operation?.operation ?: watchedOperation)
                    sourceGated = true
                    if (mode == SessionMode.LIBRARY || sources?.selected == null || scanner?.runtime != sources.selected!!.first.runtime) scanner?.process?.cancel()
                    operation?.process?.cancel()
                    applications?.markStale()
                } else sourceGated = false
                pollChildren()
                if (mode == SessionMode.APPLICATION) tickApplications(gate)
                else if (closing == null && gate) {
                    if (scanner == null && now() >= nextScan) scanner = launch(null)
                    if (operation == null && observedOnce) pending.take()?.let { kind -> startOperation(kind) }
                }
                if (closing == null && operation == null && scanner == null && sources?.ready != null &&
                    (mode == SessionMode.LIBRARY || handoffApplication != null && applications?.busy == false)) {
                    val selected = sources.ready!!
                    if (nextSessionDirectory == null) {
                        handoffStarted = now()
                        nextSessionDirectory = NativeRunDirectory(directory.substringBeforeLast('/')).path
                        event("session-restarting", obj("composition" to selected.first.composition.toJson(),
                            "runtime" to selected.first.runtime.json(), "nextSessionDirectory" to nextSessionDirectory!!.json()))
                    }
                    flushOutput()
                    if (output.isNotEmpty() && now() - handoffStarted >= 1000) stop("output-backpressure")
                    if (closing == null && output.isEmpty() && kvik_interrupt_requested() == 0) {
                        try {
                            check(fsync(journal) == 0) { "Cannot persist restart boundary" }
                            checkNotNull(restart) { "Missing runtime replacement boundary" }.invoke(SessionResume(nextSessionDirectory!!,
                                directory, workingDirectory, arguments, jsonControls, watchMillis, pending.pending ?: watchedOperation,
                                watchedOperation, line.toList(), selected.second, if (keys) {
                                    val encoded = checkNotNull(kvik_foreground_export_terminal(boundary)) { "Cannot preserve terminal lease" }
                                    try { encoded.toKString() } finally { free(encoded) }
                                } else null, mode, pending.snapshot(), handoffApplication, applicationModule, applicationTarget))
                            error("Runtime replacement unexpectedly returned")
                        } catch (failure: Exception) {
                            sources.replacementFailed(); nextSessionDirectory = null; handoffApplication = null
                            // Exec failure leaves no old app: request a fresh preparation on recovery.
                            if (mode == SessionMode.APPLICATION) pending.request(Operation.APPLICATION)
                            event("diagnostic", obj("code" to "KVIK_SESSION_RESTART".json(), "message" to (failure.message ?: "Runtime replacement failed").take(512).json()))
                        }
                    }
                }
                flushOutput()
                if (closing != null && operation == null && scanner == null && sources?.busy != true && applications?.busy != true) break
                usleep(10_000u)
            }
            event("session-finished", obj("reason" to closing!!.json()))
            // Never wait indefinitely for a disconnected or non-reading stdout consumer.
            while (output.isNotEmpty() && now() - closingAt < 1000) { flushOutput(); usleep(10_000u) }
            return if (closing == "signal" || closing == "parent-exit") 130 else if (closing in setOf("quit", "end-of-input")) 0 else 1
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            closeSessionResources(primaryFailure, { host.close() }, {
                if (journal >= 0) {
                    val descriptor = journal; journal = -1
                    val syncResult = fsync(descriptor); val syncError = errno
                    val closeResult = close(descriptor); val closeError = errno
                    check(syncResult == 0 && closeResult == 0) { "Session journal cleanup failed (fsync=$syncError, close=$closeError)" }
                }
            }, {
                val restored = kvik_foreground_close_checked(boundary)
                val restoreError = errno
                kvik_interrupt_restore(); signal(SIGPIPE, previousPipe)
                check(restored == 0) { "Terminal restoration failed (errno=$restoreError)" }
            })
        }
    }
}
