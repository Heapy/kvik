package kvik.core

import kotlin.test.*

class ScriptSourceDeclarationsTest {
    private val entry = LogicalPath("scripts/main.kts")
    private val spec = ScriptSpec(listOf(LogicalPath("scripts")), entryScript = entry)
    private fun read(files: Map<String, String>, script: ScriptSpec = spec) = readScriptSourceDeclarations(script) {
        files[it.value]?.encodeToByteArray() ?: error("Missing captured source: ${it.value}")
    }

    @Test fun importedDeclarationsAreCapturedOnceAndIgnoreUnreachableFiles() {
        val files = mapOf("scripts/main.kts" to "@file:Import(\"a.kts\", \"b.kts\")\nprintln(42)",
            "scripts/a.kts" to "@file:Import(\"shared.kts\")", "scripts/b.kts" to "@file:Import(\"shared.kts\")",
            "scripts/shared.kts" to "@file:DependsOn(\"example:library:1\")", "scripts/unreachable.kts" to "@file:DependsOn(EXECUTE())")
        val reads = mutableListOf<String>()
        val declarations = readScriptSourceDeclarations(spec) { reads += it.value; files.getValue(it.value).encodeToByteArray() }!!
        assertEquals(4, reads.size)
        assertEquals(listOf(Coordinate.parse("example:library:1")), declarations.files.getValue(LogicalPath("scripts/shared.kts")).dependencies)
        assertFalse("scripts/unreachable.kts" in reads)
    }

    @Test fun bodyAndCommentEditsDoNotChangeDeclarationIdentity() {
        val original = read(mapOf(entry.value to "@file:DependsOn(\"g:a:1\")\nprintln(1)"))!!
        val edited = read(mapOf(entry.value to "// explanation\n@file:DependsOn( \"g:a:1\" )\nprintln(2)"))!!
        assertEquals(original.toJson(), edited.toJson())
        assertNotEquals(original.toJson(), read(mapOf(entry.value to "@file:DependsOn(\"g:a:2\")"))!!.toJson())
        assertNull(read(mapOf(entry.value to "println(42)")))
    }

    @Test fun importsCannotReadUndeclaredPathsOrCycle() {
        for (reference in listOf("../../outside.kts", "../outside.kts", "/outside.kts", "other.kt", "a//b.kts")) {
            val reads = mutableListOf<String>()
            assertFails { readScriptSourceDeclarations(spec) {
                reads += it.value
                "@file:Import(\"$reference\")".encodeToByteArray()
            } }
            assertEquals(listOf(entry.value), reads, reference)
        }
        assertFailsWith<IllegalArgumentException> {
            read(mapOf(entry.value to "@file:Import(\"helper.kts\")", "scripts/helper.kts" to "@file:Import(\"main.kts\")"))
        }
    }

    @Test fun exactCoordinatesAndRepositoryTransportPolicyApplyBeforeResolution() {
        for (coordinate in listOf("g:a:+", "g:a:LATEST", "g:a:1-SNAPSHOT", "g:a", "g:a:1:classifier"))
            assertFailsWith<IllegalArgumentException> { read(mapOf(entry.value to "@file:DependsOn(\"$coordinate\")")) }
        for (url in listOf("http://example.org/maven", "https://u:p@example.org/maven", "https://example.org/../maven", "https://example.org/maven?secret=x"))
            assertFailsWith<IllegalArgumentException> { read(mapOf(entry.value to "@file:Repository(\"$url\")")) }
        val invalid = assertFailsWith<IllegalArgumentException> { read(mapOf(entry.value to "\n@file:DependsOn(\"g:a:+\")")) }
        assertTrue(invalid.message!!.contains("scripts/main.kts:2:1"))
    }

    @Test fun ordinaryKotlinDoesNotReadSourceForDependencyDeclarations() {
        assertNull(readScriptSourceDeclarations(ScriptSpec(listOf(LogicalPath("main.kt")))) { error("Unexpected read") })
    }

    @Test fun equivalentRepositoryBasesHaveOneIdentityAcrossScriptsAndExplicitConfiguration() {
        fun declarations(url: String) = read(mapOf(entry.value to "@file:Repository(\"$url\")"))!!
        val plain = declarations("https://example.org/maven")
        val slash = declarations("https://example.org/maven/")
        assertEquals(plain.toJson(), slash.toJson())
        val base = listOf(Repository("central", "https://repo.maven.apache.org/maven2"))
        assertEquals(scriptRepositories(base, plain), scriptRepositories(base, slash))
        assertEquals(base, scriptRepositories(base, declarations("https://repo.maven.apache.org/maven2/")))
        val explicit = listOf(Repository("declared", "https://example.org/maven/"))
        assertEquals(explicit, scriptRepositories(explicit, plain))
        assertEquals("https://example.org", declarations("https://example.org").files.getValue(entry).repositories.single())
    }
}
