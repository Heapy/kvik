package kvik.core

/** A script producing Kotlin sources in a fresh `generated` directory for one source set. */
data class ScriptGeneratorSpec(
    val module: String,
    val sourceSet: String,
    val inputs: List<LogicalPath> = emptyList(),
    val arguments: List<String> = emptyList(),
) {
    init {
        require(listOf(module, sourceSet).all { it.length in 1..128 && it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) }) {
            "Script generator requires valid module and sourceSet identifiers"
        }
        require(inputs.size <= 256 && inputs.all { it.value.length <= 1024 }) { "Script generator input roots exceed bounds" }
        require(inputs.indices.all { index -> inputs.drop(index + 1).none {
            pathsOverlap(LogicalPath(inputs[index].value.lowercase()), LogicalPath(it.value.lowercase()))
        } }) { "Duplicate/overlapping script generator input roots" }
        require(arguments.size <= 256 && arguments.all { it.length <= 4096 && '\u0000' !in it }) {
            "Script generator arguments exceed bounds or contain NUL"
        }
    }

    fun toJson(): JsonValue.Object = obj("module" to module.json(), "sourceSet" to sourceSet.json(),
        "inputs" to inputs.map { it.value.json() }.json(), "arguments" to arguments.map { it.json() }.json())

    companion object {
        fun fromJson(value: JsonValue): ScriptGeneratorSpec {
            val fields = value.fields(setOf("module", "sourceSet"), setOf("inputs", "arguments"))
            return ScriptGeneratorSpec(fields.getValue("module").stringValue(), fields.getValue("sourceSet").stringValue(),
                fields["inputs"]?.arrayValue()?.map { LogicalPath(it.stringValue()) } ?: emptyList(),
                fields["arguments"]?.arrayValue()?.map(JsonValue::stringValue) ?: emptyList())
        }
    }
}

/** The caller supplies the compilation's source-set closure, including common ancestors. */
fun scriptGenerators(project: ProjectSpec, module: String, sourceSets: Set<String>): List<ExpandedScript> =
    project.scripts.entries.filter { (_, script) ->
        script.generator?.let { it.module == module && it.sourceSet in sourceSets } == true
    }.sortedBy { it.key }.map { expandScript(project, it.key) }

internal fun validateScriptGenerators(project: ProjectSpec) {
    val generators = project.scripts.filterValues { it.generator != null }
    if (generators.isEmpty()) return
    val modules = project.modules.associateBy { it.id }
    for ((name, script) in generators) {
        val generator = script.generator!!
        val module = modules[generator.module] ?: error("Script generator $name selects unknown module ${generator.module}")
        require(module.sourceSets.any { it.name == generator.sourceSet }) {
            "Script generator $name selects unknown source set ${generator.module}:${generator.sourceSet}"
        }
        require(module.targets.any { target -> sourceSetClosure(module, listOfNotNull(target.main, target.test)).any { it.name == generator.sourceSet } }) {
            "Script generator $name selects unreachable source set ${generator.module}:${generator.sourceSet}"
        }
    }
    // Module-level graph intentionally rejects cycles even when their edges select different target/scopes.
    // Runtime and compiler-plugin workspace prerequisites participate: a generator must finish first.
    val edges = project.modules.associate { module ->
        "module:${module.id}" to (module.sourceSets.flatMap { it.dependencies }.filterIsInstance<Dependency.Module>().map { "module:${it.module}" } +
            module.compilerPlugins.mapNotNull { it.module?.let { name -> "module:$name" } } +
            generators.filterValues { it.generator!!.module == module.id }.keys.map { "generator:$it" }).toSet()
    }.toMutableMap()
    for (name in generators.keys) {
        val script = expandScript(project, name)
        edges["generator:$name"] = (script.dependencies.mapNotNull { (it.value as? Dependency.Module)?.module } +
            script.compilerPlugins.mapNotNull { it.value.module }).map { "module:$it" }.toSet()
    }
    val visited = mutableSetOf<String>()
    val active = linkedSetOf<String>()
    fun visit(node: String) {
        require(node !in active) { "Script generator prerequisite cycle (conservative module-level validation): ${(active.toList() + node).joinToString(" -> ")}" }
        if (!visited.add(node)) return
        active += node
        edges[node].orEmpty().sorted().forEach(::visit)
        active -= node
    }
    edges.keys.sorted().forEach(::visit)
}
