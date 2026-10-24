@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.core.*
import kvik.execution.platform.*
import kvik.kodesource.*
import kvik.locking.*
import kvik.packages.*
import kvik.projectsnapshot.*
import kvik.resolution.*
import kotlin.time.Clock

/** Registry discovery is live; all configuration and lock reads then use pinned immutable revisions. */
internal fun workspaceCommand(args: List<String>): JsonValue.Object {
    require(args.firstOrNull() == "dependencies") { "Expected workspace dependencies" }
    val options = queryArguments(args.drop(1),
        setOf("--kode", "--kode-home", "--project", "--lock", "--store", "--revision"),
        repeats = setOf("--source-project", "--at", "--configuration", "--dependency-lock", "--pin", "--scope-pin", "--exclude-metadata-edge",
            "--project-pin", "--project-scope-pin", "--project-exclude-metadata-edge"))
    val directory = currentDirectory()
    val defaults = ProjectOptions()
    val executable = projectPath(directory, requireNotNull(options.values["--kode"]) { "--kode is required" })
    val home = projectPath(directory, requireNotNull(options.values["--kode-home"]) { "--kode-home is required" })
    val store = projectPath(directory, options.values["--store"] ?: defaults.store)
    val configuration = LogicalPath(options.values["--project"] ?: defaults.project)
    val lock = LogicalPath(options.values["--lock"] ?: defaults.lock)
    val selectedNames = options.repeats["--source-project"].orEmpty()
    require(selectedNames.distinct().size == selectedNames.size && selectedNames.size <= 256) { "Duplicate or excessive workspace projects" }
    fun overrides(flag: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        options.repeats[flag].orEmpty().forEach { value ->
            val name = value.substringBefore('=')
            val selection = value.substringAfter('=', "")
            require(selection.isNotBlank() && result.put(name, selection) == null) { "$flag requires unique PROJECT=VALUE entries" }
        }
        return result
    }
    val revisions = overrides("--at")
    val configurations = overrides("--configuration").mapValues { LogicalPath(it.value) }
    val locks = overrides("--dependency-lock").mapValues { LogicalPath(it.value) }
    val projectPolicy = linkedMapOf<String, MutableList<String>>()
    for (flag in listOf("--pin", "--scope-pin", "--exclude-metadata-edge")) {
        options.repeats["--project-${flag.removePrefix("--")}"].orEmpty().forEach { value ->
            val name = value.substringBefore('=')
            val selection = value.substringAfter('=', "")
            require(selection.isNotBlank()) { "Project resolver options require PROJECT=VALUE" }
            projectPolicy.getOrPut(name) { mutableListOf() }.addAll(listOf(flag, selection))
        }
    }
    val commonPolicy = buildList {
        for (flag in listOf("--pin", "--scope-pin", "--exclude-metadata-edge")) {
            options.repeats[flag].orEmpty().forEach { add(flag); add(it) }
        }
    }
    projectOptions(commonPolicy, lockOptions = true).resolverPolicy()
    check(kvik_interrupt_install() == 0) { "Cannot install workspace query interruption handlers" }
    try {
        return withSourceWorkspace { scratch -> runtimeProcessHost().use { host ->
            val provider = KodeSourceProvider(NativeKodeTransport(host, executable, home, scratch))
            val registry = provider.projects(::sourceCheckpoint)
            val known = registry.map { it.name }.toSet()
            require(selectedNames.all { it in known }) { "Unknown workspace project selection" }
            val selected = registry.filter { selectedNames.isEmpty() || it.name in selectedNames }
            val names = selected.map { it.name }.toSet()
            require((revisions.keys + configurations.keys + locks.keys + projectPolicy.keys).all { it in names }) { "Workspace overrides must identify selected registered projects" }
            val projects = mutableListOf<WorkspaceDependencyProject>()
            val failures = mutableListOf<JsonValue>()
            var retainedBytes = 0L
            var retainedNodes = 0L
            val now = Clock.System.now().epochSeconds
            for (entry in selected) {
                sourceCheckpoint()
                try {
                    val selector = revisions[entry.name] ?: options.values["--revision"] ?: entry.main
                        ?: error("Registered project has no main revision; select it with --at")
                    val resolved = provider.resolve(SourceSelector(entry.name, selector), ::sourceCheckpoint)
                    val configPath = configurations[entry.name] ?: configuration
                    val root = configPath.value.substringBeforeLast('/', "")
                    fun read(path: LogicalPath, maxBytes: Int): ByteArray {
                        val file = resolved.manifest.files.singleOrNull { it.path == path }
                            ?: error("Source file is absent: ${path.value}")
                        require(file.blob.sizeBytes <= maxBytes) { "Source file exceeds query bound: ${path.value}" }
                        return requireNotNull(resolved.fetch(file.blob, maxBytes, ::sourceCheckpoint)) { "Source file is unavailable: ${path.value}" }
                            .also { resolved.verify(file.blob, it, ::sourceCheckpoint) }
                    }
                    val configurationBytes = read(configPath, 4 * 1024 * 1024)
                    val parsed = parseProjectConfiguration(parseJson(configurationBytes.decodeToString(throwOnInvalidSequence = true)))
                        .withScriptDeclarations(boundedScriptSourceReader { path ->
                            val data = read(LogicalPath(if (root.isEmpty()) path.value else "$root/${path.value}"), 4 * 1024 * 1024)
                            retainedBytes += data.size
                            if (retainedBytes > 16 * 1024 * 1024) throw CliFailure("KVIK_WORKSPACE_BOUND", DiagnosticCategory.CONFIGURATION,
                                "Workspace script inputs exceed 16 MiB; select fewer projects", ExitStatus.INCOMPLETE)
                            data
                        })
                    val policy = projectOptions(commonPolicy + projectPolicy[entry.name].orEmpty(), lockOptions = true).resolverPolicy()
                    val effective = effectiveConfiguration(parsed, policy)
                    val lockRelative = locks[entry.name] ?: lock
                    val lockPath = LogicalPath(if (root.isEmpty()) lockRelative.value else "$root/${lockRelative.value}")
                    val content by lazy { ReadOnlyContentStore(store, effective.maxArtifactBytes) }
                    val readOnly = object : ContentStore {
                        override fun put(bytes: ByteArray): Digest = error("Workspace dependency queries cannot write content")
                        override fun get(digest: Digest): ByteArray? = content.get(digest)
                    }
                    val query = compactWorkspaceDependencyQuery(queryProjectDependencies(configPath.value, lockPath.value, store, parsed, effective,
                        { read(lockPath, 16 * 1024 * 1024) }, readOnly, NativePomParser(), now), ::sourceCheckpoint)
                    val reference = SourceReference(resolved.provenance, root, resolved.manifest.digest)
                    retainedBytes += configurationBytes.size.toLong() + query.canonical().encodeToByteArray().size +
                        reference.toJson().canonical().encodeToByteArray().size + 4096
                    retainedNodes += query.values.getValue("domains").arrayValue().sumOf {
                        it.objectValue().getValue("graph").objectValue().getValue("nodes").arrayValue().size.toLong()
                    }
                    if (retainedBytes > 16 * 1024 * 1024 || retainedNodes > 65536) throw CliFailure(
                        "KVIK_WORKSPACE_BOUND", DiagnosticCategory.CONFIGURATION,
                        "Workspace graph exceeds 16 MiB retained input or 65,536 node bound; select fewer projects", ExitStatus.INCOMPLETE)
                    sourceCheckpoint()
                    projects += WorkspaceDependencyProject(entry.name, reference, parsed.project, query)
                } catch (failure: Exception) {
                    sourceCheckpoint() // Cancellation must not be converted into a partial project result.
                    if (failure is RuntimeIntegrityFailure || failure is RunStreamFailure) throw failure
                    if (failure is CliFailure && failure.code == "KVIK_WORKSPACE_BOUND") throw failure
                    failures += obj("project" to entry.name.json(), "code" to "KVIK_WORKSPACE_PROJECT_UNAVAILABLE".json(),
                        "message" to (failure.message ?: "Cannot inspect registered project").take(4096).json())
                }
            }
            sourceCheckpoint()
            val result = queryWorkspaceDependencies(projects, failures, now, ::sourceCheckpoint)
            JsonValue.Object(result.values + mapOf("registry" to obj("provider" to "kode".json(),
                "home" to home.json(), "consistency" to "live-discovery-pinned-projects".json(),
                "selectedProjects" to selected.map { it.name.json() }.json())))
                .also {
                    sourceCheckpoint()
                    require(it.canonical().encodeToByteArray().size.toLong() + 1 <= 16 * 1024 * 1024) { "Workspace graph exceeds 16 MiB" }
                    sourceCheckpoint()
                }
        } }
    } finally { kvik_interrupt_restore() }
}
