package kvik.locking

import kvik.core.*

/** Dependency-first source sets shared by every published target's main compilation. */
fun commonPublicationSourceSets(module: ModuleSpec): List<SourceSet> {
    if (module.publication == null || module.targets.size < 2) return emptyList()
    val closures = module.targets.map { sourceSetClosure(module, listOf(it.main)) }
    val shared = closures.map { sets -> sets.map { it.name }.toSet() }.reduce { left, right -> left intersect right }
    return closures.first().filter { it.name in shared }
}

/** Common metadata sees its own compile dependencies and only exported transitive declarations. */
fun commonPublicationDependencyClosure(project: ProjectSpec, module: String): DependencyClosure {
    val modules = project.modules.associateBy { it.id }
    fun sources(id: String): List<SourceSet> {
        val specification = modules[id] ?: error("Unknown common publication module: $id")
        require(specification.publication != null) { "Common library dependency requires publication: $id" }
        return commonPublicationSourceSets(specification).also { sets ->
            require(sets.any { it.directories.isNotEmpty() }) { "Module $id has no shared publication sources" }
        }
    }
    val reached = linkedSetOf<String>()
    val external = linkedSetOf<Coordinate>()
    fun visit(dependencies: List<Dependency>, exportedOnly: Boolean) {
        for (dependency in dependencies) {
            if (dependency.scope != DependencyScopeKind.COMPILE || exportedOnly && !dependency.exported) continue
            when (dependency) {
                is Dependency.External -> external += dependency.coordinate
                is Dependency.Module -> if (reached.add(dependency.module)) {
                    visit(sources(dependency.module).flatMap { it.dependencies }, true)
                }
            }
        }
    }
    visit(sources(module).flatMap { it.dependencies }, false)
    return DependencyClosure(reached.toList(), external.toList())
}
