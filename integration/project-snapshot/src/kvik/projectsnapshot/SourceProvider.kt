package kvik.projectsnapshot

import kvik.core.*

private fun opaqueSourceValue(value: String) {
    require(value.length in 1..4096 && value.none { it.code < 32 || it.code == 127 }) { "Invalid source identity" }
    value.json().canonical()
}

data class SourceSelector(val project: String, val selector: String) {
    init { opaqueSourceValue(project); opaqueSourceValue(selector) }
}

/** Provider-native IDs are provenance, never Kvik content digests or portable action identity. */
data class SourceProvenance(val provider: String, val project: String, val revision: String, val tree: String) {
    init { listOf(provider, project, revision, tree).forEach(::opaqueSourceValue) }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(), "provider" to provider.json(),
        "project" to project.json(), "revision" to revision.json(), "tree" to tree.json())
    companion object {
        fun fromJson(value: JsonValue): SourceProvenance {
            val fields = value.fields(setOf("schemaVersion", "provider", "project", "revision", "tree")); fields.requireVersion()
            return SourceProvenance(fields.getValue("provider").stringValue(), fields.getValue("project").stringValue(),
                fields.getValue("revision").stringValue(), fields.getValue("tree").stringValue())
        }
    }
}

/** Complete regular-file/directory inventory. Root is implicit; all other parents, including empty directories, are explicit. */
class SourceManifest(files: TreeManifest, directories: List<LogicalPath>, checkpoint: () -> Unit = {}) {
    init {
        require(files.files.size <= 100_000 && directories.size <= 100_000 && files.files.size + directories.size <= 200_000) { "Source manifest too large" }
        checkpoint()
    }
    private val entries = files.files.toList().sortedBy { it.path.value }
    private val folders = directories.toList().sortedBy { it.value }
    val files: TreeManifest get() = TreeManifest(entries.toList())
    val directories: List<LogicalPath> get() = folders.toList()
    init {
        require(folders.distinct().size == folders.size) { "Duplicate source directory" }
        val paths = entries.map { it.path } + folders
        require(paths.distinct().size == paths.size) { "Source file/directory collision" }
        val directorySet = folders.toSet()
        var pathBytes = 0L
        for (path in paths) {
            checkpoint()
            val size = path.value.encodeToByteArray().size
            // Include JSON escaping and a conservative fixed allowance for each file's digest/mode framing.
            pathBytes += path.value.json().canonical().encodeToByteArray().size + 256L
            require(pathBytes <= 16L * 1024 * 1024) { "Source manifest metadata exceeds 16 MiB" }
            require(size <= 4096) { "Source path too long" }
            val parent = path.value.substringBeforeLast('/', "")
            require(parent.isEmpty() || LogicalPath(parent) in directorySet) { "Missing source parent directory: ${path.value}" }
        }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to CONTRACT_VERSION.json(),
        "files" to entries.map(FileEntry::toJson).json(), "directories" to folders.map { it.value.json() }.json())
    val digest: Digest by lazy { Sha256.digest(toJson()) }
    companion object {
        fun fromJson(value: JsonValue): SourceManifest {
            val fields = value.fields(setOf("schemaVersion", "files", "directories")); fields.requireVersion()
            return SourceManifest(TreeManifest(fields.getValue("files").arrayValue().map(FileEntry::fromJson)),
                fields.getValue("directories").arrayValue().map { LogicalPath(it.stringValue()) })
        }
    }
}

/** Required policy for the eventual materialization filesystem; no inferred platform default.
 * Lexical policies do not perform Unicode normalization. Physical materialization must still reject aliases. */
fun interface SourcePathPolicy {
    fun aliasKey(path: LogicalPath): String
    companion object {
        val CaseSensitive = SourcePathPolicy { it.value }
        val LexicalCaseInsensitive = SourcePathPolicy { it.value.lowercase() }
    }
}

interface SourceSnapshotProvider {
    /** Resolve once. Implementations must validate provider-native revision/tree/blob identities separately. */
    fun resolve(selector: SourceSelector, checkpoint: () -> Unit): ResolvedSourceSnapshot
}

interface ResolvedSourceSnapshot {
    val provenance: SourceProvenance
    val manifest: SourceManifest
    /** Fetch exactly this Kvik digest from the resolved revision. Bound reads before allocation; never follow a selector. */
    fun fetch(digest: Digest, maxBytes: Int, checkpoint: () -> Unit): ByteArray?
}

data class ProviderSnapshot(val provenance: SourceProvenance, val manifestDigest: Digest, val snapshot: ProjectInputSnapshot)

