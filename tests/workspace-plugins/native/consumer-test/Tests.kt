package fixture.consumer

import kotlin.test.Test
import kotlin.test.assertEquals

class ConsumerTest {
    @Test
    fun transformedProductionAndTestHaveExpectedBehavior() {
        val testValue = "PLUGIN_MARKER"
        println("MAIN:" + message())
        println("TEST:" + testValue)
        assertEquals(message(), testValue)
    }
}
