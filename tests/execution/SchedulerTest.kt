package kvik.execution.tests

import kvik.core.*
import kvik.execution.*
import kotlin.test.*

private fun spec(name: String = "one", effect: Boolean = false): ActionSpec {
    val digest = Sha256.digest(byteArrayOf())
    return ActionSpec(name, digest, if (effect) ActionKind.TEST else ActionKind.COMPILE,
        mapOf("os" to "macos"), "jvm", mapOf("compiler" to digest), TreeManifest(emptyList()), emptyList(), emptyList(), emptyMap(),
        cachePolicy = if (effect) CachePolicy.NEVER else CachePolicy.LOCAL)
}

private class ManualExecutor : ActionExecutor {
    val started = mutableListOf<String>()
    val running = mutableMapOf<String, Handle>()
    var request: (ActionSpec) -> ResourceRequest = { ResourceRequest(1) }
    override fun resources(action: ActionSpec) = request(action)
    inner class Handle(val id: String, val action: ActionSpec) : RunningAction {
        var status: ActionStatus? = null
        var closed = false
        override fun poll() = status?.let { ActionResult(id, action.key(Sha256), it, exitCode = if (it == ActionStatus.EXECUTED) 0 else 1) }
        override fun cancel() { status = ActionStatus.CANCELLED }
        override fun close() { closed = true }
    }
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        started += actionId
        return Handle(actionId, action).also { running[actionId] = it }
    }
    fun finish(id: String, status: ActionStatus = ActionStatus.EXECUTED) { running.getValue(id).status = status }
}

class SchedulerTest {
    @Test fun dependenciesDedupAndStableEvents() {
        val executor = ManualExecutor()
        val events = mutableListOf<RunEvent>()
        val nodes = listOf(ActionNode("b", emptySet()) { spec() }, ActionNode("a", emptySet()) { spec() },
            ActionNode("c", setOf("b")) { predecessors -> assertEquals(setOf("b"), predecessors.keys); spec("child") })
        val run = SchedulerRun("run", nodes, executor, ResourceBudget(4, 4), EventJournal { events += it })
        run.tick(); assertEquals(listOf("a"), executor.started)
        executor.finish("a"); run.tick(); assertEquals(listOf("a", "c"), executor.started)
        executor.finish("c"); assertTrue(run.tick())
        assertEquals(setOf("a", "b", "c"), run.results.keys)
        assertTrue(run.results.values.all { it.successful })
        assertEquals((1L..events.size.toLong()).toList(), events.map { it.sequence })
        assertEquals(listOf("a", "c"), events.filter { it.type == "action-started" }.map { it.payload.objectValue().getValue("actionId").stringValue() })
        assertTrue(events.all { it.type in setOf("action-started", "action-finished") })
        val finished = events.filter { it.type == "action-finished" }.associateBy { it.payload.objectValue().getValue("actionId").stringValue() }
        assertEquals(run.results.keys, finished.keys)
        for (event in events.filter { it.type == "action-started" }) {
            val id = event.payload.objectValue().getValue("actionId").stringValue()
            assertTrue(event.sequence < finished.getValue(id).sequence)
        }
        val childStarted = events.single { it.type == "action-started" && it.payload.objectValue().getValue("actionId").stringValue() == "c" }
        assertTrue(finished.getValue("b").sequence < childStarted.sequence)
    }

    @Test fun effectsAndSeparateRunsNeverDeduplicate() {
        val executor = ManualExecutor()
        val nodes = listOf("a", "b").map { ActionNode(it, emptySet()) { spec(effect = true) } }
        repeat(2) { index ->
            SchedulerRun("run$index", nodes, executor, ResourceBudget(2, 2), EventJournal {}).use { run ->
                run.tick(); executor.finish("a"); executor.finish("b"); assertTrue(run.tick())
            }
        }
        assertEquals(listOf("a", "b", "a", "b"), executor.started)
    }

