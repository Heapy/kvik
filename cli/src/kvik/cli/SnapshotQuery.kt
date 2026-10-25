@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.projectsnapshot.NativeProjectSnapshotter
import kvik.execution.platform.*

/** Internal owned observer; never publishes CAS content or asserts tool inventory freshness. */
internal fun snapshotQueryCommand(args: List<String>): JsonValue {
    val (source, remaining) = sourceProjectOptions(args)
    if (source != null) return sourceSnapshotQuery(source, remaining)
    require(args.size == 8 && args[0] == "--project" && args[2] == "--lock" && args[4] == "--tools" && args[6] == "--store") { "Invalid snapshot-query arguments" }
    val project = readProject(args[1])
    requireDisjointStore(project, args[7], args[3], args[5])
    val store = object : ContentStore {
        override fun get(digest: Digest): ByteArray? = null
        override fun put(bytes: ByteArray): Digest = error("Snapshot observer cannot write content")
    }
    fun tools() = Sha256.digest(NativeFiles.read(args[5], 1024 * 1024))
    val before = tools()
    val inputs = NativeProjectSnapshotter(project.root, store, parseConfiguration = ::parseBuildProjectConfiguration).currentFingerprint(project.name, args[3], Sha256.digest(project.bytes))
    val after = tools()
    val fingerprint = if (inputs != null && before == after) Sha256.digest(obj("inputs" to inputs.toJson(), "toolsDocument" to after.toJson())) else null
    return obj("schemaVersion" to 1.json(), "fingerprint" to (fingerprint?.toJson() ?: JsonValue.Null))
}

/** Source refs are resolved in this owned child; the controller receives one immutable identity. */
private fun sourceSnapshotQuery(source: KodeProjectOptions, args: List<String>): JsonValue {
    val options = queryArguments(args, setOf("--project", "--lock", "--tools", "--store"), flags = setOf("--offline"))
    require(options.values.keys == setOf("--project", "--lock", "--tools", "--store")) { "Invalid source snapshot-query arguments" }
    val project = projectOptions(listOf("--project", options.values.getValue("--project"), "--lock", options.values.getValue("--lock"),
        "--store", options.values.getValue("--store")) + if ("--offline" in options.flags) listOf("--offline") else emptyList(), lockOptions = true)
    val tools = options.values.getValue("--tools")
    NativeFiles.validateAbsolute(tools)
    check(kvik_interrupt_install() == 0) { "Cannot install source observation handlers" }
    try {
        val before = Sha256.digest(NativeFiles.read(tools, 1024 * 1024))
        return withPreparedSourceProject(source, project) { prepared ->
            require(prepared.entry(LogicalPath(project.lock)) != null) { "Dependency lock is absent from source revision" }
            val after = Sha256.digest(NativeFiles.read(tools, 1024 * 1024))
            val fingerprint = if (before == after) Sha256.digest(obj("source" to prepared.reference.toJson(), "toolsDocument" to after.toJson())) else null
            obj("schemaVersion" to 1.json(), "fingerprint" to (fingerprint?.toJson() ?: JsonValue.Null), "source" to prepared.reference.toJson())
        }
    } finally { kvik_interrupt_restore() }
}
