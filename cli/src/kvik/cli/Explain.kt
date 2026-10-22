package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.runevidence.committedRunEvents
import kvik.runevidence.explainRetainedAction
import kvik.runevidence.verificationRecordFromJson

/** Historical queries deliberately do not observe current project sources or verify installed tools. */
internal fun explainCommand(args: List<String>): JsonValue.Object {
    if (args.size != 4 || args[0] != "--run" || args[2] != "--action" || args[3].isBlank()) {
        throw CliFailure("KVIK_EXPLAIN_ARGUMENTS", DiagnosticCategory.CONFIGURATION,
            "Use explain --run PATH --action ACTION_ID", ExitStatus.INVALID_CONFIGURATION)
    }
    val directory = projectPath(currentDirectory(), args[1])
    val id = args[3]
    try {
        NativeFiles.ensureDirectory(directory, create = false)
        fun read(name: String) = parseJson(NativeFiles.read("$directory/$name", 16 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true))
        val record = verificationRecordFromJson(read("verification.json"))
        require(record.runId == directory.substringAfterLast('/')) { "Verification run identity does not match directory" }
        val events = committedRunEvents(record, read("completion.json"), NativeFiles.read("$directory/events.jsonl", 16 * 1024 * 1024))
        val result = record.actions.singleOrNull { it.actionId == id }
            ?: throw CliFailure("KVIK_EXPLAIN_ACTION_MISSING", DiagnosticCategory.CONFIGURATION, "Action is not present in retained verification: $id", ExitStatus.INVALID_CONFIGURATION)
        val action = if (result.key == null) null else read("actions/${Sha256.digest(id.encodeToByteArray()).hash}.json")
        return explainRetainedAction(record, events, id, action)
    } catch (failure: CliFailure) { throw failure
    } catch (failure: Exception) {
        throw CliFailure("KVIK_EXPLAIN_EVIDENCE_INVALID", DiagnosticCategory.INFRASTRUCTURE,
            failure.message ?: "Cannot explain retained run", ExitStatus.INCOMPLETE)
    }
}