    @Test fun memoryParallelAndExclusiveBudgets() {
        val executor = ManualExecutor()
        executor.request = { ResourceRequest(2, if (it.implementation in setOf("a", "b")) setOf("database") else emptySet()) }
        val nodes = listOf("a", "b", "c", "d").map { id -> ActionNode(id, emptySet()) { spec(id) } }
        val run = SchedulerRun("run", nodes, executor, ResourceBudget(3, 4), EventJournal {})
        run.tick(); assertEquals(listOf("a", "c"), executor.started)
        executor.finish("a"); run.tick(); assertEquals(listOf("a", "c", "b"), executor.started)
        executor.finish("c"); run.tick(); assertEquals(listOf("a", "c", "b", "d"), executor.started)
        run.close(); assertTrue(executor.running.values.all { it.closed })
    }

    @Test fun failureBlocksDependentsButRetainsIndependentFailures() {
        val executor = ManualExecutor()
        val nodes = listOf(ActionNode("a", emptySet()) { spec("a") }, ActionNode("b", setOf("a")) { error("must not resolve") },
            ActionNode("c", emptySet()) { spec("c") })
        val run = SchedulerRun("run", nodes, executor, ResourceBudget(2, 2), EventJournal {})
        run.tick(); executor.finish("a", ActionStatus.FAILED); executor.finish("c", ActionStatus.FAILED)
        assertTrue(run.tick()); assertEquals(ActionStatus.BLOCKED, run.results.getValue("b").status)
        assertEquals(2, run.results.values.count { it.status == ActionStatus.FAILED })
    }

    @Test fun cancellationClosesActiveAndCancelsPending() {
        val executor = ManualExecutor()
        val nodes = listOf("a", "b").map { id -> ActionNode(id, emptySet()) { spec(id) } }
        val run = SchedulerRun("run", nodes, executor, ResourceBudget(1, 1), EventJournal {})
        run.tick(); run.cancel(); assertTrue(run.tick())
        assertTrue(run.results.values.all { it.status == ActionStatus.CANCELLED })
        assertTrue(executor.running.getValue("a").closed)
    }

    @Test fun invalidGraphStartsNothingAndOversizedRequestFails() {
        val executor = ManualExecutor()
        assertFails { SchedulerRun("run", listOf(ActionNode("a", setOf("a")) { spec() }), executor, ResourceBudget(1, 1), EventJournal {}) }
        assertTrue(executor.started.isEmpty())
        executor.request = { ResourceRequest(2) }
        val run = SchedulerRun("run", listOf(ActionNode("a", emptySet()) { spec() }), executor, ResourceBudget(1, 1), EventJournal {})
        assertTrue(run.tick()); assertEquals(ActionStatus.FAILED, run.results.getValue("a").status)
        assertTrue(executor.started.isEmpty())
    }

    @Test fun journalFailureReapsStartedWork() {
        val executor = ManualExecutor()
        val run = SchedulerRun("run", listOf(ActionNode("a", emptySet()) { spec() }), executor, ResourceBudget(1, 1), EventJournal {
            if (it.type == "action-started") error("disk full")
        })
        assertFails { run.tick() }
        assertTrue(executor.running.getValue("a").closed)
    }

    @Test fun completionJournalFailureStopsOtherWorkAndPreventsDependentLaunch() {
        val executor = ManualExecutor()
        val nodes = listOf(ActionNode("a", emptySet()) { spec("a") },
            ActionNode("b", setOf("a")) { spec("b") }, ActionNode("c", emptySet()) { spec("c") })
        val run = SchedulerRun("run", nodes, executor, ResourceBudget(2, 2), EventJournal {
            if (it.type == "action-finished") error("disk full")
        })
        run.tick(); executor.finish("a")
        assertFails { run.tick() }
        assertFalse("b" in executor.started)
        assertTrue(executor.running.values.all { it.closed })
        assertEquals(ActionStatus.CANCELLED, executor.running.getValue("c").status)
    }
}
