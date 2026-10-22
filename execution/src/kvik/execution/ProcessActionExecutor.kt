package kvik.execution

import kvik.core.*

/** Adapter boundary: prepare creates isolated paths; collect validates outputs after successful exit.
 * One instance belongs to one run. Cache lookup/publication is a separate executor decorator.
 */
class ProcessActionExecutor(
    private val ownerRunId: String,
    private val host: ProcessHost,
    private val prepare: (String, ActionSpec) -> ProcessRequest,
    private val collect: (String, ActionSpec) -> TreeManifest,
    private val resourceRequest: (ActionSpec) -> ResourceRequest = { ResourceRequest() },
) : ActionExecutor {
    override fun resources(action: ActionSpec): ResourceRequest = resourceRequest(action)
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        require(runId == ownerRunId) { "Executor belongs to a different run" }
        val key = action.key(Sha256)
        val process = host.start(prepare(actionId, action).copy(timeoutMillis = action.timeoutMillis))
        return object : RunningAction {
            private var result: ActionResult? = null
            override fun poll(): ActionResult? {
                result?.let { return it }
                val observed = process.poll() ?: return null
                val status = when {
                    observed.cancelled -> ActionStatus.CANCELLED
                    observed.timedOut || observed.exitCode != 0 -> ActionStatus.FAILED
                    else -> ActionStatus.EXECUTED
                }
                return ActionResult(actionId, key, status,
                    outputs = if (status == ActionStatus.EXECUTED) collect(actionId, action) else TreeManifest(emptyList()),
                    exitCode = observed.exitCode, diagnostic = if (observed.timedOut) "Process timeout" else null).also { result = it }
            }
            override fun cancel() = process.cancel()
            override fun close() = process.close()
        }
    }
}
