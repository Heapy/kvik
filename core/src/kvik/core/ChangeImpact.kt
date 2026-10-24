package kvik.core

/** Selection for one explicitly requested build/test/ABI matrix. Omitted checks are baseline-covered, not freshly executed. */
data class AffectedCheckSelection(
    val requested: Set<String>,
    val selected: Set<String>,
    val reasons: Map<String, List<String>>,
    val changedPaths: Set<LogicalPath>,
    val fullReasons: Set<String>,
) {
    init {
        require(requested.containsAll(selected)) { "Selected checks must belong to the requested matrix" }
        require(reasons.keys == selected && reasons.values.all { it.isNotEmpty() && it.distinct().size == it.size }) {
            "Every selected check requires nonempty unique reasons; omitted checks cannot have selection reasons"
        }
        require(fullReasons.isEmpty() || selected == requested) { "Full selection must include every requested check" }
    }

    fun toJson(): JsonValue.Object = obj(
        "schemaVersion" to 1.json(),
        "requestedChecks" to requested.sorted().map { it.json() }.json(),
        "selectedChecks" to selected.sorted().map { it.json() }.json(),
        "omittedChecks" to (requested - selected).sorted().map { it.json() }.json(),
        "changedPaths" to changedPaths.map { it.value }.sorted().map { it.json() }.json(),
        "reasons" to JsonValue.Object(reasons.entries.sortedBy { it.key }.associate { it.key to it.value.sorted().map { value -> value.json() }.json() }),
        "fullReasons" to fullReasons.sorted().map { it.json() }.json(),
    )
}

/** Source compatibility for callers of the original test-only selector. */
typealias AffectedTestSelection = AffectedCheckSelection

/**
 * Conservative input-based check avoidance between immutable source snapshots with identical configuration.
 * The caller must supply a full reason for changed configuration, dependency/toolchain identities or other
 * inputs outside these logical source paths. Baseline completion must describe successful checks under those
 * same identities. A missing check always runs, even when the source diff is empty.
 *
 * All paths are configuration-relative and matched lexically, so deletion and both ends of a rename work
 * without consulting a checkout. Unclassified changes select the full requested matrix.
 */
