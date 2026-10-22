package kvik.core

import kotlin.test.*

class CompilerPluginsTest {
    private val input = LogicalPath("input/message.txt")
    private val output = LogicalPath("output/report")
    private val plugin = JvmPluginApplication("example.plugin", "2.4.20", listOf(LogicalPath("input/classes")),
        listOf("value=one", "value=two=three"), listOf(input),
        listOf(PluginPathOption("file", PluginPathKind.INPUT_FILE, input), PluginPathOption("report", PluginPathKind.OUTPUT_DIRECTORY, output)), listOf(output))

    @Test fun pluginAndWorkerRoundtripPreserveOrderedArguments() {
        assertEquals(plugin, JvmPluginApplication.fromJson(parseJson(plugin.toJson().canonical())))
        val request = WorkerRequest("compile", "jvm-compile", Sha256.digest(byteArrayOf()), listOf("input/a.kt"), emptyList(), emptyList(), emptyList(), "output/classes", listOf(plugin))
        assertEquals(request, WorkerRequest.fromJson(parseJson(request.toJson().canonical())))
        val legacy = request.copy(pluginApplications = emptyList())
        assertFalse("pluginApplications" in legacy.toJson().objectValue())
        assertEquals(legacy, WorkerRequest.fromJson(legacy.toJson()))
        assertFails { request.copy(pluginClasspath = listOf("serialization.jar")) }
        assertFails { request.copy(pluginApplications = listOf(plugin, plugin)) }
    }

    @Test fun incompatibleOrUnboundPluginsAreRejected() {
        assertFails { plugin.copy(compilerVersion = "2.4.10") }
        assertFails { plugin.copy(classpath = emptyList()) }
        assertFails { plugin.copy(inputs = emptyList()) }
        assertFails { plugin.copy(outputs = emptyList()) }
        assertFails { plugin.copy(options = listOf("missing-value")) }
        assertFails { plugin.copy(options = listOf("file=undeclared")) }
        assertFails { plugin.copy(pathOptions = emptyList()) }
        assertEquals(2, plugin.copy(inputs = listOf(input, LogicalPath("input/ancillary.txt"))).inputs.size)
        assertFails { plugin.copy(outputs = listOf(output, LogicalPath("output/report/nested"))) }
        assertFails { plugin.copy(classpath = listOf(output)) }
        assertFails { JvmPluginApplication.fromJson(JsonValue.Object(plugin.toJson().objectValue() + ("unknown" to true.json()))) }
    }

    @Test fun workspaceContractIsBoundedAndPublishedDefaultsSurvive() {
        val legacy = CompilerPluginSpec(null, Coordinate.parse("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.20"), setOf("jvm"), setOf("main"), emptyList(), emptyList())
        assertNull(legacy.id)
        val workspace = CompilerPluginSpec("producer", null, setOf("jvm"), setOf("main", "test"), emptyList(), emptyList(), "example.plugin", "2.4.20")
        assertFails { workspace.copy(id = null) }
        assertEquals(setOf("macosArm64"), workspace.copy(targets = setOf("macosArm64")).targets)
        assertEquals(setOf("jvm", "macosArm64"), workspace.copy(targets = setOf("jvm", "macosArm64")).targets)
        assertFails { workspace.copy(targets = setOf("linuxX64")) }
        assertEquals(setOf("script"), workspace.copy(scopes = setOf("script")).scopes)
        assertFails { workspace.copy(targets = setOf("macosArm64"), scopes = setOf("script")) }
        assertFails { workspace.copy(targets = setOf("jvm", "macosArm64"), scopes = setOf("script")) }
        assertFails { workspace.copy(scopes = setOf("main", "script")) }
        val native = workspace.copy(targets = setOf("macosArm64"))
        assertFails { native.copy(options = listOf("missing-value")) }
        assertFails { native.copy(pathOptions = listOf(PluginPathOption("file", PluginPathKind.INPUT_FILE, input))) }
        assertFails { native.copy(outputs = listOf(output, LogicalPath("output/report/nested"))) }
        assertFails { legacy.copy(id = "example.plugin") }
    }

    @Test fun nativeConsumersUseJvmProducersAndPreserveSelectedScopes() {
        for (targets in listOf(listOf("macosArm64"), listOf("jvm"), listOf("jvm", "macosArm64"))) {
            for (scopes in listOf(listOf("main"), listOf("test"), listOf("main", "test"))) {
                val parsed = configuration(targets, scopes)
                val selected = parsed.project.modules.last().compilerPlugins.single()
                assertEquals(targets.toSet(), selected.targets)
                assertEquals(scopes.toSet(), selected.scopes)
                assertEquals(parsed, parseProjectConfiguration(parseJson(parsed.document.canonical())))
                assertEquals("jvm", parsed.project.modules.first().targets.single().name)
            }
        }
        assertFailsWith<IllegalArgumentException> { configuration(listOf("macosArm64"), producerTarget = "macosArm64") }
        assertFailsWith<IllegalArgumentException> { configuration(listOf("macosArm64"), producer = "missing") }
        assertFailsWith<IllegalArgumentException> { configuration(listOf("macosArm64"), producer = "consumer") }
        assertFailsWith<IllegalArgumentException> { configuration(listOf("macosArm64"), consumerTargets = listOf("jvm")) }
        assertFailsWith<IllegalArgumentException> { configuration(listOf("jvm"), scopes = listOf("script")) }
    }

    @Test fun everySelectedTestCompilationMustExistWithoutRequiringUnselectedTests() {
        for (missing in listOf("jvm", "macosArm64")) {
            assertFailsWith<IllegalArgumentException> {
                configuration(listOf("jvm", "macosArm64"), listOf("test"), missingTests = setOf(missing))
            }
            val selected = if (missing == "jvm") "macosArm64" else "jvm"
            configuration(listOf(selected), listOf("test"), missingTests = setOf(missing))
        }
        configuration(listOf("macosArm64"), listOf("test"), consumerTargets = listOf("macosArm64"))
        configuration(listOf("jvm", "macosArm64"), listOf("main"), missingTests = setOf("jvm", "macosArm64"))
    }

    private fun configuration(
        targets: List<String>, scopes: List<String> = listOf("main"), missingTests: Set<String> = emptySet(),
        consumerTargets: List<String> = listOf("jvm", "macosArm64"), producerTarget: String = "jvm",
        producer: String = "producer",
    ): ProjectConfiguration {
        fun strings(values: List<String>) = values.joinToString(",") { "\"$it\"" }
        val selectedTargets = consumerTargets.joinToString(",") {
            """{"name":"$it","main":"main","test":${if (it in missingTests) "null" else "\"test\""},"compilerOptions":[]}"""
        }
        return parseProjectConfiguration(parseJson("""{
            "schemaVersion":1,"name":"plugins","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],
            "modules":[
                {"id":"producer","sourceSets":[{"name":"main","directories":["producer"],"resources":[],"dependsOn":[],"dependencies":[]}],
                 "targets":[{"name":"$producerTarget","main":"main","test":null,"compilerOptions":[]}],"compilerPlugins":[]},
                {"id":"consumer","sourceSets":[
                    {"name":"main","directories":["consumer"],"resources":[],"dependsOn":[],"dependencies":[]},
                    {"name":"test","directories":["test"],"resources":[],"dependsOn":["main"],"dependencies":[]}],
                 "targets":[$selectedTargets],"compilerPlugins":[{"module":"$producer","id":"example.plugin","compilerVersion":"2.4.20",
                    "targets":[${strings(targets)}],"scopes":[${strings(scopes)}],"options":[],"inputs":[]}]}
            ]}
        """))
    }
}
