@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.platform.*

/** Publish the complete retained report scope. Grouping and threshold evaluation are separate operations. */
internal fun coveragePublishCommand(args: List<String>): ExitStatus {
    fun arguments(message: String): Nothing = throw CliFailure("KVIK_COVERAGE_PUBLISH_ARGUMENTS", DiagnosticCategory.CONFIGURATION,
        "$message. Use coverage-publish --report PATH --repository OWNER/REPO --sha SHA --output NEW_DIRECTORY",
        ExitStatus.INVALID_CONFIGURATION)
    val options = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        if (flag !in setOf("--report", "--repository", "--sha", "--name", "--api-url", "--token-env", "--output")) arguments("Unknown option")
        val value = args.getOrNull(index++) ?: arguments("Missing option value")
        if (value.isBlank() || value.startsWith("--")) arguments("Empty option value")
        if (options.put(flag, value) != null) arguments("Repeated option")
    }
    val destination = githubCheckDestination(options, "Kvik coverage", ::arguments)
    val source = projectPath(currentDirectory(), options["--report"] ?: arguments("Missing --report"))
    val sourceDirectory = source.substringBeforeLast('/')
    val output = projectPath(currentDirectory(), options["--output"] ?: arguments("Missing --output"))
    if (pathsPhysicallyOverlap(sourceDirectory, output)) arguments("Output must be outside the source report directory")
    if (NativeFiles.info(output) != null) arguments("Output already exists; use a new attempt directory")
    check(kvik_interrupt_install() == 0) { "Cannot install publication interruption handlers" }
    fun checkpoint() {
        if (kvik_interrupt_requested() != 0) throw CliFailure("KVIK_COVERAGE_PUBLISH_CANCELLED", DiagnosticCategory.CANCELLATION,
            "Publication cancelled; inspect the attempt directory before retrying", ExitStatus.CANCELLED)
    }
    try {
        checkpoint()
        val reportBytes = NativeFiles.read(source, 64 * 1024 * 1024)
        val value = parseJson(reportBytes.decodeToString(throwOnInvalidSequence = true))
        val aggregate: CoverageAggregate
        val originals: List<FileEntry>
        when (value.objectValue().getValue("kind").stringValue()) {
            "line-coverage" -> {
                val report = CoverageReport.fromJson(value)
                aggregate = aggregateCoverage(CoverageAggregationScope(report.inputSnapshot, report.configuration,
                    report.dependencyLock, setOf(report.check)), listOf(CoverageContribution(Sha256.digest(report.toJson()), report)))
                originals = report.originals.files
            }
            "coverage-aggregate" -> {
                aggregate = CoverageAggregate.fromJson(value)
                originals = aggregate.contributions.flatMap { contribution ->
                    val prefix = "contributors/${contribution.digest.hash}/"
                    listOf(FileEntry(LogicalPath("${prefix}coverage.json"), contribution.digest)) +
                        contribution.report.originals.files.map { it.copy(path = LogicalPath(prefix + it.path.value)) }
                }
            }
            else -> arguments("Unsupported coverage report kind")
        }
        // Keep a self-contained source tree so retries do not depend on the original report directory.
        val artifacts = linkedMapOf("source/report.json" to reportBytes)
        var totalBytes = reportBytes.size.toLong()
        for (entry in originals) {
            checkpoint()
            totalBytes += entry.digest.sizeBytes
            require(totalBytes <= 256L * 1024 * 1024) { "Publication inputs exceed 256 MiB" }
            val retainedPath = "source/${entry.path.value}"
            require(retainedPath !in artifacts) { "Duplicate retained source path" }
            val bytes = NativeFiles.read("$sourceDirectory/${entry.path.value}", 64 * 1024 * 1024)
            require(Sha256.digest(bytes) == entry.digest) { "Original coverage artifact differs" }
            artifacts[retainedPath] = bytes
        }
        val reportDigest = Sha256.digest(reportBytes)
        val summary = buildString {
            append("Retained line coverage: ${aggregate.status.name.lowercase()}. Complete measurements: ${aggregate.complete}.\n\n")
            append("Expected checks: ${aggregate.scope.expectedChecks.size}; received: ${aggregate.contributions.size}; ")
            append("missing: ${aggregate.targets.sumOf { it.missingChecks.size }}; unsupported: ${aggregate.targets.sumOf { it.unsupportedChecks.size }}.\n\n")
            for (target in aggregate.targets.take(20)) {
                append("${target.target.take(128)}: covered ${target.covered ?: "unknown"} / executable ${target.executable ?: "unknown"} lines; ")
                append("status ${target.status.name.lowercase()}; complete ${target.complete}.\n")
            }
            if (aggregate.targets.size > 20) append("${aggregate.targets.size - 20} further targets are retained in the report.\n")
            append("\nShared sources count once per target; targets are not pooled. Unknown measurements do not become zero coverage.\n")
            append("No coverage threshold was evaluated; use coverage-check to enforce a policy.\n\n")
            append("Input snapshot: ${aggregate.scope.inputSnapshot.hash}\nReport SHA-256: ${reportDigest.hash}\n\n")
            append("Commit supplied by publisher; this command does not establish snapshot-to-commit equivalence or current source freshness.")
        }
        return publishGitHubCheck(destination, output, "source/report.json", reportBytes, aggregate.status,
            "Kvik coverage: ${aggregate.status.name.lowercase()}", summary, artifacts, ::checkpoint)
    } catch (failure: CliFailure) { throw failure
    } catch (_: Exception) {
        throw CliFailure("KVIK_COVERAGE_PUBLISH_FAILED", DiagnosticCategory.INFRASTRUCTURE,
            "Cannot prepare or retain publication; inspect the output directory before retrying. No tests were executed.", ExitStatus.INCOMPLETE)
    } finally { kvik_interrupt_restore() }
}
