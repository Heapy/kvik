package kvik.projectgraph

import kvik.core.*
import kotlin.test.*
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.milliseconds

private val executorComposition = Sha256.digest("plugin source".encodeToByteArray())
private const val implementation = "example.assets:manifest-v1"
private fun executorAction() = ActionSpec(implementation, executorComposition, ActionKind.GENERATE,
    mapOf("os" to "fixture"), "custom", mapOf("runtime" to executorComposition), TreeManifest(emptyList()),
    emptyList(), emptyList(), emptyMap(), timeoutMillis = 100)

private class ProbeExecutor : ActionExecutor {
    var starts = 0
    var cancelled = 0
    var closed = 0
    var complete = true
    var wrongIdentity = false
    override fun resources(action: ActionSpec) = ResourceRequest(42, setOf("shared"), 2)
    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        starts++
        return object : RunningAction {
            override fun poll(): ActionResult? = if (!complete) null else ActionResult(
                if (wrongIdentity) "other" else actionId, action.key(Sha256), ActionStatus.EXECUTED, exitCode = 0)
            override fun cancel() { cancelled++ }
            override fun close() { closed++ }
        }
    }
}

private class ExecutorPlugin(val supplied: Map<String, ActionExecutor>, override val id: String = "example.assets",
                             override val apiVersion: Int = CONTRACT_VERSION) : ProjectExecutorContributor {
    override val configurationSchema = obj()
    override fun executors(context: ProjectExecutionContext) = supplied
}

private fun executionContext(): ProjectExecutionContext = ProjectExecutionContext("run", "/private/plugin", executorComposition,
    object : ContentStore {
        override fun put(bytes: ByteArray) = error("Unexpected content write")
        override fun get(digest: Digest): ByteArray? = error("Unexpected content read")
    }, object : ProcessHost {
        override fun start(request: ProcessRequest): OwnedProcess = error("Unexpected process launch")
        override fun close() = Unit
    })

class PluginExecutorsTest {
    @Test fun registrationIsLazyAndForwardsResourcesWithinItsOwnRun() {
        val probe = ProbeExecutor()
        val executor = pluginActionExecutors(listOf(ExecutorPlugin(mapOf(implementation to probe))), { executionContext() }).getValue(implementation)
        assertEquals(0, probe.starts)
        assertEquals(ResourceRequest(42, setOf("shared"), 2), executor.resources(executorAction()))
        assertFails { executor.start("other-run", "generate", executorAction()) }
        assertEquals(0, probe.starts)
        val handle = executor.start("run", "generate", executorAction())
        val result = assertNotNull(handle.poll())
        assertEquals(ActionStatus.EXECUTED, result.status)
        assertEquals(1, probe.closed) // Cleanup precedes the caller seeing a cacheable result.
        assertEquals(result, handle.poll())
        handle.close(); handle.cancel()
        assertEquals(1, probe.closed)
        assertEquals(0, probe.cancelled)
    }

    @Test fun namespacesVersionsPoliciesAndRunCompositionAreChecked() {
        val probe = ProbeExecutor()
        fun register(vararg plugins: BuildPlugin) = pluginActionExecutors(plugins.toList(), { executionContext() })
        for (name in listOf("kvik-jvm-compiler-v1", "other:manifest-v1", "example.assets:", "example.assets:../bad"))
            assertFails { register(ExecutorPlugin(mapOf(name to probe))) }
        assertFails { register(ExecutorPlugin(emptyMap())) }
        assertFails { register(ExecutorPlugin(mapOf(implementation to probe), apiVersion = CONTRACT_VERSION + 1)) }
        val plugin = ExecutorPlugin(mapOf(implementation to probe))
        assertFails { register(plugin, plugin) }
        val executor = register(plugin).getValue(implementation)
        for (action in listOf(executorAction().copy(cachePolicy = CachePolicy.REMOTE),
            executorAction().copy(implementation = "other:manifest-v1"),
            executorAction().copy(composition = Sha256.digest(byteArrayOf(1))),
            executorAction().copy(kind = ActionKind.TEST, cachePolicy = CachePolicy.NEVER))) {
            assertFails { executor.resources(action) }
            assertFails { executor.start("run", "generate", action) }
        }
        assertEquals(0, probe.starts)
    }

    @Test fun timeoutAndCancellationCloseEvenAnUnfinishedPluginHandle() {
        for (timeout in listOf(true, false)) {
            val probe = ProbeExecutor().also { it.complete = false }
            val clock = TestTimeSource()
            val executor = pluginActionExecutors(listOf(ExecutorPlugin(mapOf(implementation to probe))), { executionContext() }, clock).getValue(implementation)
            val handle = executor.start("run", "generate", executorAction())
            assertNull(handle.poll())
            if (timeout) clock += 100.milliseconds else handle.cancel()
            val result = assertNotNull(handle.poll())
            assertEquals(if (timeout) ActionStatus.FAILED else ActionStatus.CANCELLED, result.status)
            assertEquals(if (timeout) 124 else 130, result.exitCode)
            assertEquals(1, probe.cancelled)
            assertEquals(1, probe.closed)
            handle.close()
            assertEquals(1, probe.closed)
        }
    }

    @Test fun invalidResultCannotBecomeAnExecutedAction() {
        val probe = ProbeExecutor().also { it.wrongIdentity = true }
        val executor = pluginActionExecutors(listOf(ExecutorPlugin(mapOf(implementation to probe))), { executionContext() }).getValue(implementation)
        val handle = executor.start("run", "generate", executorAction())
        val result = assertNotNull(handle.poll())
        assertEquals(ActionStatus.FAILED, result.status)
        assertEquals("generate", result.actionId)
        assertEquals(result, handle.poll())
        assertEquals(1, probe.closed)
    }
}
