@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.foreground

import kotlinx.cinterop.*
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.NativeRunDirectory
import kvik.execution.platform.*
import platform.posix.*
import kotlin.time.TimeSource

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
) {
    private val pending = PendingOperation(initial)
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
    private val sources = bootstrap?.let { Recomposition(it, host, directory, bootstrapEnvironment, watchMillis, ::event) }
    private var sourceGated = false
    private var nextSessionDirectory: String? = null
    private var handoffStarted = 0L
    private var journal = -1
    private var boundary: COpaquePointer? = null
    private data class Child(val process: OwnedProcess, val path: String, val revision: Long, val operation: Operation?)
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
    private fun launch(kind: Operation?): Child {
        val path = NativeRunDirectory(directory).path
        val command = if (kind == null) listOf(executable, "snapshot-query") + snapshotArguments
            else listOf(executable, kind.command) + arguments + listOf("--runs", "$path/runs", "--offline")
        NativeFiles.writeNew("$path/command.json", command.map { it.json() }.json().canonical().encodeToByteArray())
        val process = host.start(ProcessRequest(command, workingDirectory,
            mapOf("LANG" to "C", "LC_ALL" to "C", "TZ" to "UTC", "KVIK_BOOTSTRAP_OFFLINE" to "1"),
            "$path/stdout.jsonl", "$path/stderr.log", if (kind == null) 60_000 else 30 * 60_000))
        return Child(process, path, revision, kind)
    }
    private fun control(value: Control) {
        if (closing != null) return
        when (value.command) {
            "r", "t" -> {
                watchedOperation = if (value.command == "t") Operation.TEST else Operation.BUILD
                pending.request(watchedOperation)
                watchPending = false
                stableObserved = observed
                sources?.retry()
            }
            "q" -> stop("quit")
        }
        event("control", obj("requestId" to (value.requestId?.json() ?: JsonValue.Null), "command" to value.command.json(),
            "help" to (if (value.command == "?") "r: queue build; t: queue fresh tests; q: close; status: active/pending; input watching never asserts tool freshness".json() else JsonValue.Null),
            "compositionReady" to (sources?.allowWork ?: true).json(),
            "active" to (operation?.operation?.command?.json() ?: JsonValue.Null), "pending" to (pending.pending?.command?.json() ?: JsonValue.Null)))
    }
    private fun pollInput() = memScoped {
        val buffer = allocArray<ByteVar>(4096)
        when (val count = kvik_foreground_read(boundary, buffer, 4096)) {
            -3 -> stop("parent-exit")
            -2 -> stop("end-of-input")
            -1 -> stop("input-error")
            else -> if (count > 0) for (index in 0 until count) {
                if (closing != null) break
                val byte = buffer[index]
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
        scanner?.let { child -> completed(child)?.let { result ->
            scanner = null; nextScan = now() + watchMillis
            var fingerprint: String? = null
            if (result.exitCode == 0 && !result.cancelled && !result.timedOut) {
                try {
                    val fields = parseJson(NativeFiles.read("${child.path}/stdout.jsonl", 8192).decodeToString()).fields(setOf("schemaVersion", "fingerprint"))
                    fields.requireVersion()
                    fingerprint = fields.getValue("fingerprint").let { if (it == JsonValue.Null) null else Digest.fromJson(it).toJson().canonical() }
                } catch (_: Exception) { /* Invalid/unstable observations are never a current claim. */ }
            }
            val firstObservation = !observedOnce
            val changed = observedOnce && fingerprint != observed
            if (!observedOnce || changed) {
                observed = fingerprint; observedOnce = true; revision++
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
    fun run(): Int {
        check(kvik_interrupt_install() == 0) { "Cannot install session interrupt handler" }
        val previousPipe = signal(SIGPIPE, SIG_IGN)
        if (resumed) kvik_foreground_resume_signals()
        try {
            boundary = kvik_foreground_open()
            check(boundary != null) { "Cannot establish foreground parent/stream ownership" }
            journal = open("$directory/session.jsonl", O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW or O_CLOEXEC, 384)
            check(journal >= 0) { "Cannot create session journal" }
            event("session-started", obj("control" to (if (jsonControls) "jsonl" else "lines").json(),
                "directory" to directory.json(), "previousSessionDirectory" to (previousDirectory?.json() ?: JsonValue.Null),
                "composition" to (bootstrap?.composition?.toJson() ?: JsonValue.Null),
                "capabilities" to (listOf("library-build", "library-test", "input-watch") + if (bootstrap != null) listOf("source-recomposition") else emptyList()).map { it.json() }.json(),
                "limitations" to (listOf("no-application-restart", "no-continuous-tool-verification") + if (bootstrap == null) listOf("source-recomposition-unsupported-without-bootstrap-context") else emptyList()).map { it.json() }.json()))
            while (true) {
                pollInput()
                if (kvik_interrupt_requested() != 0) stop("signal")
                if (closing != null) { scanner?.process?.cancel(); operation?.process?.cancel(); sources?.cancel() }
                sources?.tick(now(), operation == null && scanner == null, closing != null)
                val gate = sources?.allowWork ?: true
                if (!gate) {
                    if (!sourceGated) pending.request(operation?.operation ?: watchedOperation)
                    sourceGated = true
                    scanner?.process?.cancel(); operation?.process?.cancel()
                } else sourceGated = false
                pollChildren()
                if (closing == null && gate) {
                    if (scanner == null && now() >= nextScan) scanner = launch(null)
                    if (operation == null && observedOnce) pending.take()?.let { kind ->
                        operation = launch(kind); generation++
                        event("operation-started", obj("generation" to generation.json(), "operation" to kind.command.json(), "evidenceDirectory" to operation!!.path.json()))
                    }
                }
                if (closing == null && operation == null && scanner == null && sources?.ready != null) {
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
                                watchedOperation, line.toList(), selected.second))
                            error("Runtime replacement unexpectedly returned")
                        } catch (failure: Exception) {
                            sources.replacementFailed(); nextSessionDirectory = null
                            event("diagnostic", obj("code" to "KVIK_SESSION_RESTART".json(), "message" to (failure.message ?: "Runtime replacement failed").take(512).json()))
                        }
                    }
                }
                flushOutput()
                if (closing != null && operation == null && scanner == null && sources?.busy != true) break
                usleep(10_000u)
            }
            event("session-finished", obj("reason" to closing!!.json()))
            // Never wait indefinitely for a disconnected or non-reading stdout consumer.
            while (output.isNotEmpty() && now() - closingAt < 1000) { flushOutput(); usleep(10_000u) }
            return if (closing == "signal" || closing == "parent-exit") 130 else if (closing in setOf("quit", "end-of-input")) 0 else 1
        } finally {
            host.close()
            if (journal >= 0) { fsync(journal); close(journal) }
            kvik_foreground_close(boundary); kvik_interrupt_restore(); signal(SIGPIPE, previousPipe)
        }
    }
}
