package kvik.core

import kotlin.test.*

class ScriptGeneratorsTest {
    private fun module(id: String, dependency: String? = null, plugin: String? = null,
                       targets: List<String> = listOf("jvm")): JsonValue = obj(
        "id" to id.json(), "sourceSets" to listOf(
            obj("name" to "main".json(), "directories" to listOf("$id/src".json()).json(), "resources" to emptyList<JsonValue>().json(),
                "dependsOn" to emptyList<JsonValue>().json(), "dependencies" to listOfNotNull(dependency?.let {
                    obj("module" to it.json(), "scope" to "runtime".json())
                }).json()),
            obj("name" to "test".json(), "directories" to listOf("$id/test".json()).json(), "resources" to emptyList<JsonValue>().json(),
                "dependsOn" to emptyList<JsonValue>().json(), "dependencies" to emptyList<JsonValue>().json()),
            obj("name" to "unused".json(), "directories" to emptyList<JsonValue>().json(), "resources" to emptyList<JsonValue>().json(),
                "dependsOn" to emptyList<JsonValue>().json(), "dependencies" to emptyList<JsonValue>().json()),
        ).json(), "targets" to targets.map { target -> obj("name" to target.json(), "main" to "main".json(),
            "test" to "test".json(), "compilerOptions" to emptyList<JsonValue>().json()) }.json(),
        "compilerPlugins" to listOfNotNull(plugin?.let { obj("module" to it.json(), "targets" to listOf("jvm".json()).json(),
            "scopes" to listOf("main".json()).json(), "options" to emptyList<JsonValue>().json(), "inputs" to emptyList<JsonValue>().json(),
            "id" to "test.plugin".json(), "compilerVersion" to "2.4.20".json()) }).json())

    private fun generator(sourceSet: String = "main", dependency: String? = null) = ScriptSpec(
        listOf(LogicalPath("scripts/Generate.kt")), resources = listOf(LogicalPath("templates")),
        dependencies = listOfNotNull(dependency?.let { ScriptDependencyReference.Module(it, DependencyScopeKind.RUNTIME) }),
        generator = ScriptGeneratorSpec("library", sourceSet, listOf(LogicalPath("schema")), listOf("with spaces", "")))

    private fun config(script: ScriptSpec = generator(), modules: List<JsonValue> = listOf(module("library"), module("app", "library"), module("unrelated")),
                       extraScripts: Map<String, ScriptSpec> = emptyMap()): ProjectConfiguration = parseProjectConfiguration(obj(
        "schemaVersion" to 1.json(), "name" to "test".json(), "repositories" to listOf(obj("id" to "central".json(),
            "url" to "https://repo.maven.apache.org/maven2".json())).json(), "modules" to modules.json(),
        "scripts" to JsonValue.Object((extraScripts + ("generate" to script)).mapValues { it.value.toJson() })))

    @Test fun declarationRoundTripAndBounds() {
        val script = generator()
        assertEquals(script, ScriptSpec.fromJson(script.toJson()))
        assertFalse("generator" in script.copy(generator = null).toJson().values)
        assertFails { script.generator!!.copy(inputs = listOf(LogicalPath("schema"), LogicalPath("schema/child"))) }
        assertFails { script.generator!!.copy(inputs = listOf(LogicalPath("schema"), LogicalPath("SCHEMA"))) }
        assertFails { script.generator!!.copy(arguments = listOf("bad\u0000arg")) }
        assertFails { script.generator!!.copy(arguments = List(257) { "arg" }) }
        assertFails { script.generator!!.copy(module = "../bad") }
        assertFails { config(script.copy(generator = script.generator!!.copy(module = "absent"))) }
        assertFails { config(generator("absent")) }
        assertFails { config(generator("unused")) }
        assertFails { parseStandaloneScriptConfiguration(obj("schemaVersion" to 1.json(), "name" to "standalone".json(),
            "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
            "script" to script.toJson())) }
    }

    @Test fun nativeJsAndTestOnlyAttachmentsAreAllowed() {
        for (target in listOf("macosArm64", "js", "wasmJs")) for (scope in listOf("main", "test")) {
            val project = config(generator(scope), listOf(module("library", targets = listOf(target)))).project
            assertEquals(listOf("generate"), scriptGenerators(project, "library", setOf(scope)).map { it.name })
            assertTrue(scriptGenerators(project, "library", setOf(if (scope == "main") "test" else "main")).isEmpty())
        }
    }

