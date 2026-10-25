@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.locking.*
import kvik.packages.*
import kvik.execution.platform.*
import kvik.resolution.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.time.Clock

/** Query output has its own bounded versioned schema; no build/test execution is reachable. */
internal fun printPackageQuery(value: JsonValue.Object): ExitStatus {
    var result = value
    if (result.canonical().encodeToByteArray().size > 16 * 1024 * 1024) {
        val fields = result.values.toMutableMap()
        fields["status"] = "incomplete".json(); fields["truncated"] = true.json()
        fields["diagnostics"] = listOf(QueryDiagnostic("KVIK_QUERY_TRUNCATED", "Query JSON exceeds 16 MiB; narrow the query").toJson()).json()
        fields["sources"] = emptyList<JsonValue>().json()
        when (fields.getValue("kind").stringValue()) {
            "package-dependencies" -> fields["graph"] = JsonValue.Null
            "project-dependencies" -> { fields["domains"] = emptyList<JsonValue>().json(); fields["declarations"] = emptyList<JsonValue>().json(); fields.remove("availableVersions") }
            "package-versions" -> fields["versions"] = emptyList<JsonValue>().json()
        }
        result = JsonValue.Object(fields)
    }
    println(result.canonical())
    return if (result.values.getValue("status").stringValue() == "complete") ExitStatus.SUCCESS else ExitStatus.INCOMPLETE
}

internal data class QueryArguments(val values: Map<String, String>, val repeats: Map<String, List<String>>, val flags: Set<String>)
internal fun queryArguments(args: List<String>, values: Set<String>, repeats: Set<String> = emptySet(), flags: Set<String> = emptySet()): QueryArguments {
    val selected = linkedMapOf<String, String>(); val repeated = linkedMapOf<String, MutableList<String>>(); val enabled = linkedSetOf<String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        if (flag in flags || flag == "--json") { require(enabled.add(flag)) { "Duplicate option: $flag" }; continue }
        require(flag in values || flag in repeats) { "Unsupported option: $flag" }
        val value = args.getOrNull(index++)
        require(value != null && value.isNotBlank() && !value.startsWith("--") && value.length <= 4096) { "Missing or oversized value for $flag" }
        if (flag in repeats) repeated.getOrPut(flag) { mutableListOf() } += value
        else require(selected.put(flag, value) == null) { "Duplicate option: $flag" }
    }
    return QueryArguments(selected, repeated, enabled)
}

private fun cacheOutsideInput(cache: String, input: String) {
    require(input != cache && !input.startsWith("$cache/")) { "Query cache must not contain project configuration or dependency lock" }
    memScoped {
        val a = alloc<stat>(); val b = alloc<stat>()
        var parent = input
        while (parent.isNotEmpty()) {
            val left = lstat(cache, a.ptr); val leftError = errno
            val right = lstat(parent, b.ptr); val rightError = errno
            require(left == 0 || leftError == ENOENT) { "Cannot inspect query cache" }
            require(right == 0 || rightError == ENOENT) { "Cannot inspect query input" }
            require(left != 0 || right != 0 || a.st_dev != b.st_dev || a.st_ino != b.st_ino) { "Query cache aliases a project input or its directory" }
            parent = parent.substringBeforeLast('/', "")
        }
    }
}

/** A source query reads one immutable revision; only local files need a freshness recheck. */
private class PackageQueryProject(val project: ProjectFile, val options: ProjectOptions, val source: PreparedSourceProject?) {
    val path: String get() = source?.revisionPath(source.configuration.name)?.value ?: project.path
    val lock: String get() = source?.revisionPath(LogicalPath(options.lock))?.value ?: projectPath(project.root, options.lock)
    val store: String get() = projectPath(project.root, options.store)
    fun cache(path: String?): String {
        val cache = projectPath(project.root, path ?: ".kvik/package-queries")
        if (source == null) {
            cacheOutsideInput(cache, project.path); cacheOutsideInput(cache, lock)
        } else for (input in listOf(source.options.executable, source.options.home, store)) {
            require(!pathsPhysicallyOverlap(cache, input)) { "Query cache overlaps source provider or content store: $input" }
        }
        return cache
    }
    fun finish(result: JsonValue.Object): JsonValue.Object = if (source == null) unchangedQueryProject(result, project)
        else JsonValue.Object(result.values + ("source" to source.reference.toJson())).also { sourceCheckpoint() }
}

