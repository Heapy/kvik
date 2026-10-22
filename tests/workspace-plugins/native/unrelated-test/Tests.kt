package fixture.unrelated

import kotlin.test.Test
import kotlin.test.assertEquals

class UnrelatedTest {
    @Test
    fun ordinaryBehavior() {
        assertEquals("unrelated", unchanged())
    }
}
