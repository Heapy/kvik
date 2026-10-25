package kvik.projectgraph

import kvik.core.*
import kotlin.test.*

class GeneratedResourcesTest {
    private val digest = Sha256.digest(byteArrayOf())
    private val config = parseProjectConfiguration(parseJson("""{
        "schemaVersion":1,"name":"resource-fixture","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],
        "modules":[{"id":"library","sourceSets":[{"name":"main","directories":["src"],"resources":["res"],"dependsOn":[],"dependencies":[]}],
            "targets":[{"name":"jvm","main":"main","test":null,"compilerOptions":[]}],"compilerPlugins":[]}],
        "scripts":{"assets":{"sources":["Generate.kt"],"generator":{"module":"library","sourceSet":"main","resources":["config.json"]}}}
    }"""))

    @Test fun fixedPathsRejectCollisionsMissingOutputsAndUnexpectedFiles() {
        val selection = moduleResources(config.project, emptyList(), "library", "jvm", "main")
        val id = selection.dependencies.single()
        fun result(vararg paths: String) = mapOf(id to ActionResult(id, digest, ActionStatus.EXECUTED,
            TreeManifest(paths.map { FileEntry(LogicalPath(it), digest) }), 0))
        assertEquals(listOf(LogicalPath("runtime/library/main/config.json")), selection.paths)
        assertEquals(selection.paths, selection.resolve(result("generated/config.json")).map { it.path })
        for (bad in listOf(result(), result("generated/config.json", "generated/extra"), result("generated/Config.json"))) {
            assertFailsWith<IllegalArgumentException> { selection.resolve(bad) }
        }
        assertFailsWith<IllegalArgumentException> { selection.resolve(mapOf(id to ActionResult(id, digest, ActionStatus.FAILED))) }
        for (path in listOf("res/config.json", "res/CONFIG.JSON", "res/config.json/child")) {
            assertFailsWith<IllegalArgumentException> {
                moduleResources(config.project, listOf(FileEntry(LogicalPath(path), digest)), "library", "jvm", "main")
            }
        }
    }
}
