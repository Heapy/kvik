package kvik.foreground

import kotlin.test.*

class ProtocolTest {
    @Test fun coalescesBuildsWithoutLosingTests() {
        val queue = PendingOperation(Operation.BUILD)
        assertEquals(Operation.BUILD, queue.take())
        queue.request(Operation.BUILD); queue.request(Operation.TEST); queue.request(Operation.BUILD)
        assertEquals(Operation.TEST, queue.take()); assertNull(queue.take())
    }
    @Test fun strictControlsKeepRequestIdentity() {
        assertEquals(Control("request-1", "t"), parseControl("""{"schemaVersion":1,"requestId":"request-1","command":"t"}""", true))
        assertEquals(Control(null, "r"), parseControl(" r\r", false))
        for (value in listOf("{}", """{"schemaVersion":2,"requestId":"x","command":"q"}""",
            """{"schemaVersion":1,"requestId":"x","command":"shell"}""",
            """{"schemaVersion":1,"requestId":"x\n","command":"q"}""")) assertFails { parseControl(value, true) }
    }
    @Test fun releasesLiveOwnerAfterEveryCleanupBoundaryEvenWhenTheyFail() {
        val order = mutableListOf<String>()
        val failure = assertFailsWith<IllegalStateException> {
            closeSessionResources(null,
                { order += "children"; error("children") },
                { order += "journal"; error("journal") },
                { order += "terminal"; error("terminal") },
                { order += "owner"; error("owner") })
        }
        assertEquals(listOf("children", "journal", "terminal", "owner"), order)
        assertEquals("children", failure.message)
        assertEquals(3, failure.suppressedExceptions.size)
    }
    @Test fun remoteCancellationRequiresAnExactIdentityAndRetryIdsAreBounded() {
        val request = SessionRequest("retry-1", "cancel", "operation-1")
        assertEquals(request, SessionRequest.fromJson(request.toJson()))
        assertEquals(SessionRequest("retry-1", "status").key, request.key)
        assertFails { SessionRequest("", "r") }
        assertFails { SessionRequest("x".repeat(129), "r") }
        assertFails { SessionRequest("x\n", "r") }
        assertFails { SessionRequest("x", "cancel") }
        assertFails { SessionRequest("x", "r", "operation-1") }
        assertFails { SessionRequest("x", "cancel", "../operation") }
        assertFails { SessionRequest("x", "shell") }
    }

}
