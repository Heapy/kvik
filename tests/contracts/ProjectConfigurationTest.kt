package kvik.core

import kotlin.test.*

class ProjectConfigurationTest {
    @Test fun iosFrameworkConfigurationIsStrictAndPreservesDeclaredLinkSettings() {
        for (target in listOf("iosArm64", "iosSimulatorArm64")) {
            val original = example.replace("\"name\":\"jvm\"", "\"name\":\"$target\"").replace("\"test\":\"jvmTest\"", "\"test\":null")
            fun selected(value: String) = original.replace("\"compilerOptions\":[]", "\"compilerOptions\":[],\"nativeFramework\":$value")
            val configuration = parse(selected("""{"baseName":"Example","isStatic":true}"""))
            assertEquals(NativeFrameworkSpec("Example", true), configuration.project.modules.single().targets.single().nativeFramework)
            assertEquals(configuration, parse(configuration.document.canonical()))
            assertNull(parse(original).project.modules.single().targets.single().nativeFramework)
            assertEquals(NativeTarget.MACOS_ARM64.platform, NativeTarget.require(target).executionPlatform)
            assertFalse(NativeTarget.require(target).supportsHostExecution)
            for (value in listOf("{}", "null", """{"baseName":"../Bad"}""", """{"baseName":"Good","isStatic":1}""", """{"baseName":"Good","export":"dependency"}"""))
                assertFails { parse(selected(value)) }
            assertFails { parse(selected("""{"baseName":"Good"}""").replace("\"test\":null", "\"test\":\"jvmTest\"")) }
            assertFails { parse(original.replace("\"compilerOptions\":[]", "\"compilerOptions\":[],\"application\":{\"entryPoint\":\"sample.main\"}")) }
        }
    }

    @Test fun applicationsAreOptionalTargetSpecificAndKeepCompilerOptionsSeparate() {
        assertNull(parse().project.modules.single().targets.single().application)
        val settings = ApplicationSpec("sample.MainKt", listOf("--input", "literal path"), mapOf("MODE" to "dev"))
        fun selected(value: String) = example.replace("\"compilerOptions\":[]", "\"compilerOptions\":[],\"application\":$value")
        val configuration = parse(selected(settings.toJson().canonical()))
        val target = configuration.project.modules.single().targets.single()
        assertEquals(settings, target.application)
        assertTrue(target.compilerOptions.isEmpty())
        assertEquals(configuration, parse(configuration.document.canonical()))
        assertNotEquals(parse().digest, configuration.digest)
        assertEquals(settings, parse(selected(settings.toJson().canonical()).replace("\"test\":\"jvmTest\"", "\"test\":null"))
            .project.modules.single().targets.single().application)
        for (invalid in listOf("null", "[]", "{}", """{"entryPoint":"MainKt","command":"sh"}""")) assertFails { parse(selected(invalid)) }
        val nested = selected(ApplicationSpec("sample.Outer\$Nested").toJson().canonical())
        parse(nested)
        assertFails { parse(nested.replace("\"name\":\"jvm\"", "\"name\":\"macosArm64\"")) }
        parse(selected(ApplicationSpec("sample.main").toJson().canonical()).replace("\"name\":\"jvm\"", "\"name\":\"macosArm64\""))
    }

    @Test fun nativeTimeoutsAreBoundedOptionalAndPreserveTheDocument() {
        val native = example.replace("\"name\":\"jvm\"", "\"name\":\"macosArm64\"")
        fun selected(value: String) = native.replace("\"compilerOptions\":[]", "\"compilerOptions\":[],\"nativeTimeouts\":$value")
        assertNull(parse(native).project.modules.single().targets.single().nativeTimeouts)
        for ((json, expected) in listOf(
            """{"compileMillis":1}""" to NativeTimeouts(compileMillis = 1),
            """{"linkMillis":3600000}""" to NativeTimeouts(linkMillis = 3_600_000),
            """{"compileMillis":180000,"linkMillis":600000}""" to NativeTimeouts(180_000, 600_000),
        )) {
            val parsed = parse(selected(json))
            assertEquals(expected, parsed.project.modules.single().targets.single().nativeTimeouts)
            assertEquals(parseJson(selected(json)), parsed.document)
            assertEquals(parsed, parse(parsed.document.canonical()))
            assertNotEquals(parse(native).digest, parsed.digest)
            assertEquals(expected, parse(selected(json).replace("\"test\":\"jvmTest\"", "\"test\":null"))
                .project.modules.single().targets.single().nativeTimeouts)
            assertFails { parse(selected(json).replace("\"name\":\"macosArm64\"", "\"name\":\"jvm\"")) }
        }
        for (json in listOf("null", "[]", "{}", """{"compileMillis":1,"typo":1}""", """{"timeoutMillis":1}"""))
            assertFails("accepted $json") { parse(selected(json)) }
        for (field in listOf("compileMillis", "linkMillis")) {
            for (value in listOf("null", "true", "\"600000\"", "1.5", "[]", "{}", "0", "-1", "3600001", "9223372036854775807", "9223372036854775808"))
                assertFails("accepted $field=$value") { parse(selected("""{"$field":$value}""")) }
        }
        assertFails { NativeTimeouts() }
        assertFails { NativeTimeouts(compileMillis = 0) }
        assertFails { NativeTimeouts(linkMillis = 3_600_001) }
        assertFails { TargetSpec("jvm", "main", null, emptyList(), nativeTimeouts = NativeTimeouts(linkMillis = 1)) }
    }

