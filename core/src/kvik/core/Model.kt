package kvik.core

const val CONTRACT_VERSION: Int = 1

data class Digest(val hash: String, val sizeBytes: Long) {
    init {
        require(hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }) { "Expected lowercase SHA-256" }
        require(sizeBytes >= 0) { "Negative digest size" }
    }
    fun toJson(): JsonValue = obj("hash" to hash.json(), "sizeBytes" to sizeBytes.json())
    companion object {
        fun fromJson(value: JsonValue): Digest = value.fields(setOf("hash", "sizeBytes")).let {
            Digest(it.getValue("hash").stringValue(), it.getValue("sizeBytes").longValue())
        }
    }
}

fun interface Hasher { fun digest(bytes: ByteArray): Digest }
fun Hasher.digest(value: JsonValue): Digest = digest(value.canonical().encodeToByteArray())

/** Relative POSIX path, preserved byte-for-byte: no traversal, links or normalization aliases. */
data class LogicalPath(val value: String) {
    init {
        require(value.isNotEmpty() && '\\' !in value && ':' !in value && value.none { it.code < 32 }) { "Invalid logical path: $value" }
        require(value.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Path must be normalized and relative: $value" }
        value.json().canonical() // Reject invalid Unicode before UTF-8 encoding.
    }
}

data class FileEntry(val path: LogicalPath, val digest: Digest, val executable: Boolean = false) {
    fun toJson(): JsonValue = obj("path" to path.value.json(), "digest" to digest.toJson(), "executable" to executable.json())
    companion object {
        fun fromJson(value: JsonValue): FileEntry = value.fields(setOf("path", "digest", "executable")).let {
            FileEntry(LogicalPath(it.getValue("path").stringValue()), Digest.fromJson(it.getValue("digest")), it.getValue("executable").booleanValue())
        }
    }
}

/** Symlinks and special files are rejected in v1; empty directories are not artifacts. */
data class TreeManifest(val files: List<FileEntry>) {
    init {
        require(files.map { it.path }.distinct().size == files.size) { "Duplicate artifact path" }
        val paths = files.map { it.path.value }.toSet()
        for (path in paths) {
            val components = path.split('/')
            require((1 until components.size).none { components.take(it).joinToString("/") in paths }) { "File/directory collision: $path" }
        }
    }
    fun toJson(): JsonValue = obj("schemaVersion" to CONTRACT_VERSION.json(), "files" to files.sortedBy { it.path.value }.map { it.toJson() }.json())
    companion object {
        fun fromJson(value: JsonValue): TreeManifest = value.fields(setOf("schemaVersion", "files")).let {
            it.requireVersion()
            TreeManifest(it.getValue("files").arrayValue().map(FileEntry::fromJson))
        }
    }
}

enum class CachePolicy { NEVER, LOCAL, REMOTE }
enum class ActionKind { COMPILE, GENERATE, LINK, PACKAGE, VERIFY, TEST, SCRIPT, PUBLISH, BENCHMARK, SERVER }

/** Resolved inputs include artifacts from prerequisites. Construct only after they succeed. */
data class ActionSpec(
    val implementation: String,
    val composition: Digest,
    val kind: ActionKind,
    val platform: Map<String, String>,
    val target: String,
    val toolchains: Map<String, Digest>,
    val inputs: TreeManifest,
    val outputs: List<LogicalPath>,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val parameters: JsonValue.Object = obj(),
    val timeoutMillis: Long = 120_000,
    val cachePolicy: CachePolicy = CachePolicy.LOCAL,
) {
    init {
        require(implementation.isNotBlank() && target.isNotBlank() && platform.isNotEmpty() && toolchains.isNotEmpty())
        require(timeoutMillis > 0)
        require(outputs.distinct().size == outputs.size) { "Duplicate output path" }
        require(environment.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) })
        require(arguments.none { '\u0000' in it } && environment.values.none { '\u0000' in it })
        if (kind in setOf(ActionKind.TEST, ActionKind.SCRIPT, ActionKind.PUBLISH, ActionKind.BENCHMARK, ActionKind.SERVER)) {
            require(cachePolicy == CachePolicy.NEVER) { "$kind execution cannot be cached in v1" }
        }
    }

    fun toJson(): JsonValue = obj(
        "schemaVersion" to CONTRACT_VERSION.json(), "implementation" to implementation.json(),
        "composition" to composition.toJson(), "kind" to kind.name.lowercase().json(),
        "platform" to JsonValue.Object(platform.mapValues { it.value.json() }), "target" to target.json(),
        "toolchains" to JsonValue.Object(toolchains.mapValues { it.value.toJson() }), "inputs" to inputs.toJson(),
        "outputs" to outputs.sortedBy { it.value }.map { it.value.json() }.json(),
        "arguments" to arguments.map { it.json() }.json(), "environment" to JsonValue.Object(environment.mapValues { it.value.json() }),
        "parameters" to parameters, "timeoutMillis" to timeoutMillis.json(), "cachePolicy" to cachePolicy.name.lowercase().json(),
    )
    fun key(hasher: Hasher): Digest = hasher.digest(toJson())
}