/** Immutable capture needs one manifest observation, not local filesystem double-scanning.
 * Exceptions (including cancellation) propagate; unavailable data never falls back to local or newer sources. */
class ProviderProjectSnapshotter(private val provider: SourceSnapshotProvider, private val content: ContentStore,
    private val pathPolicy: SourcePathPolicy, private val limits: SnapshotLimits = SnapshotLimits()) {
    fun capture(selector: SourceSelector, configurationPath: LogicalPath, lockPath: LogicalPath,
        expectedConfiguration: Digest? = null, selection: SnapshotSelection = SnapshotSelection.Project,
        checkpoint: () -> Unit = {}): ProviderSnapshot {
        checkpoint()
        val resolved = provider.resolve(selector, checkpoint)
        checkpoint()
        val provenance = resolved.provenance.copy()
        require(provenance.project == selector.project) { "Resolved source project differs from requested project" }
        // Read these properties once; the fetch handle stays bound to the resolved revision.
        val manifest = resolved.manifest
        val frozen = SourceManifest(manifest.files, manifest.directories, checkpoint)
        val frozenSelection = SnapshotSelection.fromJson(selection.toJson())
        val capture = ProviderObservation(resolved, frozen, content, pathPolicy, limits, checkpoint)
            .capture(configurationPath, lockPath, expectedConfiguration, frozenSelection)
        checkpoint()
        return ProviderSnapshot(provenance, frozen.digest, capture)
    }
}

