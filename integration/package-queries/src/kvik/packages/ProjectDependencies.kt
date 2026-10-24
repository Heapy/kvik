package kvik.packages

import kvik.core.*
import kvik.locking.*
import kvik.resolution.*

/** Filters affect display only. The complete lock is verified before any domain is reported. */
data class ProjectQueryFilter(val modules: List<String> = emptyList(), val targets: List<String> = emptyList(),
                              val compilation: String? = null, val usage: String? = null, val scripts: List<String> = emptyList()) {
    init {
        require(modules.size <= 1024 && modules.all { it.length <= 4096 } && modules.distinct().size == modules.size && targets.distinct().size == targets.size)
        require(targets.all { it == "jvm" || NativeTarget.find(it) != null || KotlinJsTarget.find(it) != null })
        require(scripts.size <= 256 && scripts.distinct().size == scripts.size && scripts.all { it.length in 1..128 && it.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*")) })
        require(compilation == null || compilation in setOf("main", "test", "script"))
        require(usage == null || usage in setOf("java-api", "java-runtime", "kotlin-api", "kotlin-runtime"))
    }
    fun includes(domain: ResolutionDomain): Boolean {
        val selected = if (modules.isEmpty() && scripts.isEmpty()) true else when (val owner = domain.consumer) {
            is ResolutionConsumer.Module -> owner.name in modules
            is ResolutionConsumer.Script -> owner.name in scripts
        }
        return selected &&
        (targets.isEmpty() || domain.target in targets) && (compilation == null || domain.compilation == compilation) &&
        (usage == null || domain.usage == usage)
    }
}

fun projectDeclarations(project: ProjectSpec, scripts: Map<String, ExpandedScript> = emptyMap()): List<JsonValue> = buildList {
    fun declaration(module: String, source: String?, kind: String, coordinate: Coordinate?, dependency: String?,
                    scope: String, exported: Boolean, targets: List<String>, compilations: List<String>) = obj(
        "module" to module.json(), "sourceSet" to (source?.json() ?: JsonValue.Null), "kind" to kind.json(),
        "coordinate" to (coordinate?.toString()?.json() ?: JsonValue.Null), "dependencyModule" to (dependency?.json() ?: JsonValue.Null),
        "scope" to scope.json(), "exported" to exported.json(), "targets" to targets.map(String::json).json(), "compilations" to compilations.map(String::json).json())
    for (module in project.modules) {
        for (source in module.sourceSets) {
            val reached = module.targets.flatMap { target -> listOfNotNull("main", target.test?.let { "test" }).mapNotNull { compilation ->
                val roots = if (compilation == "main") listOf(target.main) else listOf(target.main, target.test!!)
                if (sourceSetClosure(module, roots).any { it.name == source.name }) target.name to compilation else null
            } }
            for (dependency in source.dependencies) add(declaration(module.id, source.name,
                if (dependency is Dependency.External) "external" else "module", (dependency as? Dependency.External)?.coordinate,
                (dependency as? Dependency.Module)?.module, dependency.scope.name.lowercase(), dependency.exported,
                reached.map { it.first }.distinct(), reached.map { it.second }.distinct()))
        }
        for (plugin in module.compilerPlugins) add(declaration(module.id, null, "compiler-plugin", plugin.coordinate, plugin.module,
            "compile", false, plugin.targets.sorted(), plugin.scopes.sorted()))
        module.sqlDelight?.let { settings ->
            for (coordinate in sqlDelightCompilerCoordinates(settings.version)) add(declaration(module.id, settings.sourceSet,
                "sqldelight-compiler", coordinate, null, "runtime", false, listOf("jvm"), listOf("main")))
        }
        for (target in module.targets) target.nativeTestSettings?.let { settings ->
            for (artifact in listOf("symbol-processing-api", "symbol-processing-aa-embeddable", "symbol-processing-common-deps")) {
                add(declaration(module.id, null, "ksp-runtime", Coordinate("com.google.devtools.ksp", artifact, settings.kspVersion), null,
                    "runtime", false, listOf(target.name), listOf("test")))
            }
            add(declaration(module.id, null, "symbol-processor", settings.processor, null, "runtime", false, listOf(target.name), listOf("test")))
        }
    }
    for (name in project.scripts.keys) {
        val script = scripts[name] ?: expandScript(project, name)
        fun declared(kind: String, coordinate: Coordinate?, module: String?, scope: String, origin: ScriptOrigin) = obj(
            "script" to name.json(), "sourceSet" to JsonValue.Null, "kind" to kind.json(),
            "coordinate" to (coordinate?.toString()?.json() ?: JsonValue.Null), "dependencyModule" to (module?.json() ?: JsonValue.Null),
            "scope" to scope.json(), "exported" to false.json(), "targets" to listOf("jvm".json()).json(),
            "compilations" to listOf("script".json()).json(), "origin" to origin.toJson())
        for ((dependency, origin) in script.dependencies) add(declared(if (dependency is Dependency.External) "external" else "module",
            (dependency as? Dependency.External)?.coordinate, (dependency as? Dependency.Module)?.module, dependency.scope.name.lowercase(), origin))
        for ((plugin, origin) in script.compilerPlugins) add(declared("compiler-plugin", plugin.coordinate, plugin.module, "compile", origin))
    }

}

/** No network or write capability is accepted. Paths are reconstructed from the verified lock's bytes. */
fun queryProjectDependencies(projectPath: String, lockPath: String, storePath: String, configuration: ProjectConfiguration,
                             effective: EffectiveConfiguration, lockBytes: () -> ByteArray, content: ContentStore,
                             parser: PomParser, now: Long, filter: ProjectQueryFilter = ProjectQueryFilter()): JsonValue.Object {
    require(effective.document.objectValue().getValue("project") == configuration.document &&
        parseProjectConfiguration(configuration.document).project == configuration.project) { "Effective configuration and declarations disagree" }
    require(filter.modules.all { id -> configuration.project.modules.any { it.id == id } }) { "Unknown queried module" }
    require(filter.scripts.all { it in configuration.project.scripts }) { "Unknown queried script" }
    require(filter.targets.all { name -> name == "jvm" && configuration.project.scripts.isNotEmpty() || configuration.project.modules.any { module -> module.targets.any { it.name == name } } }) { "Unknown queried target" }
    val queryBase = obj("project" to projectPath.json(), "lock" to lockPath.json(), "store" to storePath.json(),
        "modules" to filter.modules.map(String::json).json(), "targets" to filter.targets.map(String::json).json(),
        "compilation" to (filter.compilation?.json() ?: JsonValue.Null), "usage" to (filter.usage?.json() ?: JsonValue.Null))
    val query = if (filter.scripts.isEmpty()) queryBase else JsonValue.Object(queryBase.objectValue() + ("scripts" to filter.scripts.map(String::json).json()))
    val diagnostics = mutableListOf<QueryDiagnostic>()
    val sources = linkedMapOf<String, QuerySource>()
    val domains = mutableListOf<JsonValue>()
    var lockDigest: Digest? = null
    var truncated = false
    var freshness = "fresh"
    var lockVerified = false
    try {
        val bytes = lockBytes()
        lockDigest = Sha256.digest(bytes)
        val lock = DependencyLocks(content, parser).loadLocked(bytes, effective)
        lockVerified = true
        val identities = linkedMapOf<String, Digest>()
        lock.selections.forEach { selection ->
            selection.metadata.forEach { metadata ->
                require(metadata.url.length <= 4096) { "Locked metadata URL exceeds query bound" }
                identities[metadata.url] = metadata.digest
                val matches = effective.repositoriesFor(selection.domain).filter { metadata.url.startsWith(it.url.trimEnd('/') + "/") }
                require(matches.size == 1) { "Ambiguous locked metadata repository provenance; overlapping repository bases are unsupported" }
                val repository = matches.single().id
                sources[metadata.url] = QuerySource(repository, metadata.url, "metadata", metadata.digest, null, "locked", "found", "fresh")
            }
            selection.artifacts.forEach { artifact ->
                require(artifact.url.length <= 4096 && artifact.repository.length <= 4096) { "Locked artifact identity exceeds query bound" }
                identities[artifact.url] = artifact.digest
                sources[artifact.url] = QuerySource(artifact.repository, artifact.url, "artifact", artifact.digest, null, "locked", "found", "fresh")
            }
        }
        val replay = object : ArtifactTransport {
            override fun fetch(url: String): ByteArray {
                val digest = identities[url] ?: throw MissingResource(url)
                return content.get(digest)?.also { require(Sha256.digest(it) == digest) { "Corrupt locked query input: $url" } }
                    ?: error("Missing locked query input: $url")
            }
        }
        val selected = effective.requests.groupBy { it.domain }.filterKeys(filter::includes)
        if (selected.size > 256) { truncated = true; diagnostics += QueryDiagnostic("KVIK_QUERY_TRUNCATED", "Project query exceeds 256 domains; select module/target/compilation/usage") }
        for ((domain, requests) in selected.entries.take(256)) {
            val graph = MavenResolver(replay, parser, effective.policy.pinsFor(domain), effective.policy.maxComponents,
                effective.policy.maxRequests, resolutionArtifactByteLimit(domain), effective.policy.exclusionsFor(domain)).resolveGraph(requests.map { it.coordinate }, requests.first().variant, effective.repositoriesFor(domain), requireAllExclusions = true)
            require(graph.selections == lock.selections.filter { it.domain == domain }.map { it.selection }) { "Query graph disagrees with locked selections: $domain" }
            val json = graph.toJson()
            if (json.values.getValue("truncated").booleanValue()) truncated = true
            domains += obj("domain" to domain.toJson(), "graph" to json)
        }
        if (truncated && diagnostics.isEmpty()) diagnostics += QueryDiagnostic("KVIK_QUERY_TRUNCATED", "Shortest-path output exceeds its bound; graph edges remain available")
    } catch (failure: Exception) {
        // A partially verified graph cannot represent a successful lock proof.
        domains.clear(); sources.clear()
        val message = failure.message ?: "Dependency lock is unavailable or invalid"
        val outputBound = lockVerified && message.contains("query", ignoreCase = true) && message.contains("bound", ignoreCase = true)
        if (outputBound) truncated = true else freshness = "indeterminate"
        diagnostics += QueryDiagnostic(if (outputBound) "KVIK_QUERY_BOUND" else "KVIK_QUERY_LOCK_INVALID", message)
    }
    var declarations = projectDeclarations(configuration.project, configuration.project.scripts.keys.associateWith { expandScript(configuration, it) })
    if (declarations.any { declaration -> declaration.objectValue().any { (name, value) ->
            value is JsonValue.StringValue && value.value.length > if (name == "coordinate") 1024 else 4096
        } }) {
        declarations = emptyList(); truncated = true
        diagnostics += QueryDiagnostic("KVIK_QUERY_TRUNCATED", "Declaration identity exceeds query string bound")
    }
    if (declarations.size > 16384) { truncated = true; diagnostics += QueryDiagnostic("KVIK_QUERY_TRUNCATED", "Declaration output exceeds 16,384 entries") }
    return JsonValue.Object(envelope("project-dependencies", query, now, sources.values.toList(), diagnostics,
        basis = "locked-inputs", freshness = freshness, truncated = truncated) + mapOf(
        "configuration" to effective.digest.toJson(), "dependencyLock" to (lockDigest?.toJson() ?: JsonValue.Null),
        "declarations" to declarations.take(16384).json(), "declarationsScope" to "all-project".json(), "domains" to domains.json()))
}
