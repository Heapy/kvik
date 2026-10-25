package kvik.projectgraph

import kvik.core.*
import kvik.compiler.jvm.*
import kvik.compiler.js.*
import kotlin.test.*

class TestLaunchesTest {
    private val digest = Sha256.digest(byteArrayOf())
    private val resources = TestResources(768, setOf("database"), 2)
    private fun file(path: String, executable: Boolean = false) = FileEntry(LogicalPath(path), digest, executable)

    @Test fun javaAgentsBindOrderedOptionsDeclaredInputsAndOutputs() {
        val options = mutableListOf(JvmAgentOption(JvmAgentOptionKind.LITERAL, "config="),
            JvmAgentOption(JvmAgentOptionKind.INPUT_FILE, "agent/config.txt"), JvmAgentOption(JvmAgentOptionKind.LITERAL, ",out="),
            JvmAgentOption(JvmAgentOptionKind.OUTPUT_FILE, "instrumentation/probe/report.bin"))
        val agent = JvmTestAgent("probe", LogicalPath("agent/probe.jar"), options, listOf(LogicalPath("instrumentation/probe/report.bin")))
        val launch = JvmTestLaunch(JvmTestSelection("app", listOf(LogicalPath("classes")), listOf("fixture.Tests"), agents = listOf(agent)))
        options.clear()
        val inputs = TreeManifest(listOf(file("classes/Test.class"), file("agent/probe.jar"), file("agent/config.txt")))
        val tools = listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith { digest }
        val action = launch.action(inputs, digest, tools)
        launch.outputContract.requireMatches(action)
        assertEquals(CachePolicy.NEVER, action.cachePolicy)
        assertEquals("-javaagent:input/agent/probe.jar=config=input/agent/config.txt,out=output/instrumentation/probe/report.bin", launch.selection.agents.single().argument())
        assertEquals(launch.parameters, JvmTestSelection.fromJson(launch.parameters).toJson())
        for (path in listOf("agent/probe.jar", "agent/config.txt")) {
            assertFails { launch.action(TreeManifest(inputs.files.filter { it.path.value != path }), digest, tools) }
            val changed = TreeManifest(inputs.files.map { if (it.path.value == path) it.copy(digest = Sha256.digest(byteArrayOf(1))) else it })
            assertNotEquals(action.key(Sha256), launch.action(changed, digest, tools).key(Sha256))
        }
        val changedOptions = launch.selection.agents.single().copy(options = listOf(JvmAgentOption(JvmAgentOptionKind.LITERAL, "changed")))
        assertNotEquals(action.key(Sha256), JvmTestLaunch(launch.selection.copy(agents = listOf(changedOptions))).action(inputs, digest, tools).key(Sha256))
        assertFails { agent.copy(jar = LogicalPath("agent=bad.jar")) }
        assertFails { agent.copy(outputs = listOf(LogicalPath("test-results.json"))) }
        assertFails { launch.selection.copy(agents = listOf(agent, agent)) }
        assertFails { JvmAgentOption(JvmAgentOptionKind.LITERAL, "bad\u0000option") }
        assertFails { agent.copy(options = listOf(JvmAgentOption(JvmAgentOptionKind.OUTPUT_FILE, "instrumentation/probe/undeclared"))) }
        assertFails { agent.copy(outputs = listOf(LogicalPath("instrumentation/probe/a"), LogicalPath("instrumentation/probe/a/b"))) }
    }

    @Test fun launchSettingsMatchAllAdaptersAndRejectRecipeDrift() {
        val inputs = TreeManifest(listOf(file("classes/Main.class"), file("tests.kexe", true), file("tests.mjs")))
        val jvmTools = listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith { digest }
        val cases = listOf(
            JvmTestLaunch(JvmTestSelection("app", listOf(LogicalPath("classes")), listOf("fixture.Tests"), resources = resources), 77_000) to jvmTools,
            NativeTestLaunch("app", NativeTarget.MACOS_ARM64, LogicalPath("tests.kexe"), filter = "fixture.*", resources = resources) to
                NativeTarget.MACOS_ARM64.toolIdentityNames.associateWith { digest },
            NativeTestLaunch("app", NativeTarget.LINUX_X64, LogicalPath("tests.kexe"), kotest = true, resources = resources) to
                NativeTarget.LINUX_X64.toolIdentityNames.associateWith { digest },
        ) + KotlinJsTarget.entries.map { target ->
            JsTestLaunch(JsTestSelection("app", target, LogicalPath("tests.mjs"), "fixture*", resources), 42_000) to mapOf("node" to digest)
        }
        for ((launch, tools) in cases) {
            val action = launch.action(inputs, digest, tools)
            launch.requireMatches(action)
            val description = launch.toJson().values
            assertEquals(action.implementation.json(), description.getValue("implementation"))
            assertEquals(action.target.json(), description.getValue("target"))
            assertEquals(action.parameters, description.getValue("parameters"))
            assertEquals(action.timeoutMillis.json(), description.getValue("timeoutMillis"))
            assertEquals(resources, TestResources.fromJson(description.getValue("resources")))
            for (changed in listOf(action.copy(timeoutMillis = 1), action.copy(arguments = action.arguments + "extra"),
                action.copy(environment = emptyMap()), action.copy(outputs = emptyList()))) {
                assertFailsWith<IllegalArgumentException> { launch.requireMatches(changed) }
            }
        }
    }

    @Test fun settingsSnapshotCallerCollectionsAndValidateInstrumentationAtResolution() {
        val classes = mutableListOf("fixture.Tests")
        val roots = mutableListOf(LogicalPath("classes"))
        val excludes = mutableListOf("generated.*")
        val source = CoverageSource("app", "main", LogicalPath("src/Main.kt"), digest)
        val coverage = JvmCoverageSpec(JACOCO_VERSION, digest, digest,
            listOf(CoverageClassRoot("app", LogicalPath("classes"))), listOf(source), excludes)
        val launch = JvmTestLaunch(JvmTestSelection("app", roots, classes, coverage = coverage))
        classes.clear(); roots.clear(); excludes.clear()
        assertEquals(listOf("fixture.Tests"), launch.selection.classes)
        assertEquals(listOf("generated.*"), launch.selection.coverage!!.excludes)
        val tools = listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith { digest }
        val inputs = TreeManifest(listOf(file("classes/Main.class"), FileEntry(source.inputPath, digest)))
        assertEquals(coverage.copy(excludes = listOf("generated.*")).toJson(), launch.parameters.values.getValue("coverage"))
        launch.requireMatches(launch.action(inputs, digest, tools))
        assertFailsWith<IllegalArgumentException> { launch.action(TreeManifest(inputs.files.take(1)), digest, tools) }
        val bad = JvmTestLaunch(launch.selection.copy(coverage = launch.selection.coverage!!.copy(
            classDirectories = listOf(CoverageClassRoot("app", LogicalPath("undeclared"))))))
        assertFailsWith<IllegalArgumentException> { bad.action(inputs, digest, tools) }
        assertFailsWith<IllegalArgumentException> { NativeTestLaunch("app", NativeTarget.MACOS_ARM64, LogicalPath("tests"), kotest = true, filter = "*") }
        assertFailsWith<IllegalArgumentException> { JvmTestLaunch(launch.selection, 0) }
    }
}
