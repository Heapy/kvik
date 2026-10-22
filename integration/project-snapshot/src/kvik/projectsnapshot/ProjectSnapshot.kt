@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.projectsnapshot

import kvik.core.*
import kvik.cache.NativeFiles
import kotlinx.cinterop.*
import platform.posix.*

class SnapshotFailure(message: String) : Exception(message)
private class SnapshotRace(message: String) : Exception(message)

data class SnapshotLimits(
    val maxFiles: Int = 20_000,
    val maxDirectories: Int = 10_000,
    val maxEntries: Int = 40_000,
    val maxDepth: Int = 64,
    val maxFileBytes: Int = 64 * 1024 * 1024,
    val maxTotalBytes: Long = 256L * 1024 * 1024,
) {
    init {
        require(maxFiles in 1..100_000 && maxDirectories in 1..100_000 && maxEntries in 1..200_000 && maxDepth in 1..128)
        require(maxFileBytes in 1..64 * 1024 * 1024 && maxTotalBytes in 1..1024L * 1024 * 1024)
    }
}

/** Exact authoring bytes are retained; graph inputs exclude the configuration and external lock unless explicitly declared. */
class ProjectInputSnapshot internal constructor(
    internal val ownerRoot: String,
    val configurationPath: LogicalPath,
    val lockPath: String,
    configurationBytes: ByteArray,
    lockBytes: ByteArray,
    val inputs: TreeManifest,
    val fingerprint: Digest,
) {
    private val config = configurationBytes.copyOf()
    private val lock = lockBytes.copyOf()
    val configurationBytes: ByteArray get() = config.copyOf()
    val lockBytes: ByteArray get() = lock.copyOf()
    val configuration: ProjectConfiguration get() = parseProjectConfiguration(parseJson(config.decodeToString(throwOnInvalidSequence = true)))
}

sealed interface SnapshotCapture {
    data class Captured(val snapshot: ProjectInputSnapshot) : SnapshotCapture
    data class Indeterminate(val reason: String) : SnapshotCapture
}

/**
 * Two complete observations, never an atomic filesystem snapshot. Ancestors must be trusted and
 * stable through use; changing files/directories are detected where observed. ABA edits and edits
 * after the final observation are not excluded. Call freshness after execution before claiming current.
 */
class NativeProjectSnapshotter(private val root: String, private val content: ContentStore, private val limits: SnapshotLimits = SnapshotLimits()) {
    init { physicalDirectory(root) }

    fun capture(configurationPath: LogicalPath, lockPath: String, expectedConfiguration: Digest? = null): SnapshotCapture {
        val first = try { scan(configurationPath, lockPath, publish = true, expectedConfiguration) }
        catch (race: SnapshotRace) { return SnapshotCapture.Indeterminate(race.message ?: "Inputs changed while capturing") }
        catch (failure: SnapshotFailure) { throw failure }
        catch (failure: Exception) { throw SnapshotFailure(failure.message ?: "Cannot capture project inputs") }
        val second = try { scan(configurationPath, lockPath, publish = false, expectedConfiguration) }
        catch (failure: Exception) { return SnapshotCapture.Indeterminate(failure.message ?: "Inputs changed while confirming capture") }
        if (first.fingerprint != second.fingerprint) return SnapshotCapture.Indeterminate("Project inputs changed between capture observations")
        return SnapshotCapture.Captured(first)
    }

    /** Never calls put, repairs CAS, creates directories, or scans unrelated project paths. */
    fun freshness(snapshot: ProjectInputSnapshot): Freshness {
        require(snapshot.ownerRoot == root) { "Snapshot belongs to a different project root" }
        return try {
            val current = currentFingerprint(snapshot.configurationPath, snapshot.lockPath)
            if (current == null) Freshness.INDETERMINATE
            else if (current == snapshot.fingerprint) Freshness.CURRENT else Freshness.STALE
        } catch (_: Exception) { Freshness.INDETERMINATE }
    }

    /** Read-only post-exit query; no deserialization of a prior capture or CAS access is required. */
    fun currentFingerprint(configurationPath: LogicalPath, lockPath: String, expectedConfiguration: Digest? = null): Digest? {
        val first = try { scan(configurationPath, lockPath, publish = false, expectedConfiguration) }
        catch (_: SnapshotRace) { return null }
        val second = try { scan(configurationPath, lockPath, publish = false, expectedConfiguration) }
        catch (_: Exception) { return null }
        return first.fingerprint.takeIf { it == second.fingerprint }
    }

    private fun scan(configurationPath: LogicalPath, lockPath: String, publish: Boolean, expectedConfiguration: Digest?): ProjectInputSnapshot =
        Observation(root, content.takeIf { publish }, limits).capture(configurationPath, lockPath, expectedConfiguration)
}

