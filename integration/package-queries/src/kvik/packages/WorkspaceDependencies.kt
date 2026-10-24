package kvik.packages

import kvik.core.*

/** Dependencies must come from queryProjectDependencies after replaying this project's lock. */
data class WorkspaceDependencyProject(val name: String, val source: SourceReference, val project: ProjectSpec,
                                      val dependencies: JsonValue.Object)

/** Drop artifact bodies, provenance lists, declarations and shortest paths after lock replay.
 * Workspace ownership needs only each scoped node's selected coordinate and identity. */
fun compactWorkspaceDependencyQuery(query: JsonValue.Object, checkpoint: () -> Unit = {}): JsonValue.Object {
    checkpoint()
    val fields = query.values.filterKeys { it in setOf("schemaVersion", "kind", "freshnessBasis", "status", "truncated",
        "configuration", "dependencyLock", "diagnostics") }
    val domains = query.values.getValue("domains").arrayValue().map { entry ->
        checkpoint()
        val domain = entry.objectValue()
        val graph = domain.getValue("graph").objectValue()
        val nodes = graph.getValue("nodes").arrayValue().map { node ->
            checkpoint()
            val value = node.objectValue()
            obj("id" to value.getValue("id"), "coordinate" to value.getValue("coordinate"))
        }
        obj("domain" to domain.getValue("domain"), "graph" to JsonValue.Object(
            graph.filterKeys { it in setOf("truncated", "diagnostics") } + ("nodes" to nodes.json())))
    }
    return JsonValue.Object(fields + ("domains" to domains.json())).also { checkpoint() }
}

private data class WorkspacePublication(val project: String, val module: String, val target: String?, val coordinate: Coordinate) {
    fun toJson() = obj("project" to project.json(), "module" to module.json(),
        "target" to (target?.json() ?: JsonValue.Null), "coordinate" to coordinate.toString().json())
}

