@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.foreground

import kotlinx.cinterop.*
import kvik.cache.NativeFiles
import kvik.cache.CacheFailure
import kvik.core.*
import kvik.files.platform.kvik_files_flock
import platform.posix.*
import kotlin.time.TimeSource

/** Local clients share the owner's private directory, never its workers or process identifiers. */
internal data class SessionRequest(val requestId: String, val command: String, val operationId: String? = null) {
    init {
        require(requestId.length in 1..128 && requestId.all { it.code in 33..126 }) { "Invalid requestId" }
        require(command in setOf("r", "t", "q", "status", "cancel")) { "Unsupported session command" }
        require((command == "cancel") == (operationId != null)) { "Only cancel requires --operation" }
        require(operationId == null || operationId.length in 1..128 && operationId.all { it.isLetterOrDigit() || it in "-_." }) { "Invalid operationId" }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to 1.json(), "requestId" to requestId.json(),
        "command" to command.json(), "operationId" to (operationId?.json() ?: JsonValue.Null))
    val key: String get() = Sha256.digest(requestId.encodeToByteArray()).hash + ".json"
    companion object {
        fun fromJson(value: JsonValue): SessionRequest {
            val fields = value.fields(setOf("schemaVersion", "requestId", "command", "operationId"))
            fields.requireVersion()
            return SessionRequest(fields.getValue("requestId").stringValue(), fields.getValue("command").stringValue(),
                fields.getValue("operationId").let { if (it == JsonValue.Null) null else it.stringValue() })
        }
    }
}

private fun sessionDocument(path: String, limit: Int = 64 * 1024): JsonValue =
    parseJson(NativeFiles.read(path, limit).decodeToString(throwOnInvalidSequence = true))

private fun openOwnerLock(directory: String, create: Boolean, publication: Boolean = false): Int {
    NativeFiles.ensureDirectory(directory, create = false)
    val descriptor = open("$directory/${if (publication) "request-publication.lock" else "owner.lock"}", O_RDWR or O_NOFOLLOW or O_NONBLOCK or O_CLOEXEC or
        (if (create) O_CREAT or (if (publication) 0 else O_EXCL) else 0), 384)
    check(descriptor >= 0) { "Cannot open foreground owner lock" }
    try {
        memScoped {
            val status = alloc<stat>()
            check(fstat(descriptor, status.ptr) == 0 && status.st_mode.toInt() and S_IFMT == S_IFREG) { "Invalid foreground owner lock" }
        }
        return descriptor
    } catch (failure: Throwable) { close(descriptor); throw failure }
}

/** A held lock is an observation of ownership, not a PID which might be reused. */
internal fun sessionOwnerAlive(directory: String): Boolean {
    val descriptor = openOwnerLock(directory, false)
    try {
        while (true) {
            if (kvik_files_flock(descriptor, LOCK_SH or LOCK_NB) == 0) return false
            if (errno == EINTR) continue
            check(errno == EWOULDBLOCK || errno == EAGAIN) { "Cannot observe foreground owner lock" }
            return true
        }
    } finally { close(descriptor) }
}