fun selectAffectedChecks(
    configuration: ProjectConfiguration,
    modules: Set<String>,
    targets: Set<String>,
    kinds: Set<String>,
    changedPaths: Set<LogicalPath>,
    baselineCompleted: Set<String>,
    fullReasons: Set<String> = emptySet(),
): AffectedCheckSelection {
    require(modules.isNotEmpty() && targets.isNotEmpty()) { "Affected checks require nonempty explicit module and target selections" }
    require(kinds.isNotEmpty() && kinds.all { it in setOf("build", "test", "abi-check") }) { "Affected check kinds must be a nonempty subset of build, test and abi-check" }
    require(fullReasons.all { it.isNotBlank() && '\u0000' !in it && '\n' !in it && '\r' !in it }) { "Full selection reasons must be nonempty single lines" }
    val byId = configuration.project.modules.associateBy { it.id }
    require(byId.size == configuration.project.modules.size) { "Duplicate project module identifiers" }
    val requested = linkedMapOf<String, ImpactRequest>()
    for (module in modules.sorted()) {
        val specification = byId[module] ?: throw IllegalArgumentException("Unknown module: $module")
        for (target in targets.sorted()) {
            val platform = specification.targets.singleOrNull { it.name == target }
                ?: throw IllegalArgumentException("Missing or duplicate target $module:$target")
            for (kind in kinds.sorted()) {
                if (kind == "test") require(platform.test != null) { "Missing tests $module:$target" }
                val exact = if (kind == "abi-check") setOf(platform.abi?.baseline
                    ?: throw IllegalArgumentException("Missing ABI settings $module:$target")) else emptySet()
                requested["$module:$target:$kind"] = ImpactRequest(
                    ImpactCompilation(module, target, if (kind == "test") "test" else "main"), exact)
            }
        }
    }

    fun matches(path: LogicalPath, root: LogicalPath): Boolean = path == root || path.value.startsWith(root.value + "/")
    val knownRoots = configuration.project.modules.flatMap { module ->
        module.sourceSets.flatMap { it.directories + it.resources } + module.compilerPlugins.flatMap { it.inputs } + listOfNotNull(module.sqlDelight?.sourceDirectory)
    }.toSet()
    val knownExact = configuration.project.modules.flatMap { module -> module.targets.mapNotNull { it.abi?.baseline } }.toSet()
    val effectiveFullReasons = fullReasons.toMutableSet()
    changedPaths.filter { path -> path !in knownExact && knownRoots.none { matches(path, it) } }.forEach {
        effectiveFullReasons += "unknown-path:${it.value}"
    }

    // Cache direct input/dependency descriptions, not transitive visibility: every dependency can affect a check.
    val direct = mutableMapOf<ImpactCompilation, ImpactInputs>()
    fun inputs(compilation: ImpactCompilation): ImpactInputs = direct.getOrPut(compilation) {
        val module = byId[compilation.module] ?: throw IllegalArgumentException("Unknown dependency module: ${compilation.module}")
        val target = module.targets.singleOrNull { it.name == compilation.target }
            ?: throw IllegalArgumentException("Missing or duplicate dependency target ${module.id}:${compilation.target}")
        val root = if (compilation.scope == "main") target.main else target.test
            ?: throw IllegalArgumentException("Missing dependency tests ${module.id}:${compilation.target}")
        val sources = sourceSetClosure(module, listOf(root))
        val roots = sources.flatMap { it.directories + it.resources }.toMutableSet()
        module.sqlDelight?.takeIf { generator -> sources.any { it.name == generator.sourceSet } }?.let { roots += it.sourceDirectory }
        val dependencies = sources.flatMap { it.dependencies }.filterIsInstance<Dependency.Module>().map {
            ImpactCompilation(it.module, compilation.target, "main")
        }.toMutableSet()
        if (compilation.scope == "test") dependencies += ImpactCompilation(module.id, compilation.target, "main")
        for (plugin in module.compilerPlugins.filter { it.appliesTo(compilation.target, compilation.scope) }) {
            roots += plugin.inputs
            plugin.module?.let { dependencies += ImpactCompilation(it, "jvm", "main") }
        }
        ImpactInputs(roots, dependencies)
    }

    val reasons = linkedMapOf<String, List<String>>()
    for ((check, request) in requested) {
        val roots = mutableSetOf<LogicalPath>()
        val reached = mutableSetOf<ImpactCompilation>()
        val pending = ArrayDeque<ImpactCompilation>()
        pending.addLast(request.compilation)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!reached.add(current)) continue
            val currentInputs = inputs(current)
            roots += currentInputs.roots
            currentInputs.dependencies.forEach { pending.addLast(it) }
        }
        val causes = effectiveFullReasons.map { "full:$it" }.toMutableSet()
        if (check !in baselineCompleted) causes += "baseline-missing"
        for (path in changedPaths) if (path in request.exact || roots.any { matches(path, it) }) causes += "changed:${path.value}"
        if (causes.isNotEmpty()) reasons[check] = causes.sorted()
    }
    return AffectedCheckSelection(requested.keys.toSet(), reasons.keys.toSet(), reasons,
        changedPaths.toSet(), effectiveFullReasons.toSet())
}

/** Select only test checks, preserving the original API and check IDs. */
fun selectAffectedTests(
    configuration: ProjectConfiguration,
    modules: Set<String>,
    targets: Set<String>,
    changedPaths: Set<LogicalPath>,
    baselineCompleted: Set<String>,
    fullReasons: Set<String> = emptySet(),
): AffectedTestSelection = selectAffectedChecks(configuration, modules, targets, setOf("test"), changedPaths, baselineCompleted, fullReasons)

private data class ImpactRequest(val compilation: ImpactCompilation, val exact: Set<LogicalPath>)
private data class ImpactCompilation(val module: String, val target: String, val scope: String)
private data class ImpactInputs(val roots: Set<LogicalPath>, val dependencies: Set<ImpactCompilation>)
