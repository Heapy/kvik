package kvik.projectsnapshot

import kvik.core.*

/** Persist this selection with run metadata; absent means the original whole-project capture. */
sealed interface SnapshotSelection {
    data object Project : SnapshotSelection
    data class Script(val name: String) : SnapshotSelection {
        init { require(name.length in 1..128 && name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))) { "Invalid snapshot script name" } }
    }
    data object StandaloneScript : SnapshotSelection
    data class AbiCheck(val modules: Set<String>, val targets: Set<String>) : SnapshotSelection {
        init {
            require(modules.isNotEmpty() && modules.size <= 1000 && modules.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) })
            require(targets.isNotEmpty() && targets.all { it == "jvm" || NativeTarget.find(it) != null })
        }
    }
    fun toJson(): JsonValue = when (this) {
        Project -> obj("kind" to "project".json())
        is Script -> obj("kind" to "script".json(), "name" to name.json())
        StandaloneScript -> obj("kind" to "standalone-script".json())
        is AbiCheck -> obj("kind" to "abi-check".json(), "modules" to modules.sorted().map { it.json() }.json(),
            "targets" to targets.sorted().map { it.json() }.json())
    }
    companion object {
        fun fromJson(value: JsonValue): SnapshotSelection {
            val fields = value.fields(setOf("kind"), setOf("name", "modules", "targets"))
            return when (fields.getValue("kind").stringValue()) {
                "project" -> { value.fields(setOf("kind")); Project }
                "script" -> { value.fields(setOf("kind", "name")); Script(fields.getValue("name").stringValue()) }
                "standalone-script" -> { value.fields(setOf("kind")); StandaloneScript }
                "abi-check" -> {
                    value.fields(setOf("kind", "modules", "targets"))
                    fun names(key: String) = fields.getValue(key).arrayValue().map(JsonValue::stringValue).also {
                        require(it.distinct().size == it.size) { "Duplicate ABI snapshot selection" }
                    }.toSet()
                    AbiCheck(names("modules"), names("targets"))
                }
                else -> error("Unknown snapshot selection")
            }
        }
    }
}

/** Kinds are deliberate: module roots are directories, plugin inputs required files, and
 * script roots can be a file or directory. Consumers must not merge them into an implicit source set. */
data class ProjectInputPlan(val directories: List<LogicalPath>, val files: List<LogicalPath>, val scriptRoots: List<LogicalPath>) {
    val paths: List<LogicalPath> get() = (directories + files + scriptRoots).distinct()
}

fun projectInputPlan(configuration: ProjectConfiguration, selection: SnapshotSelection = SnapshotSelection.Project): ProjectInputPlan {
    val project = configuration.project
    val modules = project.modules.associateBy { it.id }
    val wholeProject = selection == SnapshotSelection.Project || selection is SnapshotSelection.AbiCheck
    val scripts = when (selection) {
        SnapshotSelection.Project -> project.scripts.keys.map { expandScript(project, it) }
        is SnapshotSelection.AbiCheck -> project.scripts.keys.map { expandScript(project, it) }
        is SnapshotSelection.Script -> listOf(expandScript(project, selection.name))
        SnapshotSelection.StandaloneScript -> error("Standalone selection requires companion configuration")
    }
    val selected = if (wholeProject) project.modules else
        scriptPrerequisiteModules(project, scripts.single()).map { modules.getValue(it) }
    val sources = selected.flatMap { module ->
        if (wholeProject) module.sourceSets else
            sourceSetClosure(module, listOf(module.targets.single { it.name == "jvm" }.main))
    }
    val plugins = selected.flatMap { module -> module.compilerPlugins.filter {
        wholeProject || "jvm" in it.targets && "main" in it.scopes
    } } + scripts.flatMap { it.compilerPlugins.map { value -> value.value } }
    val baselines = if (selection is SnapshotSelection.AbiCheck) selection.modules.flatMap { id ->
        val module = modules[id] ?: error("Unknown ABI module: $id")
        selection.targets.map { target ->
            val spec = module.targets.singleOrNull { it.name == target } ?: error("Missing ABI target $id:$target")
            requireNotNull(spec.abi) { "Declare abi.baseline for $id:$target before checking its API" }.baseline
        }
    } else emptyList()
    return plan(sources.flatMap { it.directories + it.resources }, plugins.flatMap { it.inputs } + baselines,
        scripts.flatMap { it.declaration.sources + it.declaration.resources })
}

fun standaloneScriptInputPlan(configuration: StandaloneScriptConfiguration): ProjectInputPlan {
    val script = configuration.expanded()
    return plan(emptyList(), script.compilerPlugins.flatMap { it.value.inputs }, script.declaration.sources + script.declaration.resources)
}

private fun plan(directories: List<LogicalPath>, files: List<LogicalPath>, scriptRoots: List<LogicalPath>) = ProjectInputPlan(
    directories.distinct().sortedBy { it.value }, files.distinct().sortedBy { it.value }, scriptRoots.distinct().sortedBy { it.value })
