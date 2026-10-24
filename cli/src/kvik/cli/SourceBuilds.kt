@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.kodesource.*
import kvik.projectsnapshot.*
import kvik.execution.platform.*
import kotlinx.cinterop.*
import platform.posix.*

internal class CapturedSourceConfiguration(val root: String, val name: LogicalPath, val bytes: ByteArray)

internal class PreparedSourceProject(
    val configuration: CapturedSourceConfiguration,
    val options: KodeProjectOptions,
    val reference: SourceReference,
    val namespace: Digest,
    val claims: RevisionClaims,
    private val resolved: ResolvedSourceSnapshot,
    val retention: KodeRetentionReference? = null,
) {
    val project: ProjectFile by lazy {
        ProjectFile(configuration.root, configuration.name, configuration.bytes,
            parseProjectConfiguration(parseJson(configuration.bytes.decodeToString(throwOnInvalidSequence = true))))
    }

    fun revisionPath(path: LogicalPath): LogicalPath =
        LogicalPath(if (reference.root.isEmpty()) path.value else "${reference.root}/${path.value}")

    fun scopedManifest(checkpoint: () -> Unit): SourceManifest = resolved.scopedTo(reference.root, checkpoint).manifest

    fun entry(path: LogicalPath): SourceFileEntry? {
        val absolute = revisionPath(path)
        require(absolute !in resolved.manifest.directories) { "Source file path is a directory: ${absolute.value}" }
        require(resolved.manifest.files.none { absolute.value.startsWith("${it.path.value}/") }) {
            "Source file path has a file as parent: ${absolute.value}"
        }
        return resolved.manifest.files.singleOrNull { it.path == absolute }
    }

    fun read(entry: SourceFileEntry, store: String, maxBytes: Int): ByteArray {
        require(entry.blob.sizeBytes <= maxBytes) { "Source file exceeds byte limit: ${entry.path.value}" }
        val bytes = resolved.knownDigest(entry.blob)?.let(ReadOnlySourceContent(store)::get)
            ?: resolved.fetch(entry.blob, maxBytes, ::sourceCheckpoint) ?: error("Source file is unavailable: ${entry.path.value}")
        resolved.verify(entry.blob, bytes, ::sourceCheckpoint)
        return bytes
    }

    fun capture(content: ContentStore, lockPath: LogicalPath, selection: SnapshotSelection, checkpoint: () -> Unit): ProjectInputSnapshot =
        captureResolved(resolved, reference.root, configuration.name, lockPath, selection, content, checkpoint)
}

private fun captureResolved(resolved: ResolvedSourceSnapshot, root: String, configuration: LogicalPath, lock: LogicalPath,
                            selection: SnapshotSelection, content: ContentStore, checkpoint: () -> Unit): ProjectInputSnapshot {
    val scoped = resolved.scopedTo(root, checkpoint)
    val provider = object : SourceSnapshotProvider {
        override fun resolve(selector: SourceSelector, checkpoint: () -> Unit): ResolvedSourceSnapshot {
            checkpoint()
            require(selector.project == scoped.provenance.project && selector.selector == scoped.provenance.revision)
            return scoped
        }
    }
    return ProviderProjectSnapshotter(provider, content, SourcePathPolicy.CaseSensitive).capture(
        SourceSelector(scoped.provenance.project, scoped.provenance.revision), configuration, lock,
        selection = selection, checkpoint = checkpoint).snapshot
}

/** Verification queries must not create directories, repair cache entries or acquire a provider process. */
internal class ReadOnlySourceContent(private val root: String) : ContentStore {
    init { NativeFiles.validateAbsolute(root) }
    override fun put(bytes: ByteArray): Digest = error("Source verification cannot publish content")
    override fun get(digest: Digest): ByteArray? {
        require(digest.sizeBytes <= 64 * 1024 * 1024) { "Source blob exceeds byte limit" }
        if (NativeFiles.info(root) == null) return null
        NativeFiles.ensureDirectory(root, create = false)
        val directory = "$root/blobs"
        if (NativeFiles.info(directory) == null) return null
        NativeFiles.ensureDirectory(directory, create = false)
        val path = "$directory/${digest.hash}-${digest.sizeBytes}"
        if (NativeFiles.info(path) == null) return null
        val bytes = NativeFiles.read(path, 64 * 1024 * 1024)
        require(Sha256.digest(bytes) == digest) { "Corrupt cached source blob: ${digest.hash}" }
        return bytes
    }
}

