package fixture.consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
class ConsumerTest {
    @Test fun transformedProductionAndTestHaveExpectedBehavior() {
        assertEquals("helper:v1:hello:runtime", message())
        assertEquals("helper:v1:hello:runtime", "PLUGIN_MARKER")
    }
}
