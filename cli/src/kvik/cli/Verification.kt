package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.projectsnapshot.NativeProjectSnapshotter
import kvik.projectsnapshot.SnapshotSelection
import kvik.runevidence.verificationRecordFromJson
import kvik.toolchains.ToolComponent

internal data class CheckedRun(
    val record: VerificationRecord,
    val metadata: Map<String, JsonValue>,
    val freshness: Freshness,
    val reason: String?,
    val javaExecutable: String?,
) {
    val exitStatus: ExitStatus get() = when {
        freshness != Freshness.CURRENT -> ExitStatus.INCOMPLETE
        record.status == VerificationStatus.PASSED -> ExitStatus.SUCCESS
        record.status == VerificationStatus.FAILED -> ExitStatus.FAILED
        record.status == VerificationStatus.CANCELLED -> ExitStatus.CANCELLED
        else -> ExitStatus.INCOMPLETE
    }
}

/** Read-only validation shared by queries and prepared application execution. */
internal fun checkRun(directory: String): CheckedRun {
    NativeFiles.ensureDirectory(directory, create = false)
    fun document(name: String) = parseJson(NativeFiles.read("$directory/$name", 16 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true))
    val record = verificationRecordFromJson(document("verification.json"))
    val metadata = document("run.json").fields(setOf("schemaVersion", "projectRoot", "configurationPath", "lockPath", "toolsPath", "targets"), setOf("inputSelection"))
    metadata.requireVersion()
    require(record.runId == directory.substringAfterLast('/')) { "Verification run identity does not match directory" }
    var current = Freshness.INDETERMINATE
    var reason: String? = null
    var javaExecutable: String? = null
    try {
        val completion = document("completion.json").fields(setOf("schemaVersion", "verification", "event", "journal"))
        completion.requireVersion()
        require(Digest.fromJson(completion.getValue("verification")) == Sha256.digest(record.toJson())) { "Verification was not committed completely" }
        val journalBytes = NativeFiles.read("$directory/events.jsonl", 16 * 1024 * 1024)
        require(Digest.fromJson(completion.getValue("journal")) == Sha256.digest(journalBytes)) { "Retained event journal differs from committed evidence" }
        val journal = journalBytes.decodeToString(throwOnInvalidSequence = true)
        require(journal.endsWith('\n')) { "Run journal has an incomplete final event" }
        val lastEvent = parseJson(journal.trimEnd('\n').substringAfterLast('\n'))
        val event = lastEvent.fields(setOf("schemaVersion", "runId", "sequence", "type", "payload"))
        event.requireVersion()
        require(event.getValue("runId").stringValue() == record.runId && event.getValue("sequence").longValue() > 0 &&
            event.getValue("type").stringValue() == "run-finished" && event.getValue("payload") == record.toJson() &&
            Digest.fromJson(completion.getValue("event")) == Sha256.digest(lastEvent)) { "Run completion event does not match retained verification" }
        val store = object : ContentStore {
            override fun get(digest: Digest): ByteArray? = null
            override fun put(bytes: ByteArray): Digest = error("A verification query cannot publish content")
        }
        val snapshots = NativeProjectSnapshotter(metadata.getValue("projectRoot").stringValue(), store)
        fun fingerprint() = snapshots.currentFingerprint(LogicalPath(metadata.getValue("configurationPath").stringValue()), metadata.getValue("lockPath").stringValue(),
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
                require(targets.isNotEmpty() && targets.all { it in setOf("jvm", "macosArm64") }) { "Invalid retained target selection" }
                val tools = BuildTools(metadata.getValue("toolsPath").stringValue())
                val verified = tools.verify(targets)
                    .flatMap { (target, identities) -> identities.map { (name, digest) -> "$target:$name" to digest } }.toMap()
                if (verified != record.toolchains) { current = Freshness.STALE; reason = "Verified tool identities changed" }
                else {
                    current = freshness(record.inputSnapshot, fingerprint())
                    if (current != Freshness.CURRENT) reason = "Inputs changed or became unavailable during tool verification"
                    else if ("jvm" in targets) javaExecutable = "${tools.roots.getValue(ToolComponent.JDK)}/bin/java"
                }
            }
        }
    } catch (failure: Exception) {
        current = Freshness.INDETERMINATE
        reason = failure.message ?: "Cannot verify current inputs/tools"
    }
    return CheckedRun(record, metadata, current, reason, javaExecutable)
}

/** Read-only query; missing tools or unstable inputs never preserve a current claim. */
internal fun verificationCommand(args: List<String>): ExitStatus {
    require(args.size == 2 && args[0] == "--run") { "Use verification --run PATH" }
    val checked = checkRun(projectPath(currentDirectory(), args[1]))
    println(obj("schemaVersion" to CONTRACT_VERSION.json(), "verification" to checked.record.toJson(),
        "freshness" to checked.freshness.name.lowercase().json(), "reason" to (checked.reason?.json() ?: JsonValue.Null)).canonical())
    return checked.exitStatus
}
