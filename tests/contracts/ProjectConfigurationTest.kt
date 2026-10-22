package kvik.core

import kotlin.test.*

class ProjectConfigurationTest {
    @Test fun dependencyScopeVisibilityAndTestOptionsAreExplicitAndStrict() {
        fun document(dependency: String) = example.replace("\"dependencies\":[]", "\"dependencies\":[$dependency]")
        val private = parse(document("""{"coordinate":"g:api:1"}""")).project.modules.single().sourceSets.first().dependencies.single()
        assertEquals(Dependency.External(Coordinate.parse("g:api:1")), private)
        val exported = parse(document("""{"coordinate":"g:api:1","scope":"compile","exported":true}"""))
        assertTrue(exported.project.modules.single().sourceSets.first().dependencies.single().exported)
        val runtime = parse(document("""{"coordinate":"g:runtime:1","scope":"runtime"}"""))
        assertEquals(DependencyScopeKind.RUNTIME, runtime.project.modules.single().sourceSets.first().dependencies.single().scope)
        assertNotEquals(exported.digest, runtime.digest)
        for (dependency in listOf("""{"coordinate":"g:a:1","scope":"provided"}""", """{"coordinate":"g:a:1","scope":"runtime","exported":true}""",
            """{"coordinate":"g:a:1","scope":null}""", """{"coordinate":"g:a:1","exported":"true"}""", """{"coordinate":"g:a:1","module":"library"}"""))
            assertFails { parse(document(dependency)) }
        val main = example.replace("\"compilerOptions\":[]", "\"compilerOptions\":[\"-Xexplicit-api=strict\"]")
        val inherited = parse(main).project.modules.single().targets.single()
        assertEquals(inherited.compilerOptions, inherited.optionsFor("test"))
        val overridden = parse(main.replace("\"compilerOptions\":", "\"testCompilerOptions\":[],\"compilerOptions\":"))
        assertTrue(overridden.project.modules.single().targets.single().optionsFor("test").isEmpty())
        assertNotEquals(parse(main).digest, overridden.digest)
        assertFails { parse(main.replace("\"compilerOptions\":", "\"testCompilerOptions\":null,\"compilerOptions\":")) }
        assertFails { parse(overridden.document.canonical().replace("\"test\":\"jvmTest\"", "\"test\":null")) }
    }
    private val example = """
        {"schemaVersion":1,"name":"sample","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],"modules":[
          {"id":"library","sourceSets":[
            {"name":"commonMain","directories":["library/common"],"resources":[],"dependsOn":[],"dependencies":[]},
            {"name":"jvmMain","directories":["library/jvm"],"resources":[],"dependsOn":["commonMain"],"dependencies":[]},
            {"name":"jvmTest","directories":["library/test"],"resources":[],"dependsOn":["commonMain","jvmMain"],"dependencies":[]}
          ],"targets":[{"name":"jvm","main":"jvmMain","test":"jvmTest","compilerOptions":[]}],"compilerPlugins":[]}
        ]}
    """.trimIndent()

    private fun parse(text: String = example) = parseProjectConfiguration(parseJson(text))

    private fun withSettings(settings: String) = example.replace("\"compilerOptions\":[]", "\"compilerOptions\":[],\"testSettings\":$settings")

    @Test fun explicitTestSelectionsPreserveOrderDefaultsAndDocumentIdentity() {
        val settings = """{"classes":["sample.ZTest","sample.Outer${'$'}Nested","DefaultPackageTest"],"engines":["kotest","junit"]}"""
        val configuration = parse(withSettings(settings))
        val target = configuration.project.modules.single().targets.single()
        assertEquals(JvmTestSettings(listOf("sample.ZTest", "sample.Outer\$Nested", "DefaultPackageTest"), listOf("kotest", "junit")), target.testSettings)
        assertEquals(parseJson(withSettings(settings)), configuration.document)
        assertEquals(configuration.digest, parse(configuration.document.canonical()).digest)
        assertNotEquals(configuration.digest, parse(withSettings(settings.replace("sample.ZTest", "sample.OtherTest"))).digest)
        assertNotEquals(configuration.digest, parse(withSettings(settings.replace("\"sample.ZTest\",\"sample.Outer${'$'}Nested\"", "\"sample.Outer${'$'}Nested\",\"sample.ZTest\""))).digest)
        assertNotEquals(configuration.digest, parse(withSettings(settings.replace("\"kotest\",\"junit\"", "\"junit\",\"kotest\""))).digest)
        val implicitEngine = parse(withSettings("""{"classes":[]}"""))
        val explicitEngine = parse(withSettings("""{"classes":[],"engines":["junit"]}"""))
        assertEquals(JvmTestSettings(emptyList()), implicitEngine.project.modules.single().targets.single().testSettings)
        assertEquals(implicitEngine.project, explicitEngine.project)
        assertNotEquals(implicitEngine.digest, explicitEngine.digest)
        assertNotEquals(parse().digest, implicitEngine.digest)
        assertFalse(TestCounts(0, 0, 0, 0, true).satisfiesExecution)
    }