internal class LiveSessionOwner(private val directory: String) : AutoCloseable {
    private var descriptor = openOwnerLock(directory, true)
    private var lastState: String? = null
    init {
        try {
            check(kvik_files_flock(descriptor, LOCK_EX or LOCK_NB) == 0) { "Foreground session already owned" }
            NativeFiles.ensureDirectory("$directory/requests")
            NativeFiles.ensureDirectory("$directory/replies")
        } catch (failure: Throwable) { close(); throw failure }
    }
    fun update(state: JsonValue.Object) {
        val text = state.canonical()
        if (text == lastState) return
        val stage = NativeFiles.tempDirectory(directory)
        try {
            NativeFiles.writeNew("$stage/state", text.encodeToByteArray())
            check(rename("$stage/state", "$directory/state.json") == 0) { "Cannot publish session state" }
            lastState = text
        } finally { NativeFiles.removeTree(stage) }
    }
    fun poll(handler: (SessionRequest) -> JsonValue.Object) {
        // Clients bound outstanding requests under their publication lock. The owner never waits on it.
        for (name in NativeFiles.names("$directory/requests").filter { it.matches(Regex("[0-9a-f]{64}\\.json")) }.take(8)) {
            val path = "$directory/requests/$name"
            val request = SessionRequest.fromJson(sessionDocument(path, 4096))
            require(request.key == name) { "Session request identity differs from filename" }
            val replyPath = "$directory/replies/$name"
            if (NativeFiles.info(replyPath) == null) {
                val outcome = handler(request)
                val reply = obj("schemaVersion" to 1.json(), "sessionId" to directory.substringAfterLast('/').json(),
                    "request" to request.toJson(), "outcome" to outcome)
                NativeFiles.publish(replyPath, reply.canonical().encodeToByteArray(), 64 * 1024)
            }
            // A client may race the first acknowledgement and publish a conflicting replay.
            // Keep the original immutable reply; that client receives an identity conflict.
            // Such a stale request must never terminate the foreground owner.
            NativeFiles.removeEntry(path)
        }
    }
    override fun close() {
        if (descriptor >= 0) { val previous = descriptor; descriptor = -1; close(previous) }
    }
}

internal fun querySession(directory: String): JsonValue.Object {
    val state = sessionDocument("$directory/state.json")
    require(state.objectValue().getValue("sessionId").stringValue() == directory.substringAfterLast('/')) { "Session state identity mismatch" }
    return obj("schemaVersion" to 1.json(), "kind" to "session-status".json(),
        "ownerAlive" to sessionOwnerAlive(directory).json(), "state" to state)
}

/** Repeating an ID can recover its original acknowledgement; it cannot re-run a command. */
internal fun submitSession(directory: String, request: SessionRequest, waitMillis: Long): JsonValue.Object {
    require(waitMillis in 0..10_000)
    NativeFiles.ensureDirectory(directory, create = false)
    fun reply(): JsonValue? {
        val path = "$directory/replies/${request.key}"
        if (NativeFiles.info(path) == null) return null
        return sessionDocument(path).also {
            val fields = it.fields(setOf("schemaVersion", "sessionId", "request", "outcome")); fields.requireVersion()
            require(fields.getValue("sessionId").stringValue() == directory.substringAfterLast('/') && fields.getValue("request") == request.toJson()) {
                "requestId already belongs to a different command"
            }
        }
    }
    fun result(status: String, response: JsonValue? = null) = obj("schemaVersion" to 1.json(), "kind" to "session-control".json(),
        "sessionId" to directory.substringAfterLast('/').json(), "requestId" to request.requestId.json(),
        "status" to status.json(), "reply" to (response ?: JsonValue.Null))
    reply()?.let { return result("acknowledged", it) }
    if (!sessionOwnerAlive(directory)) return result("owner-exited")
    NativeFiles.ensureDirectory("$directory/requests", create = false)
    NativeFiles.ensureDirectory("$directory/replies", create = false)
    val elapsed = TimeSource.Monotonic.markNow()
    val publicationLock = openOwnerLock(directory, true, publication = true)
    try {
        while (kvik_files_flock(publicationLock, LOCK_EX or LOCK_NB) != 0) {
            if (errno == EINTR) continue
            check(errno == EWOULDBLOCK || errno == EAGAIN) { "Cannot lock session request publication" }
            reply()?.let { return result("acknowledged", it) }
            if (!sessionOwnerAlive(directory)) return reply()?.let { result("acknowledged", it) } ?: result("owner-exited")
            if (elapsed.elapsedNow().inWholeMilliseconds >= waitMillis) return result("busy")
            usleep(10_000u)
        }
        if (reply() == null && sessionOwnerAlive(directory)) {
            val path = "$directory/requests/${request.key}"
            if (NativeFiles.info(path) != null) {
                val queued = try { sessionDocument(path, 4096) } catch (failure: CacheFailure) {
                    // The owner publishes its reply before removing the queue entry, without
                    // taking this client-only lock. A retry may observe that removal here.
                    if (NativeFiles.info(path) != null) throw failure
                    reply() // Still enforce identity if the acknowledgement appeared meanwhile.
                    null
                }
                require(queued == null || queued == request.toJson()) { "requestId already belongs to a different command" }
            } else {
                require(NativeFiles.names("$directory/requests").count { it.endsWith(".json") } < 64) { "Foreground request queue is full" }
                NativeFiles.publish(path, request.toJson().canonical().encodeToByteArray(), 4096)
            }
        }
    } finally { close(publicationLock) }
    while (true) {
        reply()?.let { return result("acknowledged", it) }
        if (!sessionOwnerAlive(directory)) return reply()?.let { result("acknowledged", it) } ?: result("owner-exited")
        if (elapsed.elapsedNow().inWholeMilliseconds >= waitMillis) return result("pending")
        usleep(10_000u)
    }
}

