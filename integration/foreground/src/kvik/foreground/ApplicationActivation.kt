package kvik.foreground

import kvik.core.*

const val APPLICATION_ACTIVATION_MAX_BYTES: Int = 8192
const val APPLICATION_ACTIVATION_WAIT_MILLIS: Long = 60_000

/** Invocation-local readiness; executable arguments are deliberately absent. */
data class ApplicationReady(
    val token: Digest,
    val preparationDirectory: String,
    val descriptor: Digest,
    val verification: Digest,
    val composition: Digest,
    val module: String,
    val target: String,
) {
    init {
        activationPath(preparationDirectory)
        require(module.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid application readiness module" }
        require(target in setOf("jvm", "macosArm64", "linuxX64", "js", "wasmJs")) { "Invalid application readiness target" }
        boundedActivation(toJson())
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to 1.json(), "token" to token.toJson(),
        "preparationDirectory" to preparationDirectory.json(), "descriptor" to descriptor.toJson(),
        "verification" to verification.toJson(), "composition" to composition.toJson(), "module" to module.json(), "target" to target.json())
    companion object {
        fun parse(bytes: ByteArray): ApplicationReady {
            val fields = activationDocument(bytes).fields(setOf("schemaVersion", "token", "preparationDirectory", "descriptor", "verification", "composition", "module", "target"))
            fields.requireVersion()
            return ApplicationReady(Digest.fromJson(fields.getValue("token")), fields.getValue("preparationDirectory").stringValue(),
                Digest.fromJson(fields.getValue("descriptor")), Digest.fromJson(fields.getValue("verification")),
                Digest.fromJson(fields.getValue("composition")), fields.getValue("module").stringValue(), fields.getValue("target").stringValue())
        }
    }
}

/** The ready digest covers the exact canonical readiness bytes, including its invocation token. */
data class ApplicationActivation(val token: Digest, val ready: Digest) {
    init { boundedActivation(toJson()) }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to 1.json(), "token" to token.toJson(), "ready" to ready.toJson())
    fun requireReady(value: ApplicationReady) {
        require(token == value.token && ready == Sha256.digest(value.toJson())) { "Application activation does not match readiness" }
    }
    companion object {
        fun parse(bytes: ByteArray): ApplicationActivation {
            val fields = activationDocument(bytes).fields(setOf("schemaVersion", "token", "ready")); fields.requireVersion()
            return ApplicationActivation(Digest.fromJson(fields.getValue("token")), Digest.fromJson(fields.getValue("ready")))
        }
    }
}

/** Published only after the ownership host accepts the application process; not a health assertion. */
data class ApplicationStarted(val token: Digest, val ready: Digest, val executionDirectory: String) {
    init { activationPath(executionDirectory); boundedActivation(toJson()) }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to 1.json(), "token" to token.toJson(), "ready" to ready.toJson(), "executionDirectory" to executionDirectory.json())
    companion object {
        fun parse(bytes: ByteArray): ApplicationStarted {
            val fields = activationDocument(bytes).fields(setOf("schemaVersion", "token", "ready", "executionDirectory")); fields.requireVersion()
            return ApplicationStarted(Digest.fromJson(fields.getValue("token")), Digest.fromJson(fields.getValue("ready")), fields.getValue("executionDirectory").stringValue())
        }
    }
}

private fun activationPath(path: String) {
    require(path.startsWith('/') && path != "/" && path.length <= 4096 && path.none { it.code < 32 }) { "Invalid activation path" }
    require(path.drop(1).split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Activation path must be absolute and normalized" }
}
private fun boundedActivation(value: JsonValue) {
    require(value.canonical().encodeToByteArray().size <= APPLICATION_ACTIVATION_MAX_BYTES) { "Application activation record exceeds 8 KiB" }
}
private fun activationDocument(bytes: ByteArray): JsonValue {
    require(bytes.size <= APPLICATION_ACTIVATION_MAX_BYTES) { "Application activation record exceeds 8 KiB" }
    val value = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
    require(value.canonical().encodeToByteArray().contentEquals(bytes)) { "Application activation record must be canonical" }
    return value
}
