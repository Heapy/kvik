package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.projectsnapshot.NativeProjectSnapshotter

/** Internal owned observer; never publishes CAS content or asserts tool inventory freshness. */
internal fun snapshotQueryCommand(args: List<String>): JsonValue {
    require(args.size == 8 && args[0] == "--project" && args[2] == "--lock" && args[4] == "--tools" && args[6] == "--store") { "Invalid snapshot-query arguments" }
    val project = readProject(args[1])
    requireDisjointStore(project, args[7], args[3], args[5])
    val store = object : ContentStore {
        override fun get(digest: Digest): ByteArray? = null
        override fun put(bytes: ByteArray): Digest = error("Snapshot observer cannot write content")
    }
    fun tools() = Sha256.digest(NativeFiles.read(args[5], 1024 * 1024))
    val before = tools()
    val inputs = NativeProjectSnapshotter(project.root, store).currentFingerprint(project.name, args[3], Sha256.digest(project.bytes))
    val after = tools()
    val fingerprint = if (inputs != null && before == after) Sha256.digest(obj("inputs" to inputs.toJson(), "toolsDocument" to after.toJson())) else null
    return obj("schemaVersion" to 1.json(), "fingerprint" to (fingerprint?.toJson() ?: JsonValue.Null))
}
