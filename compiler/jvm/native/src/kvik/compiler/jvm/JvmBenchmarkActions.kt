package kvik.compiler.jvm

import kvik.core.*

fun jvmBenchmarkHarnessAction(harness: JvmBenchmarkHarness, inputs: TreeManifest, composition: Digest,
                              toolchains: Map<String, Digest>, timeoutMillis: Long = 120_000): ActionSpec {
    require(toolchains.keys == jvmIdentities)
    harness.requireInputs(inputs)
    return ActionSpec("kvik-jvm-benchmark-harness-v1", composition, ActionKind.GENERATE, jvmHostPlatform(), "jvm", toolchains.toMap(),
        TreeManifest(inputs.files.toList()), listOf(LogicalPath("benchmark-harness")), listOf("kvik.jvm.BenchmarkWorker", "harness"),
        mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin"), harness.toJson(), timeoutMillis, CachePolicy.LOCAL)
}

fun jvmBenchmarkAction(run: JvmBenchmarkRun, inputs: TreeManifest, composition: Digest, toolchains: Map<String, Digest>): ActionSpec {
    require(toolchains.keys == jvmIdentities)
    run.requireInputs(inputs)
    return ActionSpec("kvik-jvm-benchmark-v1", composition, ActionKind.BENCHMARK, jvmHostPlatform(), "jvm", toolchains.toMap(),
        TreeManifest(inputs.files.toList()), listOf(LogicalPath("benchmark-summary.json"), LogicalPath("benchmark-results.json")),
        listOf("kvik.jvm.BenchmarkWorker", "run"), mapOf("LANG" to "C.UTF-8", "PATH" to "/usr/bin:/bin"),
        run.toJson(), run.settings.timeoutMillis, CachePolicy.NEVER)
}
