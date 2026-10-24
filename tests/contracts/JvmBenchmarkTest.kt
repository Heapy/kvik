package kvik.core

import kotlin.test.*

class JvmBenchmarkTest {
    private val settings = JvmBenchmarkSettings(listOf("example.Bench"))
    private val digest = Sha256.digest("input".encodeToByteArray())
    private val classpath = listOf(LogicalPath("modules/app/jvm/main"), LogicalPath("deps/jmh.jar"))
    private val inputs = TreeManifest(listOf(FileEntry(LogicalPath("modules/app/jvm/main/example/Bench.class"), digest), FileEntry(classpath[1], digest)))
    @Test fun defaultsAndSmokeKeepExplicitIsolationAndRoundTrip() {
        assertEquals(settings, JvmBenchmarkSettings.fromJson(obj("classes" to listOf("example.Bench".json()).json())))
        assertEquals(settings, JvmBenchmarkSettings.fromJson(settings.toJson()))
        val harness = JvmBenchmarkHarness("app", classpath, settings.classes)
        harness.requireInputs(inputs)
        assertEquals(harness, JvmBenchmarkHarness.fromJson(harness.toJson()))
        val run = JvmBenchmarkRun("app", listOf(LogicalPath("harness/app")) + classpath, BenchmarkProfile.SMOKE, settings.copy(forks = 2))
        assertEquals(run, JvmBenchmarkRun.fromJson(run.toJson()))
        assertEquals(1, run.effectiveSettings.warmups)
        assertEquals(1, run.effectiveSettings.iterations)
        assertEquals(50L, run.effectiveSettings.iterationMillis)
        assertEquals(2, run.effectiveSettings.forks)
    }
    @Test fun unsafeSettingsAndMalformedInputsAreRejected() {
        for (classes in listOf(emptyList(), listOf("example.Bench", "example.Bench"), listOf("example..Bench"), listOf("example.Bench\n"))) assertFails { settings.copy(classes = classes) }
        assertFails { settings.copy(forks = 0) }
        assertFails { settings.copy(warmups = 0) }
        assertFails { settings.copy(iterations = 0) }
        assertFails { settings.copy(iterationMillis = -1) }
        assertFails { settings.copy(timeoutMillis = 86_400_001) }
        assertFails { settings.copy(mode = "all") }
        assertFails { settings.copy(outputTimeUnit = "seconds") }
        assertFails { JvmBenchmarkSettings.fromJson(JsonValue.Object(settings.toJson().values + ("threads" to Long.MAX_VALUE.json()))) }
        assertFails { JvmBenchmarkSettings.fromJson(JsonValue.Object(settings.toJson().values + ("unknown" to true.json()))) }
        val harness = JvmBenchmarkHarness("app", classpath, settings.classes)
        assertFails { harness.requireInputs(TreeManifest(inputs.files.drop(1))) }
        assertFails { harness.requireInputs(TreeManifest(inputs.files + FileEntry(LogicalPath("unrelated.txt"), digest))) }
        assertFails { harness.requireInputs(TreeManifest(inputs.files.map { it.copy(executable = true) })) }
        assertFails { JvmBenchmarkHarness("app", listOf(LogicalPath("classes"), LogicalPath("classes/nested")), settings.classes) }
        assertFails { JvmBenchmarkRun("app", classpath, BenchmarkProfile.MEASUREMENT, settings) }
        val run = JvmBenchmarkRun("app", listOf(LogicalPath("harness/app")) + classpath, BenchmarkProfile.MEASUREMENT, settings)
        assertFails { run.requireInputs(TreeManifest(inputs.files + FileEntry(LogicalPath("harness/app/Generated.class"), digest))) }
        run.requireInputs(TreeManifest(inputs.files + listOf("META-INF/BenchmarkList", "META-INF/CompilerHints").map { FileEntry(LogicalPath("harness/app/$it"), digest) }))
    }
}
