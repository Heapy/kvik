package kvik.compiler.native

/** Compiled into the runtime composition, then injected into a private Native compilation directory. */
val kotestRuntimeSource: String = """
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, io.kotest.common.KotestInternal::class)
package kvik.kotest.runtime

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.SpecRef
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestType
import io.kotest.engine.TestEngineLauncher
import io.kotest.engine.listener.AbstractTestEngineListener
import io.kotest.engine.listener.TestEngineInitializedContext
import io.kotest.engine.test.TestResult
import io.kotest.engine.test.names.DisplayNameFormatting
import kotlinx.cinterop.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.*
import kotlin.reflect.KClass

private fun quote(value: String): String = buildString {
    append('"')
    for (c in value) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
    }
    append('"')
}

private class Journal {
    private val request = getenv("KVIK_KOTEST_REQUEST")?.toKString() ?: error("Missing Kotest request identity")
    private val path = getenv("KVIK_KOTEST_REPORT")?.toKString() ?: error("Missing Kotest report path")
    private val fd = open(path, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, 384)
    private var sequence = 0
    private var bytes = 0
    init { check(fd >= 0 && request.matches(Regex("[0-9a-f]{64}"))) }
    fun write(event: String, fields: String = "") {
        check(sequence < 100_000)
        val data = ("{\"version\":1,\"request\":" + quote(request) + ",\"sequence\":" + sequence++ + ",\"event\":" + quote(event) + fields + "}\n").encodeToByteArray()
        bytes += data.size
        check(data.size <= 64 * 1024 && bytes <= 16 * 1024 * 1024)
        data.usePinned { pinned ->
            var offset = 0
            while (offset < data.size) {
                val count = platform.posix.write(fd, pinned.addressOf(offset), (data.size - offset).toULong())
                if (count < 0 && errno == EINTR) continue
                check(count > 0); offset += count.toInt()
            }
        }
    }
    fun sync() { check(fsync(fd) == 0) }
    fun close() { check(platform.posix.close(fd) == 0) }
}
private object State { var journal: Journal? = null; var engineReturned = false }
private fun field(name: String, value: String) = "," + quote(name) + ":" + quote(value)
private fun message(error: Throwable?) = (error?.let { it::class.simpleName + ": " + it.message } ?: "").take(8192)

public suspend fun invokeTestEngine(specs: List<SpecRef>, config: AbstractProjectConfig?): Unit {
    check(State.journal == null) { "Multiple Kotest launches are unsupported" }
    val journal = Journal().also { State.journal = it }
    val mutex = Mutex()
    suspend fun emit(event: String, fields: String = "") { mutex.withLock { journal.write(event, fields) } }
    val names: Map<KClass<*>, String> = specs.associate { it.kclass to it.fqn }
    check(names.size == specs.size && specs.map { it.fqn }.distinct().size == specs.size)
    journal.write("inventory", ",\"specs\":[" + specs.joinToString(",") { quote(it.fqn) } + "]")
    var formatting = DisplayNameFormatting(config)
    fun id(test: TestCase): String {
        // Pinned Kotest 6.2.5 TeamCityPathRenderer / TeamCityTestNameEscaper semantics.
        val name = formatting.format(test).replace(".", " ")
        return test.parent?.let { id(it) + " ⇢ " + name } ?: (names.getValue(test.spec::class) + "." + name)
    }
    fun details(test: TestCase) = field("spec", names.getValue(test.spec::class)) + field("id", id(test)) + field("type", if (test.type == TestType.Test) "leaf" else "container")
    val listener = object : AbstractTestEngineListener() {
        override suspend fun engineStarted() { emit("engine-start") }
        override suspend fun engineInitialized(context: TestEngineInitializedContext) { formatting = DisplayNameFormatting(context.projectConfig); emit("engine-initialized") }
        override suspend fun engineFinished(t: List<Throwable>) { emit("engine-finish", ",\"errors\":" + t.size + field("message", t.joinToString("; ") { message(it) }.take(8192))) }
        override suspend fun specStarted(ref: SpecRef) { emit("spec-start", field("spec", ref.fqn)) }
        override suspend fun specIgnored(kclass: KClass<*>, reason: String?) { emit("spec-ignored", field("spec", names.getValue(kclass)) + field("message", reason.orEmpty().take(8192))) }
        override suspend fun specFinished(ref: SpecRef, result: TestResult) { emit("spec-finish", field("spec", ref.fqn) + field("status", result.name) + field("message", message(result.errorOrNull))) }
        override suspend fun testStarted(testCase: TestCase) { emit("test-start", details(testCase)) }
        override suspend fun testIgnored(testCase: TestCase, reason: String?) { emit("test-ignored", details(testCase) + field("message", reason.orEmpty().take(8192))) }
        override suspend fun testFinished(testCase: TestCase, result: TestResult) { emit("test-finish", details(testCase) + field("status", result.name) + field("message", message(result.errorOrNull))) }
    }
    val result = TestEngineLauncher().withSpecRefs(specs).withProjectConfig(config).withoutEnvFilters()
        .withConsoleListener().withTeamCityListener().withListener(listener).execute()
    journal.write("engine-return", ",\"errors\":" + result.errors.size)
    journal.sync()
    State.engineReturned = true
}

public fun main(): Unit {
    check(State.engineReturned) { "Kotest engine did not return" }
    val journal = checkNotNull(State.journal)
    journal.write("process-main")
    journal.sync()
    journal.close()
}
""".trimIndent() + "\n"
