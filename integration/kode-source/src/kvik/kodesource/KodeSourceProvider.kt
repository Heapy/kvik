package kvik.kodesource

import kvik.core.*
import kvik.projectsnapshot.*
import kotlin.io.encoding.Base64

/** Read-only kode v1 adapter. Resolving always contacts kode; cached inventory is keyed by verified tree,
 * never by a moving selector. This API does not claim offline revision resolution. */
class KodeSourceProvider(private val transport: KodeTransport, private val cache: KodeSourceCache? = null) : SourceSnapshotProvider {
    override fun resolve(selector: SourceSelector, checkpoint: () -> Unit): ResolvedSourceSnapshot {
        require(Regex("[a-z0-9][a-z0-9._-]{0,63}").matches(selector.project)) { "Invalid kode project" }
        require(validSelector(selector.selector)) { "Invalid kode selector" }
        val resolved = response(listOf("resolve", selector.project, selector.selector), RESOLVE_LIMIT, "resolve", checkpoint)
            .fields(setOf("schemaVersion", "operation", "project", "selector", "revision", "tree"), setOf("ref"))
        require(resolved.getValue("project").stringValue() == selector.project && resolved.getValue("selector").stringValue() == selector.selector) { "Kode resolve scope mismatch" }
        val revision = objectId(resolved.getValue("revision").stringValue())
        val tree = objectId(resolved.getValue("tree").stringValue(), revision.length)
        if (isObjectId(selector.selector)) {
            require(revision == selector.selector && "ref" !in resolved) { "Kode immutable resolution mismatch" }
        } else require(resolved["ref"]?.stringValue() == selector.selector) { "Kode reference resolution mismatch" }
        checkpoint()
        val cached = cache?.manifest(selector.project, tree)
        checkpoint()
        val manifest = if (cached != null) SourceManifest(cached.files, cached.directories, checkpoint) else {
            val document = response(listOf("tree", selector.project, "--rev", revision), MANIFEST_LIMIT, "tree", checkpoint)
                .fields(setOf("schemaVersion", "operation", "project", "revision", "tree", "entries"))
            require(document.getValue("project").stringValue() == selector.project && document.getValue("revision").stringValue() == revision &&
                document.getValue("tree").stringValue() == tree) { "Kode tree scope mismatch" }
            val entries = document.getValue("entries").arrayValue()
            require(entries.size <= 100_000) { "Kode tree entry limit exceeded" }
            val files = entries.map { entry ->
                checkpoint()
                val fields = entry.fields(setOf("path", "mode", "type", "blob", "size"))
                require(fields.getValue("type").stringValue() == "blob") { "Kode tree contains a non-file entry" }
                val mode = fields.getValue("mode").stringValue()
                require(mode == "100644" || mode == "100755") { "Kode links and special files are unsupported" }
                val path = LogicalPath(fields.getValue("path").stringValue())
                require(path.value.split('/').none { it == ".git" }) { "Kode .git paths are unsupported" }
                val size = fields.getValue("size").longValue()
                require(size >= 0) { "Negative kode blob size" }
                SourceFileEntry(path, SourceBlob(objectId(fields.getValue("blob").stringValue(), revision.length), size), mode == "100755")
            }
            SourceManifest(files, parentDirectories(files, checkpoint), checkpoint)
        }
        verifyTree(manifest, tree, checkpoint)
        if (cached == null) { cache?.storeManifest(selector.project, tree, manifest); checkpoint() }
        return Resolved(selector.project, revision, tree, manifest)
    }

    private inner class Resolved(val project: String, revision: String, tree: String, override val manifest: SourceManifest) : ResolvedSourceSnapshot {
        override val provenance = SourceProvenance("kode", project, revision, tree)
        private val declared = manifest.files.map { it.blob }.toSet()

        private fun declared(blob: SourceBlob) { require(blob in declared) { "Blob does not belong to the resolved kode inventory" } }

        override fun knownDigest(blob: SourceBlob): Digest? {
            declared(blob)
            return cache?.knownDigest(project, blob)?.also { require(it.sizeBytes == blob.sizeBytes) { "Kode digest hint size mismatch" } }
        }

        override fun verify(blob: SourceBlob, bytes: ByteArray, checkpoint: () -> Unit) {
            declared(blob); checkpoint()
            require(bytes.size.toLong() == blob.sizeBytes) { "Kode blob size mismatch" }
            require(gitObjectId("blob", bytes, blob.identity.length, checkpoint) == blob.identity) { "Kode blob identity mismatch" }
            cache?.storeDigest(project, blob, Sha256.digest(bytes))
            checkpoint()
        }

        override fun fetch(blob: SourceBlob, maxBytes: Int, checkpoint: () -> Unit): ByteArray {
            declared(blob)
            require(maxBytes in 0..MAX_BLOB_BYTES && blob.sizeBytes <= maxBytes) { "Kode blob exceeds selected read bound" }
            // JSON control-character escaping can expand valid UTF-8 input by six times.
            val fields = response(listOf("blob", project, blob.identity), (blob.sizeBytes * 6 + 4096).toInt(), "blob", checkpoint)
                .fields(setOf("schemaVersion", "operation", "project", "blob", "encoding", "content"))
            require(fields.getValue("project").stringValue() == project && fields.getValue("blob").stringValue() == blob.identity) { "Kode blob scope mismatch" }
            val content = fields.getValue("content").stringValue()
            val bytes = when (fields.getValue("encoding").stringValue()) {
                "utf-8" -> {
                    require(content.length <= maxBytes) { "Kode blob exceeds selected read bound" }
                    content.encodeToByteArray()
                }
                "base64" -> {
                    require(content.length.toLong() <= ((blob.sizeBytes + 2) / 3) * 4) { "Kode base64 exceeds selected read bound" }
                    Base64.decode(content).also { require(Base64.encode(it) == content) { "Noncanonical kode base64" } }
                }
                else -> error("Unsupported kode content encoding")
            }
            checkpoint(); verify(blob, bytes, checkpoint)
            return bytes
        }
    }

