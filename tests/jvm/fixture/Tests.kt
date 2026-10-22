package fixture
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*

class PassingTests {
    @Test fun serializationAndDependency() {
        assertEquals("""{"text":"hello JVM"}""", payload())
        Files.writeString(Path.of("effects.txt"), "ran\n", CREATE, APPEND)
    }
    @TestFactory fun dynamic() = listOf(DynamicTest.dynamicTest("actual platform") { assertEquals("JVM", platformName()) })
    @Test @Disabled("intentional fixture skip") fun disabled() { fail<Unit>("must not execute") }
}
class FailingTests {
    @Test fun fails() { assertEquals(2, 1, "intentional fixture failure") }
}
@Disabled("whole class skipped")
class SkippedTests { @Test fun skipped() { fail<Unit>("must not execute") } }
class EmptyTests
class ContainerFailureTests {
    companion object { @JvmStatic @BeforeAll fun setup() { error("intentional setup failure") } }
    @Test fun neverReached() { fail<Unit>("must not execute") }
}
class ContainerAssumptionTests {
    companion object { @JvmStatic @BeforeAll fun setup() { Assumptions.assumeTrue(false, "environment unavailable") } }
    @Test fun neverReached() { fail<Unit>("must not execute") }
}
