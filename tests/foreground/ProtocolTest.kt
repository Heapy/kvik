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
}
