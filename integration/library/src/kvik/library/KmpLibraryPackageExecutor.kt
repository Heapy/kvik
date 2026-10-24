package kvik.library

import kvik.core.*
import kotlin.time.TimeSource

/** Packages supplied metadata bytes; never compiles, fabricates a KLIB, or starts a process. */
class KmpLibraryPackageExecutor(private val content: ContentStore) : ActionExecutor {
    override fun resources(action: ActionSpec) = ResourceRequest(384)
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        val selection = KmpLibraryPackage.fromJson(parseJson(action.parameters.canonical()))
        val snapshot = kmpLibraryPackageAction(selection, action.inputs, action.composition)
        require(snapshot == action) { "KMP package action differs from its typed contract" }
        val key = snapshot.key(Sha256)
        val psm = selection.projectStructure()
        val generated = mapOf("META-INF/MANIFEST.MF" to jarManifest, PROJECT_STRUCTURE_PATH to psm)
        val metadata = snapshot.inputs.files.filter { it.path.value.startsWith("metadata/") }.map {
            it.copy(path = LogicalPath(it.path.value.removePrefix("metadata/")))
        }
        val entries = (metadata + generated.map { (path, bytes) -> FileEntry(LogicalPath(path), Sha256.digest(bytes)) }).sortedBy { it.path.value }
        val targets = snapshot.inputs.files.filter { it.path.value.startsWith("library/") }
        val started = TimeSource.Monotonic.markNow()
        return object : RunningAction {
            private var cursor = 0
            private var zip: StoredZip? = null
            private var cancelled = false
            private var closed = false
            private var result: ActionResult? = null
            private var artifact: Digest? = null
            private var publication = 0
            private val outputs = mutableListOf<FileEntry>()
            private fun checkpoint() {
                if (cancelled) throw KmpPackagingStopped(true)
                if (started.elapsedNow().inWholeMilliseconds >= snapshot.timeoutMillis) throw KmpPackagingStopped(false)
            }
            private fun checked(entry: FileEntry): ByteArray {
                val bytes = content.get(entry.digest) ?: error("KMP package input unavailable: ${entry.path.value}")
                require(Sha256.digest(bytes) == entry.digest) { "KMP package input digest mismatch: ${entry.path.value}" }
                checkpoint()
                return bytes
            }
            private fun publish(name: String, bytes: ByteArray): Digest {
                checkpoint()
                val digest = Sha256.digest(bytes)
                require(content.put(bytes) == digest) { "KMP publication returned incorrect digest" }
                checkpoint()
                val entry = FileEntry(LogicalPath("${selection.directory()}/$name"), digest)
                checked(entry)
                outputs += entry
                return digest
            }
            private fun finish(status: ActionStatus, code: Int, message: String? = null): ActionResult {
                val completed = ActionResult(actionId, key, status,
                    TreeManifest(if (status == ActionStatus.EXECUTED) outputs.sortedBy { it.path.value } else emptyList()), code, message)
                result = completed; zip = null
                return completed.detachedKmp()
            }
            override fun poll(): ActionResult? {
                check(!closed) { "KMP package action is closed" }
                result?.let { return it.detachedKmp() }
                try {
                    checkpoint()
                    if (cursor < targets.size) { checked(targets[cursor++]); return null }
                    val index = cursor - targets.size
                    if (index < entries.size) {
                        val entry = entries[index]
                        val bytes = generated[entry.path.value] ?: checked(entry)
                        val writer = zip ?: StoredZip(entries, includeDirectories = true).also { zip = it }
                        writer.append(bytes, ::checkpoint)
                        cursor++
                        return null
                    }
                    when (publication++) {
                        0 -> artifact = publish("${selection.basename()}.jar", checkNotNull(zip).finish(::checkpoint)).also { zip = null }
                        1 -> publish("${selection.basename()}.pom", selection.pom())
                        2 -> publish("${selection.basename()}.module", selection.moduleMetadata(checkNotNull(artifact), snapshot.inputs))
                        else -> return finish(ActionStatus.EXECUTED, 0)
                    }
                    checkpoint()
                    return null
                } catch (stopped: KmpPackagingStopped) {
                    return finish(if (stopped.cancelled) ActionStatus.CANCELLED else ActionStatus.FAILED,
                        if (stopped.cancelled) 130 else 124, if (stopped.cancelled) null else "KMP library packaging timed out")
                } catch (failure: Exception) {
                    return finish(ActionStatus.FAILED, 1, failure.message ?: "KMP library packaging failed")
                }
            }
            override fun cancel() { if (!closed && result == null) cancelled = true }
            override fun close() { closed = true; zip = null; outputs.clear() }
        }
    }
}
private class KmpPackagingStopped(val cancelled: Boolean) : Exception()
private fun ActionResult.detachedKmp() = copy(outputs = TreeManifest(outputs.files.toList()))
