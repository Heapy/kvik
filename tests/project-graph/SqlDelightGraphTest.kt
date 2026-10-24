package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kvik.compiler.jvm.*
import kvik.compiler.sqldelight.*
import kotlin.test.*

class SqlDelightGraphTest {
    private fun digest(value: String) = Sha256.digest(value.encodeToByteArray())
    private val tools = mapOf("jvm" to listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith(::digest))
    private val settings = SqlDelightSpec("main", LogicalPath("data/sql"), "fixture.database", "Database")
    private fun configuration(spec: SqlDelightSpec = settings): ProjectConfiguration {
        fun module(id: String, sql: SqlDelightSpec?) = obj("id" to id.json(),
            "sourceSets" to listOf(obj("name" to "main".json(), "directories" to listOf("$id/src".json()).json(),
                "resources" to emptyList<JsonValue>().json(), "dependsOn" to emptyList<JsonValue>().json(),
                "dependencies" to listOf(obj("coordinate" to "g:runtime:1".json())).json())).json(),
            "targets" to listOf(obj("name" to "jvm".json(), "main" to "main".json(), "test" to JsonValue.Null,
                "compilerOptions" to emptyList<JsonValue>().json())).json(), "compilerPlugins" to emptyList<JsonValue>().json())
            .let { if (sql == null) it else JsonValue.Object(it.values + ("sqlDelight" to sql.toJson())) }
        return parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "sqlFixture".json(),
            "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
            "modules" to listOf(module("data", spec), module("other", spec.copy(sourceDirectory = LogicalPath("other/sql"))), module("plain", null)).json()))
    }
    private fun files(sql: String = "schema", includeSql: Boolean = true, includeKotlin: Boolean = true) = TreeManifest(buildList {
        for (module in listOf("data", "other", "plain")) {
            if (includeKotlin || module != "data") add(FileEntry(LogicalPath("$module/src/Main.kt"), digest("$module Kotlin")))
            if (module != "plain" && (includeSql || module != "data")) add(FileEntry(LogicalPath("$module/sql/fixture/Tables.sq"), digest(if (module == "data") sql else "other SQL")))
        }
    })
    private fun graph(configuration: ProjectConfiguration = configuration(), files: TreeManifest = files(), changedTool: Boolean = false): ProjectActionGraph {
        val effective = effectiveConfiguration(configuration)
        val lock = DependencyLock(effective.digest, effective.requests.map { request ->
            val artifact = LockedArtifact(request.coordinate, request.variant.usage, "central",
                "https://repo.maven.apache.org/maven2/${request.coordinate.name}.jar", digest(request.coordinate.toString() +
                    if (changedTool && request.domain.purpose == "sqldelight-compiler") " changed" else ""))
            ScopedSelection(request.domain, LockedSelection(request.coordinate, request.variant, listOf(artifact),
                listOf(MetadataIdentity("https://repo.maven.apache.org/maven2/${request.coordinate.name}.pom", digest("metadata")))))
        })
        return projectActionGraph(effective, lock, files, digest("runtime"), tools,
            ProjectGraphRequest(setOf("data", "other", "plain"), setOf("jvm"), ProjectMode.BUILD))
    }
    private fun resolve(graph: ProjectActionGraph, emptyGeneration: Boolean = false, invalidGeneration: Boolean = false): Map<String, ActionSpec> {
        val results = mutableMapOf<String, ActionResult>()
        return graph.nodes.associate { node ->
            val action = node.resolve(results.filterKeys { it in node.dependencies })
            val output = when {
                node.id.endsWith(":sqldelight") -> if (emptyGeneration && node.id.startsWith("data:")) emptyList() else {
                    val prefix = if (invalidGeneration) "other-output" else "generated/sqldelight/${node.id.substringBefore(':')}"
                    listOf(FileEntry(LogicalPath("$prefix/Database.kt"), digest(action.key(Sha256).toString())))
                }
                else -> listOf(FileEntry(LogicalPath(JvmCompilation.fromJson(action.parameters).output.value + if (node.id.endsWith(":sqldelight-worker")) "/kvik/sqldelight/Generate.class" else "/Main.class"), digest(action.key(Sha256).toString())))
            }
            results[node.id] = ActionResult(node.id, action.key(Sha256), ActionStatus.EXECUTED, TreeManifest(output), 0)
            node.id to action
        }
    }

    @Test fun generatedSourceHasExplicitPrerequisiteAndToolIsolation() {
        val graph = graph()
        assertEquals(setOf("data:jvm:sqldelight"), graph.nodes.single { it.id == "data:jvm:main" }.dependencies)
        assertEquals(setOf("data:jvm:sqldelight-worker"), graph.nodes.single { it.id == "data:jvm:sqldelight" }.dependencies)
        val actions = resolve(graph)
        val compile = JvmCompilation.fromJson(actions.getValue("data:jvm:main").parameters)
        assertTrue(LogicalPath("generated/sqldelight/data/Database.kt") in compile.sources)
        assertTrue(compile.classpath.size == 1)
        assertFalse(actions.getValue("data:jvm:main").inputs.files.any { it.path.value.endsWith(".sq") || it.path.value.startsWith("tools/") })
        val generation = SqlDelightGeneration.fromJson(actions.getValue("data:jvm:sqldelight").parameters)
        assertEquals(listOf(LogicalPath("data/sql/fixture/Tables.sq")), generation.sources)
        assertEquals(LogicalPath("tools/sqldelight-worker"), generation.worker)
        assertEquals(actions.getValue("data:jvm:sqldelight-worker").key(Sha256), actions.getValue("other:jvm:sqldelight-worker").key(Sha256))
    }

    @Test fun sqlOptionsAndToolChangesInvalidateOnlyTheirConsumers() {
        val initial = resolve(graph())
        val edited = resolve(graph(files = files(sql = "edited SQL")))
        val options = resolve(graph(configuration = configuration(settings.copy(packageName = "fixture.changed"))))
        for (changed in listOf(edited, options)) {
            assertNotEquals(initial.getValue("data:jvm:sqldelight").key(Sha256), changed.getValue("data:jvm:sqldelight").key(Sha256))
            assertNotEquals(initial.getValue("data:jvm:main").key(Sha256), changed.getValue("data:jvm:main").key(Sha256))
            assertEquals(initial.getValue("data:jvm:sqldelight-worker").key(Sha256), changed.getValue("data:jvm:sqldelight-worker").key(Sha256))
            assertEquals(initial.getValue("plain:jvm:main").key(Sha256), changed.getValue("plain:jvm:main").key(Sha256))
        }
        assertEquals(initial.getValue("other:jvm:main").key(Sha256), edited.getValue("other:jvm:main").key(Sha256))
        val changedTool = resolve(graph(changedTool = true))
        assertNotEquals(initial.getValue("data:jvm:sqldelight-worker").key(Sha256), changedTool.getValue("data:jvm:sqldelight-worker").key(Sha256))
        assertNotEquals(initial.getValue("data:jvm:sqldelight").key(Sha256), changedTool.getValue("data:jvm:sqldelight").key(Sha256))
        assertEquals(initial.getValue("plain:jvm:main").key(Sha256), changedTool.getValue("plain:jvm:main").key(Sha256))
    }

    @Test fun supportsGeneratedOnlySourcesAndRemovesDeletedSqlOutputs() {
        val generatedOnly = resolve(graph(files = files(includeKotlin = false)))
        assertEquals(listOf(LogicalPath("generated/sqldelight/data/Database.kt")), JvmCompilation.fromJson(generatedOnly.getValue("data:jvm:main").parameters).sources)
        val deleted = resolve(graph(files = files(includeSql = false)), emptyGeneration = true)
        assertEquals(listOf(LogicalPath("src/data/src/Main.kt")), JvmCompilation.fromJson(deleted.getValue("data:jvm:main").parameters).sources)
        assertTrue(SqlDelightGeneration.fromJson(deleted.getValue("data:jvm:sqldelight").parameters).sources.isEmpty())
        assertFailsWith<IllegalArgumentException> { resolve(graph(files = files(includeSql = false, includeKotlin = false)), emptyGeneration = true) }
    }

    @Test fun rejectsUnownedOutputsAndUnsupportedSqlInputs() {
        assertFailsWith<IllegalArgumentException> { resolve(graph(), invalidGeneration = true) }
        for (extension in listOf("sqm", "db", "txt")) assertFailsWith<IllegalArgumentException> {
            graph(files = TreeManifest(files().files + FileEntry(LogicalPath("data/sql/1.$extension"), digest("unsupported"))))
        }
        val graph = graph()
        val node = graph.nodes.single { it.id == "data:jvm:main" }
        assertFailsWith<IllegalArgumentException> { node.resolve(mapOf("data:jvm:sqldelight" to ActionResult("data:jvm:sqldelight", digest("key"), ActionStatus.FAILED, TreeManifest(emptyList()), 1))) }
    }
}