private fun <T> withPackageQueryProject(source: KodeProjectOptions?, options: ProjectOptions,
                                        block: (PackageQueryProject) -> T): T {
    if (source == null) return block(PackageQueryProject(readProject(options.project), options, null))
    check(kvik_interrupt_install() == 0) { "Cannot install source query interruption handlers" }
    try {
        return withPreparedSourceProject(source, options) { prepared -> block(PackageQueryProject(prepared.project, options, prepared)) }
    } finally { kvik_interrupt_restore() }
}

internal fun packageCommand(args: List<String>): JsonValue.Object {
    val operation = args.firstOrNull()
    require(operation in setOf("versions", "dependencies")) { "Expected packages versions or packages dependencies" }
    val coordinate = args.getOrNull(1) ?: error("Missing package coordinate")
    val versionQuery = operation == "versions"
    val (source, remaining) = sourceProjectOptions(args.drop(2))
    val options = queryArguments(remaining, setOf("--project", "--query-cache") + (if (source != null) setOf("--store") else emptySet()) +
        if (versionQuery) setOf("--channel", "--limit", "--cursor") else setOf("--target", "--usage", "--jvm-target"),
        repeats = if (versionQuery) emptySet() else setOf("--pin"), flags = setOf("--offline", "--refresh"))
    val offline = "--offline" in options.flags || getenv("KVIK_BOOTSTRAP_OFFLINE")?.toKString() == "1"
    require(!offline || "--refresh" !in options.flags) { "Refresh cannot be combined with offline" }
    val mode = if (offline) QueryMode.OFFLINE else if ("--refresh" in options.flags) QueryMode.REFRESH else QueryMode.CACHE
    val selected = ProjectOptions(project = options.values["--project"] ?: "kvik.json",
        store = options.values["--store"] ?: ".kvik/cache", offline = offline)
    return withPackageQueryProject(source, selected) { context ->
        val project = context.project
        val cache = context.cache(options.values["--query-cache"])
        val repositories = project.configuration.project.repositories
        require(repositories.isNotEmpty()) { "Package queries require configured repositories" }
        val transport = QueryTransport(repositories, NativeQueryCache(cache, allowRepair = mode == QueryMode.REFRESH),
            if (offline) null else NativeHttpsTransport(), mode, Clock.System.now().epochSeconds)
        val result = if (versionQuery) queryVersions(context.path, PackageId.parse(coordinate), repositories, transport, NativeVersionXmlParser(),
            options.values["--channel"] ?: "all", options.values["--limit"]?.toIntOrNull() ?: if ("--limit" in options.values) error("Invalid page limit") else 50,
            options.values["--cursor"], context.source?.reference)
        else {
            val target = options.values["--target"] ?: error("Package dependencies requires --target")
            val usage = options.values["--usage"] ?: error("Package dependencies requires --usage")
            require(target == "jvm" || NativeTarget.find(target) != null || KotlinJsTarget.find(target) != null) { "Unsupported query target" }
            val variant = if (target == "jvm") {
                val jvm = options.values["--jvm-target"]?.let { value -> require(value in setOf("11", "17", "21", "25")); value.toInt() } ?: DEFAULT_JVM_TARGET
                jvmVariantRequest(usage, jvm)
            } else {
                require("--jvm-target" !in options.values) { "JVM bytecode target requires a JVM metadata query" }
                VariantRequest(target, usage, KotlinJsTarget.find(target)?.variantAttributes.orEmpty())
            }
            val pins = options.repeats["--pin"].orEmpty().map(Coordinate::parse)
            ResolverPolicy(pins) // Conflicting pins are invalid arguments, not unsupported producer metadata.
            queryPackageDependencies(context.path, Coordinate.parse(coordinate), variant, repositories, pins, transport, NativePomParser())
        }
        context.finish(result)
    }
}

private fun unchangedQueryProject(result: JsonValue.Object, project: ProjectFile): JsonValue.Object {
    val unchanged = try {
        NativeFiles.read(project.path, 4 * 1024 * 1024).contentEquals(project.bytes) &&
            project.configuration.withScriptDeclarations(localScriptSourceReader(project.root)).scriptDeclarations == project.configuration.scriptDeclarations
    } catch (_: Exception) { false }
    return if (unchanged) result else JsonValue.Object(result.values + mapOf("status" to "incomplete".json(), "freshness" to "indeterminate".json(),
        "diagnostics" to (result.values.getValue("diagnostics").arrayValue() + QueryDiagnostic("KVIK_QUERY_INPUT_CHANGED", "Project configuration or script declarations changed during query; retry").toJson()).json()))
}

