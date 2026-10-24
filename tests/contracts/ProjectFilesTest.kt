package kvik.core

import kotlin.test.*

class ProjectFilesTest {
    private val configurationPath = LogicalPath("project.json")
    private val lockPath = LogicalPath("dependencies.lock")
    private val source = SourceReference(SourceProvenance("kode", "project-17", "revision-42", "tree-31"), "workspace/app",
        Sha256.digest("complete manifest including excluded files".encodeToByteArray()))

    private fun sourceSet(name: String, directory: String, parents: List<String> = emptyList(), resources: List<String> = emptyList()) =
        SourceSet(name, listOf(LogicalPath(directory)), resources.map(::LogicalPath), parents, emptyList())

    private val module = ModuleSpec("library", listOf(
        sourceSet("commonMain", "src/common", resources = listOf("resources")),
        sourceSet("jvmMain", "src/jvm", listOf("commonMain")),
        sourceSet("jvmTest", "tests/jvm", listOf("commonMain", "jvmMain")),
        sourceSet("nativeMain", "src/native", listOf("commonMain")),
        sourceSet("nativeTest", "tests/native", listOf("nativeMain")),
    ), listOf(
        TargetSpec("jvm", "jvmMain", "jvmTest", emptyList(), abi = AbiSettings(LogicalPath("api/library.txt"))),
        TargetSpec("macosArm64", "nativeMain", "nativeTest", emptyList()),
    ), emptyList())

    private fun configuration(modules: List<ModuleSpec> = listOf(module), scripts: Map<String, ScriptSpec> = emptyMap(),
                              profiles: Map<String, ScriptDependencyProfile> = emptyMap()) =
        ProjectConfiguration(ProjectSpec("sample", emptyList(), modules, dependencyProfiles = profiles, scripts = scripts), obj())

    private fun tagged(paths: List<String>, config: ProjectConfiguration = configuration()) =
        tagProjectFiles(config, configurationPath, lockPath, paths.map(::LogicalPath))

    @Test fun inheritedProductionSourcesAreNotTestSourcesAndTargetsFollowClosures() {
        val files = tagged(listOf("tests/native/Test.kt", "src/common/Public.kt", "tests/jvm/Test.kt", "src/jvm/Main.kt", "resources/a.txt"))
            .associate { it.path.value to it.tags }
        assertEquals(setOf("module:library", "source-set:commonMain", "target:jvm", "target:macosArm64", "scope:production", "kind:source"),
            files.getValue("src/common/Public.kt"))
        assertEquals(setOf("module:library", "source-set:jvmTest", "target:jvm", "scope:test", "kind:source"), files.getValue("tests/jvm/Test.kt"))
        assertEquals(setOf("module:library", "source-set:nativeTest", "target:macosArm64", "scope:test", "kind:source"), files.getValue("tests/native/Test.kt"))
        assertFalse(roleFilePredicate("tests").matches(files.getValue("src/common/Public.kt")))
        assertTrue(roleFilePredicate("production").matches(files.getValue("resources/a.txt")))
        assertFalse("kind:source" in files.getValue("resources/a.txt"))
        assertFalse(files.values.flatten().any { it.startsWith("api:") })
    }

    @Test fun overlappingOwnersNestedRootsAndKindsAccumulateWithoutPrefixAliases() {
        val other = ModuleSpec("other", listOf(sourceSet("main", "src/common/nested", resources = listOf("src/common"))),
            listOf(TargetSpec("jvm", "main", null, emptyList())), emptyList())
        val files = tagged(listOf("src/common/nested/Shared.kt", "src/commonish/A.kt", "src/common", "src/common/nested"),
            configuration(listOf(module, other))).associate { it.path.value to it.tags }
        assertEquals(setOf("module:library", "module:other", "source-set:commonMain", "source-set:main", "target:jvm",
            "target:macosArm64", "scope:production", "kind:source", "kind:resource"), files.getValue("src/common/nested/Shared.kt"))
        assertEquals(setOf("kind:unclassified"), files.getValue("src/commonish/A.kt"))
        assertEquals(setOf("kind:unclassified"), files.getValue("src/common"))
        // A nested directory root itself is not a source of that root, but belongs to the broader resource root.
        assertTrue("kind:resource" in files.getValue("src/common/nested"))
        assertTrue("kind:source" in files.getValue("src/common/nested"))
    }

