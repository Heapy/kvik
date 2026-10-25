package kvik.projectgraph

import kvik.core.*
import kotlin.time.TimeSource

/** Invocation-owned services. Workspace is private to this plugin and retained with the run. */
data class ProjectExecutionContext(
    val runId: String,
    val workspace: String,
    val composition: Digest,
    val content: ContentStore,
    val processes: ProcessHost,
)

/** Registers native source implementations; registration must not execute work or launch processes. */
interface ProjectExecutorContributor : BuildPlugin {
    /** Keys are <plugin-id>:<implementation-version>, e.g. example.assets:manifest-v1. */
    fun executors(context: ProjectExecutionContext): Map<String, ActionExecutor>
}

/** Registration and validation happen before execution, including before a possible cache hit. */
fun pluginActionExecutors(
    plugins: List<BuildPlugin>,
    context: (String) -> ProjectExecutionContext,
    timeSource: TimeSource = TimeSource.Monotonic,
): Map<String, ActionExecutor> {
    require(plugins.map { it.id }.distinct().size == plugins.size) { "Duplicate build plugin ID" }
    require(plugins.all { it.apiVersion == CONTRACT_VERSION }) { "Incompatible build plugin API version" }
    return buildMap {
        for (plugin in plugins.filterIsInstance<ProjectExecutorContributor>().sortedBy { it.id }) {
            require(plugin.id.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}"))) { "Invalid executor plugin ID: ${plugin.id}" }
            val services = context(plugin.id)
            val supplied = plugin.executors(services).toMap()
            require(supplied.isNotEmpty() && supplied.size <= 128) { "Executor plugin must register 1..128 implementations: ${plugin.id}" }
            for ((implementation, executor) in supplied) {
                require(implementation.startsWith("${plugin.id}:") &&
                    implementation.removePrefix("${plugin.id}:").matches(Regex("[a-z][a-z0-9-]{0,63}"))) {
                    "Executor implementation must use its plugin namespace and a versioned name: $implementation"
                }
                require(implementation !in this) { "Duplicate action executor: $implementation" }
                put(implementation, guardedPluginExecutor(implementation, executor, services, timeSource))
            }
        }
    }
}

private fun guardedPluginExecutor(
    implementation: String, delegate: ActionExecutor, context: ProjectExecutionContext, timeSource: TimeSource,
): ActionExecutor = object : ActionExecutor {
    private fun validate(action: ActionSpec) {
        require(action.implementation == implementation && action.composition == context.composition) {
            "Plugin executor action belongs to a different implementation or composition"
        }
        require(action.kind in setOf(ActionKind.GENERATE, ActionKind.COMPILE, ActionKind.LINK, ActionKind.PACKAGE, ActionKind.VERIFY)) {
            "Custom plugin executors currently support artifact and verification actions only"
        }
        require(action.cachePolicy != CachePolicy.REMOTE) { "Custom plugin executors cannot use remote caching" }
    }
    override fun resources(action: ActionSpec): ResourceRequest {
        validate(action)
        return delegate.resources(action)
    }
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        validate(action)
        require(runId == context.runId) { "Plugin executor belongs to a different run" }
        val key = action.key(Sha256)
        val started = timeSource.markNow()
        val running = delegate.start(runId, actionId, action)
        return object : RunningAction {
            private var result: ActionResult? = null
            private var closed = false
            private fun stop(status: ActionStatus, code: Int, message: String): ActionResult {
                try { running.cancel() } finally { close() }
                return ActionResult(actionId, key, status, exitCode = code, diagnostic = message).also { result = it }
            }
            private fun timedOut() = started.elapsedNow().inWholeMilliseconds >= action.timeoutMillis
            override fun poll(): ActionResult? {
                result?.let { return it }
                check(!closed) { "Plugin action is closed" }
                if (timedOut()) return stop(ActionStatus.FAILED, 124, "Plugin action timeout")
                val observed = running.poll() ?: return null
                if (timedOut()) return stop(ActionStatus.FAILED, 124, "Plugin action timeout")
                // Reap action-owned work before results can be published to the local cache.
                close()
                val checked = if (observed.actionId != actionId || observed.key != key ||
                    observed.status !in setOf(ActionStatus.EXECUTED, ActionStatus.FAILED, ActionStatus.CANCELLED)) {
                    ActionResult(actionId, key, ActionStatus.FAILED, exitCode = 1, diagnostic = "Plugin executor returned an invalid action result")
                } else observed.copy(outputs = TreeManifest(observed.outputs.files.toList()), processUsage = observed.processUsage.toList())
                result = checked
                return checked
            }
            override fun cancel() {
                if (result == null && !closed) stop(ActionStatus.CANCELLED, 130, "Plugin action cancelled")
            }
            override fun close() {
                if (!closed) { closed = true; running.close() }
            }
        }
    }
}