    @Test fun commonSourceAttachmentSelectsBothNativeAndJvmConsumers() {
        val targets = listOf("jvm", "macosArm64")
        val original = module("library", targets = targets).objectValue()
        val sources = original.getValue("sourceSets").arrayValue().map { source ->
            val fields = source.objectValue()
            if (fields.getValue("name").stringValue() == "main") JsonValue.Object(fields +
                ("dependsOn" to listOf("common".json()).json())) else source
        } + obj("name" to "common".json(), "directories" to emptyList<JsonValue>().json(),
            "resources" to emptyList<JsonValue>().json(), "dependsOn" to emptyList<JsonValue>().json(), "dependencies" to emptyList<JsonValue>().json())
        val configuration = config(generator("common"), listOf(JsonValue.Object(original + ("sourceSets" to sources.json()))))
        val checks = targets.map { "library:$it:build" }.toSet()
        val selection = selectAffectedChecks(configuration, setOf("library"), targets.toSet(), setOf("build"),
            setOf(LogicalPath("schema/changed.json")), checks)
        assertEquals(checks, selection.selected)
        assertTrue(selection.fullReasons.isEmpty())
    }

    @Test fun runtimeAndCompilerPluginPrerequisitesCannotFormGeneratorCycles() {
        assertFailsWith<IllegalArgumentException> { config(generator(dependency = "library")) }
        assertFailsWith<IllegalArgumentException> { config(generator(dependency = "helper"), listOf(module("library"), module("helper", "library"))) }
        val pluginScript = generator().copy(compilerPlugins = listOf(CompilerPluginSpec("helper", null, setOf("jvm"), setOf("script"),
            emptyList(), emptyList(), "test.plugin", "2.4.20")))
        assertFailsWith<IllegalArgumentException> { config(pluginScript, listOf(module("library"), module("helper", plugin = "library"))) }
        val nested = generator().copy(generator = ScriptGeneratorSpec("helper", "main"),
            dependencies = listOf(ScriptDependencyReference.Module("library")))
        val failure = assertFailsWith<IllegalArgumentException> { config(generator(dependency = "helper"),
            listOf(module("library"), module("helper")), mapOf("nested" to nested)) }
        assertContains(failure.message!!, "generator prerequisite cycle")
    }

    @Test fun sourceResourcePluginAndDataChangesSelectOnlyConsumersAndDownstreamChecks() {
        val script = generator().copy(compilerPlugins = listOf(CompilerPluginSpec("helper", null, setOf("jvm"), setOf("script"),
            emptyList(), listOf(LogicalPath("plugin/config.txt")), "test.plugin", "2.4.20")))
        val configuration = config(script, listOf(module("library"), module("app", "library"), module("helper"), module("unrelated")))
        val modules = setOf("library", "app", "unrelated")
        val checks = modules.flatMap { listOf("$it:jvm:build", "$it:jvm:test") }.toSet()
        for (file in listOf("scripts/Generate.kt", "templates/name.txt", "schema/api.json", "plugin/config.txt", "helper/src/Plugin.kt")) {
            val selected = selectAffectedChecks(configuration, modules, setOf("jvm"), setOf("build", "test"), setOf(LogicalPath(file)), checks)
            assertEquals(checks.filterNot { it.startsWith("unrelated:") }.toSet(), selected.selected, file)
            assertTrue(selected.fullReasons.isEmpty(), file)
        }
        val unchanged = selectAffectedChecks(configuration, modules, setOf("jvm"), setOf("build", "test"), emptySet(), checks)
        assertEquals(checks.filterNot { it.startsWith("unrelated:") }.toSet(), unchanged.selected)
        assertTrue(unchanged.reasons.values.all { "generator-fresh:generate" in it })
        val tags = projectFileTagRules(configuration, LogicalPath("kvik.json"), null).tags(LogicalPath("schema/api.json"))
        assertTrue(tags.containsAll(setOf("module:library", "source-set:main", "kind:generator-input", "generator:script")))
        for (sourceSet in listOf("main", "test")) {
            val scoped = config(script.copy(generator = script.generator!!.copy(sourceSet = sourceSet)),
                listOf(module("library"), module("helper")))
            val pluginTags = projectFileTagRules(scoped, LogicalPath("kvik.json"), null).tags(LogicalPath("plugin/config.txt"))
            assertTrue(pluginTags.containsAll(setOf("module:library", "source-set:$sourceSet", "kind:generator-input", "kind:plugin-input", "generator:script")))
            assertEquals(sourceSet == "main", roleFilePredicate("production").matches(pluginTags))
            assertEquals(sourceSet == "test", roleFilePredicate("tests").matches(pluginTags))
        }
    }

    @Test fun testGeneratorDoesNotSelectProductionOrDownstreamChecks() {
        val configuration = config(generator("test"))
        val modules = setOf("library", "app", "unrelated")
        val checks = modules.flatMap { listOf("$it:jvm:build", "$it:jvm:test") }.toSet()
        assertEquals(setOf("library:jvm:test"), selectAffectedChecks(configuration, modules, setOf("jvm"), setOf("build", "test"),
            setOf(LogicalPath("schema/api.json")), checks).selected)
    }
}