/** Declared publication ownership is not a claim that current source reproduces the locked artifact. */
fun queryWorkspaceDependencies(projects: List<WorkspaceDependencyProject>, failures: List<JsonValue>, now: Long,
                               checkpoint: () -> Unit = {}): JsonValue.Object {
    checkpoint()
    require(now in 0..253402297199L) { "Invalid workspace query observation time" }
    require(projects.size + failures.size in 0..256) { "Workspace query supports at most 256 projects" }
    val failureNames = failures.map { it.objectValue().getValue("project").stringValue() }
    val names = projects.map { it.name } + failureNames
    require(names.distinct().size == names.size && names.all { it.length in 1..4096 && it.none { c -> c.code < 32 || c.code == 127 } }) {
        "Workspace project names must be unique nonempty identities"
    }
    // Account for serialized entries before accumulating them; the final envelope is checked as well.
    var outputBytes = 0L
    fun bounded(value: JsonValue): JsonValue {
        checkpoint()
        outputBytes += value.canonical().encodeToByteArray().size.toLong() + 1
        require(outputBytes <= 16L * 1024 * 1024) { "Workspace dependency query exceeds 16 MiB response bound" }
        return value
    }
    val diagnostics = mutableListOf<JsonValue>()
    var incomplete = failures.isNotEmpty()
    var truncated = false
    fun diagnostic(project: String, value: JsonValue, domain: JsonValue? = null) {
        val fields = value.objectValue() + ("project" to project.json()) +
            (domain?.let { mapOf("domain" to it) } ?: emptyMap())
        diagnostics += bounded(JsonValue.Object(fields))
    }
    for (failure in failures) {
        val fields = failure.objectValue()
        require(fields.getValue("code").stringValue().isNotBlank() && fields.getValue("message") is JsonValue.StringValue)
        diagnostic(fields.getValue("project").stringValue(), failure)
    }
    val publications = mutableListOf<WorkspacePublication>()
    fun publication(value: WorkspacePublication) {
        require(publications.size < 16384) { "Workspace dependency query exceeds 16,384 publications" }
        bounded(value.toJson())
        publications += value
    }
    for (input in projects.sortedBy { it.name }) for (module in input.project.modules.sortedBy { it.id }) {
        checkpoint()
        module.publication?.let { spec ->
            // Single-target packages emit only the suffixed coordinate. A root requires KMP packaging.
            if (module.targets.size > 1) publication(WorkspacePublication(input.name, module.id, null, spec.coordinate))
            for (target in module.targets.sortedBy { it.name }) {
                publication(WorkspacePublication(input.name, module.id, target.name, spec.coordinateFor(target.name)))
            }
        }
    }
    val sortedPublications = publications.sortedWith(compareBy({ it.project }, { it.module }, { it.target ?: "" }, { it.coordinate.toString() }))
    val byCoordinate = sortedPublications.groupBy { it.coordinate.toString() }
    val byModule = sortedPublications.groupBy { it.coordinate.group to it.coordinate.name }
    val summaries = mutableListOf<JsonValue>()
    val edges = mutableListOf<JsonValue>()
    for (input in projects.sortedBy { it.name }) {
        checkpoint()
        val query = input.dependencies.values
        require(query.getValue("schemaVersion").longValue() == 1L && query.getValue("kind").stringValue() == "project-dependencies" &&
            query.getValue("freshnessBasis").stringValue() == "locked-inputs") { "Expected a locked project dependency query" }
        val status = query.getValue("status").stringValue()
        require(status in setOf("complete", "incomplete")) { "Unknown project dependency query status" }
        val projectTruncated = query.getValue("truncated").booleanValue()
        truncated = truncated || projectTruncated
        val lock = query.getValue("dependencyLock")
        val missingLock = lock == JsonValue.Null
        val sourceDiagnostics = query.getValue("diagnostics").arrayValue()
        if (status != "complete" || projectTruncated || missingLock || sourceDiagnostics.isNotEmpty()) incomplete = true
        for (value in sourceDiagnostics) diagnostic(input.name, value)
        if (missingLock) diagnostic(input.name, QueryDiagnostic("KVIK_WORKSPACE_LOCK_MISSING", "Project has no verified dependency lock").toJson())
        if ((status != "complete" || projectTruncated) && sourceDiagnostics.isEmpty()) {
            diagnostic(input.name, QueryDiagnostic("KVIK_WORKSPACE_QUERY_INCOMPLETE", "Project dependency query is incomplete or truncated").toJson())
        }
        summaries += bounded(obj("name" to input.name.json(), "source" to input.source.toJson(),
            "configuration" to query.getValue("configuration"), "dependencyLock" to lock,
            "queryStatus" to (if (status == "complete" && !projectTruncated && !missingLock && sourceDiagnostics.isEmpty()) "complete" else "incomplete").json()))
        if (missingLock) continue
        val domains = query.getValue("domains").arrayValue().sortedBy { it.objectValue().getValue("domain").canonical() }
        for (entry in domains) {
            val fields = entry.objectValue()
            val domain = fields.getValue("domain")
            val graph = fields.getValue("graph").objectValue()
            val graphTruncated = graph.getValue("truncated").booleanValue()
            if (graphTruncated) {
                incomplete = true; truncated = true
                diagnostic(input.name, QueryDiagnostic("KVIK_WORKSPACE_DOMAIN_TRUNCATED", "Dependency domain query is truncated").toJson(), domain)
            }
            for (value in graph["diagnostics"]?.arrayValue().orEmpty()) {
                incomplete = true
                diagnostic(input.name, value, domain)
            }
            for (node in graph.getValue("nodes").arrayValue().sortedBy { it.objectValue().getValue("id").stringValue() }) {
                require(edges.size < 65536) { "Workspace dependency query exceeds 65,536 edges" }
                val nodeFields = node.objectValue()
                val coordinate = Coordinate.parse(nodeFields.getValue("coordinate").stringValue())
                val exact = byCoordinate[coordinate.toString()].orEmpty()
                val otherVersions = byModule[coordinate.group to coordinate.name].orEmpty().map { it.coordinate.toString() }
                    .filter { it != coordinate.toString() }.distinct().sorted()
                val match = when {
                    exact.size > 1 -> "ambiguous"
                    exact.size == 1 -> "exact"
                    otherVersions.isNotEmpty() -> "version-mismatch"
                    else -> "external"
                }
                if (exact.size > 1) {
                    incomplete = true
                    diagnostic(input.name, obj("code" to "KVIK_WORKSPACE_PUBLICATION_AMBIGUOUS".json(),
                        "message" to "Multiple workspace publications declare $coordinate".json(), "node" to nodeFields.getValue("id")), domain)
                }
                edges += bounded(obj("consumer" to input.name.json(), "domain" to domain, "node" to nodeFields.getValue("id"),
                    "coordinate" to coordinate.toString().json(), "match" to match.json(), "producers" to exact.map { it.toJson() }.json(),
                    "otherVersions" to otherVersions.map(String::json).json(), "sourceArtifactEquivalence" to "unchecked".json()))
            }
        }
    }
    val result = JsonValue.Object(envelope("workspace-dependencies", obj("projects" to names.sorted().map(String::json).json()), now,
        emptyList(), emptyList(), status = if (incomplete) "incomplete" else "complete", basis = "locked-inputs",
        freshness = if (incomplete) "indeterminate" else "fresh", truncated = truncated) + mapOf(
        "projects" to summaries.json(), "publications" to sortedPublications.map { it.toJson() }.json(), "edges" to edges.json(),
        "diagnostics" to diagnostics.sortedBy { it.canonical() }.json()))
    require(result.canonical().encodeToByteArray().size.toLong() + 1 <= 16L * 1024 * 1024) { "Workspace dependency query exceeds 16 MiB response bound" }
    checkpoint()
    return result
}