    private fun response(arguments: List<String>, limit: Int, operation: String, checkpoint: () -> Unit): JsonValue {
        checkpoint()
        val result = transport.execute(arguments, limit, checkpoint)
        checkpoint()
        require(result.stdout.size <= limit && result.stderr.size <= limit) { "Kode transport exceeded output bound" }
        if (result.exitCode != 0) throw SnapshotFailure("Kode $operation failed (exit ${result.exitCode})")
        val value = parseJson(result.stdout.decodeToString(throwOnInvalidSequence = true), maxDepth = 16)
        val fields = value.objectValue()
        fields.requireVersion()
        require(fields["operation"]?.stringValue() == operation) { "Kode operation mismatch" }
        return value
    }

    companion object {
        private const val RESOLVE_LIMIT = 16 * 1024
        private const val MANIFEST_LIMIT = 16 * 1024 * 1024
        private const val MAX_BLOB_BYTES = 64 * 1024 * 1024
    }
}

private fun isObjectId(value: String) = (value.length == 40 || value.length == 64) && value.all { it in '0'..'9' || it in 'a'..'f' } && value.any { it != '0' }
private fun objectId(value: String, width: Int = value.length): String {
    require(isObjectId(value) && value.length == width) { "Invalid kode object identity" }
    return value
}
private fun validSelector(value: String): Boolean = isObjectId(value) || (value.length in 1..200 &&
    value.split('/').all { Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(it) && !it.endsWith(".lock") })

private fun parentDirectories(files: List<SourceFileEntry>, checkpoint: () -> Unit): List<LogicalPath> {
    val directories = mutableSetOf<String>()
    files.forEach { entry ->
        val parts = entry.path.value.split('/')
        require(parts.size <= 128) { "Kode tree path exceeds depth limit" }
        for (end in 1 until parts.size) {
            checkpoint(); directories += parts.take(end).joinToString("/")
            require(directories.size <= 100_000) { "Kode directory limit exceeded" }
        }
    }
    return directories.sorted().map(::LogicalPath)
}

/** `tree -r` omits tree nodes: reconstruct all of them, with Git's unsigned UTF-8 ordering and '/' directory suffix.
 * A hidden empty subtree, omitted file, wrong mode or path therefore cannot silently become a different inventory. */
private fun verifyTree(manifest: SourceManifest, expected: String, checkpoint: () -> Unit) {
    val files = manifest.files
    require(manifest.directories.toSet() == parentDirectories(files, checkpoint).toSet()) { "Kode empty or extra directory is not represented by recursive tree output" }
    val sizes = mutableMapOf<String, Long>()
    val children = mutableMapOf<String, MutableList<TreeChild>>()
    files.forEach { file ->
        checkpoint()
        objectId(file.blob.identity, expected.length)
        require(file.path.value.split('/').none { it == ".git" }) { "Kode .git paths are unsupported" }
        val previous = sizes.put(file.blob.identity, file.blob.sizeBytes)
        require(previous == null || previous == file.blob.sizeBytes) { "Conflicting sizes for one kode blob" }
        val path = file.path.value
        children.getOrPut(path.substringBeforeLast('/', "")) { mutableListOf() } += TreeChild(path.substringAfterLast('/'), false, file.blob.identity, file.executable)
    }
    val directories = manifest.directories.map { it.value }.sortedByDescending { it.count { char -> char == '/' } }
    fun hash(path: String): String {
        checkpoint()
        val sorted = children[path].orEmpty().sortedWith { a, b -> compareBytes(a.sortName, b.sortName) }
        val records = sorted.map { child ->
            val prefix = "${if (child.directory) "40000" else if (child.executable) "100755" else "100644"} ${child.name}\u0000".encodeToByteArray()
            prefix + ByteArray(expected.length / 2) { index -> child.id.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }
        val size = records.sumOf { it.size.toLong() }
        require(size <= 16L * 1024 * 1024) { "Kode tree object exceeds bound" }
        val bytes = ByteArray(size.toInt())
        var offset = 0
        records.forEach { checkpoint(); it.copyInto(bytes, offset); offset += it.size }
        return gitObjectId("tree", bytes, expected.length, checkpoint)
    }
    directories.forEach { path ->
        val id = hash(path)
        children.getOrPut(path.substringBeforeLast('/', "")) { mutableListOf() } += TreeChild(path.substringAfterLast('/'), true, id, false)
    }
    require(hash("") == expected) { "Kode recursive inventory does not match tree identity (omitted entries or unsupported empty subtree)" }
}

private class TreeChild(val name: String, val directory: Boolean, val id: String, val executable: Boolean) {
    val sortName = (name + if (directory) "/" else "").encodeToByteArray()
}
private fun compareBytes(a: ByteArray, b: ByteArray): Int {
    for (index in 0 until minOf(a.size, b.size)) {
        val compared = (a[index].toInt() and 255).compareTo(b[index].toInt() and 255)
        if (compared != 0) return compared
    }
    return a.size.compareTo(b.size)
}
