package fixture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ApplicationTests {
    @Test fun dependencyRemainsAvailable() {
        assertEquals("runtime-external", libraryValue())
    }
}
