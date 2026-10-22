package kvik.cache.remote

import kvik.core.*

/** Exact adapter/version, target and platform approval. Evidence must refer to an actual portability test. */
data class PortabilityApproval(val implementation: String, val target: String, val platform: Map<String, String>, val evidence: String) {
    init { require(implementation.isNotBlank() && target.isNotBlank() && platform.isNotEmpty() && evidence.isNotBlank()) }
}

internal val utf8Order = Comparator<String> { left, right ->
    val a = left.encodeToByteArray(); val b = right.encodeToByteArray()
    var result = 0
    for (index in 0 until minOf(a.size, b.size)) {
        result = (a[index].toInt() and 255) - (b[index].toInt() and 255)
        if (result != 0) break
    }
    if (result == 0) a.size - b.size else result
}

internal data class DirectoryEncoding(val root: ByteArray, val children: Map<Digest, ByteArray>) {
    fun tree(): ByteArray = proto {
        bytes(1, root)
        for ((_, child) in children.entries.sortedBy { it.key.hash }) bytes(2, child)
    }
}

internal fun directoryEncoding(manifest: TreeManifest, limits: TransferLimits): DirectoryEncoding {
    demand(manifest.files.size <= limits.files, "Too many files")
    for (file in manifest.files) demand(file.path.value.split('/').size <= limits.depth, "Tree exceeds depth limit")
    val children = linkedMapOf<Digest, ByteArray>()
    fun encode(files: List<FileEntry>): ByteArray {
        val direct = files.filter { '/' !in it.path.value }.sortedWith(compareBy(utf8Order) { it.path.value })
        val grouped = files.filter { '/' in it.path.value }.groupBy { it.path.value.substringBefore('/') }
        val directories = grouped.keys.sortedWith(utf8Order).map { name ->
            val encoded = encode(grouped.getValue(name).map { it.copy(path = LogicalPath(it.path.value.substringAfter('/'))) })
            val digest = Sha256.digest(encoded)
            children[digest] = encoded
            name to digest
        }
        return proto {
            for (file in direct) bytes(1, proto {
                text(1, file.path.value); bytes(2, file.digest.wire()); number(4, if (file.executable) 1 else 0)
            })
            for ((name, digest) in directories) bytes(2, proto { text(1, name); bytes(2, digest.wire()) })
        }.also { demand(it.size <= limits.blobBytes, "Directory exceeds blob limit") }
    }
    val root = encode(manifest.files)
    return DirectoryEncoding(root, children.toMap())
}

