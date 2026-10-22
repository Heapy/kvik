package kvik.compiler.native

/** Compiled into the runtime composition, then injected into a private Native compilation directory. */
val kotestRuntimeSource: String = """
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, io.kotest.common.KotestInternal::class, kotlin.experimental.ExperimentalNativeApi::class)
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
import kotlin.time.TimeSource
import kotlin.time.TimeMark

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
        val data = ("{\"version\":2,\"request\":" + quote(request) + ",\"sequence\":" + sequence++ + ",\"event\":" + quote(event) + fields + "}\n").encodeToByteArray()
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
// Bounds account for UTF-8 and JSON escaping, so metadata cannot overflow the journal record.
private fun bounded(value: String, characters: Int = 8192, wireBytes: Int = 4096): String {
    var end = 0
    var bytes = 0
    while (end < value.length && end < characters) {
        val c = value[end]
        val pair = c.isHighSurrogate() && end + 1 < value.length && value[end + 1].isLowSurrogate()
        val count = if (pair) 2 else 1
        val cost = when { pair -> 4; c.code < 32 -> 6; c == '"' || c == '\\' -> 2; c.code < 128 -> 1; c.code < 2048 -> 2; else -> 3 }
        if (end + count > characters || bytes + cost > wireBytes) break
        end += count; bytes += cost
    }
    return value.substring(0, end)
}
private fun message(error: Throwable?) = if (error == null) "" else bounded((error::class.simpleName ?: "Throwable") + ": " + bounded(error.message.orEmpty()))
private fun nullableField(name: String, value: String?) = "," + quote(name) + ":" + (value?.let(::quote) ?: "null")
private fun failure(error: Throwable?, failed: Boolean): String {
    if (!failed) return "null"
    if (error == null) return "{\"kind\":\"unknown\",\"type\":null,\"message\":null,\"stackTrace\":null}"
    val stack = buildString {
        for (frame in error.getStackTrace()) {
            if (length >= 32768) break
            append(frame.take(32768 - length)); if (length < 32768) append('\n')
        }
    }
    return "{\"kind\":" + quote(if (error is AssertionError) "assertion" else "exception") +
        nullableField("type", (error::class.qualifiedName ?: error::class.simpleName)?.let { bounded(it, wireBytes = 1024) }) +
        nullableField("message", error.message?.let { bounded(it) }) + nullableField("stackTrace", bounded(stack, 32768, 12000)) + "}"
}

public suspend fun invokeTestEngine(specs: List<SpecRef>, config: AbstractProjectConfig?): Unit {
    check(State.journal == null) { "Multiple Kotest launches are unsupported" }
    val journal = Journal().also { State.journal = it }
    val mutex = Mutex()
    val starts = mutableMapOf<String, TimeMark>()
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
    fun details(test: TestCase) = field("spec", names.getValue(test.spec::class)) + field("id", id(test)) +
        field("type", if (test.type == TestType.Test) "leaf" else "container") + field("displayName", bounded(formatting.format(test)))
    val listener = object : AbstractTestEngineListener() {
        override suspend fun engineStarted() { emit("engine-start") }
        override suspend fun engineInitialized(context: TestEngineInitializedContext) { formatting = DisplayNameFormatting(context.projectConfig); emit("engine-initialized") }
        override suspend fun engineFinished(t: List<Throwable>) { emit("engine-finish", ",\"errors\":" + t.size + field("message", t.joinToString("; ") { message(it) }.take(8192))) }
        override suspend fun specStarted(ref: SpecRef) { emit("spec-start", field("spec", ref.fqn)) }
        override suspend fun specIgnored(kclass: KClass<*>, reason: String?) { emit("spec-ignored", field("spec", names.getValue(kclass)) + field("message", bounded(reason.orEmpty()))) }
        override suspend fun specFinished(ref: SpecRef, result: TestResult) { emit("spec-finish", field("spec", ref.fqn) + field("status", result.name) + field("message", message(result.errorOrNull))) }
        override suspend fun testStarted(testCase: TestCase) {
            mutex.withLock {
                check(starts.put(id(testCase), TimeSource.Monotonic.markNow()) == null)
                journal.write("test-start", details(testCase))
            }
        }
        override suspend fun testIgnored(testCase: TestCase, reason: String?) { emit("test-ignored", details(testCase) + field("message", bounded(reason.orEmpty()))) }
        override suspend fun testFinished(testCase: TestCase, result: TestResult) {
            mutex.withLock {
                val duration = checkNotNull(starts.remove(id(testCase))).elapsedNow().inWholeNanoseconds
                check(duration >= 0)
                journal.write("test-finish", details(testCase) + field("status", result.name) + field("message", message(result.errorOrNull)) +
                    ",\"durationNanos\":" + duration + nullableField("skipReason", result.reasonOrNull?.let { bounded(it) }) +
                    ",\"failure\":" + failure(result.errorOrNull, result.name in setOf("Failure", "Error")))
            }
        }
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