private fun physicalDirectory(path: String) {
    try { NativeFiles.ensureDirectory(path, create = false) }
    catch (failure: Exception) { throw SnapshotFailure(failure.message ?: "Invalid physical directory: $path") }
}

private data class Status(val device: ULong, val inode: ULong, val mode: Int, val size: Long, val modified: Pair<Long, Long>, val changed: Pair<Long, Long>) {
    val kind: Int get() = mode and S_IFMT
    val identity: Pair<ULong, ULong> get() = device to inode
}
private fun stat.status() = Status(st_dev.toULong(), st_ino, st_mode.toInt(), st_size,
    st_mtimespec.tv_sec to st_mtimespec.tv_nsec, st_ctimespec.tv_sec to st_ctimespec.tv_nsec)
private fun status(path: String): Status? = memScoped {
    val value = alloc<stat>()
    if (lstat(path, value.ptr) != 0) {
        if (errno == ENOENT) return null
        throw SnapshotFailure("Cannot inspect $path: errno=$errno")
    }
    value.status()
}

private class Observation(private val root: String, private val content: ContentStore?, private val limits: SnapshotLimits) {
    private val identities = mutableMapOf<Pair<ULong, ULong>, String>()
    private val bytes = mutableMapOf<String, ByteArray>()
    private val entries = linkedMapOf<LogicalPath, FileEntry>()
    private val directories = linkedMapOf<String, JsonValue>()
    private var totalBytes = 0L
    private var directoryCount = 0
    private var entryCount = 0

    fun capture(configurationPath: LogicalPath, lockPath: String, expectedConfiguration: Digest?): ProjectInputSnapshot {
        physicalDirectory(root)
        NativeFiles.validateAbsolute(lockPath)
        physicalDirectory(lockPath.substringBeforeLast('/'))
        val configurationFile = "$root/${configurationPath.value}"
        require(configurationFile != lockPath) { "Configuration and lock paths must differ" }
        ancestors(configurationPath.value)
        val configurationBytes = readFile(configurationFile, minOf(limits.maxFileBytes, 4 * 1024 * 1024), publish = false)
        if (expectedConfiguration != null && Sha256.digest(configurationBytes) != expectedConfiguration)
            throw SnapshotRace("Configuration changed after input/store preflight")
        val lockBytes = readFile(lockPath, minOf(limits.maxFileBytes, 16 * 1024 * 1024), publish = false)
        val configuration = try { parseProjectConfiguration(parseJson(configurationBytes.decodeToString(throwOnInvalidSequence = true))) }
        catch (failure: Exception) { throw SnapshotFailure(failure.message ?: "Invalid configuration") }
        val roots = configuration.project.modules.flatMap { module -> module.sourceSets.flatMap { it.directories + it.resources } }.distinct().sortedBy { it.value }
        val plugins = configuration.project.modules.flatMap { it.compilerPlugins.flatMap { plugin -> plugin.inputs } }.distinct().sortedBy { it.value }
        if (roots.size + plugins.size > limits.maxEntries) throw SnapshotFailure("Too many declared input paths")
        for (path in roots + plugins) requireDeclared(path)
        publish(configurationBytes); publish(lockBytes)
        for (directory in roots) {
            ancestors(directory.value)
            visit(directory.value, directory.value.split('/').size, allowMissing = true)
        }
        for (file in plugins) {
            ancestors(file.value)
            addFile(file.value)
        }
        val tree = TreeManifest(entries.values.sortedBy { it.path.value })
        val identity = obj("schemaVersion" to 1.json(), "implementation" to "kvik-project-input-snapshot-v1".json(),
            "configurationPath" to configurationPath.value.json(), "configurationBytes" to Sha256.digest(configurationBytes).toJson(),
            "lockBytes" to Sha256.digest(lockBytes).toJson(), "roots" to roots.map { it.value.json() }.json(),
            "pluginInputs" to plugins.map { it.value.json() }.json(), "inputs" to tree.toJson(),
            "directories" to JsonValue.Object(directories.toMap()))
        return ProjectInputSnapshot(root, configurationPath, lockPath, configurationBytes, lockBytes, tree, Sha256.digest(identity))
    }

    private fun requireDeclared(path: LogicalPath) {
        if (path.value.split('/').size > limits.maxDepth) throw SnapshotFailure("Declared input path exceeds depth limit")
        if (path.value.split('/').any { it.lowercase() == ".kvik" }) throw SnapshotFailure("Generated .kvik paths cannot be project inputs: ${path.value}")
    }

    private fun register(path: String, value: Status) {
        val previous = identities[value.identity]
        if (previous != null && previous != path) throw SnapshotFailure("Filesystem path aliases: $previous and $path")
        identities[value.identity] = path
    }

