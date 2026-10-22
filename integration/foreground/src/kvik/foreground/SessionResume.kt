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
) {
    fun toJson(): JsonValue.Object = obj("schemaVersion" to 1.json(), "directory" to directory.json(),
        "previousDirectory" to previousDirectory.json(), "workingDirectory" to workingDirectory.json(),
        "arguments" to arguments.map { it.json() }.json(), "jsonControls" to jsonControls.json(), "watchMillis" to watchMillis.json(),
        "pending" to pending.command.json(), "watched" to watched.command.json(),
        "partialInput" to partialInput.map { (it.toInt() and 255).json() }.json(),
        "launch" to parseJson(launchBytes.decodeToString(throwOnInvalidSequence = true)))

    companion object {
        const val MAX_BYTES = 128 * 1024
        fun parse(bytes: ByteArray): SessionResume {
            require(bytes.size <= MAX_BYTES) { "Session handoff exceeds 128 KiB" }
            val fields = parseJson(bytes.decodeToString(throwOnInvalidSequence = true)).fields(setOf("schemaVersion", "directory", "previousDirectory",
                "workingDirectory", "arguments", "jsonControls", "watchMillis", "pending", "watched", "partialInput", "launch"))
            fields.requireVersion()
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
            return SessionResume(path("directory"), path("previousDirectory"), path("workingDirectory"), arguments,
                fields.getValue("jsonControls").booleanValue(), interval, operation("pending"), operation("watched"), partial, launch)
        }
    }
}