internal fun sourceCheckpoint() {
    if (kvik_interrupt_requested() != 0) throw CliFailure("KVIK_SOURCE_CANCELLED", DiagnosticCategory.CANCELLATION,
        "Source capture cancelled", ExitStatus.CANCELLED)
}

internal fun <T> withSourceWorkspace(block: (String) -> T): T {
    val temporary = boundedEnvironment("TMPDIR", 4096) ?: "/tmp"
    val pointer = realpath(temporary, null) ?: error("Cannot resolve temporary directory")
    val parent = try { pointer.toKString() } finally { free(pointer) }
    NativeFiles.ensureDirectory(parent, create = false)
    val workspace = NativeFiles.tempDirectory(parent)
    try { return block(workspace) } finally { NativeFiles.removeTree(workspace) }
}

internal fun <T> withPreparedSourceProject(source: KodeProjectOptions, options: BuildOptions,
                                          validateConfiguration: Boolean = true, block: (PreparedSourceProject) -> T): T {
    val root = currentDirectory()
    val store = projectPath(root, options.project.store)
    val runs = projectPath(root, options.runs)
    val tools = projectPath(root, options.tools)
    for (output in listOf(store, runs)) for (input in listOf(source.home, source.executable, tools))
        require(!pathsPhysicallyOverlap(output, input)) { "Source build output overlaps provider or tool configuration: $input" }
    return withPreparedSourceProject(source, options.project, loadClaims = true, retainSource = true,
        validateConfiguration = validateConfiguration, block = block)
}

internal fun <T> withPreparedSourceProject(source: KodeProjectOptions, options: ProjectOptions,
                                          loadClaims: Boolean = false, validateConfiguration: Boolean = true, retainSource: Boolean = false,
                                          block: (PreparedSourceProject) -> T): T {
    sourceCheckpoint()
    val configuration = LogicalPath(options.project)
    LogicalPath(options.lock)
    val root = currentDirectory()
    val store = projectPath(root, options.store)
    for (input in listOf(source.home, source.executable))
        require(!pathsPhysicallyOverlap(store, input)) { "Source cache overlaps provider: $input" }
    val namespace = Sha256.digest(obj("provider" to "kode".json(), "executable" to source.executable.json(), "home" to source.home.json(),
        "git" to "/usr/bin/git".json()))
    val cache = NativeKodeSourceCache("$store/source-provider", namespace)
    fun consume(resolved: ResolvedSourceSnapshot, retention: KodeRetentionReference? = null): T {
        retention?.requireSource(resolved.provenance)
        val reference = SourceReference(resolved.provenance, configuration.value.substringBeforeLast('/', ""), resolved.manifest.digest)
        try { return block(prepareResolvedSource(source, root, store, namespace, configuration, resolved, loadClaims, validateConfiguration, retention)) }
        catch (failure: RunStreamFailure) { throw failure }
        catch (failure: CliFailure) {
            throw CliFailure(failure.code, failure.category, failure.message ?: "Source build failed", failure.exitStatus,
                failure.source ?: reference)
        } catch (failure: RuntimeIntegrityFailure) {
            throw CliFailure("KVIK_RUNTIME_INTEGRITY", DiagnosticCategory.TOOLCHAIN,
                failure.message ?: "Runtime integrity check failed", ExitStatus.INVALID_CONFIGURATION, reference)
        } catch (failure: Exception) {
            throw CliFailure("KVIK_INVALID_PROJECT", DiagnosticCategory.CONFIGURATION,
                failure.message ?: "Invalid source project configuration", ExitStatus.INVALID_CONFIGURATION, reference)
        }
    }
    if (options.offline) {
        val resolved = KodeSourceProvider(KodeTransport { _, _, _ -> error("Offline source transport is forbidden") },
            cache, offline = true).resolve(SourceSelector(source.project, source.revision), ::sourceCheckpoint)
        return consume(resolved)
    }
    return withSourceWorkspace { workspace -> runtimeProcessHost().use { host ->
        val retention = if (retainSource) {
            val nonce = kotlin.random.Random.Default.nextBytes(24).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
            NativeKodeRetention(host, source.executable, source.home, workspace)
                .retainBuild(source.project, "kvik-$nonce", source.revision, ::sourceCheckpoint)
        } else null
        val provider = KodeSourceProvider(NativeKodeTransport(host, source.executable, source.home, workspace), cache)
        val resolved = provider.resolve(SourceSelector(source.project, retention?.revision ?: source.revision), ::sourceCheckpoint)
        consume(resolved, retention)
    } }
}