internal class ReapiMapping(original: ActionSpec, private val limits: TransferLimits) {
    val action = original.copy(platform = original.platform.toMap(), toolchains = original.toolchains.toMap(),
        inputs = TreeManifest(original.inputs.files.toList()), outputs = original.outputs.toList(), arguments = original.arguments.toList(),
        environment = original.environment.toMap(), parameters = parseJson(original.parameters.canonical()) as JsonValue.Object)
    val localKey = action.key(Sha256)
    val blobs = linkedMapOf<Digest, ByteArray>()
    val actionDigest: Digest
    init {
        demand(action.timeoutMillis / 1000 <= 315_576_000_000L, "Action timeout exceeds protobuf Duration range")
        demand(action.arguments.isNotEmpty() && action.arguments.first().isNotBlank(), "REAPI requires declared command arguments")
        demand(action.outputs.size <= limits.files, "Too many output roots")
        TreeManifest(action.outputs.map { FileEntry(it, Sha256.digest(byteArrayOf())) }) // Reject overlapping roots.
        val input = directoryEncoding(action.inputs, limits)
        input.children.forEach { (digest, bytes) -> blobs[digest] = bytes }
        val inputDigest = add(input.root)
        val command = proto {
            for (argument in action.arguments) bytes(1, argument.encodeToByteArray())
            for (name in action.environment.keys.sortedWith(utf8Order)) bytes(2, proto { text(1, name); text(2, action.environment.getValue(name)) })
            for (output in action.outputs.map { it.value }.sortedWith(utf8Order)) text(7, output)
        }
        val commandDigest = add(command)
        val platform = proto {
            for (name in action.platform.keys.sortedWith(utf8Order)) bytes(1, proto { text(1, name); text(2, action.platform.getValue(name)) })
        }
        actionDigest = add(proto {
            bytes(1, commandDigest.wire()); bytes(2, inputDigest.wire())
            bytes(6, proto { number(1, action.timeoutMillis / 1000); number(2, action.timeoutMillis % 1000 * 1_000_000) })
            // Salt binds every remaining Kvik contract field, while normal REAPI fields describe the actual action.
            text(9, "kvik-reapi-v1:${localKey.hash}:${localKey.sizeBytes}"); bytes(10, platform)
        })
    }
    private fun add(bytes: ByteArray): Digest {
        demand(bytes.size <= limits.blobBytes, "Mapping blob exceeds transfer limit")
        return Sha256.digest(bytes).also { blobs[it] = bytes }
    }
    fun validateOutputs(manifest: TreeManifest) {
        TreeManifest(manifest.files.toList())
        demand(manifest.files.size <= limits.files, "Too many output files")
        for (file in manifest.files) {
            demand(file.path.value.split('/').size <= limits.depth, "Output exceeds depth limit")
            demand(action.outputs.any { file.path == it || file.path.value.startsWith(it.value + "/") }, "Undeclared output path")
        }
    }
    fun result(manifest: TreeManifest): ByteArray {
        validateOutputs(manifest)
        return proto {
            for (output in action.outputs.sortedWith(compareBy(utf8Order) { it.value })) {
                val exact = manifest.files.singleOrNull { it.path == output }
                if (exact != null) {
                    bytes(2, proto { text(1, output.value); bytes(2, exact.digest.wire()); number(4, if (exact.executable) 1 else 0) })
                } else {
                    val nested = manifest.files.filter { it.path.value.startsWith(output.value + "/") }
                    if (nested.isNotEmpty()) {
                        val encoding = directoryEncoding(TreeManifest(nested.map { it.copy(path = LogicalPath(it.path.value.removePrefix(output.value + "/"))) }), limits)
                        val treeDigest = add(encoding.tree())
                        bytes(3, proto { text(1, output.value); bytes(3, treeDigest.wire()) })
                    }
                }
            }
        }.also { demand(it.size <= limits.blobBytes, "ActionResult exceeds limit") }
    }
    fun decodeResult(bytes: ByteArray, fetch: (Digest) -> ByteArray): TreeManifest {
        demand(bytes.size <= limits.blobBytes, "ActionResult exceeds limit")
        val result = ProtoMessage(bytes)
        result.only(setOf(2, 3, 4, 5, 6, 7, 8, 9)) // Includes observational metadata; all symlinks rejected.
        demand(result.number(4) == 0L, "Failed remote action is not reusable")
        // Logs are not artifacts in the core cache contract. Verify referenced digests without retaining logs.
        for (field in listOf(6, 8)) if (result.fields.any { it.number == field }) fetch(result.digest(field))
        val files = mutableListOf<FileEntry>()
        val roots = mutableSetOf<String>()
        fun outputRoot(path: String) {
            val logical = LogicalPath(path)
            demand(logical in action.outputs && roots.add(path), "Unexpected or duplicate output root")
        }
        fun file(raw: ByteArray, prefix: String, nameOnly: Boolean): FileEntry {
            val value = ProtoMessage(raw)
            value.only(if (nameOnly) setOf(1, 2, 4) else setOf(1, 2, 4, 5))
            val name = value.text(1, true)
            LogicalPath(name)
            if (nameOnly) demand('/' !in name, "Directory entry contains a separator")
            val digest = value.digest(2)
            demand(value.number(4) in 0L..1L, "Invalid executable flag")
            if (value.fields.any { it.number == 5 }) demand(Sha256.digest(value.bytes(5)) == digest, "Inline output digest mismatch")
            return FileEntry(LogicalPath(if (prefix.isEmpty()) name else "$prefix/$name"), digest, value.number(4) == 1L)
        }
        for (raw in result.repeated(2)) {
            val entry = file(raw, "", false)
            outputRoot(entry.path.value)
            files += entry
        }
        var visited = 0
        for (raw in result.repeated(3)) {
            val output = ProtoMessage(raw)
            output.only(setOf(1, 3, 4))
            val rootPath = output.text(1, true)
            outputRoot(rootPath)
            val tree = ProtoMessage(fetch(output.digest(3)))
            tree.only(setOf(1, 2))
            val children = linkedMapOf<Digest, ByteArray>()
            for (child in tree.repeated(2)) {
                demand(children.size <= limits.files * limits.depth, "Too many tree directories")
                demand(children.put(Sha256.digest(child), child) == null, "Duplicate tree directory")
            }
            fun walk(directory: ByteArray, prefix: String, depth: Int, ancestry: Set<Digest>) {
                demand(++visited <= limits.files * limits.depth && depth <= limits.depth, "Output tree expansion limit exceeded")
                val identity = Sha256.digest(directory)
                demand(identity !in ancestry, "Cyclic output tree")
                val node = ProtoMessage(directory)
                node.only(setOf(1, 2))
                val names = mutableSetOf<String>()
                for (rawFile in node.repeated(1)) {
                    val entry = file(rawFile, prefix, true)
                    demand(names.add(entry.path.value.substringAfterLast('/')), "Duplicate directory entry")
                    files += entry
                    demand(files.size <= limits.files, "Too many restored files")
                }
                for (rawDirectory in node.repeated(2)) {
                    val child = ProtoMessage(rawDirectory)
                    child.only(setOf(1, 2))
                    val name = child.text(1, true)
                    LogicalPath(name)
                    demand('/' !in name && names.add(name), "Directory collision or invalid name")
                    val contents = children[child.digest(2)] ?: throw RemoteFailure("Output tree child is missing")
                    walk(contents, "$prefix/$name", depth + 1, ancestry + identity)
                }
            }
            walk(tree.bytes(1, true), rootPath, rootPath.split('/').size, emptySet())
        }
        return TreeManifest(files.toList()).also(::validateOutputs)
    }
}
