package kvik.core

import kotlin.test.*

class ScriptsTest {
    private val repository = Repository("central", "https://repo.maven.apache.org/maven2")
    private val module = ModuleSpec("library", listOf(SourceSet("main", listOf(LogicalPath("lib")), emptyList(), emptyList(),
        listOf(Dependency.External(Coordinate.parse("g:private:1"))))), listOf(TargetSpec("jvm", "main", null, emptyList())), emptyList())
    private val plugin = CompilerPluginSpec("plugin", null, setOf("jvm"), setOf("script"), listOf("message=hello"),
        listOf(LogicalPath("config/plugin.txt")), "example.plugin", "2.4.20")
    private fun source() = listOf(LogicalPath("scripts/run.kt"))
    private fun project(script: ScriptSpec = ScriptSpec(source()), profiles: Map<String, ScriptDependencyProfile> = emptyMap(),
                        catalog: Map<String, Coordinate> = emptyMap()) = ProjectSpec("project", listOf(repository),
        listOf(module, module.copy(id = "plugin")), catalog, profiles, mapOf("run" to script))
    private fun moduleJson(plugins: String = "[]") = """{"id":"library","sourceSets":[{"name":"main","directories":["lib"],"resources":[],"dependsOn":[],"dependencies":[]}],"targets":[{"name":"jvm","main":"main","test":null,"compilerOptions":[]}],"compilerPlugins":$plugins}"""
    private fun document(extras: String = "", modules: String = moduleJson()) = """{"schemaVersion":1,"name":"project","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],"modules":[$modules]$extras}"""
    private fun standalone(extras: String = "", script: String = """{"sources":["run.kt"]}""") =
        """{"schemaVersion":1,"name":"run","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],"script":$script$extras}"""

    @Test fun oldProjectDefaultsRemainEqualAndEmptyReservedFieldsRemainValid() {
        val parsed = parseProjectConfiguration(parseJson(document())).project
        assertEquals(ProjectSpec(parsed.name, parsed.repositories, parsed.modules), parsed)
        assertEquals(parsed, parseProjectConfiguration(parseJson(document(",\"scripts\":{},\"dependencyProfiles\":{},\"dependencyCatalog\":{}"))).project)
        assertTrue(parsed.scripts.isEmpty())
    }

    @Test fun orderedExpansionRetainsAllThreeOriginPointersWithoutImplicitDependencies() {
        val configuration = project(ScriptSpec(source(), profiles = listOf("second", "first"),
            dependencies = listOf(ScriptDependencyReference.Module("library"))), linkedMapOf(
            "first" to ScriptDependencyProfile(listOf(ScriptDependencyReference.Catalog("json")), listOf("-Werror")),
            "second" to ScriptDependencyProfile(listOf(ScriptDependencyReference.External(Coordinate.parse("g:b:1"))), listOf("-jvm-target=17"), listOf(plugin)),
        ), mapOf("json" to Coordinate.parse("g:json:1")))
        val expanded = expandScript(configuration, "run")
        assertEquals(listOf(Dependency.External(Coordinate.parse("g:b:1")), Dependency.External(Coordinate.parse("g:json:1")), Dependency.Module("library")), expanded.dependencies.map { it.value })
        assertEquals(listOf("-jvm-target=17", "-Werror"), expanded.compilerOptions.map { it.value })
        assertEquals(ScriptOrigin("/dependencyProfiles/first/dependencies/0", "/scripts/run/profiles/1", "/dependencyCatalog/json"), expanded.dependencies[1].origin)
        assertEquals(ScriptOrigin("/scripts/run/dependencies/0"), expanded.dependencies[2].origin)
        assertEquals(ScriptOrigin("/dependencyProfiles/second/compilerPlugins/0", "/scripts/run/profiles/0"), expanded.compilerPlugins.single().origin)
        assertEquals(expanded, expandScript(configuration.copy(dependencyProfiles = configuration.dependencyProfiles.entries.reversed().associate { it.toPair() }), "run"))
        assertEquals(listOf(plugin), expanded.compilerPlugins.map { it.value })
        // Module private dependencies are not flattened by declaration expansion.
        assertFalse(expanded.dependencies.any { (it.value as? Dependency.External)?.coordinate?.name == "private" })
    }

