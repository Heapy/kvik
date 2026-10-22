package kvik.cli

import kvik.core.*

internal data class BuildOptions(
    val project: ProjectOptions,
    val modules: Set<String>,
    val targets: Set<String>,
    val tools: String,
    val runs: String,
    val budget: ResourceBudget,
    val remoteCache: String? = null,
    val coverage: Boolean = false,
)

internal fun buildOptions(args: List<String>): BuildOptions {
    val project = mutableListOf<String>()
    val modules = linkedSetOf<String>()
    val targets = linkedSetOf<String>()
    val values = mutableMapOf<String, String>()
    var index = 0
    var coverage = false
    while (index < args.size) {
        val flag = args[index++]
        if (flag == "--offline") { project += flag; continue }
        if (flag == "--coverage") { require(!coverage) { "Duplicate --coverage" }; coverage = true; continue }
        require(flag in setOf("--project", "--lock", "--store", "--pin", "--scope-pin", "--exclude-metadata-edge", "--module", "--target", "--tools", "--runs", "--jobs", "--memory-mib", "--remote-cache")) { "Unsupported build option: $flag" }
        val value = args.getOrNull(index++)
        require(value != null && value.isNotBlank() && !value.startsWith("--")) { "Missing value for $flag" }
        when (flag) {
            "--project", "--lock", "--store", "--pin", "--scope-pin", "--exclude-metadata-edge" -> project += listOf(flag, value)
            "--module" -> require(modules.add(value)) { "Duplicate module selection: $value" }
            "--target" -> require(targets.add(value)) { "Duplicate target selection: $value" }
            else -> require(values.put(flag, value) == null) { "Duplicate option: $flag" }
        }
    }
    require(modules.isNotEmpty() && targets.isNotEmpty()) { "Select --module and --target explicitly (repeatable)" }
    require(modules.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) }) { "Invalid module selection" }
    require(targets.all { it in setOf("jvm", "macosArm64") }) { "Unsupported target selection" }
    val jobs = values["--jobs"]?.toIntOrNull() ?: if ("--jobs" in values) error("Invalid --jobs") else 2
    val memory = values["--memory-mib"]?.toLongOrNull() ?: if ("--memory-mib" in values) error("Invalid --memory-mib") else 4096L
    return BuildOptions(projectOptions(project, lockOptions = true), modules, targets,
        values["--tools"] ?: error("Supply --tools PATH with pinned installation roots"), values["--runs"] ?: ".kvik/runs", ResourceBudget(jobs, memory), values["--remote-cache"], coverage)
}