/** Bounded replay of complete lines. A live writer's partial tail never advances the cursor. */
internal fun sessionEvents(directory: String, cursor: JsonValue?, limit: Int): JsonValue.Object = memScoped {
    require(limit in 1..256)
    NativeFiles.ensureDirectory(directory, create = false)
    val sessionId = directory.substringAfterLast('/')
    var offset = 0L
    var sequence = 0L
    if (cursor != null) {
        val fields = cursor.fields(setOf("sessionId", "offset", "sequence"))
        require(fields.getValue("sessionId").stringValue() == sessionId) { "Cursor belongs to another session" }
        offset = fields.getValue("offset").longValue(); sequence = fields.getValue("sequence").longValue()
        require(offset >= 0 && sequence >= 0 && (offset == 0L) == (sequence == 0L)) { "Invalid session cursor" }
    }
    val descriptor = open("$directory/session.jsonl", O_RDONLY or O_NOFOLLOW or O_NONBLOCK or O_CLOEXEC)
    check(descriptor >= 0) { "Cannot open session events" }
    try {
        val info = alloc<stat>()
        check(fstat(descriptor, info.ptr) == 0 && info.st_mode.toInt() and S_IFMT == S_IFREG) { "Invalid session event file" }
        require(offset <= info.st_size) { "Cursor exceeds retained session events" }
        val bytes = ByteArray(minOf(256 * 1024L, info.st_size - offset).toInt())
        var count = 0
        bytes.usePinned { pinned ->
            while (count < bytes.size) {
                val read = pread(descriptor, pinned.addressOf(count), (bytes.size - count).convert(), offset + count).toInt()
                if (read < 0 && errno == EINTR) continue
                check(read > 0) { "Cannot read session events" }
                count += read
            }
        }
        val events = mutableListOf<JsonValue>()
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index].toInt() != 10) continue
            require(index - start <= 64 * 1024) { "Session event exceeds size limit" }
            val value = parseJson(bytes.copyOfRange(start, index).decodeToString(throwOnInvalidSequence = true))
            val fields = value.fields(setOf("schemaVersion", "sessionId", "sequence", "type", "payload")); fields.requireVersion()
            require(fields.getValue("sessionId").stringValue() == sessionId && fields.getValue("sequence").longValue() == sequence + 1) { "Session event cursor or sequence mismatch" }
            sequence++; events += value; start = index + 1
            if (events.size == limit) break
        }
        require(events.isNotEmpty() || bytes.size <= 64 * 1024) { "Session event exceeds size limit" }
        val reachedEnd = offset + bytes.size == info.st_size
        offset += start
        val alive = sessionOwnerAlive(directory)
        // A writer may finish its last line and exit between the initial stat and liveness check.
        if (!alive) check(fstat(descriptor, info.ptr) == 0) { "Cannot refresh retained event size" }
        val pendingTail = reachedEnd && offset + (bytes.size - start) == info.st_size &&
            start < bytes.size && events.size < limit && bytes.drop(start).none { it.toInt() == 10 }
        obj("schemaVersion" to 1.json(), "kind" to "session-events".json(), "sessionId" to sessionId.json(),
            "ownerAlive" to alive.json(), "events" to events.json(), "pendingTail" to pendingTail.json(),
            "status" to (if (pendingTail && !alive) "incomplete" else "complete").json(),
            "hasMore" to (offset < info.st_size).json(),
            "nextCursor" to obj("sessionId" to sessionId.json(), "offset" to offset.json(), "sequence" to sequence.json()))
    } finally { close(descriptor) }
}
