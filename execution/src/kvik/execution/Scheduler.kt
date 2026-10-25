package kvik.execution

import kvik.core.*
import kotlin.time.TimeSource

/** append must durably persist before returning; listeners may observe only persisted events. */
fun interface EventJournal { fun append(event: RunEvent) }

/** Single-thread-confined, cooperative run. Call tick regularly; no background scheduler exists.
 * Emits action events only. The run coordinator owns authoritative run boundaries and verification,
 * and assigns run-wide sequence numbers when combining this action stream with other event sources.
 */
class SchedulerRun(
    val runId: String,
    nodes: List<ActionNode>,
    private val executor: ActionExecutor,
    private val budget: ResourceBudget,
    private val journal: EventJournal,
    private val hasher: Hasher = Sha256,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AutoCloseable {
    private val timeOrigin = timeSource.markNow()
    private val nodes = nodes.sortedBy { it.id }.associate { it.id to it.copy(dependencies = it.dependencies.toSet()) }
    private val completed = mutableMapOf<String, ActionResult>()
    private data class Resolved(val spec: ActionSpec, val key: Digest, val resources: ResourceRequest)
    private data class Active(val resolved: Resolved, val handle: RunningAction, val startedAfterNanos: Long)
    private val resolved = mutableMapOf<String, Resolved>()
    private val active = mutableMapOf<String, Active>()
    private val owners = mutableMapOf<Digest, String>()
    private val followers = mutableMapOf<String, String>()
    private var sequence = 0L
    private var cancelled = false
    private var finished = false
    val isFinished: Boolean get() = finished
    val results: Map<String, ActionResult> get() = completed.keys.sorted().associateWith { completed.getValue(it) }

    init {
        require(runId.isNotBlank())
        require(this.nodes.size == nodes.size) { "Duplicate action IDs" }
        require(nodes.all { it.id.isNotBlank() })
        validateGraph(this.nodes.mapValues { it.value.dependencies })
    }

    private fun emit(type: String, payload: JsonValue.Object) {
        journal.append(RunEvent(runId, ++sequence, type, payload))
    }

    private fun complete(result: ActionResult, timing: ActionTiming? = null) {
        val snapshot = result.copy(outputs = TreeManifest(result.outputs.files.toList()))
        completed[result.actionId] = snapshot
        if (timing != null) emit("action-timing", timing.toJson())
        emit("action-finished", snapshot.toJson() as JsonValue.Object)
    }

    /** Stable ID order breaks ties. Wall-clock completion across ticks is deliberately not replayed. */
    fun tick(): Boolean {
        if (finished) return true
        try {
            for (id in active.keys.sorted()) {
                val running = active.getValue(id)
                val result = try { running.handle.poll() } catch (failure: Exception) {
                    ActionResult(id, running.resolved.key, ActionStatus.FAILED, diagnostic = "Executor poll: ${failure.message}")
                } ?: continue
                running.handle.close()
                active.remove(id)
                val checked = if (result.actionId != id || result.key != running.resolved.key) {
                    ActionResult(id, running.resolved.key, ActionStatus.FAILED, diagnostic = "Executor returned a mismatched action identity")
                } else if (cancelled) result.copy(status = ActionStatus.CANCELLED) else result
                complete(checked, ActionTiming(id, running.startedAfterNanos,
                    timeOrigin.elapsedNow().inWholeNanoseconds - running.startedAfterNanos))
            }
            for ((id, owner) in followers.entries.sortedBy { it.key }) {
                val result = completed[owner] ?: continue
                complete(result.copy(actionId = id, diagnostic = "Shared within run with $owner${result.diagnostic?.let { ": $it" } ?: ""}"))
                followers.remove(id)
            }
            for ((id, node) in nodes) {
                if (id in completed || id in active || id in followers) continue
                if (cancelled) {
                    complete(ActionResult(id, resolved[id]?.key, ActionStatus.CANCELLED))
                    continue
                }
                if (node.dependencies.any { completed[it]?.successful == false }) {
                    complete(ActionResult(id, null, ActionStatus.BLOCKED, diagnostic = "Prerequisite failed"))
                    continue
                }
                if (!node.dependencies.all { completed[it]?.successful == true }) continue
                val action = try {
                    resolved.getOrPut(id) {
                        val spec = node.resolve(node.dependencies.sorted().associateWith { completed.getValue(it) }).snapshot()
                        val request = executor.resources(spec)
                        Resolved(spec, spec.key(hasher), request.copy(exclusive = request.exclusive.toSet()))
                    }
                } catch (failure: Exception) {
                    complete(ActionResult(id, null, ActionStatus.FAILED, diagnostic = "Action resolution: ${failure.message}"))
                    continue
                }
                if (action.spec.cachePolicy != CachePolicy.NEVER) {
                    owners[action.key]?.let { owner ->
                        val result = completed[owner]
                        if (result != null) complete(result.copy(actionId = id, diagnostic = "Shared within run with $owner"))
                        else followers[id] = owner
                    }
                    if (id in completed || id in followers) continue
                }
                if (action.resources.memoryMiB > budget.memoryMiB) {
                    complete(ActionResult(id, action.key, ActionStatus.FAILED, diagnostic = "Memory request exceeds run budget"))
                    continue
                }
                if (action.resources.cpuSlots > budget.maxParallel) {
                    complete(ActionResult(id, action.key, ActionStatus.FAILED, diagnostic = "CPU slot request exceeds run jobs budget"))
                    continue
                }
                val memory = active.values.sumOf { it.resolved.resources.memoryMiB }
                val cpuSlots = active.values.sumOf { it.resolved.resources.cpuSlots }
                val exclusive = active.values.flatMap { it.resolved.resources.exclusive }.toSet()
                if (active.size >= budget.maxParallel || action.resources.memoryMiB > budget.memoryMiB - memory ||
                    action.resources.cpuSlots > budget.maxParallel - cpuSlots ||
                    action.resources.exclusive.any { it in exclusive }) continue
                try {
                    val startedAfterNanos = timeOrigin.elapsedNow().inWholeNanoseconds
                    val handle = executor.start(runId, id, action.spec)
                    active[id] = Active(action, handle, startedAfterNanos)
                    if (action.spec.cachePolicy != CachePolicy.NEVER) owners[action.key] = id
                    emit("action-started", obj("actionId" to id.json(), "key" to action.key.toJson()))
                } catch (failure: Exception) {
                    if (id in active) throw failure // Journal failure must stop every owned action.
                    complete(ActionResult(id, action.key, ActionStatus.FAILED, diagnostic = "Executor start: ${failure.message}"))
                }
            }
            if (completed.size == nodes.size) {
                finished = true
            }
            return finished
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    fun cancel() {
        if (finished || cancelled) return
        cancelled = true
        var failure: Exception? = null
        for (running in active.values) {
            try { running.handle.cancel() } catch (error: Exception) { failure = error }
        }
        failure?.let { close(); throw it }
    }

    /** Cleanup remains mandatory if the event journal or an adapter throws. */
    override fun close() {
        if (finished) return
        cancelled = true
        var failure: Exception? = null
        for (running in active.values) {
            try { running.handle.cancel() } catch (error: Exception) { failure = error }
        }
        for (running in active.values) {
            try { running.handle.close() } catch (error: Exception) { failure = error }
        }
        active.clear()
        finished = true
        failure?.let { throw it }
    }
}

private fun ActionSpec.snapshot(): ActionSpec = copy(
    platform = platform.toMap(), toolchains = toolchains.toMap(), inputs = TreeManifest(inputs.files.toList()),
    outputs = outputs.toList(), arguments = arguments.toList(), environment = environment.toMap(),
    parameters = parseJson(parameters.canonical()) as JsonValue.Object,
)
