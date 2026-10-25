package kvik.bsp

import kvik.core.*
import kotlin.test.*

class BspPluginGeneratorsTest {
    private val digest = Sha256.digest("generated".encodeToByteArray())
    private val producer = "plugin:fixture:generate:code"
    private fun model(): BspProjectModel {
        val project = parseProjectConfiguration(parseJson("""{
          "schemaVersion":1,"name":"fixture","repositories":[{"id":"central","url":"https://example.org/maven"}],
          "modules":[{"id":"lib","sourceSets":[
            {"name":"main","directories":[],"resources":[],"dependsOn":[],"dependencies":[]},
            {"name":"test","directories":[],"resources":[],"dependsOn":["main"],"dependencies":[]}],
            "targets":[{"name":"jvm","main":"main","test":"test","compilerOptions":[]},
              {"name":"js","main":"main","test":"test","compilerOptions":[]}],"compilerPlugins":[]}]}
        """))
        return BspProjectModel(ProjectInspection(project, digest, digest, emptyList(), emptyMap(), emptyList()),
            "/project", LogicalPath("kvik.json"))
    }
    private val main = BspPluginGeneratedRoot(producer, "lib", "main", LogicalPath("kotlin"))
    private val tests = BspPluginGeneratedRoot(producer, "lib", "test", LogicalPath("tests"))
    private val assets = BspPluginGeneratedRoot(producer, "lib", "main", LogicalPath("assets"), listOf(LogicalPath("value.bin")))

    @Test fun pluginRootsExposeFilesToOwnersAndResolveCompilerDiagnostics() {
        val model = model()
        model.registerPluginGeneratedRoots(listOf(main, tests, assets))
        assertEquals(2, model.pluginGeneratedTargets(main).size)
        assertTrue(model.pluginGeneratedTargets(main).all { it.compilation == "main" })
        assertEquals(2, model.pluginGeneratedTargets(tests).size)
        assertEquals(4, model.pluginGeneratedTargets(assets).size)
        for (binding in listOf(main, tests)) {
            val file = FileEntry(LogicalPath(binding.prefix + "Generated.kt"), digest)
            val source = BspGeneratedSources("/project/.kvik/bsp/run/files", producer, digest, TreeManifest(listOf(file)))
            for (target in model.pluginGeneratedTargets(binding)) model.replaceGeneratedSources(target, listOf(source))
            assertEquals(2, model.inverseSources(source.uri(file)).values.getValue("targets").arrayValue().size)
            assertEquals(source.uri(file), model.diagnosticUri("lib:js:${if (binding == main) "main" else "test-compile"}", file.path.value))
            if (binding == main) {
                assertEquals(source.uri(file), model.diagnosticUri("lib:js:test-compile", file.path.value))
                assertNull(model.diagnosticUri("lib:jvm:test-compile", file.path.value))
            }
        }
        val resourceFile = FileEntry(LogicalPath(assets.prefix + "value.bin"), digest)
        val resources = BspGeneratedResources("/project/.kvik/bsp/run/files", producer, digest, TreeManifest(listOf(resourceFile)))
        for (target in model.pluginGeneratedTargets(assets)) {
            model.replaceGeneratedResources(target, listOf(resources))
            assertTrue(model.resources(listOf(target.uri)).canonical().contains(resources.uri(resourceFile)))
        }
        assertEquals(model.allTargets().toSet(), model.generatedSourceOwners(model.allTargets().first(), classpath = true).toSet())
    }

    @Test fun wrongRootsAndResourceInventoriesAreRejectedAndReloadHasNoPluginOutputs() {
        val model = model(); model.registerPluginGeneratedRoots(listOf(main, assets))
        val target = model.pluginGeneratedTargets(main).first()
        val file = FileEntry(LogicalPath(main.prefix + "Generated.kt"), digest)
        val source = BspGeneratedSources("/project/.kvik/bsp/run/files", producer, digest, TreeManifest(listOf(file)))
        val wrong = source.copy(files = TreeManifest(listOf(file.copy(path = LogicalPath("generated/Other.kt")))))
        assertFails { model.replaceGeneratedSources(target, listOf(wrong)) }
        val resource = BspGeneratedResources("/project/.kvik/bsp/run/files", producer, digest,
            TreeManifest(listOf(FileEntry(LogicalPath(assets.prefix + "wrong.bin"), digest))))
        assertFails { model.replaceGeneratedResources(target, listOf(resource)) }
        assertFails { model.registerPluginGeneratedRoots(listOf(main.copy(module = "absent"))) }
        assertTrue(model().generatedSourceTargets(producer).isEmpty())
        assertTrue(model().generatedResourceTargets(producer).isEmpty())
    }
}
