package fixture.unrelated
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
class UnrelatedTest {
    @Test fun ordinaryBehavior() { assertEquals("unrelated", unchanged()) }
}
