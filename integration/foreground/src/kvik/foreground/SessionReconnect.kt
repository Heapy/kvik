package kvik.foreground

import kvik.cache.NativeFiles
import kvik.core.*

private data class SessionLink(val directory: String, val query: JsonValue.Object) {
    val alive get() = query.values.getValue("ownerAlive").booleanValue()
    val state get() = query.values.getValue("state").objectValue()
    val next get() = state.getValue("nextSessionDirectory").let { if (it == JsonValue.Null) null else it.stringValue() }
}

/** Follow only published, reciprocal handoffs after the previous owner has released its lock. */
private fun sessionChain(anchor: String): List<SessionLink> {
    val links = mutableListOf<SessionLink>()
    var directory = anchor
    while (true) {
        NativeFiles.validateAbsolute(directory)
        require(links.size < 64 && links.none { it.directory.substringAfterLast('/') == directory.substringAfterLast('/') }) {
            "Session handoff cycle or chain exceeds 64 sessions; use a newer session anchor"
        }
        var query = querySession(directory)
        // Once ownership ends it cannot be reacquired in the same directory. Refresh state
        // after observing release, so an earlier failed exec cannot leave a stale next link.
        if (!query.values.getValue("ownerAlive").booleanValue()) query = querySession(directory)
        val link = SessionLink(directory, query)
        require(link.state.getValue("directory").stringValue() == directory) { "Session directory identity mismatch" }
        if (links.isNotEmpty()) {
            val first = sessionEvents(directory, null, 1).values.getValue("events").arrayValue().firstOrNull() ?: break
            val event = first.objectValue()
            val payload = event.getValue("payload").objectValue()
            require(event.getValue("type").stringValue() == "session-started" &&
                payload.getValue("directory").stringValue() == directory &&
                payload.getValue("previousSessionDirectory").stringValue() == links.last().directory) {
                "Session handoff does not link back to its predecessor"
            }
        }
        links += link
        if (link.alive) break
        val next = link.next ?: break
        NativeFiles.validateAbsolute(next)
        require(next.substringBeforeLast('/') == directory.substringBeforeLast('/')) { "Session handoff must stay in the same session root" }
        require(links.none { it.directory == next }) { "Session handoff cycle" }
        // Exec releases the old lock before the successor publishes its first state.
        // A missing successor is a pending handoff, never permission to start an owner.
        if (listOf("state.json", "owner.lock", "session.jsonl").any { NativeFiles.info("$next/$it") == null }) break
        directory = next
    }
    return links
}

internal fun reconnectSession(directory: String): JsonValue.Object {
    val chain = sessionChain(directory)
    val current = chain.last()
    return obj("schemaVersion" to 1.json(), "kind" to "session-reconnect".json(),
        "directory" to current.directory.json(), "ownerAlive" to current.alive.json(),
        "status" to (if (current.alive) "attached" else if (current.next != null) "restarting" else "owner-exited").json(),
        "state" to current.query.values.getValue("state"), "chain" to chain.map { it.directory.json() }.json())
}

/** One bounded page, retaining the original anchor while its session-bound cursor crosses handoffs. */
internal fun followSessionEvents(directory: String, cursor: JsonValue?, limit: Int): JsonValue.Object {
    val chain = sessionChain(directory)
    val sessionId = cursor?.objectValue()?.getValue("sessionId")?.stringValue() ?: chain.first().directory.substringAfterLast('/')
    val index = chain.indexOfFirst { it.directory.substringAfterLast('/') == sessionId }
    require(index >= 0) { "Cursor does not belong to this session handoff chain" }
    val current = chain[index]
    val page = sessionEvents(current.directory, cursor, limit)
    val fields = page.values
    val next = chain.getOrNull(index + 1)
    val advance = next != null && !fields.getValue("ownerAlive").booleanValue() &&
        !fields.getValue("hasMore").booleanValue() && !fields.getValue("pendingTail").booleanValue()
    return JsonValue.Object(fields + mapOf("kind" to "session-follow-events".json(), "directory" to current.directory.json()) +
        if (advance) mapOf("hasMore" to true.json(), "nextCursor" to obj(
            "sessionId" to next!!.directory.substringAfterLast('/').json(), "offset" to 0.json(), "sequence" to 0.json())) else emptyMap())
}