    @Test fun projectParserRetainsScriptProfileAndCatalogIdentity() {
        val profile = ScriptDependencyProfile(listOf(ScriptDependencyReference.Catalog("json")), compilerPlugins = listOf(plugin))
        val script = ScriptSpec(source(), profiles = listOf("maintenance"), dependencies = listOf(ScriptDependencyReference.Module("library")))
        val extras = ",\"dependencyCatalog\":{\"json\":\"g:json:1\"},\"dependencyProfiles\":{\"maintenance\":${profile.toJson().canonical()}},\"scripts\":{\"run\":${script.toJson().canonical()}}"
        val text = document(extras, moduleJson() + "," + moduleJson().replace("\"id\":\"library\"", "\"id\":\"plugin\""))
        val parsed = parseProjectConfiguration(parseJson(text))
        assertEquals(script, parsed.project.scripts.getValue("run"))
        assertEquals(profile, parsed.project.dependencyProfiles.getValue("maintenance"))
        assertEquals(parseJson(text), parsed.document)
        assertEquals(parsed, parseProjectConfiguration(parseJson(parsed.document.canonical())))
        val changed = parseProjectConfiguration(parseJson(text.replace("g:json:1", "g:json:2")))
        assertNotEquals(parsed.digest, changed.digest)
        assertNotEquals(expandScript(parsed.project, "run").dependencies, expandScript(changed.project, "run").dependencies)
        assertEquals(expandScript(parsed.project, "run").compilerPlugins, expandScript(changed.project, "run").compilerPlugins)
    }

    @Test fun scriptAndProfileSerializationRoundTripWithoutInferringEntrypoint() {
        val declaration = ScriptSpec(source(), listOf(LogicalPath("data")), listOf("shared"),
            listOf(ScriptDependencyReference.Module("library", DependencyScopeKind.RUNTIME)), listOf("-jvm-target=17"), listOf(plugin),
            "example.MainKt", 120_000, mapOf("MODE" to "update"))
        assertEquals(declaration, ScriptSpec.fromJson(parseJson(declaration.toJson().canonical())))
        val profile = ScriptDependencyProfile(listOf(ScriptDependencyReference.Catalog("json")), listOf("-Werror"), listOf(plugin))
        assertEquals(profile, ScriptDependencyProfile.fromJson(parseJson(profile.toJson().canonical())))
        assertNull(ScriptSpec.fromJson(parseJson("""{"sources":["run.kt"]}""")).mainClass)
        assertEquals(DEFAULT_SCRIPT_TIMEOUT_MILLIS, ScriptSpec(source()).timeoutMillis)
    }

    @Test fun exactDuplicateReferencesFailWhileVersionConflictsRemainResolverRequests() {
        for (second in listOf(ScriptDependencyReference.External(Coordinate.parse("g:a:1")),
            ScriptDependencyReference.Catalog("alias"))) {
            val spec = ScriptSpec(source(), profiles = listOf("shared"), dependencies = listOf(second))
            val configured = project(spec, mapOf("shared" to ScriptDependencyProfile(listOf(ScriptDependencyReference.Catalog("alias")))), mapOf("alias" to Coordinate.parse("g:a:1")))
            assertFailsWith<IllegalArgumentException> { expandScript(configured, "run") }
        }
        val refs = listOf(ScriptDependencyReference.External(Coordinate.parse("g:a:1")),
            ScriptDependencyReference.External(Coordinate.parse("g:a:2")),
            ScriptDependencyReference.External(Coordinate.parse("g:a:1"), DependencyScopeKind.RUNTIME))
        val expanded = expandScript(project(ScriptSpec(source(), dependencies = refs)), "run")
        assertEquals(listOf(Dependency.External(Coordinate.parse("g:a:1")), Dependency.External(Coordinate.parse("g:a:2")),
            Dependency.External(Coordinate.parse("g:a:1"), DependencyScopeKind.RUNTIME)), expanded.dependencies.map { it.value })
        assertEquals(listOf("/scripts/run/dependencies/0", "/scripts/run/dependencies/1", "/scripts/run/dependencies/2"), expanded.dependencies.map { it.origin.declaration })
        assertFails { expandScript(project(ScriptSpec(source(), dependencies = listOf(ScriptDependencyReference.Module("library"),
            ScriptDependencyReference.Module("library", DependencyScopeKind.RUNTIME)))), "run") }
    }

    @Test fun unknownCatalogAndProfilesFailEvenWhenUnused() {
        assertFails { expandScript(project(ScriptSpec(source(), profiles = listOf("missing"))), "run") }
        assertFails { expandScript(project(ScriptSpec(source(), dependencies = listOf(ScriptDependencyReference.Catalog("missing")))), "run") }
        assertFails { parseProjectConfiguration(parseJson(document(",\"dependencyProfiles\":{\"unused\":{\"dependencies\":[{\"catalog\":\"missing\"}]}}"))) }
        assertFails { parseProjectConfiguration(parseJson(document(",\"dependencyCatalog\":{\"json\":\"g:a:LATEST\"}"))) }
    }

