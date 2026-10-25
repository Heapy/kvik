package kvik.projectgraph

import kvik.core.*
import kvik.locking.*

/** Verified configuration and immutable inputs available to source build plugins. */
data class ProjectBuildContext(
    val effective: EffectiveConfiguration,
    val lock: DependencyLock,
    val snapshot: TreeManifest,
    val composition: Digest,
    val toolchains: Map<String, Map<String, Digest>>,
)

/** Exactly one plugin owns the requested project and script compilation graph. */
interface ProjectBuildPlugin : BuildPlugin {
    /** Opt in only when the core module/source-set impact model covers every graph input. */
    val usesCoreProjectImpact: Boolean get() = false
    fun projectGraph(context: ProjectBuildContext, request: ProjectGraphRequest): ProjectActionGraph
    fun scriptGraph(context: ProjectBuildContext, request: ScriptGraphRequest): ProjectActionGraph
}

/** Additive callbacks have no input-impact contract, so their composition must select all checks. */
fun supportsCoreChangeSelection(plugins: List<BuildPlugin>): Boolean =
    (plugins.singleOrNull() as? ProjectBuildPlugin)?.usesCoreProjectImpact == true

/** Adds actions using existing executor adapters; cannot replace the provider's actions or checks. */
interface ProjectGraphContributor : BuildPlugin {
    fun contribute(context: ProjectBuildContext, graph: ProjectActionGraph): List<ActionNode>
}

fun composedProjectActionGraph(
    plugins: List<BuildPlugin>, context: ProjectBuildContext, request: ProjectGraphRequest,
): ProjectActionGraph = compose(plugins, context, projectRequestedChecks(request)) { provider ->
    provider.projectGraph(context, request)
}

fun composedScriptActionGraph(
    plugins: List<BuildPlugin>, context: ProjectBuildContext, request: ScriptGraphRequest,
): ProjectActionGraph = compose(plugins, context, setOf("script:${request.name}:jvm:run")) { provider ->
    provider.scriptGraph(context, request)
}

private fun compose(
    plugins: List<BuildPlugin>, context: ProjectBuildContext, expectedChecks: Set<String>,
    provide: (ProjectBuildPlugin) -> ProjectActionGraph,
): ProjectActionGraph {
    val ordered = plugins.toList().sortedBy { it.id }
    require(ordered.all { it.id.isNotBlank() }) { "Build plugin IDs must not be blank" }
    require(ordered.map { it.id }.distinct().size == ordered.size) { "Duplicate build plugin ID" }
    require(ordered.all { it.apiVersion == CONTRACT_VERSION }) { "Incompatible build plugin API version" }
    val providers = ordered.filterIsInstance<ProjectBuildPlugin>()
    require(providers.size == 1) { "Exactly one project build plugin is required" }
    val provider = providers.single()
    val supplied = provide(provider)
    require(expectedChecks.isNotEmpty() && supplied.requestedChecks == expectedChecks && supplied.terminalActions.keys == expectedChecks) {
        "Build plugin graph must cover exactly the requested checks"
    }
    val terminals = supplied.terminalActions.toMap()
    require(terminals.values.distinct().size == terminals.size) { "A terminal action cannot satisfy multiple checks" }
    val nodes = supplied.nodes.map { guardedNode(it, context.composition, contribution = it.id in supplied.contributedActions) }.toMutableList()
    val contributed = supplied.contributedActions.toMutableSet()
    require(contributed.all { id -> nodes.any { it.id == id } }) { "Unknown contributed action" }
    validateNodes(nodes)
    require(terminals.values.all { terminal -> nodes.any { it.id == terminal } }) { "Build plugin graph omits a terminal action" }
    for (plugin in ordered.filter { it !== provider }) {
        val added = if (plugin is ProjectGraphContributor) {
            plugin.contribute(context, ProjectActionGraph(nodes.map { it.copy(dependencies = it.dependencies.toSet()) },
                expectedChecks.toSet(), terminals.toMap(), contributed.toSet(), supplied.generatedSources.toList()))
        } else {
            val authoring = context.effective.document.objectValue()
            val document = authoring["project"] ?: authoring["standaloneScript"] ?: error("Missing plugin authoring document")
            plugin.actions(parseJson(document.canonical()) as JsonValue.Object)
        }
        nodes += added.map { guardedNode(it, context.composition, contribution = true) }
        contributed += added.map { it.id }
    }
    validateNodes(nodes)
    return ProjectActionGraph(nodes.toList(), expectedChecks.toSet(), terminals, contributed.toSet(), supplied.generatedSources.toList())
}

private fun validateNodes(nodes: List<ActionNode>) {
    require(nodes.all { it.id.isNotBlank() }) { "Blank build plugin action ID" }
    require(nodes.map { it.id }.distinct().size == nodes.size) { "Duplicate build plugin action ID" }
    validateGraph(nodes.associate { it.id to it.dependencies })
}

private fun guardedNode(node: ActionNode, composition: Digest, contribution: Boolean): ActionNode =
    ActionNode(node.id, node.dependencies.toSet()) { results ->
        val action = node.resolve(results)
        require(action.composition == composition) { "Build plugin action uses a different source composition: ${node.id}" }
        require(!contribution || action.cachePolicy != CachePolicy.REMOTE) {
            "Contributed build plugin actions cannot use remote caching: ${node.id}"
        }
        require(!contribution || action.kind !in setOf(ActionKind.TEST, ActionKind.SCRIPT)) {
            "Contributed test and script execution require a declared check; use the graph provider: ${node.id}"
        }
        action
    }