private class ProviderObservation(private val resolved: ResolvedSourceSnapshot, private val manifest: SourceManifest,
    private val content: ContentStore, private val pathPolicy: SourcePathPolicy, private val limits: SnapshotLimits,
    private val checkpoint: () -> Unit) {
    private val files = manifest.files.files.associateBy { it.path.value }
    private val folders = manifest.directories.map { it.value }.toSet()
    private val children = linkedMapOf<String, MutableList<Pair<String, Boolean>>>()
    private val data = mutableMapOf<String, ByteArray>()
    private val blobs = mutableMapOf<Digest, ByteArray>()
    private val inputs = linkedMapOf<LogicalPath, FileEntry>()
    private val observedDirectories = linkedMapOf<String, JsonValue>()
    private var totalBytes = 0L
    private var visitedEntries = 0
    private var visitedDirectories = 0
    private val aliases = mutableMapOf<String, String>()

    fun capture(configurationPath: LogicalPath, lockPath: LogicalPath, expectedConfiguration: Digest?, selection: SnapshotSelection): ProjectInputSnapshot {
        validateManifest()
        require(configurationPath != lockPath) { "Configuration and lock paths must differ" }
        requestedPath(configurationPath.value); requestedPath(lockPath.value)
        if (expectedConfiguration != null && files[configurationPath.value]?.digest != expectedConfiguration)
            throw SnapshotFailure("Configuration differs from expected source snapshot")
        val configurationBytes = read(configurationPath.value, minOf(limits.maxFileBytes, 4 * 1024 * 1024))
        val lockBytes = read(lockPath.value, minOf(limits.maxFileBytes, 16 * 1024 * 1024))
        val configuration = if (selection == SnapshotSelection.StandaloneScript) null else
            parseProjectConfiguration(parseJson(configurationBytes.decodeToString(throwOnInvalidSequence = true)))
        val plan = if (configuration == null) standaloneScriptInputPlan(parseStandaloneScriptConfiguration(
            parseJson(configurationBytes.decodeToString(throwOnInvalidSequence = true)))) else projectInputPlan(configuration, selection)
        if (plan.paths.size > limits.maxEntries) throw SnapshotFailure("Too many declared input paths")
        for (path in plan.paths) { checkpoint(); declared(path) }
        for (path in plan.directories) visit(path.value)
        for (path in plan.files) addFile(path.value)
        for (path in plan.scriptRoots) {
            checkpoint()
            if (path.value in files) addFile(path.value) else visit(path.value)
        }
        checkpoint()
        val tree = TreeManifest(inputs.values.sortedBy { it.path.value })
        val fingerprint = projectSnapshotFingerprint(configurationPath, configurationBytes, lockBytes, plan, tree, observedDirectories, selection)
        return ProjectInputSnapshot("source-provider:${manifest.digest.hash}", configurationPath, lockPath.value, selection,
            configurationBytes, lockBytes, tree, fingerprint, configuration)
    }

    private fun validateManifest() {
        if (files.size > limits.maxFiles || folders.size > limits.maxDirectories || files.size + folders.size > limits.maxEntries)
            throw SnapshotFailure("Source manifest entry limit exceeded")
        fun entry(path: String, directory: Boolean) {
            checkpoint()
            if (path.split('/').size > limits.maxDepth) throw SnapshotFailure("Source manifest depth limit exceeded")
            val key = pathPolicy.aliasKey(LogicalPath(path))
            require(key.length in 1..16384) { "Invalid source alias key" }
            val previous = aliases.put(key, path)
            if (previous != null) throw SnapshotFailure("Source path aliases: $previous and $path")
            children.getOrPut(path.substringBeforeLast('/', "")) { mutableListOf() } += path.substringAfterLast('/') to directory
        }
        folders.forEach { entry(it, true) }; files.keys.forEach { entry(it, false) }
        children.values.forEach { checkpoint(); it.sortBy { child -> child.first } }
    }

    private fun declared(path: LogicalPath) {
        if (path.value.split('/').size > limits.maxDepth) throw SnapshotFailure("Declared input path exceeds depth limit")
        if (path.value.split('/').any { it.lowercase() == ".kvik" }) throw SnapshotFailure("Generated .kvik paths cannot be project inputs: ${path.value}")
        requestedPath(path.value)
        ancestors(path.value)
    }

    private fun requestedPath(path: String) {
        var current = path
        while (current.isNotEmpty()) {
            checkpoint()
            val existing = aliases[pathPolicy.aliasKey(LogicalPath(current))]
            if (existing != null && existing != current) throw SnapshotFailure("Requested source path aliases manifest entry: $current and $existing")
            current = current.substringBeforeLast('/', "")
        }
    }

    private fun ancestors(path: String) {
        var ancestor = path.substringBeforeLast('/', "")
        while (ancestor.isNotEmpty()) {
            checkpoint()
            if (ancestor in files) throw SnapshotFailure("Not an input directory: $ancestor")
            ancestor = ancestor.substringBeforeLast('/', "")
        }
    }

    private fun visit(path: String) {
        checkpoint(); ancestors(path)
        if (path in observedDirectories) return
        if (++visitedDirectories > limits.maxDirectories) throw SnapshotFailure("Too many input directories")
        if (path in files) throw SnapshotFailure("Not an input directory: $path")
        if (path !in folders) {
            observedDirectories[path] = obj("state" to "missing".json()); return
        }
        val inventory = children[path].orEmpty().map { (name, directory) ->
            checkpoint()
            if (++visitedEntries > limits.maxEntries) throw SnapshotFailure("Too many input directory entries")
            if (name.lowercase() == ".kvik") throw SnapshotFailure("Generated .kvik directory inside declared input: $path")
            val child = "$path/$name"
            if (directory) visit(child) else addFile(child)
            obj("name" to name.json(), "kind" to (if (directory) "directory" else "file").json())
        }
        observedDirectories[path] = obj("state" to "directory".json(), "entries" to inventory.json())
    }

    private fun addFile(path: String) {
        checkpoint(); ancestors(path)
        val file = files[path] ?: throw SnapshotFailure("Missing required input file: $path")
        if (file.path in inputs) return
        read(path, limits.maxFileBytes)
        inputs[file.path] = file
    }

    private fun read(path: String, maxBytes: Int): ByteArray {
        checkpoint()
        val entry = files[path] ?: throw SnapshotFailure("Missing required input file: $path")
        if (entry.digest.sizeBytes > maxBytes) throw SnapshotFailure("Input byte limit exceeded: $path")
        data[path]?.let { return it }
        if (data.size >= limits.maxFiles || totalBytes + entry.digest.sizeBytes > limits.maxTotalBytes)
            throw SnapshotFailure("Input count/byte limit exceeded: $path")
        val bytes = blobs[entry.digest] ?: run {
            val cached = content.get(entry.digest)
            checkpoint()
            val raw = cached ?: resolved.fetch(entry.digest, entry.digest.sizeBytes.toInt(), checkpoint)
                ?: throw SnapshotFailure("Missing source blob: $path")
            checkpoint()
            if (raw.size.toLong() != entry.digest.sizeBytes) throw SnapshotFailure("Source blob size mismatch: $path")
            val fetched = raw.copyOf()
            if (Sha256.digest(fetched) != entry.digest) throw SnapshotFailure("Source blob digest/size mismatch: $path")
            if (cached == null) {
                if (content.put(fetched.copyOf()) != entry.digest) throw SnapshotFailure("ContentStore returned an incorrect input digest")
                checkpoint()
            }
            blobs[entry.digest] = fetched
            fetched
        }
        totalBytes += entry.digest.sizeBytes
        data[path] = bytes
        return bytes
    }
}
