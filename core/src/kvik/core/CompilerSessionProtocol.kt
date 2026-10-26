package kvik.core

const val JVM_COMPILER_SESSION_REQUEST_BYTES: Int = 4 * 1024 * 1024
const val JVM_COMPILER_SESSION_RESULT_BYTES: Int = 64 * 1024

private fun compilerSessionSequence(sequence: Long) {
    require(sequence in 1L..1_000_000L) { "Invalid compiler session sequence" }
}

/** Private foreground-worker envelope. Paths are absolute normalized POSIX paths. */
data class CompilerSessionRequest(val sequence: Long, val directory: String, val request: Digest) {
    init {
        compilerSessionSequence(sequence)
        require(directory.startsWith('/') && directory != "/" && '\\' !in directory && directory.none { it.code < 32 })
        require(directory.drop(1).split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Action directory must be normalized" }
        require(request.sizeBytes in 1..JVM_COMPILER_SESSION_REQUEST_BYTES.toLong())
        directory.json().canonical()
    }
    fun bytes(): ByteArray = obj("schemaVersion" to 1.json(), "sequence" to sequence.json(),
        "directory" to directory.json(), "request" to request.toJson()).canonical().encodeToByteArray().also {
        require(it.size <= JVM_COMPILER_SESSION_REQUEST_BYTES)
    }
    companion object {
        fun parse(bytes: ByteArray): CompilerSessionRequest {
            require(bytes.size in 1..JVM_COMPILER_SESSION_REQUEST_BYTES)
            return parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
                .fields(setOf("schemaVersion", "sequence", "directory", "request")).let {
                    it.requireVersion()
                    CompilerSessionRequest(it.getValue("sequence").longValue(), it.getValue("directory").stringValue(),
                        Digest.fromJson(it.getValue("request")))
                }
        }
    }
}

/** Acknowledged only after the action result and logs have been closed and published. */
data class CompilerSessionResult(val sequence: Long, val request: Digest, val result: Digest, val exitCode: Int, val pid: Long) {
    init {
        compilerSessionSequence(sequence)
        require(request.sizeBytes in 1..JVM_COMPILER_SESSION_REQUEST_BYTES.toLong())
        require(result.sizeBytes > 0 && exitCode in 0..255 && pid > 0)
    }
    fun bytes(): ByteArray = obj("schemaVersion" to 1.json(), "sequence" to sequence.json(), "request" to request.toJson(),
        "result" to result.toJson(), "exitCode" to exitCode.json(), "pid" to pid.json()).canonical().encodeToByteArray().also {
        require(it.size <= JVM_COMPILER_SESSION_RESULT_BYTES)
    }
    companion object {
        fun parse(bytes: ByteArray): CompilerSessionResult {
            require(bytes.size in 1..JVM_COMPILER_SESSION_RESULT_BYTES)
            return parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
                .fields(setOf("schemaVersion", "sequence", "request", "result", "exitCode", "pid")).let {
                    it.requireVersion()
                    val code = it.getValue("exitCode").longValue()
                    require(code in 0..255)
                    CompilerSessionResult(it.getValue("sequence").longValue(), Digest.fromJson(it.getValue("request")),
                        Digest.fromJson(it.getValue("result")), code.toInt(), it.getValue("pid").longValue())
                }
        }
    }
}
