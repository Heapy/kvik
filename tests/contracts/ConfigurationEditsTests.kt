package kvik.core

import kotlin.test.*

class ConfigurationEditsTests {
    private fun target(name: String = "jvm", options: List<String> = emptyList()): JsonValue = obj(
        "name" to name.json(), "main" to "main".json(), "test" to "test".json(), "compilerOptions" to options.map { it.json() }.json())
    private fun source(name: String, dependencies: List<JsonValue> = emptyList()): JsonValue = obj(
        "name" to name.json(), "directories" to listOf("src/$name".json()).json(),
        "resources" to listOf("resources/$name".json()).json(), "dependsOn" to emptyList<JsonValue>().json(), "dependencies" to dependencies.json())
    private fun module(id: String, dependencies: List<JsonValue> = emptyList(), targets: List<JsonValue> = listOf(target()), plugins: List<JsonValue> = emptyList()): JsonValue = obj(
        "id" to id.json(), "sourceSets" to listOf(source("main", dependencies), source("test")).json(),
        "targets" to targets.json(), "compilerPlugins" to plugins.json())
    private fun document(modules: List<JsonValue>): JsonValue.Object = obj(
        "schemaVersion" to 1.json(), "name" to "example".json(), "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.example.test/maven".json())).json(),
        "modules" to modules.json(),
        "dependencyCatalog" to obj("json" to "example:json:1".json()),
        "dependencyProfiles" to obj("tools" to obj("dependencies" to listOf(obj("catalog" to "json".json())).json())),
        "scripts" to obj("maintain" to obj("sources" to listOf("scripts/maintain.kt".json()).json(), "profiles" to listOf("tools".json()).json(),
            "environment" to obj("MODE" to "maintain".json()))),
        "fileTags" to obj("docs/architecture.md" to listOf("manual:architecture".json()).json()),
    )
    private fun fixture(): ProjectConfiguration {
        val plugin = obj("module" to "plugin".json(), "id" to "example.plugin".json(), "compilerVersion" to "2.4.20".json(),
            "targets" to listOf("jvm".json()).json(), "scopes" to listOf("main".json()).json(), "options" to listOf("mode=strict".json()).json(),
            "inputs" to listOf("plugin/schema.json".json()).json(),
            "pathOptions" to listOf(obj("name" to "schema".json(), "kind" to "input-file".json(), "path" to "plugin/schema.json".json()),
                obj("name" to "output".json(), "kind" to "output-directory".json(), "path" to "generated/plugin".json())).json(),
            "outputs" to listOf("generated/plugin".json()).json())
        val jvm = JsonValue.Object(target().objectValue() + mapOf(
            "testSettings" to obj("classes" to listOf("example.SampleTest".json()).json(), "engines" to listOf("kotest".json(), "junit".json()).json()),
            "testCompilerOptions" to listOf("-Werror".json()).json(), "abi" to obj("baseline" to "api/app.txt".json())))
        return parseProjectConfiguration(document(listOf(
            module("app", listOf(obj("coordinate" to "example:api:1".json(), "scope" to "compile".json(), "exported" to true.json()),
                obj("coordinate" to "example:defaults:1".json()), obj("module" to "library".json())), listOf(jvm), listOf(plugin)),
            module("library", targets = listOf(target(), target("macosArm64"))), module("plugin"),
        )))
    }
    private fun batch(vararg operations: JsonValue): JsonValue = obj("schemaVersion" to 1.json(), "operations" to operations.toList().json())
    private fun edit(op: String, vararg fields: Pair<String, JsonValue>): JsonValue = obj("op" to op.json(), "module" to "app".json(), *fields)
    private fun dependencyEdit(op: String, vararg fields: Pair<String, JsonValue>): JsonValue = edit(op, "sourceSet" to "main".json(), *fields)
    private fun modules(config: ProjectConfiguration) = config.document.values.getValue("modules").arrayValue()
    private fun authoredModule(config: ProjectConfiguration, id: String = "app") = modules(config).single { it.objectValue().getValue("id").stringValue() == id }.objectValue()
    private fun dependencies(config: ProjectConfiguration): List<JsonValue> = authoredModule(config).getValue("sourceSets").arrayValue().single {
        it.objectValue().getValue("name").stringValue() == "main"
    }.objectValue().getValue("dependencies").arrayValue()

    @Test fun versionUpdatePreservesExportScopeOmittedDefaultsAndEveryUnrelatedAuthoringField() {
        val original = fixture()
        val before = original.document.canonical()
        val result = editProjectConfiguration(original, batch(
            dependencyEdit("set-dependency-version", "package" to "example:api".json(), "version" to "2".json()),
            dependencyEdit("set-dependency-version", "package" to "example:defaults".json(), "version" to "3".json()),
        ))
        assertEquals(obj("coordinate" to "example:api:2".json(), "scope" to "compile".json(), "exported" to true.json()), dependencies(result)[0])
        assertEquals(obj("coordinate" to "example:defaults:3".json()), dependencies(result)[1])
        for (name in listOf("scripts", "dependencyProfiles", "dependencyCatalog", "fileTags", "repositories"))
            assertEquals(original.document.values.getValue(name), result.document.values.getValue(name), name)
        for (name in listOf("targets", "compilerPlugins")) assertEquals(authoredModule(original).getValue(name), authoredModule(result).getValue(name), name)
        assertEquals(modules(original).drop(1), modules(result).drop(1))
        assertEquals(before, original.document.canonical())
        assertNotEquals(original.digest, result.digest)
    }

    @Test fun setDependencyAddsOrReplacesByPackageIdentityAndKeepsArrayOrder() {
        val original = fixture()
        val replacement = obj("coordinate" to "example:api:9".json(), "scope" to "runtime".json())
        val added = obj("coordinate" to "example:new:1".json(), "exported" to true.json())
        val result = editProjectConfiguration(original, batch(
            dependencyEdit("set-dependency", "dependency" to replacement), dependencyEdit("set-dependency", "dependency" to added)))
        assertEquals(listOf(replacement, dependencies(original)[1], dependencies(original)[2], added), dependencies(result))
        assertEquals(DependencyScopeKind.RUNTIME, result.project.modules.first().sourceSets.first().dependencies.first().scope)
    }

    @Test fun workspaceDependenciesCanBeReplacedRemovedAndAddedWithoutTouchingExternalKeys() {
        val original = fixture()
        val replacement = obj("module" to "library".json(), "scope" to "runtime".json())
        val result = editProjectConfiguration(original, batch(
            dependencyEdit("set-dependency", "dependency" to replacement),
            dependencyEdit("remove-dependency", "selector" to obj("package" to "example:defaults".json())),
        ))
        assertEquals(listOf(dependencies(original).first(), replacement), dependencies(result))
        val removed = editProjectConfiguration(result, batch(dependencyEdit("remove-dependency", "selector" to obj("module" to "library".json()))))
        assertEquals(listOf(dependencies(original).first()), dependencies(removed))
        val restored = editProjectConfiguration(removed, batch(dependencyEdit("set-dependency", "dependency" to obj("module" to "library".json()))))
        assertEquals(2, dependencies(restored).size)
    }

    @Test fun fullTargetObjectsAddReplaceAndRemoveWithExistingParserValidation() {
        val original = fixture()
        val native = target("macosArm64", listOf("-opt"))
        val added = editProjectConfiguration(original, batch(edit("set-target", "target" to native)))
        assertEquals(native, authoredModule(added).getValue("targets").arrayValue().last())
        val replacement = target("macosArm64", listOf("-g"))
        val replaced = editProjectConfiguration(added, batch(edit("set-target", "target" to replacement)))
        assertEquals(replacement, authoredModule(replaced).getValue("targets").arrayValue().last())
        assertEquals(authoredModule(original).getValue("compilerPlugins"), authoredModule(replaced).getValue("compilerPlugins"))
        val removed = editProjectConfiguration(replaced, batch(edit("remove-target", "target" to "macosArm64".json())))
        assertEquals(original.document, removed.document)
    }

    @Test fun coordinatedTargetBatchIsValidatedAfterAllOperations() {
        val original = parseProjectConfiguration(document(listOf(module("app", listOf(obj("module" to "library".json()))), module("library"))))
        val operations = listOf(
            edit("remove-target", "target" to "jvm".json()),
            edit("set-target", "target" to target("macosArm64")),
            obj("op" to "remove-target".json(), "module" to "library".json(), "target" to "jvm".json()),
            obj("op" to "set-target".json(), "module" to "library".json(), "target" to target("macosArm64")),
        )
        val result = editProjectConfiguration(original, batch(*operations.toTypedArray()))
        assertTrue(result.project.modules.all { it.targets.single().name == "macosArm64" })
        assertFails { editProjectConfiguration(original, batch(*operations.take(2).toTypedArray())) }
        assertEquals("jvm", original.project.modules.first().targets.single().name)
    }

    @Test fun operationEnvelopeSelectorAndUnknownIdentityFailuresAreAtomic() {
        val original = fixture()
        val before = original.document.canonical()
        val valid = dependencyEdit("set-dependency-version", "package" to "example:api".json(), "version" to "2".json())
        val invalid = listOf(
            obj("op" to "unknown".json(), "module" to "app".json()),
            JsonValue.Object(valid.objectValue() + ("unknown" to true.json())),
            JsonValue.Object(valid.objectValue() + ("module" to "missing".json())),
            JsonValue.Object(valid.objectValue() + ("sourceSet" to "missing".json())),
            dependencyEdit("set-dependency-version", "package" to "example:missing".json(), "version" to "2".json()),
            dependencyEdit("remove-dependency", "selector" to obj("package" to "example:api".json(), "module" to "library".json())),
            dependencyEdit("remove-dependency", "selector" to obj("coordinate" to "example:api:1".json())),
            dependencyEdit("remove-dependency", "selector" to obj()),
            dependencyEdit("remove-dependency", "selector" to obj("module" to "missing".json())),
            edit("remove-target", "target" to "linuxX64".json()),
        )
        for (operation in invalid) {
            assertFails { editProjectConfiguration(original, batch(valid, operation)) }
            assertEquals(before, original.document.canonical())
        }
    }

    @Test fun dependencyPayloadsAndVersionsUseExistingStrictProjectRules() {
        val original = fixture()
        for (dependency in listOf(
            obj("coordinate" to "example:api:2".json(), "unknown" to true.json()),
            obj("coordinate" to "example:api:2".json(), "module" to "library".json()),
            obj("coordinate" to "example:api:2".json(), "scope" to "unknown".json()),
            obj("coordinate" to "example:api:2".json(), "scope" to "runtime".json(), "exported" to true.json()),
            obj("module" to "missing".json()),
            obj("module" to "app".json()),
        )) assertFails { editProjectConfiguration(original, batch(dependencyEdit("set-dependency", "dependency" to dependency))) }
        for (version in listOf("1-SNAPSHOT", "LATEST", "1:2", "../2", ""))
            assertFails { editProjectConfiguration(original, batch(dependencyEdit("set-dependency-version", "package" to "example:api".json(), "version" to version.json()))) }
        for (name in listOf("example", "example:api:1", "example::api", "../example:api"))
            assertFails { editProjectConfiguration(original, batch(dependencyEdit("set-dependency-version", "package" to name.json(), "version" to "2".json()))) }
    }

    @Test fun targetPayloadsAndFinalModuleTargetGraphAreStrict() {
        val original = fixture()
        for (target in listOf(
            JsonValue.Object(target().objectValue() + ("unknown" to true.json())),
            JsonValue.Object(target().objectValue() - "compilerOptions"),
            JsonValue.Object(target().objectValue() + ("main" to "missing".json())),
            target("unsupported"),
        )) assertFails { editProjectConfiguration(original, batch(edit("set-target", "target" to target))) }
        assertFails { editProjectConfiguration(original, batch(edit("remove-target", "target" to "jvm".json()))) }
        // Declared plugin targets are still validated when a replacement removes their compilation.
        assertFails { editProjectConfiguration(original, batch(edit("set-target", "target" to target("macosArm64")), edit("remove-target", "target" to "jvm".json()))) }
    }

    @Test fun ambiguousExternalPackageAndWorkspaceDependencyKeysAreNeverGuessed() {
        for (dependencies in listOf(
            listOf(obj("coordinate" to "example:api:1".json()), obj("coordinate" to "example:api:2".json())),
            listOf(obj("module" to "library".json()), obj("module" to "library".json(), "scope" to "runtime".json())),
        )) {
            val original = parseProjectConfiguration(document(listOf(module("app", dependencies), module("library"))))
            val selector = if ("coordinate" in dependencies.first().objectValue()) obj("package" to "example:api".json()) else obj("module" to "library".json())
            assertFails { editProjectConfiguration(original, batch(dependencyEdit("set-dependency", "dependency" to dependencies.first()))) }
            assertFails { editProjectConfiguration(original, batch(dependencyEdit("remove-dependency", "selector" to selector))) }
            if ("package" in selector.values) assertFails {
                editProjectConfiguration(original, batch(dependencyEdit("set-dependency-version", "package" to "example:api".json(), "version" to "3".json())))
            }
        }
    }

    @Test fun ambiguousRawModuleAndSourceSetIdentitiesCannotBeResolvedByFirstMatch() {
        val original = fixture()
        val operation = dependencyEdit("set-dependency-version", "package" to "example:api".json(), "version" to "2".json())
        val duplicatedModules = original.copy(document = JsonValue.Object(original.document.values + ("modules" to (modules(original) + modules(original).first()).json())))
        assertFails { editProjectConfiguration(duplicatedModules, batch(operation)) }
        val app = authoredModule(original)
        val sources = app.getValue("sourceSets").arrayValue()
        val duplicateApp = JsonValue.Object(app + ("sourceSets" to (sources + sources.first()).json()))
        val duplicatedSources = original.copy(document = JsonValue.Object(original.document.values + ("modules" to (listOf(duplicateApp) + modules(original).drop(1)).json())))
        assertFails { editProjectConfiguration(duplicatedSources, batch(operation)) }
    }

    @Test fun versionEditDoesNotEraseUnknownAuthoringFieldsBeforeFinalValidation() {
        val original = fixture()
        val app = authoredModule(original)
        val sourceSets = app.getValue("sourceSets").arrayValue().toMutableList()
        val main = sourceSets.first().objectValue()
        val changedDependencies = main.getValue("dependencies").arrayValue().toMutableList()
        changedDependencies[0] = JsonValue.Object(changedDependencies[0].objectValue() + ("unknown" to true.json()))
        sourceSets[0] = JsonValue.Object(main + ("dependencies" to changedDependencies.json()))
        val appWithUnknown = JsonValue.Object(app + ("sourceSets" to sourceSets.json()))
        val input = original.copy(document = JsonValue.Object(original.document.values + ("modules" to (listOf(appWithUnknown) + modules(original).drop(1)).json())))
        assertFails { editProjectConfiguration(input, batch(dependencyEdit("set-dependency-version", "package" to "example:api".json(), "version" to "2".json()))) }
        assertEquals(true.json(), dependencies(input).first().objectValue().getValue("unknown"))
    }

    @Test fun batchCountAndFinalSizeAreBoundedWithoutMutatingInputs() {
        val original = fixture()
        val before = original.document.canonical()
        val operation = dependencyEdit("set-dependency-version", "package" to "example:api".json(), "version" to "2".json())
        assertFails { editProjectConfiguration(original, batch()) }
        assertFails { editProjectConfiguration(original, batch(*List(129) { operation }.toTypedArray())) }
        val accepted = editProjectConfiguration(original, batch(*List(128) { operation }.toTypedArray()))
        assertEquals("example:api:2", dependencies(accepted).first().objectValue().getValue("coordinate").stringValue())
        assertFails { editProjectConfiguration(original, JsonValue.Object(batch(operation).objectValue() + ("schemaVersion" to 2.json()))) }
        assertFails { editProjectConfiguration(original, JsonValue.Object(batch(operation).objectValue() + ("extra" to true.json()))) }
        assertFails { editProjectConfiguration(original, batch(edit("set-target", "target" to target(options = listOf("x".repeat(4 * 1024 * 1024)))))) }
        assertEquals(before, original.document.canonical())
    }
}
