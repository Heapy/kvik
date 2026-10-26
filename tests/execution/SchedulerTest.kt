package kvik.execution.tests

import kvik.core.*
import kvik.execution.*
import kotlin.test.*
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.nanoseconds

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
        var failPoll = false
        var failCancel = false
        var failClose = false
        var usage = emptyList<ProcessUsage>()
        override fun poll(): ActionResult? {
            check(!failPoll) { "poll $id" }
            return status?.let { ActionResult(id, action.key(Sha256), it,
                exitCode = if (it in setOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)) 0 else 1,
                processUsage = usage) }
        }
        override fun cancel() { status = ActionStatus.CANCELLED; check(!failCancel) { "cancel $id" } }
        override fun close() { closed = true; check(!failClose) { "close $id" } }
    }
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        started += actionId
        return Handle(actionId, action).also { running[actionId] = it }
    }
    fun finish(id: String, status: ActionStatus = ActionStatus.EXECUTED) { running.getValue(id).status = status }
}

class SchedulerTest {
    @Test fun admissionLeaseSpansPendingWorkAndCacheHitsWithoutCreditingFollowers() {
        val executor = ManualExecutor()
        val reserved = mutableListOf<Pair<String, Long>>()
        val held = mutableSetOf<String>()
        val released = mutableListOf<String>()
        val admission = ActionAdmission { id, _, spare ->
            reserved += id to spare
            assertTrue(held.add(id))
            AutoCloseable {
                assertTrue(executor.running.getValue(id).closed, "Handle must close before its lease")
                assertTrue(held.remove(id), "Lease closed more than once")
                released += id
            }
        }
        val nodes = listOf(ActionNode("a", emptySet()) { spec() }, ActionNode("b-follower", emptySet()) { spec() },
            ActionNode("c", emptySet()) { spec("independent") }, ActionNode("d", setOf("a")) { spec("dependent") })
        SchedulerRun("leases", nodes, executor, ResourceBudget(2, 3), EventJournal {}, admission = admission).use { run ->
            assertFalse(run.tick())
            assertEquals(listOf("a" to 2L, "c" to 1L), reserved)
            assertEquals(setOf("a", "c"), held)
            run.tick() // Both handles may be waiting for asynchronous preparation or remote lookup.
            assertEquals(setOf("a", "c"), held)
            assertTrue(released.isEmpty())
            executor.finish("a", ActionStatus.REMOTE_HIT)
            run.tick()
            assertEquals(ActionStatus.REMOTE_HIT, run.results.getValue("a").status)
            assertEquals(ActionStatus.REMOTE_HIT, run.results.getValue("b-follower").status)
            assertEquals(listOf("a"), released)
            assertEquals(setOf("c", "d"), held)
            assertEquals(listOf("a" to 2L, "c" to 1L, "d" to 1L), reserved)
            run.cancel()
            assertEquals(setOf("c", "d"), held, "Cancellation must retain leases until handles drain")
            assertTrue(run.tick())
            assertTrue(held.isEmpty())
            assertEquals(listOf("a", "c", "d"), released)
        }
    }

