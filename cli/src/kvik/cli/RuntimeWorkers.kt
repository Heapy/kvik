package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*

/** Verified worker implementation paths; the JDK and compiler distribution are separate tools. */
data class RuntimeJvmWorker(
    val classes: String,
    val api: String,
    val stdlib: String,
    val junit: String,
    val compilerManifest: String,
    val compiler: Digest,
    val identity: Digest,
)

/** The expected manifest is embedded in the executable, independent of mutable cache metadata. */
fun verifiedRuntimeJvmWorker(): RuntimeJvmWorker = try {
    val root = "${runtimeDirectory()}/jvm"
    NativeFiles.ensureDirectory(root, create = false)
    NativeFiles.ensureDirectory("$root/classes", create = false)
    NativeFiles.ensureDirectory("$root/lib", create = false)
    require(NativeFiles.names(root).toSet() == setOf("classes", "lib", "manifest.json", "compiler-manifest.json")) {
        "Unexpected JVM worker bundle entries"
    }
    val bytes = NativeFiles.read("$root/manifest.json", 1024 * 1024)
    require(Sha256.digest(bytes) == Digest(runtimeJvmWorkerHash, runtimeJvmWorkerSize)) {
        "JVM worker manifest differs from the compiled runtime composition"
    }
    val fields = parseJson(bytes.decodeToString(throwOnInvalidSequence = true)).fields(
        setOf("schemaVersion", "classes", "api", "stdlib", "junit", "compilerManifest", "worker"))
    fields.requireVersion()
    val expectedTree = TreeManifest.fromJson(fields.getValue("classes"))
    require(expectedTree.files.size <= 4096) { "JVM worker class tree exceeds 4096 files" }
    val actual = mutableListOf<FileEntry>()
    fun visit(directory: String, prefix: String, depth: Int) {
        require(depth <= 64) { "JVM worker class tree exceeds 64 directories" }
        for (name in NativeFiles.names(directory)) {
            val path = "$directory/$name"
            val logical = LogicalPath(prefix + name)
            val info = NativeFiles.info(path) ?: error("Missing JVM worker entry")
            if (NativeFiles.directory(info)) visit(path, "${logical.value}/", depth + 1)
            else {
                require(NativeFiles.regular(info)) { "Symlink or special JVM worker file" }
                require(actual.size < 4096) { "JVM worker class tree exceeds 4096 files" }
                actual += FileEntry(logical, Sha256.digest(NativeFiles.read(path, 8 * 1024 * 1024)), info.mode and 73 != 0)
            }
        }
    }
    visit("$root/classes", "", 0)
    require(TreeManifest(actual).toJson() == expectedTree.toJson()) { "JVM worker classes differ from the compiled runtime composition" }
    val entries = listOf("api", "stdlib", "junit", "compilerManifest").associateWith { role ->
        val entry = FileEntry.fromJson(fields.getValue(role))
        val path = entry.path.value
        require(!entry.executable && if (role == "compilerManifest") path == "compiler-manifest.json"
            else path.matches(Regex("lib/[A-Za-z0-9][A-Za-z0-9_.-]*\\.jar"))) { "Invalid JVM worker artifact path" }
        val info = NativeFiles.info("$root/$path") ?: error("Missing JVM worker artifact")
        require(NativeFiles.regular(info) && info.mode and 73 == 0 &&
            Sha256.digest(NativeFiles.read("$root/$path", 8 * 1024 * 1024)) == entry.digest) { "JVM worker artifact differs from the compiled runtime composition" }
        entry
    }
    require(entries.values.map { it.path }.distinct().size == 4) { "Duplicate JVM worker artifact" }
    require(NativeFiles.names("$root/lib").toSet() == entries.filterKeys { it != "compilerManifest" }.values.map { it.path.value.substringAfter('/') }.toSet()) {
        "Unexpected JVM worker libraries"
    }
    val identity = Sha256.digest(obj("schemaVersion" to CONTRACT_VERSION.json(), "implementation" to "kvik-jvm-worker-bundle-v1".json(),
        "workerTree" to Sha256.digest(expectedTree.toJson()).toJson(), "api" to entries.getValue("api").digest.toJson(),
        "stdlib" to entries.getValue("stdlib").digest.toJson(), "junit" to entries.getValue("junit").digest.toJson()))
    require(identity == Digest.fromJson(fields.getValue("worker"))) { "JVM worker identity differs from bundle" }
    fun path(role: String) = "$root/${entries.getValue(role).path.value}"
    RuntimeJvmWorker("$root/classes", path("api"), path("stdlib"), path("junit"), path("compilerManifest"),
        entries.getValue("compilerManifest").digest, identity)
} catch (failure: Exception) {
    throw RuntimeIntegrityFailure(failure.message ?: "Cannot verify JVM worker bundle")
}
