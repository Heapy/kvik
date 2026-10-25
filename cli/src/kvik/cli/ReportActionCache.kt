package kvik.cli

import kvik.actioncache.LocalCachingActionExecutor
import kvik.cache.*
import kvik.core.*

/** Report inputs and destinations stay outside the mutable content store. */
internal fun reportStorePath(argument: String?, sources: Collection<String>, output: String): String {
    val store = projectPath(currentDirectory(), argument ?: ".kvik/cache")
    require((sources + output).none { pathsPhysicallyOverlap(it, store) }) {
        "Report cache must be outside source and output directories; select another --store"
    }
    return store
}

/** Materialize cached bytes before the scheduler resolves downstream report actions. */
internal fun cachedReportExecutor(
    store: String,
    memoryMiB: Long,
    retain: (String, ByteArray) -> Unit,
    checkpoint: () -> Unit,
    event: (String, JsonValue.Object) -> Unit,
    transform: (String, ActionSpec) -> Map<String, ByteArray>,
): ActionExecutor {
    fun diagnostic(value: CacheDiagnostic) = event("diagnostic", Diagnostic("KVIK_CACHE_NOTICE", DiagnosticCategory.CACHE,
        "${value.code}: ${value.message}").toJson() as JsonValue.Object)
    val disk = try { NativeContentStore(store, diagnostic = ::diagnostic) } catch (failure: Exception) {
        diagnostic(CacheDiagnostic("report-cache-unavailable", failure.message ?: "Cannot open report cache"))
        null
    }
    val generated = mutableMapOf<Digest, ByteArray>()
    val content = object : ContentStore {
        override fun put(bytes: ByteArray): Digest {
            require(bytes.size <= 64 * 1024 * 1024) { "Report artifact exceeds 64 MiB" }
            val digest = Sha256.digest(bytes)
            generated[digest] = bytes.copyOf()
            try { disk?.put(bytes) } catch (failure: Exception) {
                diagnostic(CacheDiagnostic("report-cache-write-failed", failure.message ?: "Cannot cache report output"))
            }
            return digest
        }
        override fun get(digest: Digest): ByteArray? = generated[digest]?.copyOf() ?: disk?.get(digest)
    }
    val diskCache = try { disk?.let { NativeActionCache("$store/actions", it, ::diagnostic) } } catch (failure: Exception) {
        diagnostic(CacheDiagnostic("report-cache-unavailable", failure.message ?: "Cannot open report action cache"))
        null
    }
    val cache = diskCache ?: object : ActionCache {
        override fun lookup(key: Digest): CachedAction? = null
        override fun publish(action: CachedAction) = Unit
    }
    val delegate = object : ActionExecutor {
        override fun resources(action: ActionSpec) = ResourceRequest(memoryMiB)
        override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
            checkpoint()
            val outputs = transform(actionId, action)
            require(outputs.values.sumOf { it.size.toLong() } <= 256L * 1024 * 1024) { "Report outputs exceed 256 MiB" }
            val files = outputs.map { (name, bytes) ->
                checkpoint()
                FileEntry(LogicalPath(name), content.put(bytes))
            }
            val result = ActionResult(actionId, action.key(Sha256), ActionStatus.EXECUTED, TreeManifest(files), 0)
            return object : RunningAction { override fun poll() = result; override fun cancel() = Unit; override fun close() = Unit }
        }
    }
    val cached = LocalCachingActionExecutor(delegate, cache, content, ::diagnostic,
        cacheDecision = { event("action-cache-decision", it.toJson()) })
    return object : ActionExecutor {
        override fun resources(action: ActionSpec) = cached.resources(action)
        override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
            checkpoint()
            val running = cached.start(runId, actionId, action)
            return object : RunningAction {
                private var restored = false
                override fun poll(): ActionResult? {
                    checkpoint()
                    val result = running.poll() ?: return null
                    if (result.successful && !restored) {
                        require(result.outputs.files.sumOf { it.digest.sizeBytes } <= 256L * 1024 * 1024) { "Report outputs exceed 256 MiB" }
                        result.outputs.files.forEach { entry ->
                            checkpoint()
                            require(!entry.executable) { "Report output must not be executable" }
                            retain(entry.path.value, content.get(entry.digest) ?: error("Report cache output is unavailable: ${entry.path.value}"))
                        }
                        restored = true
                    }
                    return result
                }
                override fun cancel() = running.cancel()
                override fun close() = running.close()
            }
        }
    }
}

internal fun retainedReportJson(artifacts: Map<String, ByteArray>, name: String): JsonValue.Object =
    parseJson(artifacts.getValue(name).decodeToString(throwOnInvalidSequence = true)) as JsonValue.Object

internal fun reportExitStatus(result: JsonValue.Object): ExitStatus = when (result.values.getValue("status").stringValue()) {
    "passed" -> ExitStatus.SUCCESS
    "failed" -> ExitStatus.FAILED
    "incomplete" -> ExitStatus.INCOMPLETE
    "cancelled" -> ExitStatus.CANCELLED
    else -> error("Unsupported report status")
}
