package kvik.application

import kvik.core.*
import kotlin.time.TimeSource

/**
 * Thread-confined pure CAS packaging. Each poll verifies at most one input blob;
 * the final poll writes and verifies bounded launch metadata. No host paths or
 * process APIs are used. ContentStore bounds whole-buffer reads and owns durability.
 */
class ApplicationBundleExecutor(private val content: ContentStore) : ActionExecutor {
    override fun resources(action: ActionSpec): ResourceRequest {
        val largest = action.inputs.files.maxOfOrNull { it.digest.sizeBytes } ?: 0L
        // Reserve two whole-blob buffers plus room for metadata/manifests.
        return ResourceRequest(memoryMiB = 128 + 2 * (largest / (1024 * 1024)))
    }

    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        val parameters = parseJson(action.parameters.canonical())
        val launch = ApplicationLaunch.fromJson(parameters)
        val snapshot = applicationBundleAction(launch, action.inputs, action.composition, action.toolchains)
        require(snapshot == action) { "Application bundle action does not match its typed contract" }
        val key = snapshot.key(Sha256)
        val metadata = launch.toJson().canonical().encodeToByteArray()
        val expectedMetadata = Sha256.digest(metadata)
        val inputs = snapshot.inputs.files.sortedBy { it.path.value }
        val started = TimeSource.Monotonic.markNow()
        return object : RunningAction {
            private var cursor = 0
            private var cancelled = false
            private var closed = false
            private var result: ActionResult? = null

            private fun finish(status: ActionStatus, code: Int, diagnostic: String? = null, files: List<FileEntry> = emptyList()): ActionResult {
                val completed = ActionResult(actionId, key, status, TreeManifest(files), code, diagnostic)
                result = completed
                return completed.detached()
            }

            override fun poll(): ActionResult? {
                check(!closed) { "Application bundle action is closed" }
                result?.let { return it.detached() }
                if (cancelled) return finish(ActionStatus.CANCELLED, 130)
                if (started.elapsedNow().inWholeMilliseconds >= snapshot.timeoutMillis) {
                    return finish(ActionStatus.FAILED, 124, "Application bundle preparation timed out")
                }
                try {
                    if (cursor < inputs.size) {
                        val entry = inputs[cursor]
                        val bytes = content.get(entry.digest) ?: error("Application input unavailable: ${entry.path.value}")
                        require(Sha256.digest(bytes) == entry.digest) { "Application input SHA-256/size mismatch: ${entry.path.value}" }
                        cursor++
                        return null
                    }
                    require(content.put(metadata) == expectedMetadata) { "Application metadata publication returned an incorrect digest" }
                    val stored = content.get(expectedMetadata) ?: error("Application metadata unavailable after publication")
                    require(Sha256.digest(stored) == expectedMetadata) { "Application metadata SHA-256/size mismatch" }
                    if (started.elapsedNow().inWholeMilliseconds >= snapshot.timeoutMillis) {
                        return finish(ActionStatus.FAILED, 124, "Application bundle preparation timed out")
                    }
                    val files = listOf(FileEntry(LogicalPath("application/launch.json"), expectedMetadata)) + inputs.map {
                        it.copy(path = LogicalPath("application/input/${it.path.value}"))
                    }
                    return finish(ActionStatus.EXECUTED, 0, files = files.sortedBy { it.path.value })
                } catch (failure: Exception) {
                    return finish(ActionStatus.FAILED, 1, failure.message ?: "Application bundle preparation failed")
                }
            }

            override fun cancel() { if (!closed && result == null) cancelled = true }
            override fun close() { closed = true }
        }
    }
}

private fun ActionResult.detached(): ActionResult = copy(outputs = TreeManifest(outputs.files.toList()))