    @Test fun absentTestSettingsRemainBackwardsCompatible() {
        assertEquals(TargetSpec("jvm", "jvmMain", "jvmTest", emptyList()), parse().project.modules.single().targets.single())
        assertNull(parse(example.replace("\"test\":\"jvmTest\"", "\"test\":null")).project.modules.single().targets.single().testSettings)
        assertNull(parse(example.replace("\"name\":\"jvm\"", "\"name\":\"macosArm64\"")).project.modules.single().targets.single().testSettings)
        assertEquals(parseJson(example), parse().document)
    }

    @Test fun invalidTestSelectionsFailBeforeExecution() {
        for (settings in listOf(
            "null", "[]", "{}", """{"classes":null}""", """{"classes":[],"typo":true}""",
            """{"classes":["a.Test","a.Test"]}""", """{"classes":[],"engines":[]}""",
            """{"classes":[],"engines":["junit","junit"]}""", """{"classes":[],"engines":["junit-jupiter"]}""",
            """{"classes":[],"engines":["unknown"]}""", """{"classes":[],"engines":null}""",
        )) assertFails("accepted $settings") { parse(withSettings(settings)) }
        for (name in listOf("", ".Test", "Test.", "a..Test", "a.1Test", "a/Test", "a.*", "a.Test#method", "a.Test\n", " a.Test", "a.Test ")) {
            assertFails("accepted $name") { parse(withSettings(obj("classes" to listOf(name.json()).json()).canonical())) }
            assertFails { JvmTestSettings(listOf(name)) }
        }
        val selected = withSettings("""{"classes":[]}""")
        assertFails { parse(selected.replace("\"name\":\"jvm\"", "\"name\":\"macosArm64\"")) }
        assertFails { parse(selected.replace("\"test\":\"jvmTest\"", "\"test\":null")) }
        assertFails { TargetSpec("macosArm64", "main", "test", emptyList(), JvmTestSettings(emptyList())) }
        assertFails { TargetSpec("jvm", "main", null, emptyList(), JvmTestSettings(emptyList())) }
    }

    @Test fun sharedAncestorsAppearOnceAndFormattingDoesNotChangeIdentity() {
        val configuration = parse()
        val module = configuration.project.modules.single()
        assertEquals(listOf("commonMain", "jvmMain", "jvmTest"), sourceSetClosure(module, listOf("jvmTest")).map { it.name })
        assertEquals(configuration.digest, parse(configuration.document.canonical()).digest)
        assertNotEquals(configuration.digest, parse(example.replace("library/common", "library/shared")).digest)
    }

    @Test fun referencesCyclesAndDuplicateNamesFailBeforeExecution() {
        for (text in listOf(
            example.replace("\"dependsOn\":[]", "\"dependsOn\":[\"jvmMain\"]"),
            example.replace("\"dependsOn\":[]", "\"dependsOn\":[\"missing\"]"),
            example.replace("\"name\":\"jvmMain\"", "\"name\":\"commonMain\""),
            example.replace("\"main\":\"jvmMain\"", "\"main\":\"missing\""),
            example.replace("\"dependencies\":[]", "\"dependencies\":[{\"module\":\"missing\"}]"),
            example.replace("\"dependencies\":[]", "\"dependencies\":[{\"module\":\"library\"}]"),
        )) assertFails { parse(text) }
    }

    @Test fun strictSchemaAndReservedFeaturesAreNotSilentlyDropped() {
        for (text in listOf(
            example.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            example.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"typo\":true"),
            example.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"scripts\":{\"release\":{}}"),
            example.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"dependencyProfiles\":{\"shared\":[]}"),
            example.replace("library/common", "../outside"),
            example.replace("\"name\":\"jvm\"", "\"name\":\"iosArm64\""),
            example.replace("\"dependencies\":[]", "\"dependencies\":[{\"coordinate\":\"g:a:LATEST\"}]"),
            example.replace("\"dependsOn\":[\"commonMain\"]", "\"dependsOn\":[\"commonMain\",\"commonMain\"]"),
        )) assertFails { parse(text) }
    }

    @Test fun compilerPluginEdgesParticipateInTheModuleGraph() {
        val plugin = """{"module":"library","targets":["jvm"],"scopes":["main"],"options":[],"inputs":[]}"""
        assertFails { parse(example.replace("\"compilerPlugins\":[]", "\"compilerPlugins\":[$plugin]")) }
        val external = plugin.replace("\"module\":\"library\"", "\"coordinate\":\"org.example:plugin:1.0\"")
        assertEquals("org.example:plugin:1.0", parse(example.replace("\"compilerPlugins\":[]", "\"compilerPlugins\":[$external]"))
            .project.modules.single().compilerPlugins.single().coordinate.toString())
        assertFails { parse(example.replace("\"compilerPlugins\":[]", "\"compilerPlugins\":[${external.replace("\"jvm\"", "\"macosArm64\"")}]") ) }
    }
}
