package kvik.library

import kvik.core.*
import kotlin.time.TimeSource

/** Pure CAS packaging, confined to its caller. Reads/CRC work advance one input per poll. */
class LibraryPackageExecutor(private val content: ContentStore) : ActionExecutor {
    override fun resources(action: ActionSpec): ResourceRequest {
        // 64 MiB ZIP, current input/readback and store copies, plus filename/manifest working storage.
        // Reserve for archive overhead even when every input has zero bytes.
        return ResourceRequest(384)
    }

    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        val selection = LibraryPackage.fromJson(parseJson(action.parameters.canonical()))
        val snapshot = libraryPackageAction(selection, action.inputs, action.composition)
        require(snapshot == action) { "Library package action differs from its typed contract" }
        val key = snapshot.key(Sha256)
        val jar = selection.target == "jvm"
        val files = if (jar) (snapshot.inputs.files.map { it.copy(path = LogicalPath(it.path.value.removePrefix("classes/"))) } +
            FileEntry(LogicalPath("META-INF/MANIFEST.MF"), Sha256.digest(jarManifest))).sortedBy { it.path.value }
            else snapshot.inputs.files
        val started = TimeSource.Monotonic.markNow()
        return object : RunningAction {
            private var zip: StoredZip? = null
            private var cursor = 0
            private var cancelled = false
            private var closed = false
            private var result: ActionResult? = null
            private var artifact: Digest? = null
            private var publication = 0
            private val outputs = mutableListOf<FileEntry>()

            private fun checkpoint() {
                if (cancelled) throw PackagingStopped(true)
                if (started.elapsedNow().inWholeMilliseconds >= snapshot.timeoutMillis) throw PackagingStopped(false)
            }
            private fun publish(name: String, bytes: ByteArray): Digest {
                checkpoint()
                val expected = Sha256.digest(bytes)
                require(content.put(bytes) == expected) { "Library publication returned incorrect digest" }
                checkpoint()
                val stored = content.get(expected) ?: error("Library publication missing after write")
                require(Sha256.digest(stored) == expected) { "Library publication digest mismatch" }
                checkpoint()
                outputs += FileEntry(LogicalPath("${selection.directory()}/$name"), expected)
                return expected
            }
            private fun finish(status: ActionStatus, code: Int, message: String? = null): ActionResult {
                val value = ActionResult(actionId, key, status,
                    TreeManifest(if (status == ActionStatus.EXECUTED) outputs.sortedBy { it.path.value } else emptyList()), code, message)
                result = value
                zip = null
                return value.detached()
            }
            override fun poll(): ActionResult? {
                check(!closed) { "Library package action is closed" }
                result?.let { return it.detached() }
                try {
                    checkpoint()
                    if (cursor < files.size) {
                        val entry = files[cursor]
                        val bytes = if (jar && entry.path.value == "META-INF/MANIFEST.MF") jarManifest
                            else content.get(entry.digest) ?: error("Library input unavailable: ${entry.path.value}")
                        require(Sha256.digest(bytes) == entry.digest) { "Library input digest mismatch: ${entry.path.value}" }
                        checkpoint()
                        if (jar) {
                            val writer = zip ?: StoredZip(files).also { zip = it }
                            writer.append(bytes, ::checkpoint)
                        } else artifact = entry.digest
                        cursor++
                        return null
                    }
                    when (publication++) {
                        0 -> {
                            val name = "${selection.basename()}.${selection.extension()}"
                            if (jar) artifact = publish(name, checkNotNull(zip).finish(::checkpoint)).also { zip = null }
                            else outputs += FileEntry(LogicalPath("${selection.directory()}/$name"), checkNotNull(artifact))
                        }
                        1 -> publish("${selection.basename()}.pom", selection.pom())
                        2 -> publish("${selection.basename()}.module", selection.moduleMetadata(checkNotNull(artifact)))
                        else -> return finish(ActionStatus.EXECUTED, 0)
                    }
                    checkpoint()
                    return null
                } catch (stopped: PackagingStopped) {
                    return finish(if (stopped.cancelled) ActionStatus.CANCELLED else ActionStatus.FAILED,
                        if (stopped.cancelled) 130 else 124, if (stopped.cancelled) null else "Library packaging timed out")
                } catch (failure: Exception) {
                    return finish(ActionStatus.FAILED, 1, failure.message ?: "Library packaging failed")
                }
            }
            override fun cancel() { if (!closed && result == null) cancelled = true }
            override fun close() { closed = true; zip = null; outputs.clear() }
        }
    }
}

private class PackagingStopped(val cancelled: Boolean) : Exception()
private fun ActionResult.detached() = copy(outputs = TreeManifest(outputs.files.toList()))
