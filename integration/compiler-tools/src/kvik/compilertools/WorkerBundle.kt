package kvik.compilertools

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.compiler.jvm.jvmWorkerIdentity

/** Verified worker implementation paths; the JDK and compiler distribution are separate tools. */
class VerifiedJvmWorkerBundle internal constructor(
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
)

/** Caller supplies the trusted manifest digest and a physical, stable bundle root. No discovery. */
fun verifyJvmWorkerBundle(root: String, expectedManifest: Digest, checkpoint: () -> Unit = {}): VerifiedJvmWorkerBundle {
    checkpoint()
    NativeFiles.ensureDirectory(root, create = false)
    NativeFiles.ensureDirectory("$root/classes", create = false)
    NativeFiles.ensureDirectory("$root/lib", create = false)
    require(CompilerFiles.names(root, checkpoint).toSet() == setOf("classes", "lib", "manifest.json", "compiler-manifest.json")) {
        "Unexpected JVM worker bundle entries"
    }
    val bytes = NativeFiles.read("$root/manifest.json", 1024 * 1024)
    checkpoint()
    require(Sha256.digest(bytes) == expectedManifest) {
        "JVM worker manifest differs from the compiled runtime composition"
    }
    val fields = parseJson(bytes.decodeToString(throwOnInvalidSequence = true)).fields(
        setOf("schemaVersion", "classes", "api", "stdlib", "junit", "compilerManifest", "worker", "jacocoVersion", "jacocoAgent", "jacocoCli"))
    fields.requireVersion()
    require(fields.getValue("jacocoVersion").stringValue() == "0.8.14") { "Unsupported JaCoCo bundle version" }
    checkpoint()
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
    val entries = listOf("api", "stdlib", "junit", "compilerManifest", "jacocoAgent", "jacocoCli").associateWith { role ->
        checkpoint()
        val entry = FileEntry.fromJson(fields.getValue(role))
        val path = entry.path.value
        require(!entry.executable && if (role == "compilerManifest") path == "compiler-manifest.json"
            else path.matches(Regex("lib/[A-Za-z0-9][A-Za-z0-9_.-]*\\.jar"))) { "Invalid JVM worker artifact path" }
        val info = NativeFiles.info("$root/$path") ?: error("Missing JVM worker artifact")
        require(NativeFiles.regular(info) && info.mode and 73 == 0 &&
            CompilerFiles.digest("$root/$path", 8 * 1024 * 1024L, checkpoint) == entry.digest) { "JVM worker artifact differs from the compiled runtime composition" }
        entry
    }
    require(entries.values.map { it.path }.distinct().size == 6) { "Duplicate JVM worker artifact" }
    require(CompilerFiles.names("$root/lib", checkpoint).toSet() == entries.filterKeys { it != "compilerManifest" }.values.map { it.path.value.substringAfter('/') }.toSet()) {
        "Unexpected JVM worker libraries"
    }
    val identity = jvmWorkerIdentity(Sha256.digest(expectedTree.toJson()), entries.getValue("api").digest,
        entries.getValue("stdlib").digest, entries.getValue("junit").digest,
        entries.getValue("jacocoAgent").digest, entries.getValue("jacocoCli").digest, fields.getValue("jacocoVersion").stringValue())
    require(identity == Digest.fromJson(fields.getValue("worker"))) { "JVM worker identity differs from bundle" }
    checkpoint()
    fun path(role: String) = "$root/${entries.getValue(role).path.value}"
    return VerifiedJvmWorkerBundle("$root/classes", path("api"), path("stdlib"), path("junit"), path("compilerManifest"),
        entries.getValue("compilerManifest").digest, identity, entries.getValue("junit").digest,
        fields.getValue("jacocoVersion").stringValue(), path("jacocoAgent"), path("jacocoCli"),
        entries.getValue("jacocoAgent").digest, entries.getValue("jacocoCli").digest)
}
