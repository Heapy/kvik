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

/** Documentation uses shared source declarations without requiring Maven publication metadata. */
fun documentationCommonSourceSets(module: ModuleSpec, targets: Set<String>? = null): List<SourceSet> {
    val selected = module.targets.filter { targets == null || it.name in targets }
    require(targets == null || selected.size == targets.size) { "Documentation dependency lacks a supporting target: ${module.id}" }
    if (selected.size < 2) return emptyList()
    val closures = selected.map { sourceSetClosure(module, listOf(it.main)) }
    val shared = if (targets == null) closures.flatten().groupingBy { it.name }.eachCount().filterValues { it >= 2 }.keys
        else closures.map { sets -> sets.map { it.name }.toSet() }.reduce { left, right -> left intersect right }
    return closures.flatten().distinctBy { it.name }.filter { it.name in shared }
}

fun documentationCommonDependencyClosure(project: ProjectSpec, module: String, targets: Set<String>? = null): DependencyClosure {
    val modules = project.modules.associateBy { it.id }
    fun sources(id: String): List<SourceSet> = documentationCommonSourceSets(modules.getValue(id), targets).also {
        require(it.isNotEmpty()) { "Documentation dependency $id has no shared main source sets" }
    }
    val reached = linkedSetOf<String>()
    val external = linkedSetOf<Coordinate>()
    fun visit(dependencies: List<Dependency>, exportedOnly: Boolean) {
        for (dependency in dependencies) {
            if (dependency.scope != DependencyScopeKind.COMPILE || exportedOnly && !dependency.exported) continue
            when (dependency) {
                is Dependency.External -> external += dependency.coordinate
                is Dependency.Module -> if (reached.add(dependency.module)) visit(sources(dependency.module).flatMap { it.dependencies }, true)
            }
        }
    }
    visit(sources(module).flatMap { it.dependencies }, false)
    return DependencyClosure(reached.toList(), external.toList())
}
