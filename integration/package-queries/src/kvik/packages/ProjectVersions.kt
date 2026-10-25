package kvik.packages

import kvik.core.*
import kvik.locking.*

/** Adds repository observations to the displayed lock scope without resolving a new graph. */
fun withAvailableVersions(result: JsonValue.Object, effective: EffectiveConfiguration,
                          packages: Set<PackageId> = emptySet(), singleQuery: Boolean = false,
                          query: (PackageId, List<Repository>) -> JsonValue.Object): JsonValue.Object {
    data class Key(val id: PackageId, val repositories: List<Repository>)
    data class Selection(val versions: MutableSet<String> = linkedSetOf(), val domains: MutableSet<ResolutionDomain> = linkedSetOf())
    val selections = linkedMapOf<Key, Selection>()
    for (scoped in result.values.getValue("domains").arrayValue()) {
        val fields = scoped.objectValue()
        val domain = ResolutionDomain.fromJson(fields.getValue("domain"))
        val repositories = effective.repositoriesFor(domain)
        for (node in fields.getValue("graph").objectValue().getValue("nodes").arrayValue()) {
            val coordinate = Coordinate.parse(node.objectValue().getValue("coordinate").stringValue())
            val id = PackageId(coordinate.group, coordinate.name)
            if (packages.isNotEmpty() && id !in packages) continue
            val selection = selections.getOrPut(Key(id, repositories)) { Selection() }
            selection.versions += coordinate.version
            selection.domains += domain
        }
    }
    if (result.values.getValue("status").stringValue() == "complete") {
        require(packages.all { id -> selections.keys.any { it.id == id } }) { "Requested package is absent from the selected dependency scope" }
        require(!singleQuery || selections.size == 1) { "A version cursor requires one package/repository combination; narrow the dependency scope" }
    }
    // Bound network work independently of the potentially much larger dependency graph.
    val limit = 32
    val truncated = selections.size > limit
    val observations = selections.entries.sortedBy { it.key.id.toString() }.take(limit).map { (key, selection) ->
        obj("lockedVersions" to selection.versions.sorted().map(String::json).json(),
            "domains" to selection.domains.map { it.toJson() }.json(), "available" to query(key.id, key.repositories))
    }
    val incomplete = observations.any { it.values.getValue("available").objectValue().getValue("status").stringValue() != "complete" }
    val diagnostics = result.values.getValue("diagnostics").arrayValue().toMutableList()
    if (truncated) diagnostics += QueryDiagnostic("KVIK_QUERY_TRUNCATED",
        "Available-version query exceeds 32 package/repository combinations; select packages or narrow module, script, target or compilation scope").toJson()
    if (incomplete) diagnostics += QueryDiagnostic("KVIK_QUERY_VERSIONS_INCOMPLETE",
        "Some package version observations are unavailable; inspect availableVersions.packages[].available.diagnostics").toJson()
    return JsonValue.Object(result.values + mapOf(
        "status" to (if (truncated || incomplete) "incomplete".json() else result.values.getValue("status")),
        "truncated" to (truncated || result.values.getValue("truncated").booleanValue()).json(),
        "diagnostics" to diagnostics.take(1024).json(),
        "availableVersions" to obj("packageLimit" to limit.json(), "totalPackages" to selections.size.json(), "packages" to observations.json())))
}