    @Test fun filesCanHaveProductionAndTestOwnershipAcrossDifferentTargets() {
        val shared = module.copy(targets = module.targets.map {
            if (it.name == "macosArm64") it.copy(main = "jvmTest", test = null) else it
        })
        val tags = tagged(listOf("tests/jvm/Test.kt"), configuration(listOf(shared))).single().tags
        assertTrue(tags.containsAll(setOf("scope:production", "scope:test", "target:jvm", "target:macosArm64")))
    }

    @Test fun explicitMetadataScriptsPluginsAndDocumentationAreClassifiedOnlyWhenPresent() {
        val plugin = CompilerPluginSpec(null, Coordinate("example", "plugin", "1"), setOf("jvm"), setOf("main", "test"),
            emptyList(), listOf(LogicalPath("plugin/config.json"), LogicalPath("plugin/data")))
        val scriptPlugin = plugin.copy(scopes = setOf("script"), inputs = listOf(LogicalPath("script/config.json")))
        val config = configuration(listOf(module.copy(compilerPlugins = listOf(plugin),
            documentation = DocumentationSettings(includes = listOf(LogicalPath("docs/guide.md"))))),
            scripts = mapOf("release" to ScriptSpec(listOf(LogicalPath("scripts/Release.kt"), LogicalPath("scripts/lib")),
                resources = listOf(LogicalPath("scripts/assets"), LogicalPath("scripts/message.txt")), profiles = listOf("shared"))),
            profiles = mapOf("shared" to ScriptDependencyProfile(compilerPlugins = listOf(scriptPlugin))))
        val paths = listOf("project.json", "dependencies.lock", "api/library.txt", "scripts/Release.kt", "scripts/lib/Util.kt",
            "scripts/assets/icon.png", "scripts/message.txt", "plugin/config.json", "plugin/data/table.csv", "script/config.json",
            "docs/guide.md", "generated/PrivateApi.kt", "project.json.bak")
        val files = tagged(paths.reversed(), config)
        assertEquals(paths.sorted(), files.map { it.path.value })
        val tags = files.associate { it.path.value to it.tags }
        assertEquals(setOf("kind:configuration"), tags.getValue("project.json"))
        assertEquals(setOf("kind:lock"), tags.getValue("dependencies.lock"))
        assertEquals(setOf("kind:abi-baseline", "api:public", "module:library", "target:jvm", "scope:production"), tags.getValue("api/library.txt"))
        for (path in listOf("scripts/Release.kt", "scripts/lib/Util.kt")) assertEquals(
            setOf("script:release", "target:jvm", "scope:script", "kind:source", "kind:script"), tags.getValue(path))
        for (path in listOf("scripts/assets/icon.png", "scripts/message.txt")) assertEquals(
            setOf("script:release", "target:jvm", "scope:script", "kind:resource"), tags.getValue(path))
        assertEquals(setOf("kind:plugin-input", "module:library", "target:jvm", "scope:production", "scope:test"), tags.getValue("plugin/config.json"))
        assertEquals(tags.getValue("plugin/config.json"), tags.getValue("plugin/data/table.csv"))
        assertEquals(setOf("script:release", "target:jvm", "scope:script", "kind:plugin-input"), tags.getValue("script/config.json"))
        assertEquals(setOf("module:library", "kind:documentation"), tags.getValue("docs/guide.md"))
        assertEquals(setOf("kind:unclassified"), tags.getValue("generated/PrivateApi.kt"))
        assertEquals(setOf("kind:unclassified"), tags.getValue("project.json.bak"))
        assertEquals(emptyList(), tagged(emptyList(), config))
        assertEquals(files, tagged(paths, config))
    }

