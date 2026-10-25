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
    val produced = mutableMapOf<String, ByteArray>()
    val delegate = object : ActionExecutor {
        override fun resources(action: ActionSpec) = ResourceRequest(memoryMiB)
        override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
            checkpoint()
            val outputs = transform(actionId, action)
            require(outputs.values.sumOf { it.size.toLong() } <= 256L * 1024 * 1024) { "Report outputs exceed 256 MiB" }
            produced.putAll(outputs)
            val result = ActionResult(actionId, action.key(Sha256), ActionStatus.EXECUTED,
                TreeManifest(outputs.map { (name, bytes) -> FileEntry(LogicalPath(name), Sha256.digest(bytes)) }), 0)
            return object : RunningAction { override fun poll() = result; override fun cancel() = Unit; override fun close() = Unit }
        }
    }
    return cacheReportActions(store, delegate, produced::getValue, retain, checkpoint, event)
}

/** Cache both synchronous transformations and owned asynchronous report workers. */
internal fun cacheReportActions(
    store: String,
    delegate: ActionExecutor,
    outputBytes: (String) -> ByteArray,
    retain: (String, ByteArray) -> Unit,
    checkpoint: () -> Unit,
    event: (String, JsonValue.Object) -> Unit,
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
    val publishing = object : ActionExecutor {
        override fun resources(action: ActionSpec) = delegate.resources(action)
        override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
            val running = delegate.start(runId, actionId, action)
            return object : RunningAction {
                private var stored = false
                override fun poll(): ActionResult? {
                    val result = running.poll() ?: return null
                    if (result.successful && !stored) {
                        require(result.outputs.files.sumOf { it.digest.sizeBytes } <= 256L * 1024 * 1024) { "Report outputs exceed 256 MiB" }
                        for (entry in result.outputs.files) {
                            checkpoint()
                            val bytes = outputBytes(entry.path.value)
                            require(Sha256.digest(bytes) == entry.digest) { "Report output digest differs: ${entry.path.value}" }
                            content.put(bytes)
                        }
                        stored = true
                    }
                    return result
                }
                override fun cancel() = running.cancel()
                override fun close() = running.close()
            }
        }
    }
    val cached = LocalCachingActionExecutor(publishing, cache, content, ::diagnostic,
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
