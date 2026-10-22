package kvik.foreground

import kvik.core.*

/** Preparation only: active application supervisors remain independently owned processes. */
interface SessionOperations : AutoCloseable {
    fun start(request: ProcessRequest): OwnedProcess
    /** Cancel and poll every retained worker; false until ownership cleanup is acknowledged. */
    fun drain(): Boolean
}

internal const val COORDINATOR_REQUEST_BYTES = 64 * 1024
internal const val COORDINATOR_RESULT_BYTES = 8 * 1024

internal data class CoordinatorRequest(val sequence: Long, val operation: String, val arguments: List<String>, val directory: String) {
    init {
        require(sequence in 1..1_000_000)
        require(operation in setOf("build", "test", "prepare-application"))
        require(arguments.size <= 512 && arguments.all { '\u0000' !in it && it.length <= 8192 })
        require(directory.startsWith('/') && directory.drop(1).split('/').none { it.isEmpty() || it == "." || it == ".." } && '\u0000' !in directory)
    }
    fun toJson() = obj("schemaVersion" to 1.json(), "sequence" to sequence.json(), "operation" to operation.json(),
        "arguments" to arguments.map { it.json() }.json(), "directory" to directory.json())
    fun bytes(): ByteArray = toJson().canonical().encodeToByteArray().also { require(it.size <= COORDINATOR_REQUEST_BYTES) }
    companion object {
        fun parse(bytes: ByteArray): CoordinatorRequest {
            require(bytes.size <= COORDINATOR_REQUEST_BYTES)
            val value = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
            val f = value.fields(setOf("schemaVersion", "sequence", "operation", "arguments", "directory")); f.requireVersion()
            val result = CoordinatorRequest(f.getValue("sequence").longValue(), f.getValue("operation").stringValue(),
                f.getValue("arguments").arrayValue().map { it.stringValue() }, f.getValue("directory").stringValue())
            require(result.bytes().contentEquals(bytes)) { "Noncanonical coordinator request" }
            return result
        }
    }
}

internal data class CoordinatorResult(val request: Digest, val exitCode: Int, val preparationDirectory: String?,
    val descriptor: Digest?, val modelReused: Boolean, val graphReused: Boolean) {
    init {
        require(exitCode in 0..255)
        require((preparationDirectory == null) == (descriptor == null))
        require(preparationDirectory == null || exitCode == 0)
    }
    fun toJson() = obj("schemaVersion" to 1.json(), "request" to request.toJson(), "exitCode" to exitCode.json(),
        "preparation" to (preparationDirectory?.let { obj("directory" to it.json(), "descriptor" to descriptor!!.toJson()) } ?: JsonValue.Null),
        "modelReused" to modelReused.json(), "graphReused" to graphReused.json())
    fun bytes() = toJson().canonical().encodeToByteArray().also { require(it.size <= COORDINATOR_RESULT_BYTES) }
    companion object {
        fun parse(bytes: ByteArray): CoordinatorResult {
            require(bytes.size <= COORDINATOR_RESULT_BYTES)
            val f = parseJson(bytes.decodeToString(throwOnInvalidSequence = true)).fields(
                setOf("schemaVersion", "request", "exitCode", "preparation", "modelReused", "graphReused")); f.requireVersion()
            val p = f.getValue("preparation").takeUnless { it == JsonValue.Null }?.fields(setOf("directory", "descriptor"))
            val exit = f.getValue("exitCode").longValue(); require(exit in 0..255)
            val result = CoordinatorResult(Digest.fromJson(f.getValue("request")), exit.toInt(), p?.getValue("directory")?.stringValue(),
                p?.let { Digest.fromJson(it.getValue("descriptor")) }, f.getValue("modelReused").booleanValue(), f.getValue("graphReused").booleanValue())
            require(result.bytes().contentEquals(bytes)) { "Noncanonical coordinator result" }
            return result
        }
    }
}