internal fun projectDependenciesCommand(args: List<String>): JsonValue.Object {
    val (source, remaining) = sourceProjectOptions(args)
    val options = queryArguments(remaining, setOf("--project", "--store", "--lock", "--compilation", "--usage", "--query-cache", "--channel", "--limit", "--cursor"),
        repeats = setOf("--pin", "--scope-pin", "--exclude-metadata-edge", "--module", "--script", "--target", "--package"), flags = setOf("--offline", "--available-versions", "--refresh"))
    val available = "--available-versions" in options.flags
    require(available || options.values.keys.none { it in setOf("--query-cache", "--channel", "--limit", "--cursor") } && "--package" !in options.repeats && "--refresh" !in options.flags) {
        "Version query options require --available-versions"
    }
    val offline = "--offline" in options.flags || getenv("KVIK_BOOTSTRAP_OFFLINE")?.toKString() == "1"
    require(!offline || "--refresh" !in options.flags) { "Refresh cannot be combined with offline" }
    val channel = options.values["--channel"] ?: "all"
    val limit = options.values["--limit"]?.let { it.toIntOrNull() ?: error("Invalid page limit") } ?: 50
    require(channel in setOf("all", "stable", "prerelease") && limit in 1..200) { "Invalid version query channel/limit" }
    val packages = options.repeats["--package"].orEmpty().map(PackageId::parse)
    require(packages.size <= 32 && packages.distinct().size == packages.size) { "Select at most 32 distinct packages" }
    val cursor = options.values["--cursor"]
    require(cursor == null || packages.size == 1) { "A version cursor requires exactly one --package" }
    val projectFlags = buildList {
        options.values.filterKeys { it in setOf("--project", "--store", "--lock") }.forEach { (key, value) -> add(key); add(value) }
        options.repeats.filterKeys { it in setOf("--pin", "--scope-pin", "--exclude-metadata-edge") }.forEach { (key, values) -> values.forEach { add(key); add(it) } }
        if ("--offline" in options.flags) add("--offline")
    }
    val selected = projectOptions(projectFlags, lockOptions = true)
    return withPackageQueryProject(source, selected) { context ->
        val project = context.project
        val effective = effectiveConfiguration(project.configuration, selected.resolverPolicy())
        val lock = context.lock; val store = context.store
        val content by lazy { ReadOnlyContentStore(store, effective.maxArtifactBytes) }
        val readOnly = object : ContentStore {
            override fun put(bytes: ByteArray): Digest = error("Project queries cannot write content")
            override fun get(digest: Digest) = content.get(digest)
        }
        var observedLock: ByteArray? = null
        var result = queryProjectDependencies(context.path, lock, store, project.configuration, effective,
            {
                val prepared = context.source
                if (prepared != null) prepared.read(prepared.entry(LogicalPath(selected.lock))
                    ?: error("Dependency lock is absent from source revision"), store, 16 * 1024 * 1024)
                else {
                    NativeFiles.ensureDirectory(lock.substringBeforeLast('/'), create = false)
                    NativeFiles.read(lock, 16 * 1024 * 1024).also { observedLock = it }
                }
            }, readOnly, NativePomParser(), Clock.System.now().epochSeconds,
            ProjectQueryFilter(options.repeats["--module"].orEmpty(), options.repeats["--target"].orEmpty(), options.values["--compilation"], options.values["--usage"], options.repeats["--script"].orEmpty()))
        if (available) {
            val cachePath = context.cache(options.values["--query-cache"])
            val mode = if (offline) QueryMode.OFFLINE else if ("--refresh" in options.flags) QueryMode.REFRESH else QueryMode.CACHE
            val cache = NativeQueryCache(cachePath, allowRepair = mode == QueryMode.REFRESH)
            val network = if (offline) null else NativeHttpsTransport()
            val now = Clock.System.now().epochSeconds
            result = withAvailableVersions(result, effective, packages.toSet(), singleQuery = cursor != null) { id, repositories ->
                queryVersions(context.path, id, repositories, QueryTransport(repositories, cache, network, mode, now),
                    NativeVersionXmlParser(), channel, limit, cursor, context.source?.reference)
            }
        }
        if (observedLock != null) {
            val unchanged = try { NativeFiles.read(lock, 16 * 1024 * 1024).contentEquals(observedLock) } catch (_: Exception) { false }
            if (!unchanged) result = JsonValue.Object(result.values + mapOf("status" to "incomplete".json(), "freshness" to "indeterminate".json(),
                "diagnostics" to (result.values.getValue("diagnostics").arrayValue() + QueryDiagnostic("KVIK_QUERY_INPUT_CHANGED", "Dependency lock changed during query; retry").toJson()).json()))
        }
        context.finish(result)
    }
}