    @Test fun nativeKotestSelectionIsPinnedExplicitAndDoesNotChangeLegacyTargets() {
        val settings = """{"engine":"kotest","kspVersion":"2.3.12","processor":"io.kotest:kotest-framework-symbol-processor:6.2.5"}"""
        val native = example.replace("\"name\":\"jvm\"", "\"name\":\"macosArm64\"")
        fun selected(value: String = settings) = native.replace("\"compilerOptions\":[]", "\"compilerOptions\":[],\"nativeTestSettings\":$value")
        val configuration = parse(selected())
        assertEquals(NativeTestSettings("kotest", "2.3.12", Coordinate.parse("io.kotest:kotest-framework-symbol-processor:6.2.5")),
            configuration.project.modules.single().targets.single().nativeTestSettings)
        assertNull(parse(native).project.modules.single().targets.single().nativeTestSettings)
        val builtin = parse(selected("""{"engine":"kotlin","maxRetries":2}"""))
        assertEquals(NativeTestSettings("kotlin", maxRetries = 2), builtin.project.modules.single().targets.single().nativeTestSettings)
        val inspected = projectSettingsInspection(builtin, LogicalPath("kvik.json"))
        assertEquals(2.json(), inspected.getValue("target:library:macosArm64:nativeTestSettings:maxRetries").value)
        for (count in listOf(-1, 10, 4294967296L)) assertFails { parse(selected("""{"engine":"kotlin","maxRetries":$count}""")) }
        assertFails { parse(selected(settings.replace("kotest", "kotlin"))) }
        assertFails { parse(selected("""{"engine":"kotest","maxRetries":1}""")) }
        assertNotEquals(parse(native).digest, configuration.digest)
        assertEquals(configuration, parse(configuration.document.canonical()))
        for (value in listOf("null", "{}", settings.replace("2.3.12", "2.3.13"), settings.replace("6.2.5", "6.2.4"),
            settings.replace("kotest\"", "junit\""), settings.replace("{", "{\"filter\":\"*\","))) {
            assertFails { parse(selected(value)) }
        }
        assertFails { parse(selected().replace("\"name\":\"macosArm64\"", "\"name\":\"jvm\"")) }
        assertFails { parse(selected().replace("\"test\":\"jvmTest\"", "\"test\":null")) }
    }
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

    @Test fun configuredRetriesAreBoundedForAllJvmEngines() {
        for (count in listOf(0, 1, 9)) {
            val configured = parse(withSettings("""{"classes":["sample.Test"],"maxRetries":$count}"""))
            assertEquals(count, configured.project.modules.single().targets.single().testSettings!!.maxRetries)
        }
        for (value in listOf("-1", "10", "4294967296", "true", "1.5", "null", "\"1\""))
            assertFails { parse(withSettings("""{"classes":[],"maxRetries":$value}""")) }
        for (engines in listOf(listOf("kotest"), listOf("junit", "kotest"))) {
            assertEquals(1, JvmTestSettings(emptyList(), engines, maxRetries = 1).maxRetries)
            assertEquals(0, JvmTestSettings(emptyList(), engines).maxRetries)
        }
    }

    @Test fun absentTestSettingsRemainBackwardsCompatible() {
        assertEquals(TargetSpec("jvm", "jvmMain", "jvmTest", emptyList()), parse().project.modules.single().targets.single())
        assertNull(parse(example.replace("\"test\":\"jvmTest\"", "\"test\":null")).project.modules.single().targets.single().testSettings)
        assertNull(parse(example.replace("\"name\":\"jvm\"", "\"name\":\"macosArm64\"")).project.modules.single().targets.single().testSettings)
        assertEquals(parseJson(example), parse().document)
    }

    @Test fun omittedClassesEnableDiscoveryAndPreserveEngineSettings() {
        assertEquals(JvmTestSettings(emptyList(), discover = true), parse(withSettings("{}")).project.modules.single().targets.single().testSettings)
        val settings = parse(withSettings("""{"engines":["kotest"],"maxRetries":1}""")).project.modules.single().targets.single().testSettings!!
        assertTrue(settings.discover)
        assertEquals(listOf("kotest"), settings.engines)
        assertEquals(1, settings.maxRetries)
        assertFalse(parse(withSettings("""{"classes":[]}""")).project.modules.single().targets.single().testSettings!!.discover)
    }

    @Test fun invalidTestSelectionsFailBeforeExecution() {
        for (settings in listOf(
            "null", "[]", """{"classes":null}""", """{"classes":[],"typo":true}""",
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
