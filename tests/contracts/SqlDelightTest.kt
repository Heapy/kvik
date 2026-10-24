package kvik.core

import kotlin.test.*

class SqlDelightTest {
    private val settings = SqlDelightSpec("main", LogicalPath("db/sql"), "sample.db", "Database")
    private fun module(id: String = "db", dependency: String? = null) = ModuleSpec(id,
        listOf(SourceSet("main", listOf(LogicalPath("$id/src")), emptyList(), emptyList(), listOfNotNull(dependency?.let { Dependency.Module(it) })),
            SourceSet("test", listOf(LogicalPath("$id/test")), emptyList(), emptyList(), emptyList())),
        listOf(TargetSpec("jvm", "main", "test", emptyList())), emptyList(), sqlDelight = if (id == "db") settings else null)
    private fun configuration(vararg modules: ModuleSpec) = ProjectConfiguration(ProjectSpec("sql",
        listOf(Repository("central", "https://repo.maven.apache.org/maven2")), modules.toList()), obj())

    @Test fun validatesMainConsumersVersionsAndIdentifiers() {
        assertEquals(settings, SqlDelightSpec.fromJson(settings.toJson()))
        assertFails { settings.copy(version = "2.3.1") }
        for (name in listOf("", "foo-bar", "class", "_", "x\n")) {
            assertFails { settings.copy(className = name) }
            assertFails { settings.copy(packageName = "sample.$name") }
        }
        assertFails { module().copy(sqlDelight = settings.copy(sourceSet = "absent")) }
        assertFails { module().copy(sqlDelight = settings.copy(sourceSet = "test")) }
        for (target in listOf("macosArm64", "linuxX64", "iosArm64", "iosSimulatorArm64", "js", "wasmJs")) {
            module().copy(targets = listOf(TargetSpec(target, "main", null, emptyList())))
            module().copy(targets = module().targets + TargetSpec(target, "main", null, emptyList()))
        }
        assertFails { module().copy(sourceSets = module().sourceSets +
            SourceSet("native", emptyList(), emptyList(), emptyList(), emptyList()) +
            SourceSet("nativeTest", emptyList(), emptyList(), listOf("main"), emptyList()),
            targets = module().targets + TargetSpec("macosArm64", "native", "nativeTest", emptyList())) }
        val separateNative = SourceSet("native", emptyList(), emptyList(), emptyList(), emptyList())
        module().copy(sourceSets = module().sourceSets + separateNative,
            targets = module().targets + TargetSpec("macosArm64", "native", null, emptyList()))
    }

    @Test fun sharedSqlInputsSelectEveryConsumingTarget() {
        val targets = listOf("macosArm64", "js", "wasmJs")
        val data = module().copy(targets = targets.map { TargetSpec(it, "main", "test", emptyList()) })
        val unrelated = module("unrelated").copy(targets = data.targets)
        val config = configuration(data, unrelated)
        val checks = listOf("db", "unrelated").flatMap { id -> targets.flatMap { target ->
            listOf("$id:$target:build", "$id:$target:test")
        } }.toSet()
        val changed = LogicalPath("db/sql/NewQuery.sq")
        val selection = selectAffectedChecks(config, setOf("db", "unrelated"), targets.toSet(),
            setOf("build", "test"), setOf(changed), checks)
        assertEquals(checks.filter { it.startsWith("db:") }.toSet(), selection.selected)
        val tags = projectFileTagRules(config, LogicalPath("kvik.json"), null).tags(changed)
        assertTrue(targets.all { "target:$it" in tags })
    }

    @Test fun sqlChangesSelectConsumersWithoutSelectingUnrelatedModules() {
        val config = configuration(module(), module("app", "db"), module("unrelated"))
        val checks = setOf("db", "app", "unrelated").flatMap { id -> listOf("$id:jvm:build", "$id:jvm:test") }.toSet()
        val changed = LogicalPath("db/sql/NewQuery.sq")
        val selection = selectAffectedChecks(config, setOf("db", "app", "unrelated"), setOf("jvm"),
            setOf("build", "test"), setOf(changed), checks)
        assertEquals(checks.filterNot { it.startsWith("unrelated:") }.toSet(), selection.selected)
        assertTrue(selection.fullReasons.isEmpty())
        val tags = projectFileTagRules(config, LogicalPath("kvik.json"), null).tags(changed)
        assertTrue(tags.containsAll(setOf("module:db", "sourceSet:main", "target:jvm", "scope:production", "kind:generator-input", "generator:sqldelight")))
    }
}
