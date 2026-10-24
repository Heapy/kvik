package kvik.bsp

import kvik.core.*
import kotlin.test.*

class BspGeneratedSourcesTest {
    private val project = parseProjectConfiguration(parseJson("""{
      "schemaVersion":1,"name":"generated","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],"modules":[
        {"id":"lib","sourceSets":[
          {"name":"main","directories":["src"],"resources":[],"dependsOn":[],"dependencies":[]},
          {"name":"test","directories":["test"],"resources":[],"dependsOn":[],"dependencies":[]}],
         "targets":[
           {"name":"macosArm64","main":"main","test":"test","compilerOptions":[],
            "nativeTestSettings":{"engine":"kotest","kspVersion":"2.3.12","processor":"io.kotest:kotest-framework-symbol-processor:6.2.5"}},
           {"name":"jvm","main":"main","test":"test","compilerOptions":[]}],"compilerPlugins":[]}
      ]} """))
    private val digest = Digest("a".repeat(64), 3)
    private val inspection = ProjectInspection(project, digest, digest, emptyList(), emptyMap(), emptyList())
    private val root = "/project ü # %"
    private fun model() = BspProjectModel(inspection, root, LogicalPath("kvik.json"))
    private val targetUri = bspFileUri("$root/kvik.json") + "#lib/macosArm64/test"
    private val file = FileEntry(LogicalPath("generated/kotlin/io/kotest/framework/runtime/kotest.kt"), digest, false)
    private fun sources(run: String = "first") = BspGeneratedSources("$root/.kvik/bsp/$run/sources", "lib:macosArm64:ksp-test", digest, TreeManifest(listOf(file)))
    private fun JsonValue.items(name: String) = objectValue().getValue(name).arrayValue()

    @Test fun generatedFilesExposeOriginAndResolveOnlyTheirOwningTarget() {
        val model = model()
        val target = model.select(listOf(targetUri)).single()
        val source = sources()
        assertEquals(listOf(target), model.generatedSourceTargets(source.producer))
        assertTrue(model.replaceGeneratedSources(target, listOf(source)))
        val result = model.sources(listOf(targetUri)).items("items").single()
        val generated = result.items("sources").single { it.objectValue().getValue("generated").booleanValue() }.objectValue()
        assertEquals(source.uri(file), generated.getValue("uri").stringValue())
        assertEquals(1L, generated.getValue("kind").longValue())
        assertEquals("kvik-generated-source", generated.getValue("dataKind").stringValue())
        val origin = generated.getValue("data").objectValue()
        assertEquals(source.producer, origin.getValue("producer").stringValue())
        assertEquals(file.path.value, origin.getValue("logicalPath").stringValue())
        assertEquals(digest.toJson(), origin.getValue("digest"))
        assertEquals(digest.toJson(), origin.getValue("actionKey"))
        assertTrue((bspFileUri(source.directory) + "/").json() in result.items("roots"))
        assertEquals(listOf(obj("uri" to targetUri.json())), model.inverseSources(source.uri(file)).items("targets"))
        assertEquals(model.inverseSources(source.uri(file)), model.inverseSources(source.uri(file).replace("%C3%BC", "%c3%bc")))
        assertEquals(source.uri(file), model.sourceUri(target, file.path.value))
        assertTrue(model.containsSource(target, file.path.value))
        for (other in model.allTargets().filter { it != target }) {
            assertNull(model.sourceUri(other, file.path.value))
            assertTrue(model.sources(listOf(other.uri)).items("items").single().items("sources").none {
                it.objectValue().getValue("generated").booleanValue()
            })
        }
        assertTrue(model.inverseSources(source.uri(file) + ".missing").items("targets").isEmpty())
        assertTrue(model.inverseSources(source.uri(file) + "?query").items("targets").isEmpty())
    }

    @Test fun replacementsRemovePriorOwnershipAndFreshImportsHaveNoGeneratedFiles() {
        val model = model(); val target = model.select(listOf(targetUri)).single()
        val first = sources(); val second = sources("second")
        assertTrue(model.replaceGeneratedSources(target, listOf(first)))
        assertFalse(model.replaceGeneratedSources(target, listOf(first)))
        assertTrue(model.replaceGeneratedSources(target, listOf(second)))
        assertTrue(model.inverseSources(first.uri(file)).items("targets").isEmpty())
        assertEquals(1, model.inverseSources(second.uri(file)).items("targets").size)
        assertTrue(model().inverseSources(second.uri(file)).items("targets").isEmpty())
        assertTrue(model.replaceGeneratedSources(target, emptyList()))
        assertFalse(model.replaceGeneratedSources(target, emptyList()))
        assertNull(model.sourceUri(target, file.path.value))
    }

    @Test fun generatedFileOwnershipWinsOverAnOverlappingDeclaredDirectory() {
        val overlapping = parseProjectConfiguration(parseJson(project.document.canonical().replace(
            "\"directories\":[\"test\"]", "\"directories\":[\"generated\"]")))
        val model = BspProjectModel(inspection.copy(project = overlapping), root, LogicalPath("kvik.json"))
        val target = model.select(listOf(targetUri)).single()
        model.replaceGeneratedSources(target, listOf(sources()))
        assertEquals(sources().uri(file), model.sourceUri(target, file.path.value))
        assertEquals(bspFileUri("$root/generated/UserSpec.kt"), model.sourceUri(target, "generated/UserSpec.kt"))
    }

    @Test fun undeclaredGeneratorsAndPathsOutsideRetainedRunsAreRejected() {
        val model = model(); val target = model.select(listOf(targetUri)).single()
        for (producer in listOf("lib:jvm:ksp-test", "lib:macosArm64:main", "missing:macosArm64:ksp-test", "lib:linuxX64:ksp-test")) {
            assertTrue(model.generatedSourceTargets(producer).isEmpty())
            assertFails { model.replaceGeneratedSources(target, listOf(sources().copy(producer = producer))) }
        }
        assertFails { model.replaceGeneratedSources(target.copy(module = "other"), listOf(sources())) }
        assertFails { model.replaceGeneratedSources(target, listOf(sources().copy(directory = "/external/output"))) }
        assertFails { sources().copy(directory = "$root/.kvik/bsp/../outside") }
        assertFails { model.replaceGeneratedSources(target, listOf(sources(), sources("another"))) }
        assertFails { sources().copy(files = TreeManifest(listOf(file.copy(executable = true)))) }
        assertFails { sources().copy(files = TreeManifest(listOf(file.copy(path = LogicalPath("classes/Test.class"))))) }
        assertFails { sources().copy(files = TreeManifest(listOf(file.copy(digest = Digest("b".repeat(64), Long.MAX_VALUE))))) }
    }
}