    @Test fun inspectionDoesNotInventLockOwnershipAndInventoryIsBoundedAndCancellable() {
        val config = configuration()
        assertEquals(setOf("kind:unclassified"), tagProjectFiles(config, configurationPath, null, listOf(lockPath)).single().tags)
        assertEquals(setOf("kind:configuration"), tagProjectFiles(config, lockPath, null, listOf(lockPath)).single().tags)
        assertFails { tagProjectFiles(config, configurationPath, configurationPath, emptyList()) }
        assertFails { tagged(listOf("same", "same")) }
        assertFails { tagProjectFiles(config, configurationPath, lockPath, List(100_001) { LogicalPath("same") }) }
        var calls = 0
        val stopped = assertFailsWith<IllegalStateException> {
            tagProjectFiles(config, configurationPath, lockPath, listOf(LogicalPath("a"))) { if (++calls == 3) error("cancelled") }
        }
        assertEquals("cancelled", stopped.message)
    }

    @Test fun pluginActionOutputsDoNotDeclareVersionedGeneratedFiles() {
        val output = LogicalPath("generated")
        val plugin = CompilerPluginSpec("generator", null, setOf("jvm"), setOf("main"), emptyList(),
            listOf(LogicalPath("plugin/config.json")), "example.plugin", "2.4.20",
            listOf(PluginPathOption("output", PluginPathKind.OUTPUT_DIRECTORY, output)), listOf(output))
        val generator = ModuleSpec("generator", listOf(sourceSet("main", "plugin/src")),
            listOf(TargetSpec("jvm", "main", null, emptyList())), emptyList())
        val files = tagged(listOf("generated/Output.kt", "plugin/config.json"),
            configuration(listOf(module.copy(compilerPlugins = listOf(plugin)), generator)))
        assertEquals(setOf("kind:unclassified"), files.first().tags)
        assertEquals(setOf("kind:plugin-input", "module:library", "target:jvm", "scope:production"), files.last().tags)
    }

    @Test fun predicatesCombineClausesAndRolesWithoutOverridingExplicitFilters() {
        val predicate = FileTagPredicate(setOf("module:library"), setOf("scope:test", "kind:configuration"), setOf("kind:resource"))
        assertTrue(predicate.matches(setOf("module:library", "scope:test", "kind:source")))
        assertFalse(predicate.matches(setOf("module:library", "scope:production")))
        assertFalse(predicate.matches(setOf("scope:test")))
        assertFalse(predicate.matches(setOf("module:library", "scope:test", "kind:resource")))
        assertTrue(FileTagPredicate().matches(emptySet()))
        assertEquals(predicate, FileTagPredicate.fromJson(parseJson(predicate.toJson().canonical())))
        assertFalse(roleFilePredicate("production", all = setOf("module:library")).matches(setOf("kind:configuration")))
        assertFalse(roleFilePredicate("tests", none = setOf("kind:lock")).matches(setOf("kind:lock")))
        assertTrue(roleFilePredicate("all").matches(setOf("kind:unclassified")))
        assertFails { roleFilePredicate("test") }
        for (role in listOf("production", "tests")) for (tag in listOf("kind:configuration", "kind:lock"))
            assertTrue(roleFilePredicate(role).matches(setOf(tag)))
    }

