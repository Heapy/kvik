package kvik.core

import kotlin.test.*

class BuildPluginInputsTest {
    private fun configuration() = parseProjectConfiguration(parseJson("""{
        "schemaVersion":1,"name":"inputs","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],"modules":[]
    }"""))

    private fun plugin(declaration: BuildPluginInputs) = object : ProjectInputContributor {
        override val id = "example.generator"
        override val apiVersion = CONTRACT_VERSION
        override val configurationSchema = obj()
        override fun projectInputs(configuration: ProjectConfiguration): BuildPluginInputs {
            assertTrue(configuration.buildPluginInputs.isEmpty())
            return declaration
        }
    }

    @Test fun declarationsAreDetachedRecomputedAndInspectedWithoutChangingAuthoring() {
        val paths = mutableListOf(LogicalPath("schema.json"))
        val original = configuration()
        val configured = original.withBuildPluginInputs(listOf(plugin(BuildPluginInputs(paths, listOf(LogicalPath("templates"))))))
        paths.clear()
        assertEquals(listOf(LogicalPath("schema.json")), configured.buildPluginInputs.getValue("example.generator").files)
        assertEquals(original.document, configured.document)
        assertEquals(original.digest, configured.digest)
        assertTrue(configured.withBuildPluginInputs(emptyList()).buildPluginInputs.isEmpty())
        val inspection = ProjectInspection(configured, configured.digest, Sha256.digest(byteArrayOf()), emptyList(), emptyMap(), emptyList())
        assertEquals(inspection, ProjectInspection.fromJson(parseJson(inspection.toJson().canonical())))
        val tags = tagProjectFiles(configured, LogicalPath("kvik.json"), LogicalPath("kvik.lock.json"),
            listOf("schema.json", "templates/nested/code.txt", "templates-other/code.txt").map(::LogicalPath)).associate { it.path.value to it.tags }
        val expected = setOf("kind:plugin-input", "build-plugin:example.generator")
        assertEquals(expected, tags.getValue("schema.json"))
        assertEquals(expected, tags.getValue("templates/nested/code.txt"))
        assertFalse("build-plugin:example.generator" in tags.getValue("templates-other/code.txt"))
    }

    @Test fun invalidAndUnboundedDeclarationsAreRejected() {
        val path = LogicalPath("schema.json")
        assertFailsWith<IllegalArgumentException> { BuildPluginInputs(listOf(path, path)) }
        assertFailsWith<IllegalArgumentException> { BuildPluginInputs(listOf(path), listOf(path)) }
        assertFailsWith<IllegalArgumentException> { BuildPluginInputs((0..4096).map { LogicalPath("f$it") }) }
        assertFailsWith<IllegalStateException> { parseBuildPluginInputs(parseJson("null")) }
        assertFailsWith<IllegalArgumentException> { parseBuildPluginInputs(parseJson("""{"bad/id":{"files":[],"directories":[]}}""")) }
        assertFailsWith<IllegalArgumentException> { parseBuildPluginInputs(parseJson("""{"valid":{"files":["../escape"],"directories":[]}}""")) }
        val contributor = plugin(BuildPluginInputs())
        assertFailsWith<IllegalArgumentException> { configuration().withBuildPluginInputs(listOf(contributor, contributor)) }
    }
}
