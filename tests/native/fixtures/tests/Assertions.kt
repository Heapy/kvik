package fixture.tests
import kotlin.test.*
import consumer.greeting
class NativeAssertions {
    @Test fun dependencyAndActual() { assertEquals("HELLO MACOSARM64", greeting()) }
    @Test fun arithmetic() { assertEquals(42, 6 * 7) }
    @Ignore @Test fun explicitlySkipped() { error("must not execute") }
}