    @Test fun predicateAndTaggedFileJsonRejectUnknownFieldsDuplicatesAndMalformedTags() {
        for (invalid in listOf("", "scope", "scope:", ":test", "scope:test value", "scope:test/path", "scope:\n", "scope:" + "a".repeat(507))) {
            assertFails(invalid) { FileTagPredicate(all = setOf(invalid)) }
            assertFails(invalid) { TaggedProjectFile(LogicalPath("file"), setOf(invalid)) }
        }
        val valid = FileTagPredicate(setOf("custom.key:some_value:a-b", "scope:" + "a".repeat(506)))
        assertEquals(valid, FileTagPredicate.fromJson(valid.toJson()))
        assertFails { FileTagPredicate(all = (0..64).map { "tag:$it" }.toSet()) }
        assertFails { FileTagPredicate.fromJson(parseJson("""{"all":["a:b","a:b"],"any":[],"none":[]}""")) }
        assertFails { FileTagPredicate.fromJson(obj("all" to emptyList<JsonValue>().json(), "any" to emptyList<JsonValue>().json())) }
        assertFails { FileTagPredicate.fromJson(JsonValue.Object(valid.toJson().values + ("extra" to true.json()))) }
        assertFails { TaggedProjectFile.fromJson(parseJson("""{"path":"file","tags":["a:b","a:b"]}""")) }
        assertFails { TaggedProjectFile.fromJson(parseJson("""{"path":"../file","tags":["a:b"]}""")) }
        assertFails { TaggedProjectFile(LogicalPath("file"), emptySet()) }
    }

    @Test fun sourceViewsPinExactRevisionRootAndFullManifestIncludingEmptyViews() {
        val file = TaggedProjectFile(LogicalPath("workspace/app/src/A.kt"), setOf("scope:production", "kind:source"))
        val view = SourceView("production", source, roleFilePredicate("production"), listOf(file))
        assertEquals(view, SourceView.fromJson(parseJson(view.toJson().canonical())))
        assertEquals(source.toJson(), view.toJson().values.getValue("source"))
        assertEquals(setOf("schemaVersion", "kind", "name", "source", "predicate", "files"), view.toJson().values.keys)
        assertEquals("source-view", view.toJson().values.getValue("kind").stringValue())
        val empty = view.copy(files = emptyList())
        assertEquals(empty, SourceView.fromJson(empty.toJson()))
        assertEquals(source.manifest, empty.source.manifest)
        val rootView = view.copy(source = source.copy(root = ""), files = listOf(file.copy(path = LogicalPath("A.kt"))))
        assertEquals(rootView, SourceView.fromJson(rootView.toJson()))
        val earlier = file.copy(path = LogicalPath("workspace/app/a.kt"))
        val sorted = view.copy(files = listOf(file, earlier)).toJson().values.getValue("files").arrayValue()
        assertEquals(listOf(earlier.toJson(), file.toJson()), sorted)
        assertNotEquals(view.toJson(), view.copy(source = source.copy(provenance = source.provenance.copy(revision = "revision-43"))).toJson())
    }

    @Test fun sourceViewRejectsEscapesMismatchesInvalidEnvelopeAndMetadataLimits() {
        val file = TaggedProjectFile(LogicalPath("workspace/app/src/A.kt"), setOf("scope:production"))
        val view = SourceView("production", source, roleFilePredicate("production"), listOf(file))
        for (path in listOf("outside/A.kt", "workspace/application/A.kt", "workspace/app"))
            assertFails { view.copy(files = listOf(file.copy(path = LogicalPath(path)))) }
        assertFails { source.copy(root = "../outside") }
        assertFails { view.copy(files = listOf(file.copy(tags = setOf("scope:test")))) }
        assertFails { view.copy(files = listOf(file, file)) }
        assertFails { view.copy(files = List(100_001) { file }) }
        assertFails { view.copy(name = "custom") }
        for ((field, value) in listOf("schemaVersion" to 2.json(), "kind" to "tree".json(), "extra" to true.json()))
            assertFails { SourceView.fromJson(JsonValue.Object(view.toJson().values + (field to value))) }
        // UTF-8 byte bounds apply even when the character count is below 16 MiB.
        val longPath = LogicalPath("workspace/app/" + "é".repeat(8 * 1024 * 1024))
        assertFails { view.copy(files = listOf(file.copy(path = longPath))) }
        val oversized = JsonValue.Object(view.toJson().values + ("files" to listOf(file.copy(path = longPath).toJson()).json()))
        assertFails { SourceView.fromJson(oversized) }
    }
}
