package kvik.bsp

import kvik.core.*
import kotlin.test.*

class BspProjectModelTest {
    private val targetNames = listOf("jvm", "js", "wasmJs", "macosArm64", "linuxX64", "iosArm64", "iosSimulatorArm64")
    private val project = parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "sample".json(),
        "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
        "modules" to listOf("base", "lib", "app").map { module ->
            fun source(name: String, directories: List<String>, resources: List<String>, dependsOn: List<String>, dependency: String? = null) =
                obj("name" to name.json(), "directories" to directories.map { it.json() }.json(),
                    "resources" to resources.map { it.json() }.json(), "dependsOn" to dependsOn.map { it.json() }.json(),
                    "dependencies" to listOfNotNull(dependency?.let { obj("module" to it.json()) }).json())
            obj("id" to module.json(), "compilerPlugins" to emptyList<JsonValue>().json(),
                "sourceSets" to listOf(
                    source("common", listOf("$module/common", "shared"), listOf("$module/resources"), emptyList(),
                        when (module) { "app" -> "lib"; "lib" -> "base"; else -> null }),
                    source("main", listOf("$module/main"), emptyList(), listOf("common")),
                    source("test", listOf("$module/test"), listOf("$module/testResources"), emptyList()),
                ).json(), "targets" to targetNames.map { target ->
                    val hasTest = !target.startsWith("ios")
                    JsonValue.Object(buildMap {
                        put("name", target.json()); put("main", "main".json()); put("test", if (hasTest) "test".json() else JsonValue.Null)
                        put("compilerOptions", listOf("-Xexpect-actual-classes".json()).json())
                        if (hasTest) put("testCompilerOptions", listOf("-Xtest-option".json()).json())
                    })
                }.json())
        }.json()))
    private val digest = Digest("a".repeat(64), 42)
    private val inspectedFiles = listOf("app/common/Shared.kt", "app/main/Main.kt", "app/test/Test.kt", "shared/All.kt",
        "app/resources/config.json", "app/testResources/test.json", "notes.txt", "app/common-other/No.kt", "app/common/café #%.kt")
    private val inspection = ProjectInspection(project, digest, digest, inspectedFiles.map {
        FileInspection(LogicalPath(it), emptyList(), emptyList(), emptyList(), listOf("kind:unclassified"))
    }, emptyMap(), listOf("project-inspection"))
    private val model = BspProjectModel(inspection, "/tmp/space project", LogicalPath("kvik.json"))
    private fun target(module: String, platform: String = "jvm", scope: String = "main") = "file:///tmp/space%20project/kvik.json#$module/$platform/$scope"
    private fun JsonValue.items(field: String) = objectValue().getValue(field).arrayValue()
    private fun JsonValue.uri() = objectValue().getValue("uri").stringValue()

    @Test fun fileUrisEncodeUtf8AndAllReservedPathCharacters() {
        assertEquals("file:///tmp/space%20%23%25%3F%3A%5C/caf%C3%A9/%F0%9F%90%88.kt", bspFileUri("/tmp/space #%?:\\/café/🐈.kt"))
        assertEquals("file:///", bspFileUri("/"))
        assertEquals("file:///tmp/a-._~09", bspFileUri("/tmp/a-._~09"))
        for (path in listOf("relative", "", "/bad\u0000", "/bad\uD800")) assertFails { bspFileUri(path) }
        assertFails { BspProjectModel(inspection, "", LogicalPath("kvik.json")) }
        assertEquals("file:///tmp/space%20project/", model.rootUri)
    }

    @Test fun allPlatformsHaveDistinctStableCompilationTargetsAndImportOnlyCapabilities() {
        val targets = model.targets().items("targets")
        assertEquals(36, targets.size)
        assertEquals(36, targets.map { it.objectValue().getValue("id").uri() }.distinct().size)
        for (entry in targets) {
            val fields = entry.objectValue()
            assertEquals(listOf("kotlin"), fields.getValue("languageIds").arrayValue().map(JsonValue::stringValue))
            assertEquals("kvik-kotlin", fields.getValue("dataKind").stringValue())
            assertTrue(fields.getValue("capabilities").objectValue().values.all { !it.booleanValue() })
            val data = fields.getValue("data").objectValue()
            assertEquals(digest.toJson(), data.getValue("configuration"))
            assertEquals(digest.toJson(), data.getValue("composition"))
            assertEquals(data.getValue("target"), data.getValue("settings").objectValue().getValue("name"))
        }
        val changed = BspProjectModel(inspection.copy(configuration = Digest("b".repeat(64), 43)), "/tmp/space project", LogicalPath("kvik.json"))
        assertEquals(targets.map { it.objectValue().getValue("id") }, changed.targets().items("targets").map { it.objectValue().getValue("id") })
        val otherConfig = BspProjectModel(inspection, "/tmp/space project", LogicalPath("other.json"))
        assertNotEquals(targets.first().objectValue().getValue("id"), otherConfig.targets().items("targets").first().objectValue().getValue("id"))
    }

    @Test fun dependenciesAreDirectAndTestsDependOnOwnMainWithTheirOwnOptions() {
        val targets = model.targets().items("targets").associateBy { it.objectValue().getValue("id").uri() }
        val main = targets.getValue(target("app")).objectValue()
        val test = targets.getValue(target("app", scope = "test")).objectValue()
        assertEquals(listOf(target("lib")), main.getValue("dependencies").arrayValue().map { it.uri() })
        assertEquals(listOf(target("lib"), target("app")), test.getValue("dependencies").arrayValue().map { it.uri() })
        assertEquals(listOf("test"), test.getValue("tags").arrayValue().map(JsonValue::stringValue))
        assertEquals(listOf("-Xtest-option"), test.getValue("data").objectValue().getValue("compilerOptions").arrayValue().map(JsonValue::stringValue))
        assertEquals(listOf(target("lib", "wasmJs")), targets.getValue(target("app", "wasmJs")).items("dependencies").map { it.uri() })
    }

    @Test fun sourcesUseTheMainClosureAndTestsAddMainEvenWithoutExplicitDependsOn() {
        val main = model.sources(listOf(target("app"))).items("items").single()
        val test = model.sources(listOf(target("app", scope = "test"))).items("items").single()
        val mainUris = main.items("sources").map { it.uri() }
        assertEquals(listOf("app/common/", "shared/", "app/main/").map { model.rootUri + it }, mainUris)
        assertEquals(mainUris + "${model.rootUri}app/test/", test.items("sources").map { it.uri() })
        assertTrue(test.items("sources").all { it.objectValue().getValue("kind").longValue() == 2L && !it.objectValue().getValue("generated").booleanValue() })
        assertEquals(test.items("sources").map { it.uri() }, test.items("roots").map(JsonValue::stringValue))
    }

    @Test fun resourcesContainActualFilesFromTheCompilationClosure() {
        assertEquals(listOf("${model.rootUri}app/resources/config.json"),
            model.resources(listOf(target("app"))).items("items").single().items("resources").map(JsonValue::stringValue))
        assertEquals(listOf("${model.rootUri}app/resources/config.json", "${model.rootUri}app/testResources/test.json"),
            model.resources(listOf(target("app", scope = "test"))).items("items").single().items("resources").map(JsonValue::stringValue))
    }

    @Test fun inverseSourcesIncludesAllOwnersButNoResourcesManualFilesOrMissingFiles() {
        assertEquals(12, model.inverseSources("${model.rootUri}app/common/Shared.kt").items("targets").size)
        assertEquals(36, model.inverseSources("${model.rootUri}shared/All.kt").items("targets").size)
        assertEquals(5, model.inverseSources("${model.rootUri}app/test/Test.kt").items("targets").size)
        for (path in listOf("app/resources/config.json", "notes.txt", "app/common-other/No.kt", "app/common/Missing.kt", "app/common/")) {
            assertTrue(model.inverseSources(model.rootUri + path).items("targets").isEmpty(), path)
        }
    }

    @Test fun inverseSourcesUnderstandsEquivalentPercentEscapesAndRejectsRemoteOrMalformedUris() {
        val encoded = model.rootUri + "app/common/caf%C3%A9%20%23%25.kt"
        assertEquals(12, model.inverseSources(encoded).items("targets").size)
        assertEquals(model.inverseSources(encoded), model.inverseSources(encoded.replace("%C3%A9", "%c3%a9")))
        assertEquals(model.inverseSources(encoded), model.inverseSources(encoded.replace("file:///", "file://localhost/")))
        for (uri in listOf("file://remote/tmp/space%20project/shared/All.kt", "https://example.com/shared/All.kt", encoded + "?x", encoded + "#x",
            model.rootUri + "%", model.rootUri + "%FF", model.rootUri + "%00")) {
            assertTrue(model.inverseSources(uri).items("targets").isEmpty(), uri)
        }
    }

    @Test fun actionTargetsAttributeDiagnosticsAndAcceptNewSourcesWithinDeclaredRoots() {
        val main = assertNotNull(model.actionTarget("lib:jvm:main"))
        assertEquals(target("lib"), main.uri)
        assertEquals(target("app", scope = "test"), model.actionTarget("app:jvm:test-compile")?.uri)
        assertEquals(target("app", "macosArm64", "test"), model.actionTarget("app:macosArm64:test-link")?.uri)
        assertTrue(model.containsSource(main, "lib/main/New.kt"))
        assertFalse(model.containsSource(main, "app/main/New.kt"))
        assertFalse(model.containsSource(main, "lib/main/../outside.kt"))
        for (id in listOf("missing:jvm:main", "app:missing:main", "app:jvm:test", "app:jvm:ksp-test", "app:jvm:main:extra"))
            assertNull(model.actionTarget(id), id)
    }

    @Test fun requestsRejectUnknownTargetsAndReturnOnlyRequestedTargets() {
        assertFails { model.sources(listOf(target("app"), "unknown")) }
        assertFails { model.resources(listOf("unknown")) }
        assertTrue(model.sources(emptyList()).items("items").isEmpty())
        assertTrue(model.resources(emptyList()).items("items").isEmpty())
        assertEquals(1, model.sources(listOf(target("app"), target("app"))).items("items").size)
    }
}
