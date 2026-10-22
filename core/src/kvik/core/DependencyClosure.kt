package kvik.core

enum class DependencyUsage { COMPILE, RUNTIME }
data class DependencyClosure(val modules: List<String>, val external: List<Coordinate>)

/** Direct compile dependencies are visible to their owner; consumers see only exported edges.
 * Runtime traverses every dependency. Tests own their main dependencies as well as test ones. */
fun dependencyClosure(project: ProjectSpec, module: String, target: String, compilation: String, usage: DependencyUsage): DependencyClosure {
    val modules = project.modules.associateBy { it.id }
    val reached = linkedSetOf<String>()
    val external = linkedSetOf<Coordinate>()
    fun visit(id: String, scope: String, exportedOnly: Boolean) {
        val specification = modules[id] ?: error("Unknown module: $id")
        val platform = specification.targets.singleOrNull { it.name == target } ?: error("Missing target $id:$target")
        require(scope in setOf("main", "test")) { "Unknown compilation: $scope" }
        val selected = if (scope == "main") listOf(platform.main) else listOf(platform.main, platform.test ?: error("Missing tests $id:$target"))
        for (dependency in sourceSetClosure(specification, selected).flatMap { it.dependencies }) {
            if (usage == DependencyUsage.COMPILE && (dependency.scope == DependencyScopeKind.RUNTIME || exportedOnly && !dependency.exported)) continue
            when (dependency) {
                is Dependency.External -> external += dependency.coordinate
                is Dependency.Module -> if (reached.add(dependency.module)) visit(dependency.module, "main", true)
            }
        }
    }
    visit(module, compilation, false)
    return DependencyClosure(reached.toList(), external.toList())
}
