package kvik.core

import kotlin.test.*

class JvmTestTagsTest {
    @Test fun literalTagListsPreserveSelectionAndRejectInvalidNames() {
        val tags = JvmTestTags(listOf("fast", "smoke", "быстрые"), listOf("database"))
        assertEquals(tags, JvmTestTags.fromFields(tags.toJson().values))
        assertTrue(tags.enabled)
        assertFalse(JvmTestTags().enabled)
        assertTrue(JvmTestTags().toJson().values.isEmpty())
        for (tag in listOf("", " fast", "slow ", "a\tb", "a\nb", "a\u0085b", "a b", "fast|slow", "!slow", "a&b", "a,b", "any()", "x".repeat(129))) {
            assertFails("accepted '$tag'") { JvmTestTags(includeTags = listOf(tag)) }
            assertFails { JvmTestTags(excludeTags = listOf(tag)) }
        }
        assertFails { JvmTestTags(listOf("fast", "fast")) }
        assertFails { JvmTestTags((1..65).map { "tag$it" }) }
        assertFails { JvmTestTags.fromFields(mapOf("includeTags" to JsonValue.Null)) }
    }

    @Test fun filtersRequireJunitAndComposeWithExplicitClassSelection() {
        val tags = JvmTestTags(listOf("fast"), listOf("database"))
        assertEquals(tags, JvmTestSettings(listOf("example.Checks"), tags = tags).tags)
        assertEquals(tags, JvmTestSettings(emptyList(), discover = true, tags = tags).tags)
        for (engines in listOf(listOf("kotest"), listOf("junit", "kotest")))
            assertFails { JvmTestSettings(emptyList(), engines, tags = tags) }
        assertFalse(JvmTestSettings(emptyList(), listOf("kotest"), tags = JvmTestTags()).tags.enabled)
    }
}
