package kvik.core

/** Explicit JMH selection and bounded measurement settings. */
data class JvmBenchmarkSettings(
    val classes: List<String>,
    val warmups: Int = 3,
    val iterations: Int = 5,
    val iterationMillis: Long = 1_000,
    val forks: Int = 1,
    val threads: Int = 1,
    val mode: String = "avgt",
    val outputTimeUnit: String = "ns",
    val timeoutMillis: Long = 3_600_000,
) {
    init {
        requireBenchmarkClasses(classes)
        require(warmups in 1..1_000 && iterations in 1..1_000) { "Benchmark warmups and iterations must be in 1..1000" }
        require(iterationMillis in 1..3_600_000) { "Benchmark iterationMillis must be in 1..3600000" }
        require(forks in 1..100 && threads in 1..1_024) { "Benchmarks require 1..100 isolated forks and 1..1024 threads" }
        require(mode in setOf("avgt", "thrpt")) { "Benchmark mode must be avgt or thrpt" }
        require(outputTimeUnit in setOf("ns", "us", "ms", "s", "m")) { "Unsupported benchmark output time unit" }
        require(timeoutMillis in 1..86_400_000) { "Benchmark timeoutMillis must be in 1..86400000" }
    }
    fun toJson(): JsonValue.Object = obj("classes" to classes.map { it.json() }.json(), "warmups" to warmups.json(),
        "iterations" to iterations.json(), "iterationMillis" to iterationMillis.json(), "forks" to forks.json(),
        "threads" to threads.json(), "mode" to mode.json(), "outputTimeUnit" to outputTimeUnit.json(), "timeoutMillis" to timeoutMillis.json())
    companion object {
        fun fromJson(value: JsonValue): JvmBenchmarkSettings {
            val d = value.fields(setOf("classes"), setOf("warmups", "iterations", "iterationMillis", "forks", "threads", "mode", "outputTimeUnit", "timeoutMillis"))
            fun integer(name: String, default: Int): Int = d[name]?.longValue()?.also { require(it in 0..Int.MAX_VALUE.toLong()) }?.toInt() ?: default
            return JvmBenchmarkSettings(d.getValue("classes").arrayValue().map(JsonValue::stringValue), integer("warmups", 3), integer("iterations", 5),
                d["iterationMillis"]?.longValue() ?: 1_000, integer("forks", 1), integer("threads", 1), d["mode"]?.stringValue() ?: "avgt",
                d["outputTimeUnit"]?.stringValue() ?: "ns", d["timeoutMillis"]?.longValue() ?: 3_600_000)
        }
    }
}

enum class BenchmarkProfile(val wireName: String) {
    SMOKE("smoke"), MEASUREMENT("measurement");
    companion object { fun parse(value: String): BenchmarkProfile = entries.singleOrNull { it.wireName == value } ?: error("Unknown benchmark profile: $value") }
}

/** Harness keys deliberately exclude all measurement/profile settings. */
data class JvmBenchmarkHarness(val module: String, val classpath: List<LogicalPath>, val classes: List<String>) {
    init { requireBenchmarkModule(module); requireBenchmarkClasspath(classpath); requireBenchmarkClasses(classes) }
    fun requireInputs(inputs: TreeManifest) = requireBenchmarkInputs(classpath, inputs)
    fun toJson(): JsonValue.Object = obj("module" to module.json(), "classpath" to classpath.map { it.value.json() }.json(), "classes" to classes.map { it.json() }.json())
    companion object {
        fun fromJson(value: JsonValue): JvmBenchmarkHarness {
            val d = value.fields(setOf("module", "classpath", "classes"))
            return JvmBenchmarkHarness(d.getValue("module").stringValue(), d.getValue("classpath").arrayValue().map { LogicalPath(it.stringValue()) }, d.getValue("classes").arrayValue().map(JsonValue::stringValue))
        }
    }
}

data class JvmBenchmarkRun(val module: String, val classpath: List<LogicalPath>, val profile: BenchmarkProfile, val settings: JvmBenchmarkSettings) {
    init { requireBenchmarkModule(module); requireBenchmarkClasspath(classpath); require(classpath.first() == LogicalPath("harness/$module")) { "Benchmark harness must be first on the runtime classpath" } }
    val effectiveSettings: JvmBenchmarkSettings get() = if (profile == BenchmarkProfile.SMOKE) settings.copy(warmups = 1, iterations = 1, iterationMillis = 50) else settings
    fun requireInputs(inputs: TreeManifest) {
        requireBenchmarkInputs(classpath, inputs)
        for (name in listOf("META-INF/BenchmarkList", "META-INF/CompilerHints")) require(inputs.files.any { it.path == LogicalPath("harness/$module/$name") }) { "Missing generated JMH resource: $name" }
    }
    fun toJson(): JsonValue.Object = obj("module" to module.json(), "classpath" to classpath.map { it.value.json() }.json(), "profile" to profile.wireName.json(), "settings" to settings.toJson())
    companion object {
        fun fromJson(value: JsonValue): JvmBenchmarkRun {
            val d = value.fields(setOf("module", "classpath", "profile", "settings"))
            return JvmBenchmarkRun(d.getValue("module").stringValue(), d.getValue("classpath").arrayValue().map { LogicalPath(it.stringValue()) },
                BenchmarkProfile.parse(d.getValue("profile").stringValue()), JvmBenchmarkSettings.fromJson(d.getValue("settings")))
        }
    }
}

private fun requireBenchmarkModule(module: String) { require(module.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid benchmark module" } }
private fun requireBenchmarkClasses(classes: List<String>) {
    require(classes.size in 1..1_000 && classes.distinct().size == classes.size && classes.all { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) }) { "Benchmark classes must contain 1..1000 unique JVM binary names" }
}
private fun requireBenchmarkClasspath(classpath: List<LogicalPath>) {
    require(classpath.isNotEmpty() && classpath.distinct().size == classpath.size && classpath.none { ':' in it.value }) { "Invalid benchmark classpath" }
    require(classpath.indices.none { i -> classpath.indices.any { j -> i != j && classpath[i].value.startsWith(classpath[j].value + "/") } }) { "Overlapping benchmark classpath roots" }
}
private fun requireBenchmarkInputs(classpath: List<LogicalPath>, inputs: TreeManifest) {
    fun contains(root: LogicalPath, file: LogicalPath) = if (root.value.endsWith(".jar")) root == file else file.value.startsWith(root.value + "/")
    require(classpath.all { root -> inputs.files.any { contains(root, it.path) } }) { "Undeclared benchmark classpath entry" }
    require(inputs.files.all { file -> !file.executable && classpath.any { contains(it, file.path) } }) { "Unexpected benchmark input outside its classpath" }
}
