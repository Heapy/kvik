package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.runevidence.committedRunEvents
import kvik.runevidence.runStatistics
import kvik.runevidence.verificationRecordFromJson

internal fun runStatisticsCommand(args: List<String>): JsonValue.Object {
    if (args.size != 2 || args[0] != "--run" || args[1].isBlank()) {
        throw CliFailure("KVIK_RUN_STATS_ARGUMENTS", DiagnosticCategory.CONFIGURATION,
            "Use run-stats --run PATH", ExitStatus.INVALID_CONFIGURATION)
    }
    val directory = projectPath(currentDirectory(), args[1])
    try {
        NativeFiles.ensureDirectory(directory, create = false)
        fun read(name: String) = parseJson(NativeFiles.read("$directory/$name", 16 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true))
        val record = verificationRecordFromJson(read("verification.json"))
        require(record.runId == directory.substringAfterLast('/')) { "Verification run identity does not match directory" }
        val events = committedRunEvents(record, read("completion.json"), NativeFiles.read("$directory/events.jsonl", 16 * 1024 * 1024))
        return runStatistics(record, events)
    } catch (failure: Exception) {
        throw CliFailure("KVIK_RUN_STATS_INVALID", DiagnosticCategory.INFRASTRUCTURE,
            failure.message ?: "Cannot read run statistics", ExitStatus.INCOMPLETE)
    }
}
