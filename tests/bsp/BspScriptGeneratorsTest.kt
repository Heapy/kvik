package kvik.bsp

import kvik.core.*
import kotlin.test.*

class BspScriptGeneratorsTest {
    private val digest = Digest("a".repeat(64), 3)
    private val root = "/project ü # %"
    private fun model(): BspProjectModel {
        val project = parseProjectConfiguration(parseJson("""{
          "schemaVersion":1,"name":"scripts","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],"modules":[
            {"id":"lib","sourceSets":[
              {"name":"common","directories":["src"],"resources":["resources"],"dependsOn":[],"dependencies":[]},
              {"name":"main","directories":[],"resources":[],"dependsOn":["common"],"dependencies":[]},
              {"name":"test","directories":["tests"],"resources":[],"dependsOn":["main"],"dependencies":[]}],
             "targets":[{"name":"jvm","main":"main","test":"test","compilerOptions":[]},
               {"name":"js","main":"main","test":"test","compilerOptions":[]}],"compilerPlugins":[]},
            {"id":"host","sourceSets":[{"name":"main","directories":["host"],"resources":[],"dependsOn":[],"dependencies":[]}],
             "targets":[{"name":"jvm","main":"main","test":null,"compilerOptions":[]}],"compilerPlugins":[]}
          ],"scripts":{
            "sources":{"sources":["scripts/Sources.kt"],"dependencies":[{"module":"host"}],
              "generator":{"module":"lib","sourceSet":"common"}},
            "tests":{"sources":["scripts/Tests.kt"],"generator":{"module":"lib","sourceSet":"test"}},
            "resources":{"sources":["scripts/Resources.kt"],"generator":{"module":"lib","sourceSet":"common","resources":["image.bin","folder/name #%.txt"]}},
            "testResources":{"sources":["scripts/TestResources.kt"],"generator":{"module":"lib","sourceSet":"test","resources":["expected.txt"]}},
            "hostSources":{"sources":["scripts/Host.kt"],"generator":{"module":"host","sourceSet":"main"}}
          }}"""))
        val files = listOf(FileInspection(LogicalPath("resources/static.txt"), emptyList(), emptyList(), emptyList(), listOf("kind:resource")))
        return BspProjectModel(ProjectInspection(project, digest, digest, files, emptyMap(), emptyList()), root, LogicalPath("kvik.json"))
    }
    private fun id(script: String) = "script:$script:jvm:generate"
    private fun BspProjectModel.target(scope: String = "main", platform: String = "jvm", module: String = "lib") =
        allTargets().single { it.module == module && it.target == platform && it.compilation == scope }
    private fun source(script: String = "sources") = BspGeneratedSources("$root/.kvik/bsp/first/$script", id(script), digest,
        TreeManifest(listOf(FileEntry(LogicalPath("generated/scripts/$script/Generated.kt"), digest))))
    private fun resources(script: String = "resources"): BspGeneratedResources = BspGeneratedResources(
        "$root/.kvik/bsp/first/$script", id(script), digest,
        TreeManifest((if (script == "resources") listOf("image.bin", "folder/name #%.txt") else listOf("expected.txt"))
            .map { FileEntry(LogicalPath("generated/$it"), digest) }))
    private fun BspProjectModel.resourceUris(target: BspTarget): List<String> = resources(listOf(target.uri)).values
        .getValue("items").arrayValue().single().objectValue().getValue("resources").arrayValue().map(JsonValue::stringValue)

    @Test fun sourceSetsSelectMainAndTestOwnersAcrossPlatforms() {
        val model = model()
        assertEquals(listOf(model.target(), model.target(platform = "js")), model.generatedSourceTargets(id("sources")))
        assertEquals(listOf(model.target("test"), model.target("test", "js")), model.generatedSourceTargets(id("tests")))
        assertEquals(model.allTargets().filter { it.module == "lib" }, model.generatedResourceTargets(id("resources")))
        assertEquals(model.generatedSourceTargets(id("tests")), model.generatedResourceTargets(id("testResources")))
        assertTrue(model.generatedSourceTargets(id("resources")).isEmpty())
        assertTrue(model.generatedResourceTargets(id("sources")).isEmpty())
        for (id in listOf("script:missing:jvm:generate", "script:sources:js:generate", "script:sources:jvm:run")) {
            assertNull(model.scriptGenerator(id))
            assertTrue(model.generatedSourceTargets(id).isEmpty())
            assertTrue(model.generatedResourceTargets(id).isEmpty())
        }
    }

