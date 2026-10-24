package kvik.bsp

import kvik.core.*
import kotlin.test.*

class BspSqlDelightSourcesTest {
    private val digest = Digest("a".repeat(64), 3)
    private val root = "/project ü # %"
    private fun dependency(module: String, runtime: Boolean = false) = obj("module" to module.json(),
        "scope" to (if (runtime) "runtime" else "compile").json())
    private fun plugin(module: String, target: String = "jvm", scope: String = "main") = obj(
        "module" to module.json(), "targets" to listOf(target.json()).json(), "scopes" to listOf(scope.json()).json(),
        "options" to emptyList<JsonValue>().json(), "inputs" to emptyList<JsonValue>().json(),
        "id" to "fixture.plugin".json(), "compilerVersion" to "2.4.20".json())
    private fun module(id: String, sql: Boolean = false, dependencies: List<JsonValue> = emptyList(),
                       testDependencies: List<JsonValue> = emptyList(), plugins: List<JsonValue> = emptyList(),
                       platform: String = "jvm", kotest: Boolean = false): JsonValue.Object {
        fun source(name: String, parents: List<String>, dependencies: List<JsonValue>) = obj("name" to name.json(),
            "directories" to listOf("$id/$name".json()).json(), "resources" to emptyList<JsonValue>().json(),
            "dependsOn" to parents.map { it.json() }.json(), "dependencies" to dependencies.json())
        val target = obj("name" to platform.json(), "main" to "main".json(), "test" to "test".json(),
            "compilerOptions" to emptyList<JsonValue>().json())
        val targetSettings = if (kotest) JsonValue.Object(target.values + ("nativeTestSettings" to obj(
            "engine" to "kotest".json(), "kspVersion" to "2.3.12".json(),
            "processor" to "io.kotest:kotest-framework-symbol-processor:6.2.5".json()))) else target
        val fields = obj("id" to id.json(), "sourceSets" to listOf(source("shared", emptyList(), dependencies),
            source("main", listOf("shared"), emptyList()), source("test", emptyList(), testDependencies)).json(),
            "targets" to listOf(targetSettings).json(), "compilerPlugins" to plugins.json())
        return if (sql) JsonValue.Object(fields.values + ("sqlDelight" to SqlDelightSpec("shared",
            LogicalPath("$id/sql"), "fixture", "Database").toJson())) else fields
    }
    private fun model(vararg modules: JsonValue.Object): BspProjectModel {
        val project = parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "fixture".json(),
            "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
            "modules" to modules.toList().json()))
        return BspProjectModel(ProjectInspection(project, digest, digest, emptyList(), emptyMap(), emptyList()), root, LogicalPath("kvik.json"))
    }
    private fun BspProjectModel.target(module: String, scope: String = "main", platform: String = "jvm") =
        allTargets().single { it.module == module && it.compilation == scope && it.target == platform }
    private fun JsonValue.items(name: String) = objectValue().getValue(name).arrayValue()
    private fun owners(model: BspProjectModel, target: BspTarget, runtime: Boolean = false, classpath: Boolean = false) =
        model.generatedSourceOwners(target, runtime, classpath).map { "${it.module}:${it.target}:${it.compilation}" }.toSet()

    private fun platforms(module: JsonValue.Object, vararg names: String): JsonValue.Object {
        val target = module.values.getValue("targets").arrayValue().single().objectValue()
        return JsonValue.Object(module.values + ("targets" to names.map { name ->
            JsonValue.Object(target + ("name" to name.json()))
        }.json()))
    }

    @Test fun oneHostGeneratorOwnsAllConsumingMainTargetsWithoutAJvmTarget() {
        val model = model(platforms(module("db", sql = true), "js", "wasmJs", "macosArm64"))
        val action = "db:jvm:sqldelight"
        val expected = model.allTargets().filter { it.compilation == "main" }
        assertEquals(expected, model.generatedSourceTargets(action))
        assertEquals(expected, model.diagnosticTargets(action))
        assertNull(model.actionTarget(action))
        assertEquals(bspFileUri("$root/db/sql/Query.sq"), model.diagnosticUri(action, "db/sql/Query.sq"))
        val file = FileEntry(LogicalPath("generated/sqldelight/db/fixture/Database.kt"), digest)
        val source = BspGeneratedSources("$root/.kvik/bsp/first/generated", action, digest, TreeManifest(listOf(file)))
        for (owner in expected) assertTrue(model.replaceGeneratedSources(owner, listOf(source)))
        assertEquals(expected.map { it.uri }.toSet(), model.inverseSources(source.uri(file)).items("targets")
            .map { it.objectValue().getValue("uri").stringValue() }.toSet())
        for (owner in expected) {
            assertEquals(source.uri(file), model.sourceUri(owner, file.path.value))
            assertEquals(source.uri(file), model.diagnosticUri("db:${owner.target}:main", file.path.value))
            assertEquals(1, model.sources(listOf(owner.uri)).items("items").single().items("sources").count {
                it.objectValue().getValue("generated").booleanValue()
            })
        }
        // Test prerequisites prepare/clear the shared producer without changing main source ownership.
        for (test in model.allTargets().filter { it.compilation == "test" }) {
            assertEquals(expected.toSet(), model.generatedSourceOwners(test).toSet())
            assertNull(model.sourceUri(test, file.path.value))
            assertFails { model.replaceGeneratedSources(test, listOf(source)) }
        }
        val cleared = model.generatedSourceOwners(model.target("db", "test", "wasmJs"))
        for (owner in cleared) assertTrue(model.replaceGeneratedSources(owner, emptyList()))
        assertTrue(model.inverseSources(source.uri(file)).items("targets").isEmpty())
        assertTrue(expected.all { model.sourceUri(it, file.path.value) == null })
    }

    @Test fun recompiledMainSourcesKeepTestDiagnosticAttributionAndClearWithTheirOwner() {
        val model = model(platforms(module("db", sql = true), "jvm", "js", "wasmJs", "macosArm64"))
        val file = FileEntry(LogicalPath("generated/sqldelight/db/fixture/Database.kt"), digest)
        val source = BspGeneratedSources("$root/.kvik/bsp/first/generated", "db:jvm:sqldelight", digest, TreeManifest(listOf(file)))
        val state = BspDiagnostics()
        val ordinaryUri = bspFileUri("$root/db/main/Existing.kt")
        val message = obj("message" to "type mismatch".json())
        for (owner in model.generatedSourceTargets(source.producer)) {
            model.replaceGeneratedSources(owner, listOf(source))
            val test = model.target("db", "test", owner.target)
            val action = "db:${owner.target}:test-compile"
            assertEquals(listOf(test), model.diagnosticTargets(action))
            assertNull(model.sourceUri(test, file.path.value))
            assertEquals(if (owner.target == "jvm") null else source.uri(file), model.diagnosticUri(action, file.path.value))
            assertEquals(if (owner.target == "jvm") listOf(owner) else listOf(owner, test), model.generatedDiagnosticTargets(owner))
            assertNull(model.diagnosticUri(action, "generated/sqldelight/db/fixture/Absent.kt"))
            assertNull(model.diagnosticUri(action, "generated/sqldelight/db/../Other.kt"))
            assertEquals(listOf(test), model.generatedDiagnosticTargets(test))
            if (owner.target != "jvm") state.replace(mapOf(action to BspActionDiagnostics(test,
                mapOf(source.uri(file) to listOf(message), ordinaryUri to listOf(message)))))
        }
        for (owner in model.generatedSourceOwners(model.target("db", "test", "js"))) {
            for (consumer in model.generatedDiagnosticTargets(owner)) {
                val changes = state.clearDocuments(consumer, setOf(source.uri(file)))
                if (consumer.compilation == "test") {
                    val change = changes.single()
                    assertEquals(consumer.uri, change.values.getValue("buildTarget").objectValue().getValue("uri").stringValue())
                    assertEquals(source.uri(file), change.values.getValue("textDocument").objectValue().getValue("uri").stringValue())
                    assertTrue(change.values.getValue("diagnostics").arrayValue().isEmpty())
                } else assertTrue(changes.isEmpty())
            }
            model.replaceGeneratedSources(owner, emptyList())
        }
        for (platform in listOf("js", "wasmJs", "macosArm64")) {
            assertNull(model.diagnosticUri("db:$platform:test-compile", file.path.value))
        }
        val remaining = state.clear()
        assertEquals(3, remaining.size)
        assertTrue(remaining.all { it.values.getValue("textDocument").objectValue().getValue("uri").stringValue() == ordinaryUri })
        assertFails { model.generatedDiagnosticTargets(model.target("db").copy(module = "other")) }
    }

    @Test fun sharedGeneratorPrerequisitesIncludeAllConsumersButExcludeUnrelatedTargetClosures() {
        val db = platforms(module("db", sql = true), "jvm", "js", "wasmJs", "macosArm64")
        val unrelated = obj("name" to "unrelated".json(), "directories" to listOf("db/unrelated".json()).json(),
            "resources" to emptyList<JsonValue>().json(), "dependsOn" to emptyList<JsonValue>().json(),
            "dependencies" to emptyList<JsonValue>().json())
        val targets = db.values.getValue("targets").arrayValue().map {
            if (it.objectValue().getValue("name").stringValue() == "wasmJs")
                JsonValue.Object(it.objectValue() + ("main" to "unrelated".json())) else it
        }
        val partialDb = JsonValue.Object(db.values + mapOf("targets" to targets.json(),
            "sourceSets" to (db.values.getValue("sourceSets").arrayValue() + unrelated).json()))
        val app = platforms(module("app", dependencies = listOf(dependency("db"))), "jvm", "js", "wasmJs", "macosArm64")
        val model = model(partialDb, app)
        val expected = setOf("db:jvm:main", "db:js:main", "db:macosArm64:main")
        for (platform in listOf("jvm", "js", "macosArm64")) {
            assertEquals(expected, owners(model, model.target("app", platform = platform)))
            assertEquals(expected, owners(model, model.target("app", "test", platform)))
        }
        assertEquals(expected, owners(model, model.target("app"), classpath = true))
        assertEquals(expected, owners(model, model.target("db", "test"), classpath = true))
        assertTrue(owners(model, model.target("app", platform = "wasmJs")).isEmpty())
        assertTrue(owners(model, model.target("db", platform = "wasmJs")).isEmpty())
        assertEquals(setOf("jvm", "js", "macosArm64"), model.generatedSourceTargets("db:jvm:sqldelight").map { it.target }.toSet())
        assertTrue(model.generatedSourceTargets("db:js:sqldelight").isEmpty())
    }

    @Test fun sqlSourcesBelongToMainOnlyAndReplacementRemovesDeletedFiles() {
        val model = model(module("db", sql = true), module("plain"))
        val target = model.target("db")
        val first = FileEntry(LogicalPath("generated/sqldelight/db/fixture/Database.kt"), digest)
        val second = first.copy(path = LogicalPath("generated/sqldelight/db/fixture/Queries.kt"))
        val sources = BspGeneratedSources("$root/.kvik/bsp/first/generated", "db:jvm:sqldelight", digest,
            TreeManifest(listOf(first, second)))
        assertEquals(listOf(target), model.generatedSourceTargets(sources.producer))
        assertTrue(model.replaceGeneratedSources(target, listOf(sources)))
        for (file in sources.files.files) {
            assertEquals(sources.uri(file), model.sourceUri(target, file.path.value))
            assertEquals(listOf(obj("uri" to target.uri.json())), model.inverseSources(sources.uri(file)).items("targets"))
            assertNull(model.sourceUri(model.target("db", "test"), file.path.value))
        }
        assertEquals(2, model.sources(listOf(target.uri)).items("items").single().items("sources").count {
            it.objectValue().getValue("generated").booleanValue()
        })
        val replacement = sources.copy(directory = "$root/.kvik/bsp/second/generated", files = TreeManifest(listOf(first)))
        assertTrue(model.replaceGeneratedSources(target, listOf(replacement)))
        assertNull(model.sourceUri(target, second.path.value))
        assertTrue(model.inverseSources(sources.uri(first)).items("targets").isEmpty())
        assertTrue(model.replaceGeneratedSources(target, emptyList()))
        assertTrue(model.inverseSources(replacement.uri(first)).items("targets").isEmpty())
        assertNull(model.sourceUri(target, first.path.value))
        for (producer in listOf("plain:jvm:sqldelight", "db:macosArm64:sqldelight", "db:jvm:sqldelight-worker",
            "db:jvm:sqldelight:main", "missing:jvm:sqldelight", "db:jvm:ksp-test")) assertTrue(model.generatedSourceTargets(producer).isEmpty())
        assertFails { model.replaceGeneratedSources(model.target("db", "test"), listOf(sources)) }
    }

    @Test fun sqlDiagnosticsUseOnlyDeclaredSqlInputsAndMainOwner() {
        val model = model(module("db", sql = true), module("other", sql = true), module("plain"),
            module("native", platform = "macosArm64", kotest = true))
        val action = "db:jvm:sqldelight"
        assertEquals(listOf(model.target("db")), model.diagnosticTargets(action))
        assertNull(model.actionTarget(action))
        // New files need no reimport; diagnostic paths refer to the latest completed build.
        for (path in listOf("db/sql/New.sq", "db/sql/nested/café #%.sq")) {
            assertEquals(bspFileUri("$root/$path"), model.diagnosticUri(action, path))
            assertNull(model.sourceUri(model.target("db"), path))
            assertNull(model.diagnosticUri("db:jvm:main", path))
            assertNull(model.diagnosticUri("db:jvm:test-compile", path))
            assertNull(model.diagnosticUri("other:jvm:sqldelight", path))
        }
        for (path in listOf("db/sql", "db/sql.sq", "db/sql-other/Query.sq", "other/sql/Query.sq", "db/main/Query.sq",
            "db/sql/Query.kt", "db/sql/Query.sqm", "db/sql/Query.SQ", "db/sql/../outside.sq", "db/sql/./Query.sq",
            "db/sql//Query.sq", "/db/sql/Query.sq", "db\\sql\\Query.sq", "file:///db/sql/Query.sq", "db/sql/Query.sq?query",
            "db/sql/Query.sq#fragment", "db/sql/Query\u0000.sq", "db/sql/Query\uD800.sq")) {
            assertNull(model.diagnosticUri(action, path), path)
        }
        for (id in listOf("plain:jvm:sqldelight", "db:macosArm64:sqldelight", "db:jvm:sqldelight-worker",
            "db:jvm:sqldelight:main", "db:jvm:test", "native:macosArm64:ksp-test", "missing:jvm:sqldelight")) {
            assertTrue(model.diagnosticTargets(id).isEmpty(), id)
            assertNull(model.diagnosticUri(id, "db/sql/Query.sq"), id)
        }
        // A percent escape in a logical file name remains literal; it cannot become URI traversal.
        assertEquals(bspFileUri("$root/db/sql/%2e%2e/Query.sq"), model.diagnosticUri(action, "db/sql/%2e%2e/Query.sq"))
        assertTrue(model.sources(listOf(model.target("db").uri)).items("items").single().items("roots").none {
            it.stringValue() == bspFileUri("$root/db/sql/")
        })
    }

    @Test fun compilerDiagnosticOwnershipPreservesDeclaredAndGeneratedKotlinFiles() {
        val model = model(module("db", sql = true), module("other", sql = true))
        for (action in listOf("db:jvm:main", "db:jvm:test-compile", "db:jvm:test-link")) {
            assertEquals(listOf(model.actionTarget(action)), model.diagnosticTargets(action))
            assertEquals(bspFileUri("$root/db/main/New.kt"), model.diagnosticUri(action, "db/main/New.kt"))
            assertNull(model.diagnosticUri(action, "other/main/New.kt"))
            assertNull(model.diagnosticUri(action, "db/main/Query.sq"))
            assertNull(model.diagnosticUri(action, "db/main/../outside.kt"))
        }
        val file = FileEntry(LogicalPath("generated/sqldelight/db/fixture/Database.kt"), digest)
        val generated = BspGeneratedSources("$root/.kvik/bsp/run/generated", "db:jvm:sqldelight", digest, TreeManifest(listOf(file)))
        model.replaceGeneratedSources(model.target("db"), listOf(generated))
        assertEquals(generated.uri(file), model.diagnosticUri("db:jvm:main", file.path.value))
        assertNull(model.diagnosticUri("db:jvm:test-compile", file.path.value))
        assertNull(model.diagnosticUri("db:jvm:sqldelight", file.path.value))
    }

    @Test fun prerequisiteTraversalIncludesPrivateCompilationEdgesAndSeparatesRuntimeAndTestScopes() {
        val model = model(module("db", sql = true), module("runtimeDb", sql = true), module("testDb", sql = true),
            module("unused", sql = true), module("mid", dependencies = listOf(dependency("db"))),
            module("app", dependencies = listOf(dependency("mid"), dependency("runtimeDb", runtime = true)),
                testDependencies = listOf(dependency("testDb"))))
        assertEquals(setOf("db:jvm:main"), owners(model, model.target("app")))
        assertEquals(setOf("db:jvm:main", "runtimeDb:jvm:main"), owners(model, model.target("app"), runtime = true))
        assertEquals(setOf("db:jvm:main", "testDb:jvm:main"), owners(model, model.target("app", "test")))
        assertEquals(setOf("db:jvm:main", "testDb:jvm:main", "runtimeDb:jvm:main"),
            owners(model, model.target("app", "test"), runtime = true))
        assertEquals(setOf("db:jvm:main"), owners(model, model.target("db", "test")))
        assertFails { model.generatedSourceOwners(model.target("db").copy(module = "app")) }
    }

    @Test fun workspacePluginsBringTheirRuntimeAndNestedPluginProducersWithCorrectScopes() {
        val model = model(module("nested", sql = true), module("pluginRuntime", sql = true),
            module("mainPlugin", sql = true, dependencies = listOf(dependency("pluginRuntime", runtime = true)),
                plugins = listOf(plugin("nested"))), module("testPlugin", sql = true),
            module("library", plugins = listOf(plugin("mainPlugin"))),
            module("app", dependencies = listOf(dependency("library")), plugins = listOf(plugin("testPlugin", scope = "test"))))
        val mainOwners = setOf("mainPlugin:jvm:main", "pluginRuntime:jvm:main", "nested:jvm:main")
        assertEquals(mainOwners, owners(model, model.target("app")))
        assertEquals(mainOwners + "testPlugin:jvm:main", owners(model, model.target("app", "test")))
        assertEquals(mainOwners, owners(model, model.target("app", "test"), classpath = true))
    }

    @Test fun classpathPreparationOnlyClearsProducersActuallyCompiled() {
        val model = model(module("dependency", sql = true), module("plugin", sql = true),
            module("db", sql = true, dependencies = listOf(dependency("dependency")), plugins = listOf(plugin("plugin"))))
        assertEquals(setOf("dependency:jvm:main"), owners(model, model.target("db"), classpath = true))
        assertEquals(setOf("db:jvm:main", "dependency:jvm:main", "plugin:jvm:main"),
            owners(model, model.target("db", "test"), classpath = true))
        assertFails { model.generatedSourceOwners(model.target("db"), runtime = true, classpath = true) }
    }

    @Test fun nativeTestOwnersIncludeKspAndJvmHostPluginGenerators() {
        val model = model(module("host", sql = true), module("native", platform = "macosArm64", kotest = true,
            plugins = listOf(plugin("host", target = "macosArm64", scope = "test"))))
        assertTrue(owners(model, model.target("native", platform = "macosArm64")).isEmpty())
        assertEquals(setOf("native:macosArm64:test", "host:jvm:main"),
            owners(model, model.target("native", "test", "macosArm64")))
        assertFails { model.generatedSourceOwners(model.target("native", platform = "macosArm64"), classpath = true) }
    }
}
