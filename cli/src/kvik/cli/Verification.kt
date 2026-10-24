package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.projectsnapshot.NativeProjectSnapshotter
import kvik.projectsnapshot.SnapshotSelection
import kvik.runevidence.verificationRecordFromJson
import kvik.runevidence.committedRunEvents

internal data class CheckedRun(
    val record: VerificationRecord,
    val metadata: Map<String, JsonValue>,
    val freshness: Freshness,
    val reason: String?,
) {
    val exitStatus: ExitStatus get() = when {
        freshness != Freshness.CURRENT -> ExitStatus.INCOMPLETE
        record.status == VerificationStatus.PASSED -> ExitStatus.SUCCESS
        record.status == VerificationStatus.FAILED -> ExitStatus.FAILED
        record.status == VerificationStatus.CANCELLED -> ExitStatus.CANCELLED
        else -> ExitStatus.INCOMPLETE
    }
}

/** Compare sources and desired tool identities without requiring their installations. Execution
 * must independently verify any physical tool it consumes; this result contains no tool paths.
 */
internal fun checkRun(directory: String): CheckedRun {
    NativeFiles.ensureDirectory(directory, create = false)
    fun document(name: String) = parseJson(NativeFiles.read("$directory/$name", 16 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true))
    val record = verificationRecordFromJson(document("verification.json"))
    val metadata = document("run.json").fields(setOf("schemaVersion", "projectRoot", "configurationPath", "lockPath", "toolsPath", "targets"),
        setOf("inputSelection", "toolTargets", "source", "sourceCache", "sourceRetention"))
    metadata.requireVersion()
    require(record.runId == directory.substringAfterLast('/')) { "Verification run identity does not match directory" }
    var current = Freshness.INDETERMINATE
    var reason: String? = null
    try {
        committedRunEvents(record, document("completion.json"), NativeFiles.read("$directory/events.jsonl", 16 * 1024 * 1024))
        require((record.source != null) == ("source" in metadata) && (record.source != null) == ("sourceCache" in metadata)) {
            "Run source metadata is missing or differs from verification"
        }
        metadata["sourceRetention"]?.let {
            kvik.kodesource.KodeRetentionReference.fromJson(it).requireSource(requireNotNull(record.source).provenance)
        }
        val store = object : ContentStore {
            override fun get(digest: Digest): ByteArray? = null
            override fun put(bytes: ByteArray): Digest = error("A verification query cannot publish content")
        }
        val snapshots = if (record.source == null) NativeProjectSnapshotter(metadata.getValue("projectRoot").stringValue(), store) else null
        fun fingerprint() = record.source?.let { retainedSourceFingerprint(metadata, it, record.sourceClaims) }
            ?: checkNotNull(snapshots).currentFingerprint(LogicalPath(metadata.getValue("configurationPath").stringValue()), metadata.getValue("lockPath").stringValue(),
                selection = metadata["inputSelection"]?.let(SnapshotSelection::fromJson) ?: SnapshotSelection.Project)
        current = freshness(record.inputSnapshot, fingerprint())
        when {
            current == Freshness.STALE -> reason = "Project configuration, dependency lock or declared source/resource inventory changed"
            current == Freshness.INDETERMINATE -> reason = "Inputs changed during observation"
            record.composition != Digest(runtimeComposition, runtimeCompositionSize) -> {
                current = Freshness.STALE; reason = "Runtime composition changed"
            }
            else -> {
                val targets = metadata.getValue("targets").arrayValue().map(JsonValue::stringValue).toSet()
                require(targets.isNotEmpty() && targets.all { it == "jvm" || NativeTarget.find(it) != null || KotlinJsTarget.find(it) != null }) { "Invalid retained target selection" }
                val tools = BuildTools(metadata.getValue("toolsPath").stringValue())
                val toolTargets = metadata["toolTargets"]?.arrayValue()?.map(JsonValue::stringValue)?.toSet() ?: targets
                require(toolTargets.containsAll(targets) && toolTargets.all { it == "jvm" || NativeTarget.find(it) != null || KotlinJsTarget.find(it) != null }) { "Invalid retained tool target selection" }
                val nodeTargets = toolTargets.filter { "$it:node" in record.toolchains }.toSet()
                val desired = tools.desiredIdentities(toolTargets, nodeTargets)
                    .flatMap { (target, identities) -> identities.map { (name, digest) -> "$target:$name" to digest } }.toMap()
                if (desired != record.toolchains) { current = Freshness.STALE; reason = "Desired tool identities changed" }
                else {
                    current = freshness(record.inputSnapshot, fingerprint())
                    if (current != Freshness.CURRENT) reason = "Inputs changed or became unavailable during declaration validation"
                }
            }
        }
    } catch (failure: Exception) {
        current = Freshness.INDETERMINATE
        reason = failure.message ?: "Cannot verify current inputs/tools"
    }
    return CheckedRun(record, metadata, current, reason)
}

/** Read-only freshness query. Cacheable results do not require their compiler/SDK installations. */
internal fun verificationCommand(args: List<String>): ExitStatus {
    require(args.size == 2 && args[0] == "--run") { "Use verification --run PATH" }
    val checked = checkRun(projectPath(currentDirectory(), args[1]))
    println(obj("schemaVersion" to CONTRACT_VERSION.json(), "verification" to checked.record.toJson(),
        "freshness" to checked.freshness.name.lowercase().json(), "reason" to (checked.reason?.json() ?: JsonValue.Null)).canonical())
    return checked.exitStatus
}
