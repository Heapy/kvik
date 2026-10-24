package kvik.cli

import kvik.cache.*
import kvik.core.*

internal data class BenchmarkSelection(val module: String, val profile: String) {
    init {
        require(module.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")))
        require(profile in setOf("smoke", "measurement"))
    }
    val actionId: String get() = "$module:jvm:benchmark-$profile"
    fun event(runDirectory: String): JsonValue.Object {
        val directory = "$runDirectory/benchmarks/$module/$profile"
        return obj("module" to module.json(), "target" to "jvm".json(), "profile" to profile.json(),
            "actionId" to actionId.json(), "directory" to directory.json(),
            "summary" to "$directory/summary.json".json(), "results" to "$directory/results.json".json())
    }
}

internal fun materializeBenchmarks(record: VerificationRecord, selections: List<BenchmarkSelection>, content: ContentStore,
                                   runDirectory: String, beforeExpose: () -> Boolean): Boolean {
    require(record.status == VerificationStatus.PASSED && record.freshnessAtCompletion == Freshness.CURRENT)
    require(selections.isNotEmpty() && selections.distinct().size == selections.size)
    val files = selections.flatMap { selection ->
        require(selection.actionId in record.completedChecks)
        val result = record.actions.single { it.actionId == selection.actionId }
        require(result.status == ActionStatus.EXECUTED) { "Benchmark measurements must execute in the current run" }
        require(result.outputs.files.map { it.path.value }.toSet() == setOf("benchmark-summary.json", "benchmark-results.json"))
        require(result.outputs.files.all { !it.executable && it.digest.sizeBytes in 1..64L * 1024 * 1024 })
        val summary = result.outputs.files.single { it.path.value == "benchmark-summary.json" }
        val bytes = content.get(summary.digest) ?: error("Benchmark summary unavailable")
        require(Sha256.digest(bytes) == summary.digest)
        val report = JvmBenchmarkReport.fromJson(parseJson(bytes.decodeToString(throwOnInvalidSequence = true)))
        require(report.module == selection.module && report.profile.name.lowercase() == selection.profile)
        result.outputs.files.map { entry ->
            val name = if (entry.path.value == "benchmark-summary.json") "summary.json" else "results.json"
            entry.copy(path = LogicalPath("${selection.module}/${selection.profile}/$name"))
        }
    }
    val pending = NativeFiles.tempDirectory(runDirectory)
    try {
        NativeOutputTrees(content).materialize(TreeManifest(files.sortedBy { it.path.value }), "$pending/benchmarks")
        if (!beforeExpose()) return false
        NativeFiles.exposeDirectory("$pending/benchmarks", "$runDirectory/benchmarks")
        return true
    } finally { NativeFiles.removeTree(pending) }
}
