package kvik.compiler.native

import kvik.core.*

/** Decode only complete records; an interrupted UTF-8 tail cannot erase prior evidence. */
fun kotestNativeReport(module: String, request: String, bytes: ByteArray, process: ProcessResult): NativeTestReport {
    val decoded = StringBuilder()
    var start = 0
    if (bytes.size <= 16 * 1024 * 1024) {
        for (index in bytes.indices) {
            if (bytes[index].toInt() != 10) continue
            try { decoded.append(bytes.decodeToString(start, index + 1, throwOnInvalidSequence = true)) }
            catch (_: CharacterCodingException) { break }
            start = index + 1
        }
    }
    // A deliberately incomplete tail marks undecodable/truncated/oversized bytes;
    // it is never decoded with replacement or interpreted as a protocol record.
    if (start != bytes.size) decoded.append('{')
    return kotestNativeReport(module, request, decoded.toString(), process)
}

/** Discovery is finalized from typed leaf callbacks only after the single engine invocation closes. */
fun kotestNativeReport(module: String, request: String, text: String, process: ProcessResult): NativeTestReport {
    val results = linkedMapOf<String, TestResult>()
    val activeSpecs = mutableSetOf<String>()
    val doneSpecs = mutableSetOf<String>()
    val activeTests = mutableMapOf<String, Pair<String, String>>()
    val knownTests = mutableMapOf<String, Pair<String, String>>()
    var inventory: Set<String>? = null
    var started = false
    var initialized = false
    var finished = false
    var returned = false
    var main = false
    var unknownLeaves = false
    var engineFailure: String? = null
    var protocolError: String? = null
    var sequence = 0L
    var version: Long? = null
    val displayNames = mutableMapOf<String, String?>()
    try {
        require(request.matches(Regex("[0-9a-f]{64}")))
        require(text.length <= 16 * 1024 * 1024) { "Oversized Kotest journal" }
        // Only newline-terminated records are evidence; preserve their validated prefix
        // when an interrupted writer leaves an unfinished final record.
        val completePrefix = text.substring(0, text.lastIndexOf('\n') + 1)
        for (line in if (completePrefix.isEmpty()) emptyList() else completePrefix.dropLast(1).split('\n')) {
            require(line.length <= 64 * 1024 && sequence < 100_000 && !main) { "Oversized or trailing Kotest journal" }
            val raw = parseJson(line).objectValue()
            fun string(name: String) = raw.getValue(name).stringValue()
            fun message() = string("message").also { require(it.length <= 8192) }
            fun keys(vararg names: String) { require(raw.keys == setOf("version", "request", "sequence", "event") + names) { "Unexpected Kotest event fields" } }
            val recordVersion = raw.getValue("version").longValue()
            require(recordVersion in 1L..2L && (version == null || recordVersion == version) &&
                string("request") == request && raw.getValue("sequence").longValue() == sequence++) { "Kotest journal identity/order differs" }
            version = recordVersion
            val enriched = recordVersion == 2L
            fun testKeys(vararg names: String) = keys(*(names.toList() + if (enriched) listOf("displayName") else emptyList()).toTypedArray())
            fun displayName(): String? = if (enriched) string("displayName").also { require(it.length <= TEST_TEXT_LIMIT) } else null
            fun nullableText(value: JsonValue, limit: Int): String? = if (value == JsonValue.Null) null else
                value.stringValue().also { require(it.length <= limit) }
            fun failure(): TestFailure? = raw.getValue("failure").let { if (it == JsonValue.Null) null else TestFailure.fromJson(it) }
            val event = string("event")
            fun spec(): String = string("spec").also { require(it in checkNotNull(inventory)) { "Unknown Kotest spec" } }
            fun test(): Pair<String, Pair<String, String>> {
                require(initialized && !finished)
                val owner = spec()
                require(owner in activeSpecs) { "Test outside an active spec" }
                val id = string("id")
                val type = string("type")
                require(id.startsWith("$owner.") && id.length <= 8192 && type in setOf("leaf", "container")) { "Invalid typed test identity" }
                return id to (owner to type)
            }
            when (event) {
                "inventory" -> {
                    keys("specs")
                    require(inventory == null && sequence == 1L)
                    val specs = raw.getValue("specs").arrayValue().map { it.stringValue() }
                    require(specs.size <= 10_000 && specs.distinct().size == specs.size && specs.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) })
                    inventory = specs.toSet()
                }
                "engine-start" -> { keys(); require(inventory != null && !started); started = true }
                "engine-initialized" -> { keys(); require(started && !initialized && !finished); initialized = true }
                "spec-start" -> { keys("spec"); require(initialized && !finished); val id = spec(); require(id !in doneSpecs && activeSpecs.add(id)) }
                "spec-ignored" -> {
                    keys("spec", "message"); require(started && !finished)
                    val id = spec(); require(id !in activeSpecs && doneSpecs.add(id)); message(); unknownLeaves = true
                }
                "test-start" -> {
                    testKeys("spec", "id", "type"); val (id, value) = test()
                    require(id !in knownTests && activeTests.put(id, value) == null) { "Duplicate test start" }; knownTests[id] = value
                    displayNames[id] = displayName()
                }
                "test-ignored" -> {
                    testKeys("spec", "id", "type", "message"); val (id, value) = test()
                    require(id !in knownTests) { "Duplicate ignored test" }; knownTests[id] = value
                    val reason = message()
                    val name = displayName()
                    if (value.second == "leaf") results[id] = TestResult(id, module, "macosArm64", TestStatus.SKIPPED, reason,
                        TestDetails(value.first.boundedTestText(TEST_TEXT_LIMIT), name,
                            listOf(TestAttempt(1, TestStatus.SKIPPED, skipReason = reason))))
                    else unknownLeaves = true
                }
                "test-finish" -> {
                    testKeys(*(listOf("spec", "id", "type", "status", "message") +
                        if (enriched) listOf("durationNanos", "skipReason", "failure") else emptyList()).toTypedArray())
                    val (id, value) = test()
                    require(activeTests.remove(id) == value) { "Test finish without matching start" }
                    require(displayNames[id] == displayName()) { "Kotest display name changed during execution" }
                    val status = string("status"); require(status in setOf("Success", "Failure", "Error", "Ignored"))
                    val detail = message()
                    val duration = if (enriched) raw.getValue("durationNanos").let { if (it == JsonValue.Null) null else
                        it.longValue().also { nanos -> require(nanos >= 0) } } else null
                    val reason = if (enriched) nullableText(raw.getValue("skipReason"), TEST_TEXT_LIMIT) else detail.takeIf { status == "Ignored" && it.isNotEmpty() }
                    val failure = if (enriched) failure() else if (status in setOf("Failure", "Error"))
                        TestFailure(TestFailureKind.UNKNOWN, message = detail.takeIf { it.isNotEmpty() }) else null
                    require(status in setOf("Failure", "Error") || failure == null) { "Non-failing Kotest result contains failure details" }
                    require(status == "Ignored" || reason == null) { "Non-skipped Kotest result contains skip reason" }
                    if (value.second == "leaf") {
                        val testStatus = when (status) {
                            "Success" -> TestStatus.PASSED; "Ignored" -> TestStatus.SKIPPED; else -> TestStatus.FAILED
                        }
                        results[id] = TestResult(id, module, "macosArm64", testStatus, detail.takeIf { it.isNotEmpty() },
                            TestDetails(value.first.boundedTestText(TEST_TEXT_LIMIT), displayNames[id],
                                listOf(TestAttempt(1, testStatus, duration, reason, failure ?: if (testStatus == TestStatus.FAILED) TestFailure(TestFailureKind.UNKNOWN) else null))))
                    }
                    else if (status != "Success") { unknownLeaves = true; if (status != "Ignored") engineFailure = detail.ifEmpty { "Kotest container failed" } }
                }
                "spec-finish" -> {
                    keys("spec", "status", "message"); require(initialized && !finished)
                    val id = spec(); require(activeSpecs.remove(id) && doneSpecs.add(id) && activeTests.values.none { it.first == id })
                    val status = string("status"); require(status in setOf("Success", "Failure", "Error", "Ignored"))
                    val detail = message()
                    if (status != "Success") { unknownLeaves = true; if (status != "Ignored") engineFailure = detail.ifEmpty { "Kotest spec failed" } }
                }
                "engine-finish" -> {
                    keys("errors", "message"); require(started && !finished && activeSpecs.isEmpty() && activeTests.isEmpty())
                    val errors = raw.getValue("errors").longValue(); require(errors in 0..10_000)
                    val detail = message()
                    if (errors > 0) { engineFailure = detail.ifEmpty { "Kotest engine failed" }; unknownLeaves = true }
                    if (doneSpecs != inventory) unknownLeaves = true
                    finished = true
                }
                "engine-return" -> {
                    keys("errors"); require(finished && !returned)
                    val errors = raw.getValue("errors").longValue(); require(errors in 0..10_000)
                    if (errors > 0) { engineFailure = engineFailure ?: "Kotest engine returned errors"; unknownLeaves = true }; returned = true
                }
                "process-main" -> { keys(); require(returned); main = true }
                else -> error("Unsupported Kotest event: $event")
            }
        }
        require(text.endsWith('\n')) { "Missing or truncated Kotest journal" }
        require(main && activeSpecs.isEmpty() && activeTests.isEmpty()) { "Kotest invocation did not complete" }
    } catch (failure: IllegalArgumentException) { protocolError = failure.message ?: "Malformed Kotest journal" }
      catch (failure: IllegalStateException) { protocolError = failure.message ?: "Incomplete Kotest journal" }
      catch (failure: NoSuchElementException) { protocolError = failure.message ?: "Missing Kotest journal fields" }
    val complete = main && protocolError == null && !unknownLeaves && process.exitCode == 0 && !process.cancelled && !process.timedOut
    val tests = results.values.toList()
    val counts = TestCounts(if (complete) tests.size else null, tests.count { it.status != TestStatus.SKIPPED },
        tests.count { it.status == TestStatus.FAILED }, tests.count { it.status == TestStatus.SKIPPED }, complete)
    val status = when {
        process.cancelled -> VerificationStatus.CANCELLED
        engineFailure != null || counts.failed > 0 -> VerificationStatus.FAILED
        process.timedOut || protocolError != null || !main -> VerificationStatus.INCOMPLETE
        !counts.satisfiesExecution -> VerificationStatus.INCOMPLETE
        else -> VerificationStatus.PASSED
    }
    val diagnostic = protocolError ?: engineFailure ?: when {
        process.cancelled -> "Kotest invocation cancelled"
        process.timedOut -> "Kotest invocation timed out"
        process.exitCode != 0 -> "Kotest process exited ${process.exitCode}"
        unknownLeaves -> "Kotest could not enumerate all leaves (ignored/failed/missing spec or container)"
        counts.executed == 0 -> "No Kotest leaves executed"
        counts.failed > 0 -> "Kotest reported failing leaves"
        else -> null
    }
    return NativeTestReport(tests, counts, status, diagnostic)
}
