@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.platform.*
import kvik.runevidence.*

/** Publication owns a fresh attempt directory; retained test execution is never repeated. */
internal fun testPublishCommand(args: List<String>): ExitStatus {
    fun arguments(message: String): Nothing = throw CliFailure("KVIK_TEST_PUBLISH_ARGUMENTS", DiagnosticCategory.CONFIGURATION,
        "$message. Use test-publish --run PATH ... --repository OWNER/REPO --sha SHA [--expect CHECK ...] --output NEW_DIRECTORY",
        ExitStatus.INVALID_CONFIGURATION)
    val paths = linkedSetOf<String>()
    val expected = linkedSetOf<String>()
    val options = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        if (flag !in setOf("--run", "--expect", "--repository", "--sha", "--name", "--api-url", "--token-env", "--output"))
            arguments("Unknown option")
        val value = args.getOrNull(index++) ?: arguments("Missing option value")
        if (value.isBlank() || value.startsWith("--")) arguments("Empty option value")
        when (flag) {
            "--run" -> if (!paths.add(projectPath(currentDirectory(), value))) arguments("Repeated run")
            "--expect" -> if (!expected.add(value)) arguments("Repeated expected check")
            else -> if (options.put(flag, value) != null) arguments("Repeated option")
        }
    }
    if (paths.size !in 1..100) arguments("Select between one and 100 runs")
    if (paths.size > 1 && expected.isEmpty()) arguments("Multiple runs require an explicit --expect matrix")
    if (expected.size > 1000) arguments("Expected matrix exceeds 1000 checks")
    val destination = githubCheckDestination(options, "Kvik tests", ::arguments)
    val output = projectPath(currentDirectory(), options["--output"] ?: arguments("Missing --output"))
    if (paths.any { pathsPhysicallyOverlap(it, output) }) arguments("Output must be outside retained runs")
    if (NativeFiles.info(output) != null) arguments("Output already exists; use a new attempt directory")
    check(kvik_interrupt_install() == 0) { "Cannot install publication interruption handlers" }
    fun checkpoint() {
        if (kvik_interrupt_requested() != 0) throw CliFailure("KVIK_TEST_PUBLISH_CANCELLED", DiagnosticCategory.CANCELLATION,
            "Publication cancelled; inspect the attempt directory before retrying", ExitStatus.CANCELLED)
    }
    try {
        val artifacts = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        fun retain(path: String, bytes: ByteArray) {
            LogicalPath(path)
            require(path !in artifacts) { "Duplicate retained path" }
            totalBytes += bytes.size
            require(totalBytes <= 256L * 1024 * 1024) { "Publication inputs exceed 256 MiB" }
            artifacts[path] = bytes
        }
        val reports = paths.map { directory ->
            checkpoint()
            fun read(name: String) = NativeFiles.read("$directory/$name", 16 * 1024 * 1024)
            val verification = read("verification.json")
            val completion = read("completion.json")
            val journal = read("events.jsonl")
            val record = verificationRecordFromJson(parseJson(verification.decodeToString(throwOnInvalidSequence = true)))
            require(record.runId == directory.substringAfterLast('/')) { "Run directory identity differs" }
            require(Sha256.digest(verification) == Sha256.digest(record.toJson())) { "Noncanonical retained verification" }
            val events = committedRunEvents(record, parseJson(completion.decodeToString(throwOnInvalidSequence = true)), journal)
            for ((file, bytes) in listOf("verification.json" to verification, "completion.json" to completion, "events.jsonl" to journal))
                retain("runs/${record.runId}/$file", bytes)
            val report = testReport(record, events)
            // Log paths are relative to their source run. Keep that namespace in the retry
            // bundle, deduplicating shared action logs across tests and attempts.
            val logs = report.scopes.flatMap { scope -> scope.tests.flatMap { test ->
                test.details?.attempts.orEmpty().flatMap { it.logs }
            } }.groupBy { it.path }
            for ((path, references) in logs.entries.sortedBy { it.key.value }) {
                checkpoint()
                val digests = references.map { it.digest }.distinct()
                require(digests.size == 1) { "Conflicting retained test log identities" }
                val digest = digests.single()
                require(digest.sizeBytes <= 64L * 1024 * 1024 && totalBytes + digest.sizeBytes <= 256L * 1024 * 1024) {
                    "Retained test logs exceed publication limits"
                }
                val source = "$directory/${path.value}"
                NativeFiles.ensureDirectory(source.substringBeforeLast('/'), create = false)
                val bytes = NativeFiles.read(source, 64 * 1024 * 1024)
                require(Sha256.digest(bytes) == digest) { "Retained test log digest differs" }
                retain("runs/${record.runId}/${path.value}", bytes)
            }
            report
        }
        val baseline = reports.first().provenance.values
        val matrix = expected.ifEmpty { reports.single().expectedChecks.toSet() }
        val scope = TestAggregationScope(Digest.fromJson(baseline.getValue("inputSnapshot")), Digest.fromJson(baseline.getValue("configuration")),
            Digest.fromJson(baseline.getValue("dependencyLock")), Digest.fromJson(baseline.getValue("composition")), matrix)
        val aggregate = aggregateTestReports(scope, reports.map { TestReportContribution(Sha256.digest(it.toJson()), it) })
        val reportBytes = aggregate.toJson().canonical().encodeToByteArray()
        require(reportBytes.size <= 64 * 1024 * 1024) { "Report exceeds 64 MiB" }
        retain("aggregate.json", reportBytes)
        val reportDigest = Sha256.digest(reportBytes)
        val counts = aggregate.uniqueCounts
        val summary = "Retained test result: ${aggregate.status.name.lowercase()}. Complete scopes: ${aggregate.complete}.\n\n" +
            "Executed: ${counts.executed}; failed: ${counts.failed}; skipped: ${counts.skipped}; discovered: ${counts.discovered ?: "unknown"}.\n" +
            "Known attempts: ${aggregate.attempts.known}; failed attempts: ${aggregate.attempts.failed}.\n" +
            "Expected checks: ${matrix.size}; missing: ${aggregate.missingChecks.size}.\n\n" +
            "Input snapshot: ${scope.inputSnapshot.hash}\nReport SHA-256: ${reportDigest.hash}\n\n" +
            "Commit supplied by publisher; this command does not establish snapshot-to-commit equivalence."
        return publishGitHubCheck(destination, output, "aggregate.json", reportBytes, aggregate.status,
            "Kvik tests: ${aggregate.status.name.lowercase()}", summary, artifacts, ::checkpoint)
    } catch (failure: CliFailure) { throw failure
    } catch (_: Exception) {
        throw CliFailure("KVIK_TEST_PUBLISH_FAILED", DiagnosticCategory.INFRASTRUCTURE,
            "Cannot prepare or retain publication; inspect the output directory before retrying. No tests were executed.", ExitStatus.INCOMPLETE)
    } finally {
        kvik_interrupt_restore()
    }
}
