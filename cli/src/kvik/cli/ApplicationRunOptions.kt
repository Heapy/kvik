package kvik.cli

import kvik.bsp.BspRunOptions
import kvik.cache.NativeFiles
import kvik.core.*

/** Private foreground transport, bound to the imported model without changing the prepared bundle. */
internal data class ApplicationRunOptions(
    val options: BspRunOptions,
    val configuration: Digest,
    val composition: Digest,
) {
    val arguments get() = options.arguments
    val environmentVariables get() = options.environmentVariables
    val workingDirectory get() = options.workingDirectory
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "options" to options.toJson(),
        "configuration" to configuration.toJson(), "composition" to composition.toJson())
    fun requirePrepared(record: VerificationRecord) {
        require(record.configuration == configuration && record.composition == composition) {
            "Application preparation does not match the imported BSP configuration/composition"
        }
    }
}

internal fun readApplicationRunOptions(path: String): ApplicationRunOptions {
    val fields = parseJson(NativeFiles.read(path, 128 * 1024).decodeToString(throwOnInvalidSequence = true))
        .fields(setOf("schemaVersion", "options", "configuration", "composition"))
    fields.requireVersion()
    return ApplicationRunOptions(BspRunOptions.fromJson(fields.getValue("options")),
        Digest.fromJson(fields.getValue("configuration")), Digest.fromJson(fields.getValue("composition")))
}
