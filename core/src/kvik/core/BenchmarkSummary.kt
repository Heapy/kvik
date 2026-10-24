package kvik.core

/** Floating point measurements use decimal strings: the general contract JSON accepts integers only. */
data class JvmBenchmarkResult(
    val benchmark: String, val mode: String, val parameters: Map<String, String>,
    val score: String, val scoreError: String?, val unit: String, val samples: Long,
) {
    init {
        require(benchmark.length in 1..1024 && benchmark.matches(Regex("[A-Za-z_$][A-Za-z0-9_.$]*"))) { "Invalid benchmark name" }
        require(mode in setOf("avgt", "thrpt"))
        require(parameters.size <= 100 && parameters.all { (key, value) -> key.length in 1..256 && value.length <= 4096 && key.none(Char::isISOControl) && value.none(Char::isISOControl) })
        require(benchmarkDecimal(score) && (scoreError == null || benchmarkDecimal(scoreError))) { "Benchmark measurements must be finite decimals" }
        require(unit.length in 1..64 && unit.none(Char::isISOControl) && samples > 0)
    }
    val identity: String get() = obj("benchmark" to benchmark.json(), "mode" to mode.json(), "parameters" to JsonValue.Object(parameters.mapValues { it.value.json() })).canonical()
    fun toJson(): JsonValue.Object = obj("benchmark" to benchmark.json(), "mode" to mode.json(),
        "parameters" to JsonValue.Object(parameters.mapValues { it.value.json() }), "score" to score.json(),
        "scoreError" to (scoreError?.json() ?: JsonValue.Null), "unit" to unit.json(), "samples" to samples.json())
    companion object {
        fun fromJson(value: JsonValue): JvmBenchmarkResult = value.fields(setOf("benchmark", "mode", "parameters", "score", "scoreError", "unit", "samples")).let {
            JvmBenchmarkResult(it.getValue("benchmark").stringValue(), it.getValue("mode").stringValue(),
                it.getValue("parameters").objectValue().mapValues { entry -> entry.value.stringValue() },
                it.getValue("score").stringValue(), it.getValue("scoreError").let { error -> if (error == JsonValue.Null) null else error.stringValue() },
                it.getValue("unit").stringValue(), it.getValue("samples").longValue())
        }
    }
}

data class JvmBenchmarkReport(val module: String, val profile: BenchmarkProfile, val selectedClasses: List<String>, val results: List<JvmBenchmarkResult>) {
    init {
        require(module.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")))
        require(selectedClasses.isNotEmpty() && selectedClasses.size <= 1000 && selectedClasses.distinct().size == selectedClasses.size)
        JvmBenchmarkSettings(selectedClasses) // Apply the same JVM class-name validation as the requested selection.
        require(results.size in 1..100_000 && results.map { it.identity }.distinct().size == results.size) { "Missing or duplicate benchmark results" }
        require(results.all { result -> selectedClasses.any { result.benchmark.substringBeforeLast('.') == it } } &&
            selectedClasses.all { name -> results.any { it.benchmark.substringBeforeLast('.') == name } }) { "Benchmark results do not cover selected classes" }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "module" to module.json(), "profile" to profile.wireName.json(),
        "selectedClasses" to selectedClasses.map { it.json() }.json(), "results" to results.map { it.toJson() }.json())
    companion object {
        fun fromJson(value: JsonValue): JvmBenchmarkReport = value.fields(setOf("schemaVersion", "module", "profile", "selectedClasses", "results")).let {
            it.requireVersion()
            JvmBenchmarkReport(it.getValue("module").stringValue(), BenchmarkProfile.entries.single { profile -> profile.wireName == it.getValue("profile").stringValue() },
                it.getValue("selectedClasses").arrayValue().map { item -> item.stringValue() }, it.getValue("results").arrayValue().map(JvmBenchmarkResult::fromJson))
        }
    }
}

private fun benchmarkDecimal(value: String): Boolean = value.length in 1..128 &&
    value.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) && value.toDoubleOrNull()?.isFinite() == true
