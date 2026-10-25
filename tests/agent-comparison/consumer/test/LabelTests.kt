package fixture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LabelTests {
    @Test fun normalizesNames() {
        assertEquals("ada:2", label("  Ada  ", 2))
        assertEquals("grace:3", label("GRACE", 3))
    }

    @Test fun preservesInteriorWhitespace() {
        assertEquals("ada lovelace:1", label(" Ada Lovelace ", 1))
        assertEquals(":0", label("  ", 0))
    }

    @Test fun boundsCounts() {
        assertEquals("ada:0", label("Ada", Int.MIN_VALUE))
        assertEquals("ada:10", label("Ada", Int.MAX_VALUE))
        assertEquals("ada:9", label("Ada", 9))
        assertEquals("ada:10", label("Ada", 10))
    }
}