    /** Inspect only declared ancestors, without recursively scanning their siblings. */
    private fun ancestors(logical: String) {
        var relative = ""
        for (part in logical.split('/').dropLast(1)) {
            relative = if (relative.isEmpty()) part else "$relative/$part"
            val absolute = "$root/$relative"
            val value = status(absolute) ?: return
            if (value.kind != S_IFDIR) throw SnapshotFailure("Not a physical input directory: $absolute")
            register(absolute, value)
        }
    }

    private fun visit(logical: String, depth: Int, allowMissing: Boolean) {
        if (depth > limits.maxDepth) throw SnapshotFailure("Input directory depth exceeds limit")
        if (logical in directories) return
        if (++directoryCount > limits.maxDirectories) throw SnapshotFailure("Too many input directories")
        val absolute = "$root/$logical"
        val before = status(absolute)
        if (before == null) {
            if (!allowMissing) throw SnapshotRace("Input directory disappeared: $logical")
            directories[logical] = obj("state" to "missing".json())
            return
        }
        if (before.kind != S_IFDIR) throw SnapshotFailure("Not a physical input directory: $logical")
        register(absolute, before)
        val fd = open(absolute, O_RDONLY or O_DIRECTORY or O_NOFOLLOW)
        if (fd < 0) throw SnapshotRace("Cannot open input directory: $logical")
        val handle = fdopendir(fd) ?: run { close(fd); throw SnapshotRace("Cannot enumerate input directory: $logical") }
        val names = mutableListOf<String>()
        try {
            while (true) {
                __error()!!.pointed.value = 0
                val entry = readdir(handle)
                if (entry == null) {
                    if (errno != 0) throw SnapshotRace("Cannot enumerate input directory: $logical")
                    break
                }
                val name = entry.pointed.d_name.readBytes(entry.pointed.d_namlen.toInt()).decodeToString(throwOnInvalidSequence = true)
                if (name == "." || name == "..") continue
                LogicalPath(name)
                if (++entryCount > limits.maxEntries) throw SnapshotFailure("Too many input directory entries")
                if (name.lowercase() == ".kvik") throw SnapshotFailure("Generated .kvik directory inside declared input: $logical")
                names += name
            }
        } finally { closedir(handle) }
        val inventory = names.sorted().map { name ->
            val child = "$logical/$name"
            val value = status("$root/$child") ?: throw SnapshotRace("Input disappeared: $child")
            when (value.kind) {
                S_IFDIR -> { visit(child, depth + 1, allowMissing = false); obj("name" to name.json(), "kind" to "directory".json()) }
                S_IFREG -> { addFile(child); obj("name" to name.json(), "kind" to "file".json()) }
                else -> throw SnapshotFailure("Links and special input files are unsupported: $child")
            }
        }
        if (status(absolute) != before) throw SnapshotRace("Input directory changed while reading: $logical")
        directories[logical] = obj("state" to "directory".json(), "entries" to inventory.json())
    }

    private fun addFile(logical: String) {
        val path = LogicalPath(logical)
        if (logical.split('/').size > limits.maxDepth) throw SnapshotFailure("Input file path exceeds depth limit: $logical")
        if (path in entries) return
        val absolute = "$root/$logical"
        val data = readFile(absolute, limits.maxFileBytes)
        val value = status(absolute) ?: throw SnapshotRace("Input disappeared: $logical")
        if (value.kind != S_IFREG) throw SnapshotRace("Input type changed: $logical")
        entries[path] = FileEntry(path, Sha256.digest(data), value.mode and 73 != 0)
    }

    private fun publish(data: ByteArray) {
        if (content != null && content.put(data) != Sha256.digest(data)) throw SnapshotFailure("ContentStore returned an incorrect input digest")
    }

    private fun readFile(path: String, limit: Int, publish: Boolean = true): ByteArray {
        bytes[path]?.let { return it }
        if (bytes.size >= limits.maxFiles) throw SnapshotFailure("Too many input files (including configuration and lock)")
        val before = status(path) ?: throw SnapshotFailure("Missing required input file: $path")
        if (before.kind != S_IFREG) throw SnapshotFailure("Not a physical regular input file: $path")
        register(path, before)
        if (before.size !in 0..limit.toLong() || totalBytes + before.size > limits.maxTotalBytes) throw SnapshotFailure("Input byte limit exceeded: $path")
        val data = try { NativeFiles.read(path, limit) }
        catch (failure: Exception) { throw SnapshotRace(failure.message ?: "Input changed while reading: $path") }
        if (status(path) != before || data.size.toLong() != before.size) throw SnapshotRace("Input changed while reading: $path")
        totalBytes += data.size
        if (publish) this.publish(data)
        bytes[path] = data
        return data
    }
}
