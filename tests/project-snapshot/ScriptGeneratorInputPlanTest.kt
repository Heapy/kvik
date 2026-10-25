package kvik.projectsnapshot

import kvik.core.*
import kotlin.test.*

class ScriptGeneratorInputPlanTest {
    @Test fun namedScriptCapturesNestedGeneratorInputsAndPrerequisitesWithoutUnrelatedScripts() {
        fun module(id: String) = ModuleSpec(id, listOf(SourceSet("main", listOf(LogicalPath("$id/src")), emptyList(), emptyList(), emptyList())),
            listOf(TargetSpec("jvm", "main", null, emptyList())), emptyList())
        fun script(name: String, dependency: String? = null, generator: ScriptGeneratorSpec? = null) = ScriptSpec(
            listOf(LogicalPath("scripts/$name.kt")), dependencies = listOfNotNull(dependency?.let { ScriptDependencyReference.Module(it) }), generator = generator)
        val project = ProjectSpec("fixture", listOf(Repository("central", "https://repo.maven.apache.org/maven2")),
            listOf(module("library"), module("helper"), module("nested")), scripts = mapOf(
                "probe" to script("Probe", "library"),
                "generate" to script("Generate", "helper", ScriptGeneratorSpec("library", "main", listOf(LogicalPath("schema")))),
                "nested" to script("Nested", "nested", ScriptGeneratorSpec("helper", "main", listOf(LogicalPath("templates")))),
                "unrelated" to script("Unrelated")))
        val configuration = ProjectConfiguration(project, obj())
        val plan = projectInputPlan(configuration, SnapshotSelection.Script("probe"))
        assertEquals(setOf("library/src", "helper/src", "nested/src"), plan.directories.map { it.value }.toSet())
        assertEquals(setOf("scripts/Probe.kt", "scripts/Generate.kt", "scripts/Nested.kt", "schema", "templates"), plan.scriptRoots.map { it.value }.toSet())
        assertTrue(projectInputPlan(configuration).scriptRoots.contains(LogicalPath("schema")))
    }
}
