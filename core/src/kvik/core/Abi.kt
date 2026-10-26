package kvik.core

const val ABI_READER_VERSION = "0.18.1"
const val ABI_COMPILER_VERSION = "2.4.20"
const val ABI_MAX_BYTES = 8 * 1024 * 1024

private fun requireAbiScope(module: String, target: String) {
    require(module.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid ABI module" }
    require(target == "jvm" || NativeTarget.find(target) != null || KotlinJsTarget.find(target) != null) { "Unsupported ABI target" }
}

/** Baselines are reviewed source inputs. Dumping never edits them. */
data class AbiSettings(val baseline: LogicalPath) {
    fun toJson(): JsonValue.Object = obj("baseline" to baseline.value.json())
    companion object {
        fun fromJson(value: JsonValue): AbiSettings = AbiSettings(LogicalPath(
            value.fields(setOf("baseline")).getValue("baseline").stringValue()))
    }
}

/** Reads one public compilation artifact: a JVM class directory or a packed KLIB file. */
data class AbiExtraction(val module: String, val target: String, val artifact: LogicalPath) {
    init {
        requireAbiScope(module, target)
        require(target == "jvm" || artifact.value.endsWith(".klib")) { "Non-JVM ABI extraction requires a KLIB" }
    }
    fun toJson(): JsonValue.Object = obj("module" to module.json(), "target" to target.json(), "artifact" to artifact.value.json())
    companion object {
        fun fromJson(value: JsonValue): AbiExtraction {
            val fields = value.fields(setOf("module", "target", "artifact"))
            return AbiExtraction(fields.getValue("module").stringValue(), fields.getValue("target").stringValue(),
                LogicalPath(fields.getValue("artifact").stringValue()))
        }
    }
}

data class AbiWorkerRequest(val requestId: String, val compiler: Digest, val extraction: AbiExtraction, val inputs: TreeManifest) {
    init {
        require(requestId.isNotBlank() && requestId.length <= 256)
        require(inputs.files.isNotEmpty() && inputs.files.size <= 100_000)
        val path = extraction.artifact.value
        require(if (extraction.target == "jvm") inputs.files.all { it.path.value.startsWith("$path/") }
            else inputs.files.size == 1 && inputs.files.single().path == extraction.artifact) { "ABI inputs must contain only the selected compilation artifact" }
        require(inputs.files.all { !it.executable }) { "ABI inputs must be ordinary non-executable compiler outputs" }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "requestId" to requestId.json(),
        "compiler" to compiler.toJson(), "extraction" to extraction.toJson(), "inputs" to inputs.toJson())
    companion object {
        fun fromJson(value: JsonValue): AbiWorkerRequest {
            val fields = value.fields(setOf("schemaVersion", "requestId", "compiler", "extraction", "inputs"))
            fields.requireVersion()
            return AbiWorkerRequest(fields.getValue("requestId").stringValue(), Digest.fromJson(fields.getValue("compiler")),
                AbiExtraction.fromJson(fields.getValue("extraction")), TreeManifest.fromJson(fields.getValue("inputs")))
        }
    }
}

/** Exact public API snapshot, not a claim of source or behavioral compatibility. */
data class AbiSnapshot(val module: String, val target: String, val inputs: Digest, val api: String,
                       val compilerVersion: String = ABI_COMPILER_VERSION) {
    val format: String get() = if (target == "jvm") "bcv-jvm-v1" else "bcv-klib-v1"
    init {
        requireAbiScope(module, target)
        require(isSupportedJvmCompilerVersion(compilerVersion)) { "Unsupported ABI compiler version" }
        require(api.length <= ABI_MAX_BYTES && api.encodeToByteArray(throwOnInvalidSequence = true).size <= ABI_MAX_BYTES)
        require('\r' !in api && '\u0000' !in api && (api.isEmpty() || api.endsWith('\n'))) { "ABI text must use LF with a final newline" }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "module" to module.json(),
        "target" to target.json(), "inputs" to inputs.toJson(), "format" to format.json(),
        "readerVersion" to ABI_READER_VERSION.json(), "compilerVersion" to compilerVersion.json(), "api" to api.json())
    companion object {
        fun fromJson(value: JsonValue): AbiSnapshot {
            val fields = value.fields(setOf("schemaVersion", "module", "target", "inputs", "format", "readerVersion", "compilerVersion", "api"))
            fields.requireVersion()
            require(fields.getValue("readerVersion").stringValue() == ABI_READER_VERSION) { "Unsupported ABI reader version" }
            return AbiSnapshot(fields.getValue("module").stringValue(), fields.getValue("target").stringValue(),
                Digest.fromJson(fields.getValue("inputs")), fields.getValue("api").stringValue(), fields.getValue("compilerVersion").stringValue()).also {
                require(fields.getValue("format").stringValue() == it.format) { "ABI format does not match target" }
            }
        }
    }
}

data class AbiComparison(val module: String, val target: String, val snapshot: LogicalPath, val baseline: LogicalPath) {
    init { requireAbiScope(module, target); require(snapshot != baseline) }
    fun toJson(): JsonValue.Object = obj("module" to module.json(), "target" to target.json(),
        "snapshot" to snapshot.value.json(), "baseline" to baseline.value.json())
    companion object {
        fun fromJson(value: JsonValue): AbiComparison {
            val fields = value.fields(setOf("module", "target", "snapshot", "baseline"))
            return AbiComparison(fields.getValue("module").stringValue(), fields.getValue("target").stringValue(),
                LogicalPath(fields.getValue("snapshot").stringValue()), LogicalPath(fields.getValue("baseline").stringValue()))
        }
    }
}