enum class ActionStatus { EXECUTED, LOCAL_HIT, REMOTE_HIT, FAILED, CANCELLED, BLOCKED }
data class ActionResult(
    val actionId: String,
    val key: Digest?,
    val status: ActionStatus,
    val outputs: TreeManifest = TreeManifest(emptyList()),
    val exitCode: Int? = null,
    val diagnostic: String? = null,
    val processUsage: List<ProcessUsage> = emptyList(),
) {
    val successful: Boolean get() = status in setOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)
    init {
        require(!successful || (exitCode == 0 && key != null)) { "Successful action requires an identity and exit code zero" }
        require(processUsage.size <= 10000)
        require(processUsage.isEmpty() || status !in setOf(ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT, ActionStatus.BLOCKED)) {
            "Cached or blocked results cannot report process execution"
        }
    }
    fun toJson(): JsonValue = obj("actionId" to actionId.json(), "key" to (key?.toJson() ?: JsonValue.Null),
        "status" to status.name.lowercase().json(), "outputs" to outputs.toJson(),
        "exitCode" to (exitCode?.json() ?: JsonValue.Null), "diagnostic" to (diagnostic?.json() ?: JsonValue.Null)).let {
            if (processUsage.isEmpty()) it else JsonValue.Object(it.values + ("processUsage" to processUsage.map { usage -> usage.toJson() }.json()))
        }
}

/** Implementations resolve only declared predecessor outputs, never mutable build directories. */
data class ActionNode(val id: String, val dependencies: Set<String>, val resolve: (Map<String, ActionResult>) -> ActionSpec,
                      val outputContract: ActionOutputContract?) {
    constructor(id: String, dependencies: Set<String>, resolve: (Map<String, ActionResult>) -> ActionSpec) :
        this(id, dependencies, resolve, null)
    constructor(id: String, dependencies: Set<String>, outputContract: ActionOutputContract,
                resolve: (Map<String, ActionResult>) -> ActionSpec) :
        this(id, dependencies, { results -> resolve(results).also(outputContract::requireMatches) }, outputContract)
}

/** maxParallel bounds both active actions and their total CPU slot reservations. */
data class ResourceBudget(val maxParallel: Int, val memoryMiB: Long) {
    init { require(maxParallel >= 1 && memoryMiB >= 1) }
}

data class ResourceRequest(val memoryMiB: Long = 512, val exclusive: Set<String> = emptySet(), val cpuSlots: Int = 1) {
    init { require(memoryMiB >= 1 && cpuSlots >= 1) }
}

/** Native workers poll without blocking the scheduler; close reaps all owned processes. */
interface RunningAction : AutoCloseable {
    fun poll(): ActionResult?
    fun cancel()
}

interface ActionExecutor {
    fun resources(action: ActionSpec): ResourceRequest
    fun start(runId: String, actionId: String, action: ActionSpec): RunningAction
}

/** An owned process: zero timeout means no deadline; positive values bound its lifetime in milliseconds.
 * Cancellation, owner loss and descendant cleanup still apply without a deadline. Action deadlines
 * are separate: [ActionSpec.timeoutMillis] must remain strictly positive.
 */
data class ProcessRequest(
    val arguments: List<String>,
    val workingDirectory: String,
    val environment: Map<String, String>,
    val stdoutFile: String,
    val stderrFile: String,
    val timeoutMillis: Long,
) {
    init { require(arguments.isNotEmpty() && timeoutMillis >= 0) }
}

/** Fixed platform boundary vocabulary; no executable-controlled diagnostic text. */
enum class ProcessSetupStage(val platformCode: Int) {
    TIMEOUT_ARGUMENT(1), BARRIER_PIPE(2), BARRIER_READ_DUP(3), BARRIER_WRITE_DUP(4),
    BARRIER_STAT(5), BARRIER_READ_FLAGS(6), BARRIER_WRITE_FLAGS(7), BARRIER_ENVIRONMENT(8),
    STATUS_PIPE(9), STATUS_READ_DUP(10), STATUS_WRITE_DUP(11), STATUS_READ_FLAGS(12),
    FORK(13), PARENT_GROUP(14), CHILD_GROUP(15), BARRIER_BIND(16), WORKING_DIRECTORY(17),
    STDIN_OPEN(18), STDOUT_OPEN(19), STDERR_OPEN(20), STDIN_BIND(21), STDOUT_BIND(22), STDERR_BIND(23), EXEC(24),
}
data class ProcessSetupFailure(val stage: ProcessSetupStage, val errno: Int) {
    init { require(errno > 0) { "Setup failure requires a positive errno" } }
    fun toJson(): JsonValue.Object = obj("stage" to stage.name.lowercase().json(), "errno" to errno.json())
}
data class ProcessResult(
    val exitCode: Int,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val setupFailure: ProcessSetupFailure? = null,
    val usage: ProcessUsage? = null,
) {
    init {
        if (setupFailure != null) {
            require(!(timedOut && cancelled))
            require(exitCode == when {
                cancelled -> 130
                timedOut -> 124
                setupFailure.stage == ProcessSetupStage.EXEC -> 127
                else -> 125
            }) { "Setup failure and process outcome disagree" }
        }
    }
}
interface OwnedProcess : AutoCloseable {
    fun poll(): ProcessResult?
    fun cancel()
}
interface ProcessHost : AutoCloseable { fun start(request: ProcessRequest): OwnedProcess }

/** Store operations validate digests and publish immutable content atomically. */
interface ContentStore {
    fun put(bytes: ByteArray): Digest
    fun get(digest: Digest): ByteArray?
}

data class CachedAction(val key: Digest, val outputs: TreeManifest)
interface ActionCache {
    fun lookup(key: Digest): CachedAction?
    fun publish(action: CachedAction)
}

interface BuildPlugin {
    val id: String
    val apiVersion: Int
    val configurationSchema: JsonValue.Object
    fun actions(project: JsonValue.Object): List<ActionNode> = emptyList()
}
