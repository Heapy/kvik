package consumer
import kotlin.test.Test
import kotlin.test.assertEquals
class VisibilityTests {
    @Test fun privateDependencyLinksThroughPublicApi() { assertEquals(42, bridge.answer()) }
    @Test fun commonActualAndInternalMainRemainAccessible() {
        assertEquals(7, platformNumber())
        assertEquals(17, internalValue())
    }
}
