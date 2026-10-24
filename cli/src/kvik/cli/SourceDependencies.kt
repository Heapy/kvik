@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeContentStore
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.platform.*
import kvik.locking.*
import kvik.resolution.NativeHttpsTransport
import kvik.resolution.NativePomParser

internal fun replacementProjectOption(args: List<String>): Pair<String?, List<String>> {
    var replacement: String? = null
    val remaining = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        if (flag != "--replace-project") { remaining += flag; continue }
        require(replacement == null) { "Duplicate option: --replace-project" }
        val value = args.getOrNull(index++)
        require(value != null && value.isNotBlank() && !value.startsWith("--")) { "Missing value for --replace-project" }
        replacement = value
    }
    return replacement to remaining
}

/** Resolving a revision changes only local caches. Publication is an explicit Kode transaction. */
internal fun sourceDependencyCommand(source: KodeProjectOptions, options: ProjectOptions,
                                     updateRequested: Boolean, replacement: String?): JsonValue {
    val store = projectPath(currentDirectory(), options.store)
    val replacementPath = replacement?.let { projectPath(currentDirectory(), it) }
    if (replacementPath != null) require(!pathsPhysicallyOverlap(store, replacementPath)) {
        "Source cache overlaps replacement configuration"
    }
    check(kvik_interrupt_install() == 0) { "Cannot install source resolution interruption handlers" }
    try {
        return withPreparedSourceProject(source, options, validateConfiguration = replacementPath == null) { prepared ->
            val project = prepared.configuration
            val lockPath = LogicalPath(options.lock)
            require(lockPath != project.name && !lockPath.value.startsWith("${project.name.value}/")) {
                "Dependency lock cannot overwrite project configuration"
            }
            val previous = prepared.entry(lockPath)
            val update = updateRequested && !options.offline
            // Capture the replacement once. The proposal embeds these exact bytes, never a mutable local path.
            val bytes = replacementPath?.let { NativeFiles.read(it, 4 * 1024 * 1024) } ?: project.bytes
            val configuration = if (replacementPath == null) prepared.project.configuration
                else parseProjectConfiguration(parseJson(bytes.decodeToString(throwOnInvalidSequence = true)))
            val effective = effectiveConfiguration(configuration, options.resolverPolicy())
            val oldLock = previous?.let { prepared.read(it, store, 16 * 1024 * 1024) }
            val content = if (options.offline) null else NativeContentStore(store, maxBlobBytes = maxOf(effective.maxArtifactBytes, 16 * 1024 * 1024))
            content?.put(project.bytes)
            oldLock?.let { content?.put(it) }
            val lock = if (update) {
                val network = NativeHttpsTransport(maxBytes = effective.maxArtifactBytes)
                val transport = object : ArtifactTransport {
                    override fun fetch(url: String): ByteArray {
                        sourceCheckpoint()
                        val fetched = network.fetch(url)
                        sourceCheckpoint()
                        return fetched
                    }
                }
                try { DependencyLocks(checkNotNull(content), NativePomParser()).resolve(effective, transport) }
                catch (failure: CliFailure) { throw failure }
                catch (failure: Exception) {
                    throw CliFailure("KVIK_RESOLUTION_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                        failure.message ?: "Dependency resolution failed")
                }
            } else try {
                require(oldLock != null) { "Dependency lock is absent from source revision" }
                DependencyLocks(ReadOnlyContentStore(store, effective.maxArtifactBytes), NativePomParser()).loadLocked(oldLock, effective)
            } catch (failure: CliFailure) { throw failure }
            catch (failure: Exception) {
                throw CliFailure("KVIK_LOCK_INVALID", DiagnosticCategory.CONFIGURATION,
                    failure.message ?: "Dependency lock is unavailable", ExitStatus.INVALID_CONFIGURATION)
            }
            sourceCheckpoint()
            val operations = mutableListOf<JsonValue>()
            if (update && !bytes.contentEquals(project.bytes)) {
                val original = checkNotNull(prepared.entry(project.name))
                operations += obj("op" to "write".json(), "path" to original.path.value.json(),
                    "expectBlob" to original.blob.identity.json(), "executable" to original.executable.json(),
                    "content" to bytes.decodeToString(throwOnInvalidSequence = true).json())
            }
            val lockBytes = lock.bytes()
            if (update && (oldLock == null || !oldLock.contentEquals(lockBytes))) {
                operations += obj("op" to "write".json(), "path" to prepared.revisionPath(lockPath).value.json(),
                    "expectBlob" to (previous?.blob?.identity ?: "none").json(),
                    "executable" to (previous?.executable ?: false).json(),
                    "content" to lockBytes.decodeToString(throwOnInvalidSequence = true).json())
            }
            val result = obj("schemaVersion" to CONTRACT_VERSION.json(), "operation" to (if (update) "resolve" else "check-lock").json(),
                "source" to prepared.reference.toJson(), "configuration" to effective.digest.toJson(), "dependencyLock" to lock.digest.toJson(),
                "lockPath" to prepared.revisionPath(lockPath).value.json(),
                "selections" to lock.selections.size.json(), "artifacts" to lock.selections.flatMap { it.artifacts }.map { it.digest }.distinct().size.json(),
                "metadata" to lock.selections.flatMap { it.metadata }.map { it.digest }.distinct().size.json())
            if (!update) result else JsonValue.Object(result.objectValue() + mapOf(
                "changed" to operations.isNotEmpty().json(),
                "transaction" to if (operations.isEmpty()) JsonValue.Null else obj(
                    "base" to prepared.reference.provenance.revision.json(),
                    "expectHead" to prepared.reference.provenance.revision.json(),
                    "intent" to (if (replacement == null) "Update dependency lock" else "Update project configuration and dependency lock").json(),
                    "operations" to obj("schemaVersion" to 1.json(), "operations" to operations.json()))))
        }
    } finally { kvik_interrupt_restore() }
}