    @Test fun compilerPathsResolveGeneratedDiagnosticsAndInverseOwnership() {
        val model = model(); val source = source(); val file = source.files.files.single()
        for (owner in model.generatedSourceTargets(source.producer)) model.replaceGeneratedSources(owner, listOf(source))
        assertEquals(source.uri(file), model.diagnosticUri("lib:jvm:main", file.path.value))
        assertEquals(source.uri(file), model.diagnosticUri("lib:js:test-compile", file.path.value))
        assertNull(model.diagnosticUri("lib:jvm:test-compile", file.path.value))
        assertEquals(2, model.inverseSources(source.uri(file)).values.getValue("targets").arrayValue().size)
        assertFails { model.replaceGeneratedSources(model.target("test"), listOf(source)) }
        assertFails { model.replaceGeneratedSources(model.target(), listOf(source.copy(files = TreeManifest(listOf(
            file.copy(path = LogicalPath("generated/Generated.kt"))))))) }
    }

    @Test fun resourcesExposeBinaryAndEscapedUrisAlongsideStaticFiles() {
        val model = model(); val main = resources(); val test = resources("testResources")
        for (owner in model.generatedResourceTargets(main.producer)) model.replaceGeneratedResources(owner, listOf(main))
        for (owner in model.generatedResourceTargets(test.producer)) model.replaceGeneratedResources(owner, listOf(main, test))
        for (owner in model.allTargets().filter { it.module == "lib" }) {
            val expected = listOf(bspFileUri("$root/resources/static.txt")) + main.files.files.map(main::uri) +
                if (owner.compilation == "test") test.files.files.map(test::uri) else emptyList()
            assertEquals(expected.sorted(), model.resourceUris(owner))
            assertTrue(model.sources(listOf(owner.uri)).canonical().contains("\"generated\":false"))
        }
        assertTrue(model.resourceUris(model.target(module = "host")).isEmpty())
        assertTrue(model.inverseSources(main.uri(main.files.files.first())).values.getValue("targets").arrayValue().isEmpty())
    }

    @Test fun replacementAndClearingRemoveOldResourcePaths() {
        val model = model(); val owner = model.target(); val first = resources()
        val second = first.copy(directory = "$root/.kvik/bsp/second/resources")
        assertTrue(model.replaceGeneratedResources(owner, listOf(first)))
        assertFalse(model.replaceGeneratedResources(owner, listOf(first)))
        assertTrue(model.replaceGeneratedResources(owner, listOf(second)))
        assertTrue(first.files.files.none { first.uri(it) in model.resourceUris(owner) })
        assertTrue(model.replaceGeneratedResources(owner, emptyList()))
        assertFalse(model.replaceGeneratedResources(owner, emptyList()))
        assertEquals(listOf(bspFileUri("$root/resources/static.txt")), model.resourceUris(owner))
        assertEquals(model().resourceUris(owner), model.resourceUris(owner))
    }

    @Test fun generatedResourcesRequireExactDeclarationAndRetainedLocation() {
        val model = model(); val target = model.target(); val resources = resources()
        assertFails { model.replaceGeneratedResources(target, listOf(resources, resources)) }
        assertFails { model.replaceGeneratedResources(target, listOf(resources("testResources"))) }
        assertFails { model.replaceGeneratedResources(target, listOf(resources.copy(directory = "/external"))) }
        assertFails { resources.copy(directory = "$root/.kvik/bsp/../outside") }
        assertFails { model.replaceGeneratedResources(target, listOf(resources.copy(files = TreeManifest(resources.files.files.take(1))))) }
        val file = resources.files.files.first()
        assertFails { resources.copy(files = TreeManifest(listOf(file.copy(executable = true)))) }
        assertFails { resources.copy(files = TreeManifest(listOf(file.copy(path = LogicalPath("elsewhere/image.bin"))))) }
        assertFails { resources.copy(files = TreeManifest(listOf(file.copy(digest = Digest("a".repeat(64), 9L * 1024 * 1024))))) }
    }

    @Test fun rebuildingConsumersClearsHostGeneratorPrerequisitesToo() {
        val model = model()
        assertEquals(model.allTargets().toSet(), model.generatedSourceOwners(model.target("test", "js")).toSet())
        // A main classpath query does not run the requested module's own generators.
        assertTrue(model.generatedSourceOwners(model.target(), classpath = true).isEmpty())
        assertEquals(model.allTargets().toSet(), model.generatedSourceOwners(model.target("test"), classpath = true).toSet())
    }
}
