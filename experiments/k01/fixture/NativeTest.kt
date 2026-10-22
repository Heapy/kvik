import kotlin.test.Test
import kotlin.test.assertEquals

class NativeFixtureTest {
    @Test
    fun executesOnHost() {
        assertEquals(6, listOf(1, 2, 3).sum())
    }
}
