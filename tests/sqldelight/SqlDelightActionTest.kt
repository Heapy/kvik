package kvik.compiler.sqldelight

import kvik.core.*
import kotlin.test.*

class SqlDelightActionTest {
    private val settings = SqlDelightSpec("main", LogicalPath("database/sql"), "example.db", "Database")
    private val source = LogicalPath("database/sql/example/db/Item.sq")
    private val jar = LogicalPath("tools/compiler.jar")
    private val worker = LogicalPath("tools/sqldelight-worker")
    private val workerClass = LogicalPath("tools/sqldelight-worker/kvik/sqldelight/Generate.class")
    private val digest = Sha256.digest(byteArrayOf(1))
    private val generation = SqlDelightGeneration("database", settings, listOf(source), listOf(jar), worker)
    private val inputs = TreeManifest(listOf(source, jar, workerClass).map { FileEntry(it, digest) })
    private val tools = setOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith { digest }

    @Test fun generationKeysTrackSqlSettingsAndBothWorkerAndToolInputs() {
        val action = sqlDelightAction(generation, inputs, digest, tools)
        assertEquals(generation, SqlDelightGeneration.fromJson(generation.toJson()))
        assertEquals(CachePolicy.LOCAL, action.cachePolicy)
        assertEquals(ActionKind.GENERATE, action.kind)
        assertEquals(listOf(LogicalPath("generated/sqldelight/database")), action.outputs)
        for (path in listOf(source, jar, workerClass)) {
            val changed = TreeManifest(inputs.files.map { if (it.path == path) it.copy(digest = Sha256.digest(byteArrayOf(2))) else it })
            assertNotEquals(action.key(Sha256), sqlDelightAction(generation, changed, digest, tools).key(Sha256))
        }
        assertNotEquals(action.key(Sha256), sqlDelightAction(generation.copy(settings = settings.copy(className = "Changed")), inputs, digest, tools).key(Sha256))
    }

    @Test fun inputsCannotIntroduceUndeclaredSqlOrApplicationClasses() {
        assertFails { generation.copy(sources = listOf(LogicalPath("database/sql/Migration.sqm"))) }
        assertFails { generation.copy(sources = listOf(LogicalPath("elsewhere/Item.sq"))) }
        assertFails { generation.copy(classpath = emptyList()) }
        assertFails { sqlDelightAction(generation, TreeManifest(inputs.files.filter { it.path != workerClass }), digest, tools) }
        assertFails { sqlDelightAction(generation, TreeManifest(inputs.files + FileEntry(LogicalPath("consumer/Main.class"), digest)), digest, tools) }
        assertFails { sqlDelightAction(generation, inputs, digest, tools - "jdk") }
        assertFails { SqlDelightGeneration.fromJson(JsonValue.Object(generation.toJson().values + ("migration" to true.json()))) }
    }

    @Test fun emptySqlInventoryIsAnExplicitCacheableGeneration() {
        val empty = generation.copy(sources = emptyList())
        val action = sqlDelightAction(empty, TreeManifest(inputs.files.filter { it.path != source }), digest, tools)
        assertEquals(emptyList(), action.outputs)
        assertEquals(empty, SqlDelightGeneration.fromJson(action.parameters))
        assertEquals(CachePolicy.LOCAL, action.cachePolicy)
    }
}
