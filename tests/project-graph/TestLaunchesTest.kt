package kvik.projectgraph

import kvik.core.*
import kvik.compiler.jvm.*
import kvik.compiler.js.*
import kotlin.test.*

class TestLaunchesTest {
    private val digest = Sha256.digest(byteArrayOf())
    private val resources = TestResources(768, setOf("database"), 2)
    private fun file(path: String, executable: Boolean = false) = FileEntry(LogicalPath(path), digest, executable)

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
