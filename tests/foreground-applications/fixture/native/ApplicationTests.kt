package fixture

import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTests {
    @Test fun dependencyRemainsAvailable() {
        assertEquals("runtime-external", libraryValue())
    }
}