    @Test fun profilesAreNonrecursiveObjectsAndSelectionsAreUnique() {
        for (value in listOf("[]", "null", "{\"profiles\":[]}", "{\"extends\":\"other\"}", "{\"environment\":{}}"))
            assertFails { ScriptDependencyProfile.fromJson(parseJson(value)) }
        assertFails { ScriptSpec(source(), profiles = listOf("a", "a")) }
        assertFails { parseProjectConfiguration(parseJson(document(",\"dependencyProfiles\":{\"legacy\":[]}"))) }
        assertFails { ScriptDependencyReference.fromJson(parseJson("""{"catalog":"json","coordinate":"g:a:1"}""")) }
        assertFails { ScriptDependencyReference.fromJson(parseJson("""{"module":"library","exported":true}""")) }
    }

    @Test fun workspacePrerequisitesMustExistAndHaveJvmTargets() {
        for (name in listOf("missing", "nativeOnly")) {
            val spec = ScriptSpec(source(), dependencies = listOf(ScriptDependencyReference.Module(name)))
            val base = project(spec)
            val native = module.copy(id = "nativeOnly", targets = listOf(TargetSpec("macosArm64", "main", null, emptyList())))
            assertFails { expandScript(base.copy(modules = base.modules + native), "run") }
            assertFails { expandScript(base.copy(scripts = mapOf("run" to ScriptSpec(source(), compilerPlugins = listOf(plugin.copy(module = name))))), "run") }
        }
        val namedLikeModule = project().copy(scripts = mapOf("library" to ScriptSpec(source())))
        assertEquals("library", expandScript(namedLikeModule, "library").name)
    }

    @Test fun pluginScopesAreScriptOnlyAndModuleDeclarationsCannotSelectScripts() {
        val published = """{"coordinate":"g:plugin:1","targets":["jvm"],"scopes":["script"],"options":[],"inputs":[]}"""
        ScriptSpec.fromJson(parseJson("""{"sources":["run.kt"],"compilerPlugins":[$published]}"""))
        assertFails { parseProjectConfiguration(parseJson(document(modules = moduleJson("[$published]")))) }
        for (changed in listOf(plugin.copy(scopes = setOf("main")), plugin.copy(scopes = setOf("test"))))
            assertFails { ScriptSpec(source(), compilerPlugins = listOf(changed)) }
        assertFails { ScriptSpec.fromJson(parseJson("""{"sources":["run.kt"],"compilerPlugins":[${published.replace("jvm", "macosArm64")}]}""")) }
    }

    @Test fun duplicatePluginApplicationsAndOutputOverlapsFail() {
        val shared = mapOf("shared" to ScriptDependencyProfile(compilerPlugins = listOf(plugin)))
        assertFails { expandScript(project(ScriptSpec(source(), profiles = listOf("shared"), compilerPlugins = listOf(plugin)), shared), "run") }
        val other = plugin.copy(module = "library")
        assertFails { expandScript(project(ScriptSpec(source(), compilerPlugins = listOf(plugin, other))), "run") }
        val published = CompilerPluginSpec(null, Coordinate.parse("g:plugin:1"), setOf("jvm"), setOf("script"), emptyList(), emptyList())
        assertFails { expandScript(project(ScriptSpec(source(), compilerPlugins = listOf(published, published.copy(coordinate = Coordinate.parse("g:plugin:2"))))), "run") }
        val overlapping = plugin.copy(pathOptions = listOf(PluginPathOption("output", PluginPathKind.OUTPUT_DIRECTORY, LogicalPath("scripts"))), outputs = listOf(LogicalPath("scripts")))
        assertFails { expandScript(project(ScriptSpec(source(), compilerPlugins = listOf(overlapping))), "run") }
    }

    @Test fun sourceAndResourcePathsAreBoundedDisjointAndKtOnlyForExplicitScripts() {
        for (paths in listOf(emptyList(), listOf("a.kt", "a.kt"), listOf("src", "src/a.kt"), listOf("src", "SRC/a.kt"), listOf("run.kts"), listOf("run.KTS")))
            assertFails { ScriptSpec(paths.map(::LogicalPath)) }
        for (value in listOf("../run.kt", "/run.kt", "a//b", "a\\b", "a\n")) assertFails { ScriptSpec(listOf(LogicalPath(value))) }
        assertFails { ScriptSpec(source(), resources = listOf(LogicalPath("scripts"))) }
        assertFails { ScriptSpec(listOf(LogicalPath("a".repeat(1025)))) }
        ScriptSpec(listOf(LogicalPath("scripts/helpers"), LogicalPath("run.kt")))
    }

