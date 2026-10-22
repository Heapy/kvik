package kvik.foreground

import kvik.core.*

/** Digest-bound, invocation-local continuation. Never a portable cache entry or a project configuration. */
data class SessionResume(
    val directory: String,
    val previousDirectory: String,
    val workingDirectory: String,
    val arguments: List<String>,
    val jsonControls: Boolean,
    val watchMillis: Long,
    val pending: Operation,
    val watched: Operation,
    val partialInput: List<Byte>,
    val launchBytes: ByteArray,
    val terminalLease: String? = null,
) {
    fun toJson(): JsonValue.Object {
        val value = obj("schemaVersion" to (if (terminalLease == null) 1 else 2).json(), "directory" to directory.json(),
        "previousDirectory" to previousDirectory.json(), "workingDirectory" to workingDirectory.json(),
        "arguments" to arguments.map { it.json() }.json(), "jsonControls" to jsonControls.json(), "watchMillis" to watchMillis.json(),
        "pending" to pending.command.json(), "watched" to watched.command.json(),
        "partialInput" to partialInput.map { (it.toInt() and 255).json() }.json(),
        "launch" to parseJson(launchBytes.decodeToString(throwOnInvalidSequence = true)))
        return if (terminalLease == null) value else JsonValue.Object(value.values + ("terminalLease" to obj(
            "schemaVersion" to 1.json(), "platform" to "macos-arm64".json(), "state" to terminalLease.json())))
    }

    companion object {
        const val MAX_BYTES = 128 * 1024
        fun parse(bytes: ByteArray): SessionResume {
            require(bytes.size <= MAX_BYTES) { "Session handoff exceeds 128 KiB" }
            val value = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
            val version = value.objectValue().getValue("schemaVersion").longValue()
            require(version in 1L..2L) { "Unsupported session continuation version" }
            val fields = value.fields(setOf("schemaVersion", "directory", "previousDirectory",
                "workingDirectory", "arguments", "jsonControls", "watchMillis", "pending", "watched", "partialInput", "launch") +
                if (version == 2L) setOf("terminalLease") else emptySet())
            fun path(key: String) = fields.getValue(key).stringValue().also {
                require(it.startsWith('/') && '\u0000' !in it && it.length <= 4096) { "Invalid handoff path" }
            }
            fun operation(key: String) = Operation.entries.single { it.command == fields.getValue(key).stringValue() }
            val arguments = fields.getValue("arguments").arrayValue().map { it.stringValue() }
            require(arguments.size <= 512 && arguments.all { it.length <= 4096 && '\u0000' !in it }) { "Invalid handoff arguments" }
            val partial = fields.getValue("partialInput").arrayValue().map { it.longValue().also { value -> require(value in 0..255) }.toByte() }
            require(partial.size <= 4096 && partial.none { it.toInt() == 10 }) { "Invalid partial control line" }
            val interval = fields.getValue("watchMillis").longValue().also { require(it in 100..60_000) }
            val launch = fields.getValue("launch").canonical().encodeToByteArray()
            BootstrapLaunch.parse(launch)
            val terminal = if (version == 1L) null else fields.getValue("terminalLease").fields(setOf("schemaVersion", "platform", "state")).let { lease ->
                lease.requireVersion()
                require(lease.getValue("platform").stringValue() == "macos-arm64") { "Unsupported terminal lease platform" }
                require(!fields.getValue("jsonControls").booleanValue() && partial.isEmpty()) { "Terminal lease requires key controls" }
                lease.getValue("state").stringValue().also {
                    require(it.length in 2..4096 && it.length % 2 == 0 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }) { "Invalid terminal lease encoding" }
                }
            }
            return SessionResume(path("directory"), path("previousDirectory"), path("workingDirectory"), arguments,
                fields.getValue("jsonControls").booleanValue(), interval, operation("pending"), operation("watched"), partial, launch, terminal)
        }
    }
}