    @Test fun executorStartFailureReleasesAdmissionBeforeIndependentWork() {
        val events = mutableListOf<String>()
        val delegate = ManualExecutor()
        val executor = object : ActionExecutor {
            override fun resources(action: ActionSpec) = ResourceRequest(1)
            override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
                events += "start $actionId"
                check(actionId != "a") { "Cannot launch" }
                return delegate.start(runId, actionId, action)
            }
        }
        val admission = ActionAdmission { id, _, spare ->
            assertEquals(1L, spare)
            events += "reserve $id"
            AutoCloseable { events += "release $id" }
        }
        val nodes = listOf("a", "b").map { id -> ActionNode(id, emptySet()) { spec(id) } }
        SchedulerRun("start-failure", nodes, executor, ResourceBudget(2, 2), EventJournal {}, admission = admission).use { run ->
            run.tick()
            assertEquals(listOf("reserve a", "start a", "release a", "reserve b", "start b"), events)
            assertEquals(ActionStatus.FAILED, run.results.getValue("a").status)
            delegate.finish("b")
            assertTrue(run.tick())
        }
        assertEquals("release b", events.last())
    }

    @Test fun admissionFailureDrainsActiveWorkAndPreventsFurtherAdmissions() {
        val executor = ManualExecutor()
        val reserved = mutableListOf<String>()
        val released = mutableListOf<String>()
        val admission = ActionAdmission { id, _, _ ->
            reserved += id
            check(id != "b") { "Cannot reserve" }
            AutoCloseable { released += id }
        }
        val nodes = listOf("a", "b", "c").map { id -> ActionNode(id, emptySet()) { spec(id) } }
        SchedulerRun("reserve-failure", nodes, executor, ResourceBudget(3, 3), EventJournal {}, admission = admission).use { run ->
            assertEquals("Cannot reserve", assertFailsWith<IllegalStateException> { run.tick() }.message)
            assertEquals(listOf("a"), executor.started)
            assertEquals(listOf("a", "b"), reserved)
            assertTrue(executor.running.getValue("a").closed)
            assertEquals(ActionStatus.CANCELLED, executor.running.getValue("a").status)
            assertTrue(run.isFinished)
        }
        assertEquals(listOf("a"), released)
    }

    @Test fun failedStartupLeaseCleanupStillDrainsAlreadyActiveWork() {
        val delegate = ManualExecutor()
        val released = mutableListOf<String>()
        val executor = object : ActionExecutor {
            override fun resources(action: ActionSpec) = ResourceRequest(1)
            override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
                check(actionId != "b") { "launch b" }
                return delegate.start(runId, actionId, action)
            }
        }
        val admission = ActionAdmission { id, _, _ -> AutoCloseable {
            released += id
            check(id != "b") { "lease b" }
        } }
        val nodes = listOf("a", "b").map { id -> ActionNode(id, emptySet()) { spec(id) } }
        val run = SchedulerRun("start-cleanup-failure", nodes, executor, ResourceBudget(2, 2),
            EventJournal {}, admission = admission)
        val failure = assertFailsWith<IllegalStateException> { run.tick() }
        assertEquals("launch b", failure.message)
        assertEquals("lease b", failure.suppressedExceptions.single().message)
        assertTrue(delegate.running.getValue("a").closed)
        assertEquals(ActionStatus.CANCELLED, delegate.running.getValue("a").status)
        assertEquals(listOf("b", "a"), released)
        run.close()
        assertEquals(listOf("b", "a"), released)
    }

    @Test fun pollFailureClosesHandleBeforeReleasingAdmission() {
        val executor = ManualExecutor()
        val released = mutableListOf<String>()
        val admission = ActionAdmission { id, _, _ -> AutoCloseable {
            assertTrue(executor.running.getValue(id).closed)
            released += id
        } }
        SchedulerRun("poll-failure", listOf(ActionNode("a", emptySet()) { spec() }), executor,
            ResourceBudget(1, 1), EventJournal {}, admission = admission).use { run ->
            run.tick()
            executor.running.getValue("a").failPoll = true
            assertTrue(run.tick())
            assertEquals(ActionStatus.FAILED, run.results.getValue("a").status)
            assertEquals(listOf("a"), released)
        }
    }

    @Test fun journalFailureDrainsEveryHandleAndLeaseDespiteLeaseCleanupFailure() {
        val executor = ManualExecutor()
        val released = mutableListOf<String>()
        val admission = ActionAdmission { id, _, _ -> AutoCloseable {
            assertTrue(executor.running.getValue(id).closed)
            released += id
            check(id != "a") { "lease a" }
        } }
        val nodes = listOf("a", "b").map { id -> ActionNode(id, emptySet()) { spec(id) } }
        val run = SchedulerRun("journal-failure", nodes, executor, ResourceBudget(2, 2), EventJournal {
            if (it.type == "action-started" && it.payload.values["actionId"] == "b".json()) error("disk full")
        }, admission = admission)
        val failure = assertFailsWith<IllegalStateException> { run.tick() }
        assertEquals("disk full", failure.message)
        assertEquals("lease a", failure.suppressedExceptions.single().message)
        assertTrue(executor.running.values.all { it.closed && it.status == ActionStatus.CANCELLED })
        assertEquals(listOf("a", "b"), released)
        run.close()
        assertEquals(listOf("a", "b"), released)
    }

    @Test fun completionCleanupFailureStillReleasesAllAdmissionsAndStopsOtherWork() {
        val executor = ManualExecutor()
        val released = mutableListOf<String>()
        val admission = ActionAdmission { id, _, _ -> AutoCloseable {
            assertTrue(executor.running.getValue(id).closed)
            released += id
            check(id != "a") { "lease a" }
        } }
        val nodes = listOf("a", "b").map { id -> ActionNode(id, emptySet()) { spec(id) } }
        val run = SchedulerRun("close-failure", nodes, executor, ResourceBudget(2, 2), EventJournal {}, admission = admission)
        run.tick()
        executor.running.getValue("a").failClose = true
        executor.finish("a")
        val failure = assertFailsWith<IllegalStateException> { run.tick() }
        assertEquals("close a", failure.message)
        assertEquals("lease a", failure.suppressedExceptions.single().message)
        assertTrue(executor.running.values.all { it.closed })
        assertEquals(ActionStatus.CANCELLED, executor.running.getValue("b").status)
        assertEquals(listOf("a", "b"), released)
        run.close()
        assertEquals(listOf("a", "b"), released)
    }

    @Test fun cancellationFailureStillClosesEveryHandleAndAdmission() {
        val executor = ManualExecutor()
        val released = mutableListOf<String>()
        val admission = ActionAdmission { id, _, _ -> AutoCloseable {
            assertTrue(executor.running.getValue(id).closed)
            released += id
        } }
        val nodes = listOf("a", "b").map { id -> ActionNode(id, emptySet()) { spec(id) } }
        val run = SchedulerRun("cancel-failure", nodes, executor, ResourceBudget(2, 2), EventJournal {}, admission = admission)
        run.tick()
        executor.running.getValue("a").failCancel = true
        assertFailsWith<IllegalStateException> { run.cancel() }
        assertTrue(executor.running.values.all { it.closed })
        assertEquals(listOf("a", "b"), released)
        assertTrue(run.isFinished)
    }

    @Test fun outputContractDriftFailsBeforeExecutorStartIncludingCopiedNodes() {
        val executor = ManualExecutor()
        val contract = ActionOutputContract(treeOutput("classes", "jvm-classes"))
        // Primary construction and copy can replace a resolver; the scheduler still enforces the contract.
        val node = ActionNode("compile", emptySet(), { spec() }, contract).copy(resolve = { spec() })
        SchedulerRun("contract", listOf(node, ActionNode("consumer", setOf("compile")) { spec("consumer") }),
            executor, ResourceBudget(1, 1), EventJournal {}).use { run ->
            assertTrue(run.tick())
            assertTrue(executor.started.isEmpty())
            assertEquals(ActionStatus.FAILED, run.results.getValue("compile").status)
            assertEquals(ActionStatus.BLOCKED, run.results.getValue("consumer").status)
        }
    }

    @Test fun artifactShapesAreCheckedForExecutionCacheHitsAndDeduplicatedFollowers() {
        for (status in listOf(ActionStatus.EXECUTED, ActionStatus.LOCAL_HIT, ActionStatus.REMOTE_HIT)) {
            val action = spec().copy(outputs = listOf(LogicalPath("output")))
            val starts = mutableListOf<String>()
            val executor = object : ActionExecutor {
                override fun resources(action: ActionSpec) = ResourceRequest(1)
                override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
                    starts += actionId
                    return object : RunningAction {
                        override fun poll() = ActionResult(actionId, action.key(Sha256), status,
                            TreeManifest(listOf(FileEntry(LogicalPath("output"), Sha256.digest(byteArrayOf())))), 0)
                        override fun cancel() {}
                        override fun close() {}
                    }
                }
            }
            val nodes = listOf(
                ActionNode("a-file", emptySet(), ActionOutputContract(fileOutput("output", "klib"))) { action },
                ActionNode("b-tree", emptySet(), ActionOutputContract(treeOutput("output", "jvm-classes"))) { action },
                ActionNode("c-child", setOf("b-tree")) { spec("child") },
            )
            SchedulerRun("shapes", nodes, executor, ResourceBudget(2, 2), EventJournal {}).use { run ->
                run.tick(); assertTrue(run.tick())
                assertEquals(listOf("a-file"), starts)
                assertTrue(run.results.getValue("a-file").successful)
                assertEquals(ActionStatus.FAILED, run.results.getValue("b-tree").status)
                assertTrue(run.results.getValue("b-tree").outputs.files.isEmpty())
                assertEquals(ActionStatus.BLOCKED, run.results.getValue("c-child").status)
            }
        }
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    @Test fun timingUsesRunLocalClockAndDoesNotDuplicateSharedWork() {
        val clock = TestTimeSource()
        val events = mutableListOf<RunEvent>()
        val executor = ManualExecutor()
        val nodes = listOf(ActionNode("a", emptySet()) { spec() }, ActionNode("b", emptySet()) { spec() },
            ActionNode("c", emptySet()) { spec("independent") }, ActionNode("d", setOf("c")) { spec("dependent") })
        SchedulerRun("timing", nodes, executor, ResourceBudget(2, 2), EventJournal { events += it }, timeSource = clock).use { run ->
            clock += 10.nanoseconds
            run.tick()
            clock += 30.nanoseconds
            executor.finish("a", ActionStatus.LOCAL_HIT)
            run.tick()
            clock += 20.nanoseconds
            executor.finish("c", ActionStatus.FAILED)
            assertTrue(run.tick())
            assertEquals(ActionStatus.BLOCKED, run.results.getValue("d").status)
        }
        val timings = events.filter { it.type == "action-timing" }.map { ActionTiming.fromJson(it.payload) }
        assertEquals(listOf(ActionTiming("a", 10, 30), ActionTiming("c", 10, 50)), timings)
        for (timing in timings) {
            val related = events.filter { it.payload.values["actionId"] == timing.actionId.json() }
            assertEquals(listOf("action-started", "action-timing", "action-finished"), related.map { it.type })
        }
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    @Test fun timingIncludesSynchronousStartupAndCleanupOnCancellation() {
        val clock = TestTimeSource()
        val events = mutableListOf<RunEvent>()
        val action = spec()
        val executor = object : ActionExecutor {
            override fun resources(action: ActionSpec) = ResourceRequest(1)
            override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
                clock += 7.nanoseconds
                return object : RunningAction {
                    var cancelled = false
                    override fun poll() = if (cancelled) ActionResult(actionId, action.key(Sha256), ActionStatus.CANCELLED) else null
                    override fun cancel() { cancelled = true }
                    override fun close() { clock += 3.nanoseconds }
                }
            }
        }
        SchedulerRun("timing", listOf(ActionNode("a", emptySet()) { action }, ActionNode("b", emptySet()) { spec("pending") }),
            executor, ResourceBudget(1, 1), EventJournal { events += it }, timeSource = clock).use { run ->
            run.tick()
            clock += 11.nanoseconds
            run.cancel()
            assertTrue(run.tick())
        }
        assertEquals(listOf(ActionTiming("a", 0, 21)), events.filter { it.type == "action-timing" }.map { ActionTiming.fromJson(it.payload) })
    }

    @Test fun dependenciesDedupAndStableEvents() {
        val executor = ManualExecutor()
        val events = mutableListOf<RunEvent>()
        val nodes = listOf(ActionNode("b", emptySet()) { spec() }, ActionNode("a", emptySet()) { spec() },
            ActionNode("c", setOf("b")) { predecessors -> assertEquals(setOf("b"), predecessors.keys); spec("child") })
        val run = SchedulerRun("run", nodes, executor, ResourceBudget(4, 4), EventJournal { events += it })
        run.tick(); assertEquals(listOf("a"), executor.started)
        val usage = listOf(ProcessUsage(100, 200, 300))
        executor.running.getValue("a").usage = usage
        executor.finish("a"); run.tick(); assertEquals(listOf("a", "c"), executor.started)
        executor.finish("c"); assertTrue(run.tick())
        assertEquals(setOf("a", "b", "c"), run.results.keys)
        assertTrue(run.results.values.all { it.successful })
        assertEquals(usage, run.results.getValue("a").processUsage)
        assertTrue(run.results.getValue("b").processUsage.isEmpty())
        assertEquals((1L..events.size.toLong()).toList(), events.map { it.sequence })
        assertEquals(listOf("a", "c"), events.filter { it.type == "action-started" }.map { it.payload.objectValue().getValue("actionId").stringValue() })
        assertTrue(events.all { it.type in setOf("action-started", "action-timing", "action-finished") })
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

    @Test fun cpuReservationsShareCapacityWithCompilersAndReleaseAfterFailure() {
        val executor = ManualExecutor()
        executor.request = { ResourceRequest(1, cpuSlots = if (it.implementation.startsWith("test")) 2 else 1) }
        val nodes = listOf(ActionNode("a", emptySet()) { spec("test-a", effect = true) },
            ActionNode("b", emptySet()) { spec("test-b", effect = true) },
            ActionNode("c", emptySet()) { spec("compiler") })
        SchedulerRun("cpu", nodes, executor, ResourceBudget(3, 10), EventJournal {}).use { run ->
            run.tick()
            assertEquals(listOf("a", "c"), executor.started)
            executor.finish("a", ActionStatus.FAILED)
            run.tick()
            assertEquals(listOf("a", "c", "b"), executor.started)
            executor.finish("b"); executor.finish("c")
            assertTrue(run.tick())
            assertEquals(ActionStatus.FAILED, run.results.getValue("a").status)
            assertTrue(run.results.getValue("b").successful)
            assertTrue(executor.running.values.all { it.closed })
        }
    }

    @Test fun impossibleCpuRequestFailsWithoutBlockingOtherWork() {
        val executor = ManualExecutor()
        executor.request = { ResourceRequest(1, cpuSlots = if (it.implementation == "large") Int.MAX_VALUE else 1) }
        val nodes = listOf(ActionNode("a", emptySet()) { spec("large", effect = true) },
            ActionNode("b", setOf("a")) { error("must not resolve") },
            ActionNode("c", emptySet()) { spec("small") })
        SchedulerRun("cpu", nodes, executor, ResourceBudget(2, 10), EventJournal {}).use { run ->
            run.tick()
            assertEquals(listOf("c"), executor.started)
            assertEquals(ActionStatus.FAILED, run.results.getValue("a").status)
            assertEquals("CPU slot request exceeds run jobs budget", run.results.getValue("a").diagnostic)
            assertEquals(ActionStatus.BLOCKED, run.results.getValue("b").status)
            executor.finish("c")
            assertTrue(run.tick())
        }
    }

    @Test fun cpuReservationTotalsDoNotOverflowAndCancellationClosesActiveWork() {
        val executor = ManualExecutor()
        executor.request = { ResourceRequest(1, cpuSlots = Int.MAX_VALUE) }
        val nodes = listOf("a", "b").map { id -> ActionNode(id, emptySet()) { spec(id, effect = true) } }
        SchedulerRun("cpu", nodes, executor, ResourceBudget(Int.MAX_VALUE, 10), EventJournal {}).use { run ->
            run.tick()
            assertEquals(listOf("a"), executor.started)
            run.cancel()
            assertTrue(run.tick())
            assertTrue(run.results.values.all { it.status == ActionStatus.CANCELLED })
            assertTrue(executor.running.getValue("a").closed)
        }
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