    @Test fun environmentTimeoutAndMainClassHaveStrictBoundsAndNoLauncherInjection() {
        for (timeout in listOf(0L, -1L, 3_600_001L)) assertFails { ScriptSpec(source(), timeoutMillis = timeout) }
        for (key in listOf("KVIK_PROCESS_BARRIER_V1", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "PATH", "DYLD_INSERT_LIBRARIES", "a-b", "A\n"))
            assertFails { ScriptSpec(source(), environment = mapOf(key to "value")) }
        assertFails { ScriptSpec(source(), environment = mapOf("INPUT" to "bad\u0000value")) }
        assertFails { ScriptSpec(source(), environment = mapOf("INPUT" to "x".repeat(4097))) }
        for (name in listOf("", "MainKt\n", "package.main()", "../Main")) assertFails { ScriptSpec(source(), mainClass = name) }
        ScriptSpec(source(), mainClass = "example.Outer\$Nested", timeoutMillis = 3_600_000)
    }

    @Test fun expandedOptionsValidateTargetConflictsAndDeclarationBounds() {
        val profile = mapOf("target" to ScriptDependencyProfile(compilerOptions = listOf("-jvm-target=17")))
        for (options in listOf(listOf("-jvm-target=17"), listOf("-Xjdk-release=11")))
            assertFails { expandScript(project(ScriptSpec(source(), profiles = listOf("target"), compilerOptions = options), profile), "run") }
        assertFails { ScriptSpec(source(), compilerOptions = listOf("bad\noption")) }
        assertFails { ScriptSpec(source(), compilerOptions = List(257) { "-Werror" }) }
        assertFails { ScriptSpec(source(), environment = (0..127).associate { "VALUE_$it" to "x".repeat(4096) }) }
    }

    @Test fun standaloneUsesDurableDocumentAndBindsExplicitSelectedPrimarySource() {
        val config = parseStandaloneScriptConfiguration(parseJson(standalone()))
        config.requirePrimarySource(LogicalPath("run.kt"))
        assertFails { config.requirePrimarySource(LogicalPath("other.kt")) }
        assertEquals(config, parseStandaloneScriptConfiguration(parseJson(config.document.canonical())))
        assertEquals(Sha256.digest(config.document.canonical().encodeToByteArray()), config.digest)
        val dependencies = ",\"dependencyCatalog\":{\"json\":\"g:json:1\"},\"dependencyProfiles\":{\"common\":{\"dependencies\":[{\"catalog\":\"json\"}]}}"
        val expanded = parseStandaloneScriptConfiguration(parseJson(standalone(dependencies, """{"sources":["run.kt"],"profiles":["common"]}"""))).expanded()
        assertEquals(ScriptOrigin("/dependencyProfiles/common/dependencies/0", "/script/profiles/0", "/dependencyCatalog/json"), expanded.dependencies.single().origin)
        assertFails { parseStandaloneScriptConfiguration(parseJson(standalone(script = """{"sources":["run.kt"],"dependencies":[{"module":"library"}]}"""))) }
        assertFails { parseStandaloneScriptConfiguration(parseJson(standalone(",\"dependencyProfiles\":{\"unused\":{\"dependencies\":[{\"module\":\"library\"}]}}"))) }
        assertFails { parseStandaloneScriptConfiguration(parseJson(standalone(",\"project\":\"other.json\""))) }
    }

    @Test fun strictParserRejectsUnknownFieldsBadNamesAndMalformedTypes() {
        for (name in listOf("bad\n", "bad/name", "", "a".repeat(129))) {
            val key = name.json().canonical()
            assertFails { parseProjectConfiguration(parseJson(document(",\"scripts\":{$key:{\"sources\":[\"run.kt\"]}}"))) }
        }
        for (value in listOf("null", "[]", "{}", "{\"sources\":[],\"command\":\"sh\"}", "{\"sources\":[\"run.kt\"],\"mainClass\":null}", "{\"sources\":[\"run.kt\"],\"timeoutMillis\":1.5}"))
            assertFails { ScriptSpec.fromJson(parseJson(value)) }
        assertFails { ScriptDependencyReference.fromJson(parseJson("""{"coordinate":"g:a:1\n"}""")) }
    }
}
