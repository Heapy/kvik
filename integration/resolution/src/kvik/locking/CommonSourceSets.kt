package kvik.locking

import kvik.core.*

/** An editable shared source set, independent of publication names and metadata fragments. */
data class CommonMetadataSource(val module: String, val sourceSet: String, val compilation: String, val targets: Set<String>) {
    fun domain() = ResolutionDomain(module, "common", compilation, "kotlin-metadata", sourceSet = sourceSet)
}

/** Count actual consuming targets; a main source inherited by tests remains a main source. */
fun commonMetadataSourceSets(module: ModuleSpec): List<CommonMetadataSource> {
    val main = module.targets.associate { it.name to sourceSetClosure(module, listOf(it.main)).map { source -> source.name }.toSet() }
    val test = module.targets.associate { target -> target.name to
        (target.test?.let { sourceSetClosure(module, listOf(it)).map { source -> source.name }.toSet() }.orEmpty() - main.getValue(target.name)) }
    return module.sourceSets.mapNotNull { source ->
        val mainTargets = main.filterValues { source.name in it }.keys
        val testTargets = test.filterValues { source.name in it }.keys
        val targets = mainTargets + testTargets
        if (targets.size < 2) null else {
            require(mainTargets.isEmpty() || testTargets.isEmpty()) {
                "Shared metadata source set mixes main and test roles: ${module.id}:${source.name}"
            }
            CommonMetadataSource(module.id, source.name, if (mainTargets.isEmpty()) "test" else "main", targets)
        }
    }
}

fun commonMetadataSource(module: ModuleSpec, sourceSet: String): CommonMetadataSource =
    commonMetadataSourceSets(module).singleOrNull { it.sourceSet == sourceSet }
        ?: error("Source set is not shared by multiple targets: ${module.id}:$sourceSet")

/** Match a dependency by its target intersection, never by the consumer's source-set name. */
private fun sharedMainSources(module: ModuleSpec, targets: Set<String>): List<SourceSet> {
    val closures = targets.sorted().map { target ->
        val platform = module.targets.singleOrNull { it.name == target }
            ?: error("Common metadata dependency lacks target: ${module.id}:$target")
        sourceSetClosure(module, listOf(platform.main))
    }
    val shared = closures.map { sources -> sources.map { it.name }.toSet() }.reduce { left, right -> left intersect right }
    return closures.first().filter { it.name in shared }
}

/** Tests inherit the shared part of their own main compilation, including private dependencies. */
fun commonMetadataInputs(module: ModuleSpec, sourceSet: String): List<SourceSet> {
    val selected = commonMetadataSource(module, sourceSet)
    return ((if (selected.compilation == "test") sharedMainSources(module, selected.targets) else emptyList()) +
        sourceSetClosure(module, listOf(sourceSet))).distinctBy { it.name }
}

data class CommonMetadataClosure(val sources: List<CommonMetadataSource>, val external: List<Coordinate>)

/** Owner declarations are visible; workspace consumers see only exported transitive declarations. */
fun commonMetadataDependencyClosure(project: ProjectSpec, module: String, sourceSet: String): CommonMetadataClosure {
    val modules = project.modules.associateBy { it.id }
    val owner = modules.getValue(module)
    val targets = commonMetadataSource(owner, sourceSet).targets
    val reached = linkedSetOf<String>()
    val sources = linkedMapOf<Pair<String, String>, CommonMetadataSource>()
    val external = linkedSetOf<Coordinate>()
    fun visit(dependencies: List<Dependency>, exportedOnly: Boolean) {
        for (dependency in dependencies) {
            if (dependency.scope != DependencyScopeKind.COMPILE || exportedOnly && !dependency.exported) continue
            when (dependency) {
                is Dependency.External -> external += dependency.coordinate
                is Dependency.Module -> if (reached.add(dependency.module)) {
                    val specification = modules[dependency.module] ?: error("Unknown common metadata dependency: ${dependency.module}")
                    val selected = sharedMainSources(specification, targets)
                    require(selected.isNotEmpty()) { "Common metadata dependency has no shared main source sets: ${specification.id}" }
                    for (source in selected) sources[specification.id to source.name] = commonMetadataSource(specification, source.name)
                    visit(selected.flatMap { it.dependencies }, true)
                }
            }
        }
    }
    visit(commonMetadataInputs(owner, sourceSet).flatMap { it.dependencies }, false)
    return CommonMetadataClosure(sources.values.toList(), external.toList())
}
