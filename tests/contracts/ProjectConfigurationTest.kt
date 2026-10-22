package kvik.core

import kotlin.test.*

class ProjectConfigurationTest {
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
