package kvik.core

enum class DependencyUsage { COMPILE, RUNTIME }
data class DependencyClosure(val modules: List<String>, val external: List<Coordinate>)

/** Direct compile dependencies are visible to their owner; consumers see only exported edges.
 * Runtime traverses every dependency. Tests own their main dependencies as well as test ones. */
fun dependencyClosure(project: ProjectSpec, module: String, target: String, compilation: String, usage: DependencyUsage): DependencyClosure {
    val specification = project.modules.singleOrNull { it.id == module } ?: error("Unknown module: $module")
    val platform = specification.targets.singleOrNull { it.name == target } ?: error("Missing target $module:$target")
    require(compilation in setOf("main", "test")) { "Unknown compilation: $compilation" }
    val selected = if (compilation == "main") listOf(platform.main) else listOf(platform.main, platform.test ?: error("Missing tests $module:$target"))
    return dependencyClosureFromSeeds(project, target, sourceSetClosure(specification, selected).flatMap { it.dependencies }, usage)
}

/** A real consumer's ordered declarations; no fabricated module or implicit project classpath. */
fun dependencyClosureFromSeeds(project: ProjectSpec, target: String, dependencies: List<Dependency>, usage: DependencyUsage): DependencyClosure {
    val modules = project.modules.associateBy { it.id }
    val reached = linkedSetOf<String>()
    val external = linkedSetOf<Coordinate>()
    fun visit(values: List<Dependency>, exportedOnly: Boolean) {
        for (dependency in values) {
            if (usage == DependencyUsage.COMPILE && (dependency.scope == DependencyScopeKind.RUNTIME || exportedOnly && !dependency.exported)) continue
            when (dependency) {
                is Dependency.External -> external += dependency.coordinate
                is Dependency.Module -> if (reached.add(dependency.module)) {
                    val module = modules[dependency.module] ?: error("Unknown module: ${dependency.module}")
                    val platform = module.targets.singleOrNull { it.name == target } ?: error("Missing target ${module.id}:$target")
                    visit(sourceSetClosure(module, listOf(platform.main)).flatMap { it.dependencies }, true)
                }
            }
        }
    }
    visit(dependencies, false)
    return DependencyClosure(reached.toList(), external.toList())
}

fun scriptDependencyClosure(project: ProjectSpec, script: ExpandedScript, usage: DependencyUsage): DependencyClosure =
    dependencyClosureFromSeeds(project, "jvm", script.dependencies.map { it.value }, usage)

/** Modules whose main inputs are needed to compile/package one script, including host plugins.
 * Consumer visibility remains in scriptDependencyClosure; private compilation prerequisites are
 * visited in their own module scope and never added to the script's compile classpath. */
fun scriptPrerequisiteModules(project: ProjectSpec, script: ExpandedScript): List<String> {
    val modules = project.modules.associateBy { it.id }
    val reached = linkedSetOf<String>()
    fun visit(id: String) {
        if (!reached.add(id)) return
        val module = modules[id] ?: error("Unknown module: $id")
        dependencyClosure(project, id, "jvm", "main", DependencyUsage.COMPILE).modules.forEach(::visit)
        val sources = sourceSetClosure(module, listOf(module.targets.single { it.name == "jvm" }.main)).map { it.name }.toSet()
        for (generator in scriptGenerators(project, id, sources)) {
            scriptDependencyClosure(project, generator, DependencyUsage.RUNTIME).modules.forEach(::visit)
            generator.compilerPlugins.mapNotNull { it.value.module }.forEach { producer ->
                visit(producer)
                dependencyClosure(project, producer, "jvm", "main", DependencyUsage.RUNTIME).modules.forEach(::visit)
            }
        }
        for (plugin in module.compilerPlugins.filter { "jvm" in it.targets && "main" in it.scopes }) plugin.module?.let { producer ->
            visit(producer)
            dependencyClosure(project, producer, "jvm", "main", DependencyUsage.RUNTIME).modules.forEach(::visit)
        }
    }
    scriptDependencyClosure(project, script, DependencyUsage.RUNTIME).modules.forEach(::visit)
    script.compilerPlugins.mapNotNull { it.value.module }.forEach { producer ->
        visit(producer)
        dependencyClosure(project, producer, "jvm", "main", DependencyUsage.RUNTIME).modules.forEach(::visit)
    }
    return reached.toList()
}
