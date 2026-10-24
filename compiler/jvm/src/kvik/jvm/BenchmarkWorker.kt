package kvik.jvm

import kvik.core.*
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.tools.ToolProvider
import kotlin.system.exitProcess

/** One foreground-owned JVM prepares a reusable harness or performs a fresh, forked JMH run. */
object BenchmarkWorker {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3) { "BenchmarkWorker <workspace> <request> <result>" }
        val workspace = Workspace(Path.of(args[0]))
        var requestId = "invalid-request"
        val result = try {
            val request = workspace.readJson(args[1]).fields(setOf("schemaVersion", "requestId", "operation", "parameters"))
            request.requireVersion()
            requestId = request.getValue("requestId").stringValue()
            when (request.getValue("operation").stringValue()) {
                "harness" -> prepare(workspace, JvmBenchmarkHarness.fromJson(request.getValue("parameters")))
                "run" -> measure(workspace, JvmBenchmarkRun.fromJson(request.getValue("parameters")))
                else -> error("Unknown benchmark operation")
            }
            WorkerResult(requestId, WorkerStatus.SUCCESS, 0, emptyList())
        } catch (failure: Exception) {
            var cause: Throwable = failure
            while (cause is InvocationTargetException) cause = cause.targetException
            val message = (cause.message ?: cause.javaClass.simpleName).take(4096).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
            WorkerResult(requestId, WorkerStatus.INFRASTRUCTURE_ERROR, 1,
                listOf(Diagnostic("KVIK_BENCHMARK_WORKER", DiagnosticCategory.INFRASTRUCTURE, message)))
        }
        workspace.publish(args[2], result.toJson())
        exitProcess(result.exitCode)
    }

    private fun classpath(workspace: Workspace, paths: List<LogicalPath>): List<Path> {
        val declared = paths.map { workspace.classpath("input/${it.value}") }
        val worker = Path.of(BenchmarkWorker::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()
        val stdlib = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()
        require(System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Path.of(it).toRealPath() } == listOf(worker, stdlib) + declared) {
            "Unexpected benchmark worker bootstrap classpath"
        }
        return declared
    }

    private fun prepare(workspace: Workspace, harness: JvmBenchmarkHarness) {
        classpath(workspace, harness.classpath)
        val loader = BenchmarkWorker::class.java.classLoader
        val classes = harness.classes.map { Class.forName(it, false, loader) }
        val output = workspace.path("output")
        val generated = Files.createDirectory(output.resolve("benchmark-harness"))
        val sources = Files.createTempDirectory(workspace.root, ".benchmark-sources-")
        try {
            val sourceType = type("org.openjdk.jmh.generators.reflection.RFGeneratorSource")
            val source = sourceType.getConstructor().newInstance()
            sourceType.getMethod("processClasses", Collection::class.java).invoke(source, classes)
            val destinationType = type("org.openjdk.jmh.generators.core.FileSystemDestination")
            val destination = destinationType.getConstructor(java.io.File::class.java, java.io.File::class.java).newInstance(generated.toFile(), sources.toFile())
            val generatorType = type("org.openjdk.jmh.generators.core.BenchmarkGenerator")
            val generator = generatorType.getConstructor().newInstance()
            val signature = arrayOf(type("org.openjdk.jmh.generators.core.GeneratorSource"), type("org.openjdk.jmh.generators.core.GeneratorDestination"))
            generatorType.getMethod("generate", *signature).invoke(generator, source, destination)
            generatorType.getMethod("complete", *signature).invoke(generator, source, destination)
            require(call(destination, "hasErrors") == false) { "JMH harness generation failed: ${call(destination, "getErrors")}" }
            val entries = entries(generated)
            require(entries.isNotEmpty() && entries.map { call(it, "getUserClassQName") }.toSet() == selectedNames(harness.classes).keys) {
                "Every declared benchmark class must contribute a generated benchmark"
            }
            workspace.publish("output/benchmark-harness/kvik-metadata.json", JvmBenchmarkHarnessMetadata(entries.map(::groupSize).distinct().sorted()).toJson())
            val javaSources = Files.walk(sources).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.toString().endsWith(".java") }.sorted().toList() }
            require(javaSources.isNotEmpty() && javaSources.size <= 100_000) { "Missing or excessive generated JMH sources" }
            val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "JMH preparation needs a JDK Java compiler" }
            compiler.getStandardFileManager(null, null, Charsets.UTF_8).use { manager ->
                val options = listOf("-classpath", System.getProperty("java.class.path"), "-d", generated.toString(), "-proc:none", "-encoding", "UTF-8")
                require(compiler.getTask(null, manager, null, options, null, manager.getJavaFileObjectsFromPaths(javaSources)).call()) { "JMH harness compilation failed" }
            }
        } finally { workspace.removeTree(sources) }
    }

    private fun measure(workspace: Workspace, run: JvmBenchmarkRun) {
        val paths = classpath(workspace, run.classpath)
        val settings = run.effectiveSettings
        val names = selectedNames(settings.classes)
        val entries = entries(paths.first())
        val expected = expectedResults(entries, names, settings.mode)
        val groupSizes = entries.associate { (call(it, "getUsername") as String) to groupSize(it) }
        val metadata = JvmBenchmarkHarnessMetadata.fromJson(workspace.readJson("input/${run.classpath.first().value}/kvik-metadata.json"))
        require(metadata.groupSizes == groupSizes.values.distinct().sorted()) { "JMH harness group metadata differs" }
        metadata.maximumThreads(settings.threads)
        val builderType = type("org.openjdk.jmh.runner.options.OptionsBuilder")
        val builder = builderType.getConstructor().newInstance()
        fun option(name: String, kind: Class<*>, value: Any) { builderType.getMethod(name, kind).invoke(builder, value) }
        names.keys.forEach { option("include", String::class.java, "^${Pattern.quote(it)}\\.[^.]+$") }
        option("shouldFailOnError", Boolean::class.javaPrimitiveType!!, true)
        option("forks", Int::class.javaPrimitiveType!!, settings.forks)
        option("warmupForks", Int::class.javaPrimitiveType!!, 0)
        option("threads", Int::class.javaPrimitiveType!!, settings.threads)
        option("warmupIterations", Int::class.javaPrimitiveType!!, settings.warmups)
        option("measurementIterations", Int::class.javaPrimitiveType!!, settings.iterations)
        val timeType = type("org.openjdk.jmh.runner.options.TimeValue")
        val time = timeType.getMethod("milliseconds", Long::class.javaPrimitiveType).invoke(null, settings.iterationMillis)
        option("warmupTime", timeType, time); option("measurementTime", timeType, time)
        option("mode", type("org.openjdk.jmh.annotations.Mode"), enumValue("org.openjdk.jmh.annotations.Mode", if (settings.mode == "avgt") "AverageTime" else "Throughput"))
        option("timeUnit", TimeUnit::class.java, when (settings.outputTimeUnit) {
            "ns" -> TimeUnit.NANOSECONDS; "us" -> TimeUnit.MICROSECONDS; "ms" -> TimeUnit.MILLISECONDS
            "s" -> TimeUnit.SECONDS; "m" -> TimeUnit.MINUTES; else -> error("Unsupported time unit")
        })
        option("jvm", String::class.java, Path.of(System.getProperty("java.home"), "bin", "java").toString())
        // Keep forks on the verified JDK and the owner's private home/temp directories.
        option("jvmArgs", Array<String>::class.java, arrayOf("-Djava.io.tmpdir=${System.getProperty("java.io.tmpdir")}", "-Duser.home=${System.getProperty("user.home")}"))
        option("jvmArgsAppend", Array<String>::class.java, emptyArray<String>())
        option("jvmArgsPrepend", Array<String>::class.java, emptyArray<String>())
        val rawReport = workspace.path("output/benchmark-results.json", false)
        option("result", String::class.java, rawReport.toString())
        option("resultFormat", type("org.openjdk.jmh.results.format.ResultFormatType"), enumValue("org.openjdk.jmh.results.format.ResultFormatType", "JSON"))
        val runner = type("org.openjdk.jmh.runner.Runner").getConstructor(type("org.openjdk.jmh.runner.options.Options")).newInstance(call(builder, "build"))
        val rawResults = call(runner, "run") as Collection<*>
        require(rawResults.size in 1..100_000) { "JMH returned no measurements or excessive results" }
        val results = rawResults.map { raw ->
            val result = requireNotNull(raw)
            val params = call(result, "getParams")
            val name = call(params, "getBenchmark") as String
            val size = groupSizes.getValue(name)
            val effectiveThreads = ((settings.threads + size - 1) / size) * size
            require(call(params, "getForks") == settings.forks && call(params, "getThreads") == effectiveThreads) { "JMH run settings differ" }
            for ((method, count) in listOf("getWarmup" to settings.warmups, "getMeasurement" to settings.iterations)) {
                val iteration = call(params, method)
                require(call(iteration, "getCount") == count && call(iteration, "getTime") == time) { "JMH iteration settings differ" }
            }
            val forks = call(result, "getBenchmarkResults") as Collection<*>
            require(forks.size == settings.forks && forks.all { fork ->
                (call(requireNotNull(fork), "getIterationResults") as Collection<*>).size == settings.iterations
            }) { "JMH returned an incomplete fork or iteration result" }
            val primary = call(result, "getPrimaryResult")
            val score = call(primary, "getScore") as Double
            val error = call(primary, "getScoreError") as Double
            require(score.isFinite() && (error.isNaN() || error.isFinite())) { "JMH returned a nonfinite measurement" }
            val parameterKeys = (call(params, "getParamsKeys") as Collection<*>).map { it as String }.sorted()
            val parameters = parameterKeys.associateWith { params.javaClass.getMethod("getParam", String::class.java).invoke(params, it) as String }
            val mode = call(call(params, "getMode"), "shortLabel") as String
            JvmBenchmarkResult(binaryBenchmarkName(call(params, "getBenchmark") as String, names), mode, parameters, score.toString(), error.takeIf { it.isFinite() }?.toString(),
                call(primary, "getScoreUnit") as String, call(primary, "getSampleCount") as Long)
        }.sortedBy { it.identity }
        require(results.map { it.identity }.toSet() == expected && results.size == expected.size) { "JMH measurements do not cover every generated benchmark and parameter combination" }
        require(Files.isRegularFile(rawReport, NOFOLLOW_LINKS) && Files.size(rawReport) in 1..64L * 1024 * 1024) { "Missing or oversized JMH JSON report" }
        workspace.publish("output/benchmark-summary.json", JvmBenchmarkReport(run.module, run.profile, settings.classes, results).toJson())
    }

    private fun entries(root: Path): List<Any> {
        val list = root.resolve("META-INF/BenchmarkList")
        require(Files.isRegularFile(list, NOFOLLOW_LINKS) && Files.size(list) in 1..4L * 1024 * 1024) { "Missing or oversized generated JMH benchmark list" }
        return Files.newInputStream(list, NOFOLLOW_LINKS).use { stream ->
            @Suppress("UNCHECKED_CAST")
            (type("org.openjdk.jmh.runner.BenchmarkList").getMethod("readBenchmarkList", java.io.InputStream::class.java).invoke(null, stream) as List<Any>)
                .also { require(it.size in 1..100_000) }
        }
    }

    /** JMH's generated metadata already incorporates inherited methods, groups and enum parameters. */
    private fun expectedResults(entries: List<Any>, names: Map<String, String>, mode: String): Set<String> {
        require(entries.map { call(it, "getUserClassQName") }.toSet() == names.keys) { "Generated harness classes differ from selection" }
        val expected = linkedSetOf<String>()
        for (entry in entries) {
            val optional = call(entry, "getParams")
            @Suppress("UNCHECKED_CAST")
            val params = if (call(optional, "hasValue") == true) call(optional, "get") as Map<String, Array<String>> else emptyMap()
            var combinations = listOf(emptyMap<String, String>())
            for ((name, values) in params.toSortedMap()) {
                require(values.isNotEmpty() && values.distinct().size == values.size) { "Empty or duplicate JMH parameter values" }
                require(combinations.size.toLong() * values.size <= 100_000) { "Excessive JMH parameter combinations" }
                combinations = combinations.flatMap { previous -> values.map { previous + (name to it) } }
            }
            for (parameters in combinations) {
                expected += JvmBenchmarkResult(binaryBenchmarkName(call(entry, "getUsername") as String, names), mode, parameters, "0", null, "ns/op", 1).identity
                require(expected.size <= 100_000) { "Excessive benchmark combinations" }
            }
        }
        return expected.also { require(it.isNotEmpty()) }
    }

    private fun groupSize(entry: Any): Int = (call(entry, "getThreadGroups") as IntArray).let { groups ->
        require(groups.isNotEmpty() && groups.all { it in 0..1024 }) { "Invalid JMH thread group" }
        groups.sum().also { require(it in 1..1024) { "JMH method group exceeds 1024 threads" } }
    }

    // JMH uses canonical Java names, while project declarations and the normalized contract use JVM binary names.
    private fun selectedNames(classes: List<String>): Map<String, String> = classes.associateBy { name ->
        val loaded = Class.forName(name, false, BenchmarkWorker::class.java.classLoader)
        (loaded.canonicalName ?: loaded.name).replace('$', '.')
    }.also { require(it.size == classes.size) { "Benchmark class names collide in JMH metadata" } }

    private fun binaryBenchmarkName(name: String, names: Map<String, String>): String =
        requireNotNull(names[name.substringBeforeLast('.')]) { "Unexpected benchmark class: $name" } + "." + name.substringAfterLast('.')

    private fun type(name: String): Class<*> = Class.forName(name, false, BenchmarkWorker::class.java.classLoader)
    private fun call(receiver: Any, method: String): Any = requireNotNull(receiver.javaClass.getMethod(method).invoke(receiver))
    private fun enumValue(className: String, name: String): Any = requireNotNull(type(className).enumConstants.singleOrNull { (it as Enum<*>).name == name })
}
