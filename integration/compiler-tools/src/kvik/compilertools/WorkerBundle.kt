package kvik.compilertools

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.compiler.jvm.jvmWorkerIdentity
import kvik.compiler.jvm.jvmWorkerArchiveIdentity

/** Verified worker implementation paths; the JDK and compiler distribution are separate tools. */
class VerifiedJvmWorkerBundle internal constructor(
    /** A verified worker.jar for v2, or the fully verified legacy classes directory. */
    val classes: String,
    val api: String,
    val stdlib: String,
    val junit: String,
    val compilerManifest: String,
    val compiler: Digest,
    val identity: Digest,
    val junitIdentity: Digest,
    val jacocoVersion: String,
    val jacocoAgent: String,
    val jacocoCli: String,
    val jacocoAgentIdentity: Digest,
    val jacocoCliIdentity: Digest,
    val documentationManifest: String? = null,
    val documentation: DocumentationDistribution? = null,
    val commonizerJar: String? = null,
) {
    val documentationIdentity: Digest? get() = documentation?.identity
}

/** Caller supplies the trusted manifest digest and a physical, stable bundle root. No discovery. */
fun verifyJvmWorkerBundle(root: String, expectedManifest: Digest, checkpoint: () -> Unit = {}): VerifiedJvmWorkerBundle {
    checkpoint()
    NativeFiles.ensureDirectory(root, create = false)
    NativeFiles.ensureDirectory("$root/lib", create = false)
    val bytes = NativeFiles.read("$root/manifest.json", 1024 * 1024)
    checkpoint()
    require(Sha256.digest(bytes) == expectedManifest) {
        "JVM worker manifest differs from the compiled runtime composition"
    }
    val document = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
    val version = document.objectValue().getValue("schemaVersion").longValue()
    require(version == 1L || version == 2L) { "Unsupported JVM worker bundle version" }
    val workerRole = if (version == 1L) "classes" else "workerArchive"
    val fields = document.fields(
        setOf("schemaVersion", workerRole, "api", "stdlib", "junit", "compilerManifest", "worker", "jacocoVersion", "jacocoAgent", "jacocoCli"), setOf("documentationManifest", "commonizer"))
    require(CompilerFiles.names(root, checkpoint).toSet() == setOf(if (version == 1L) "classes" else "worker.jar", "lib", "manifest.json", "compiler-manifest.json") +
        if ("documentationManifest" in fields) setOf("documentation-manifest.json") else emptySet()) {
        "Unexpected JVM worker bundle entries"
    }
    require(fields.getValue("jacocoVersion").stringValue() == "0.8.14") { "Unsupported JaCoCo bundle version" }
    checkpoint()
    val workerTree = if (version == 1L) {
        NativeFiles.ensureDirectory("$root/classes", create = false)
        val expectedTree = TreeManifest.fromJson(fields.getValue("classes"))
        require(expectedTree.files.size <= 4096) { "JVM worker class tree exceeds 4096 files" }
        val actual = mutableListOf<FileEntry>()
        var entriesVisited = 0
        var classBytes = 0L
        fun visit(directory: String, prefix: String, depth: Int) {
            checkpoint()
            require(depth <= 64) { "JVM worker class tree exceeds 64 directories" }
            for (name in CompilerFiles.names(directory, checkpoint)) {
                checkpoint()
                require(++entriesVisited <= 8192) { "JVM worker tree exceeds 8192 entries" }
                val path = "$directory/$name"
                val logical = LogicalPath(prefix + name)
                val info = NativeFiles.info(path) ?: error("Missing JVM worker entry")
                if (NativeFiles.directory(info)) visit(path, "${logical.value}/", depth + 1)
                else {
                    require(NativeFiles.regular(info)) { "Symlink or special JVM worker file" }
                    require(actual.size < 4096) { "JVM worker class tree exceeds 4096 files" }
                    val digest = CompilerFiles.digest(path, 8 * 1024 * 1024L, checkpoint)
                    classBytes += digest.sizeBytes
                    require(classBytes <= 256L * 1024 * 1024) { "JVM worker classes exceed 256 MiB" }
                    actual += FileEntry(logical, digest, info.mode and 73 != 0)
                }
            }
        }
        visit("$root/classes", "", 0)
        require(TreeManifest(actual).toJson() == expectedTree.toJson()) { "JVM worker classes differ from the compiled runtime composition" }
        Sha256.digest(expectedTree.toJson())
    } else null
    val roles = listOf("api", "stdlib", "junit", "compilerManifest", "jacocoAgent", "jacocoCli") +
        (if ("documentationManifest" in fields) listOf("documentationManifest") else emptyList()) +
        (if ("commonizer" in fields) listOf("commonizer") else emptyList()) +
        (if (version == 2L) listOf("workerArchive") else emptyList())
    val entries = roles.associateWith { role ->
        checkpoint()
        val entry = FileEntry.fromJson(fields.getValue(role))
        val path = entry.path.value
        require(!entry.executable && if (role == "compilerManifest") path == "compiler-manifest.json"
            else if (role == "documentationManifest") path == "documentation-manifest.json"
            else if (role == "workerArchive") path == "worker.jar"
            else path.matches(Regex("lib/[A-Za-z0-9][A-Za-z0-9_.-]*\\.jar"))) { "Invalid JVM worker artifact path" }
        val maxBytes = if (role == "workerArchive") 64 * 1024 * 1024L else 8 * 1024 * 1024L
        require(entry.digest.sizeBytes <= maxBytes) { "JVM worker artifact exceeds size limit" }
        val info = NativeFiles.info("$root/$path") ?: error("Missing JVM worker artifact")
        require(NativeFiles.regular(info) && info.mode and 73 == 0 &&
            CompilerFiles.digest("$root/$path", maxBytes, checkpoint) == entry.digest) { "JVM worker artifact differs from the compiled runtime composition" }
        entry
    }
    require(entries.values.map { it.path }.distinct().size == roles.size) { "Duplicate JVM worker artifact" }
    require(CompilerFiles.names("$root/lib", checkpoint).toSet() == entries.values.filter { it.path.value.startsWith("lib/") }.map { it.path.value.substringAfter('/') }.toSet()) {
        "Unexpected JVM worker libraries"
    }
    val documentation = entries["documentationManifest"]?.let { entry ->
        val document = DocumentationDistribution.fromJson(parseJson(NativeFiles.read("$root/${entry.path.value}", 1024 * 1024)
            .decodeToString(throwOnInvalidSequence = true)))
        require(document.identity == entry.digest) { "Documentation manifest is not the pinned canonical document" }
        document
    }
    val identity = if (version == 1L) jvmWorkerIdentity(checkNotNull(workerTree), entries.getValue("api").digest,
        entries.getValue("stdlib").digest, entries.getValue("junit").digest,
        entries.getValue("jacocoAgent").digest, entries.getValue("jacocoCli").digest, fields.getValue("jacocoVersion").stringValue(), entries["documentationManifest"]?.digest, entries["commonizer"]?.digest)
    else jvmWorkerArchiveIdentity(entries.getValue("workerArchive").digest, entries.getValue("api").digest,
        entries.getValue("stdlib").digest, entries.getValue("junit").digest,
        entries.getValue("jacocoAgent").digest, entries.getValue("jacocoCli").digest, fields.getValue("jacocoVersion").stringValue(), entries["documentationManifest"]?.digest, entries["commonizer"]?.digest)
    require(identity == Digest.fromJson(fields.getValue("worker"))) { "JVM worker identity differs from bundle" }
    checkpoint()
    fun path(role: String) = "$root/${entries.getValue(role).path.value}"
    return VerifiedJvmWorkerBundle(if (version == 1L) "$root/classes" else path("workerArchive"), path("api"), path("stdlib"), path("junit"), path("compilerManifest"),
        entries.getValue("compilerManifest").digest, identity, entries.getValue("junit").digest,
        fields.getValue("jacocoVersion").stringValue(), path("jacocoAgent"), path("jacocoCli"),
        entries.getValue("jacocoAgent").digest, entries.getValue("jacocoCli").digest,
        entries["documentationManifest"]?.let { "$root/${it.path.value}" }, documentation,
        entries["commonizer"]?.let { "$root/${it.path.value}" })
}