private fun prepareResolvedSource(source: KodeProjectOptions, root: String, store: String, namespace: Digest,
                                  configuration: LogicalPath, resolved: ResolvedSourceSnapshot,
                                  loadClaims: Boolean, validateConfiguration: Boolean, retention: KodeRetentionReference?): PreparedSourceProject {
    val directory = configuration.value.substringBeforeLast('/', "")
    val reference = SourceReference(resolved.provenance, directory, resolved.manifest.digest)
    try {
        val entry = resolved.manifest.files.singleOrNull { it.path == configuration } ?: error("Configuration is absent from source revision")
        require(entry.blob.sizeBytes <= 4 * 1024 * 1024) { "Source configuration exceeds byte limit" }
        val cached = resolved.knownDigest(entry.blob)?.let(ReadOnlySourceContent(store)::get)
        val bytes = cached ?: resolved.fetch(entry.blob, 4 * 1024 * 1024, ::sourceCheckpoint) ?: error("Source configuration is unavailable")
        require(bytes.size.toLong() == entry.blob.sizeBytes)
        resolved.verify(entry.blob, bytes, ::sourceCheckpoint)
        sourceCheckpoint()
        return PreparedSourceProject(CapturedSourceConfiguration(root, LogicalPath(configuration.value.substringAfterLast('/')), bytes),
            source, reference, namespace, if (loadClaims) resolved.claims(::sourceCheckpoint) else RevisionClaims(emptyMap()), resolved, retention).also {
                // Replacements may repair invalid configuration; its original bytes still carry the native blob guard.
                if (validateConfiguration) it.project
            }
    } catch (failure: CliFailure) { throw failure }
    catch (failure: Exception) { throw CliFailure("KVIK_SOURCE_INPUT", DiagnosticCategory.CONFIGURATION,
        failure.message ?: "Cannot capture source revision", ExitStatus.INVALID_CONFIGURATION, reference) }
}

internal fun retainedSourceFingerprint(metadata: Map<String, JsonValue>, reference: SourceReference,
                                       claims: List<ClaimAssessment>?): Digest {
    require(metadata["source"]?.let(SourceReference::fromJson) == reference) { "Run source metadata differs from verification" }
    val location = metadata.getValue("sourceCache").fields(setOf("store", "namespace"))
    val store = location.getValue("store").stringValue()
    val cache = NativeKodeSourceCache("$store/source-provider", Digest.fromJson(location.getValue("namespace")))
    val provider = KodeSourceProvider(KodeTransport { _, _, _ -> error("Verification cannot invoke a source provider") }, cache, offline = true)
    val resolved = provider.resolve(SourceSelector(reference.provenance.project, reference.provenance.revision)) {}
    require(resolved.provenance == reference.provenance && resolved.manifest.digest == reference.manifest) { "Retained source identity differs" }
    if (claims != null) require(resolved.claims {}.entries == claims.associate { it.id to it.declaration }) {
        "Retained revision claims differ from verification"
    }
    return captureResolved(resolved, reference.root, LogicalPath(metadata.getValue("configurationPath").stringValue()),
        LogicalPath(metadata.getValue("lockPath").stringValue()), metadata["inputSelection"]?.let(SnapshotSelection::fromJson) ?: SnapshotSelection.Project,
        ReadOnlySourceContent(store)) {}.fingerprint
}
