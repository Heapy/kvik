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
        assertFails { workspace.copy(targets = setOf("macosArm64")) }
        assertFails { workspace.copy(scopes = setOf("script")) }
        assertFails { legacy.copy(id = "example.plugin") }
    }
}
