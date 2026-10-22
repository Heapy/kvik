package kvik.compiler.native

import kvik.core.*

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
    try {
        require(request.matches(Regex("[0-9a-f]{64}")))
        require(text.length <= 16 * 1024 * 1024 && text.endsWith('\n')) { "Missing or truncated Kotest journal" }
        for (line in text.dropLast(1).split('\n')) {
            require(line.length <= 64 * 1024 && sequence < 100_000 && !main) { "Oversized or trailing Kotest journal" }
            val raw = parseJson(line).objectValue()
            fun string(name: String) = raw.getValue(name).stringValue()
            fun message() = string("message").also { require(it.length <= 8192) }
            fun keys(vararg names: String) { require(raw.keys == setOf("version", "request", "sequence", "event") + names) { "Unexpected Kotest event fields" } }
            require(raw.getValue("version").longValue() == 1L && string("request") == request && raw.getValue("sequence").longValue() == sequence++) { "Kotest journal identity/order differs" }
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
                    keys("spec", "id", "type"); val (id, value) = test()
                    require(id !in knownTests && activeTests.put(id, value) == null) { "Duplicate test start" }; knownTests[id] = value
                }
                "test-ignored" -> {
                    keys("spec", "id", "type", "message"); val (id, value) = test()
                    require(id !in knownTests) { "Duplicate ignored test" }; knownTests[id] = value
                    val reason = message()
                    if (value.second == "leaf") results[id] = TestResult(id, module, "macosArm64", TestStatus.SKIPPED, reason)
                    else unknownLeaves = true
                }
                "test-finish" -> {
                    keys("spec", "id", "type", "status", "message"); val (id, value) = test()
                    require(activeTests.remove(id) == value) { "Test finish without matching start" }
                    val status = string("status"); require(status in setOf("Success", "Failure", "Error", "Ignored"))
                    val detail = message()
                    if (value.second == "leaf") results[id] = TestResult(id, module, "macosArm64", when (status) {
                        "Success" -> TestStatus.PASSED; "Ignored" -> TestStatus.SKIPPED; else -> TestStatus.FAILED
                    }, detail.takeIf { it.isNotEmpty() })
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
        process.timedOut || protocolError != null || !main -> VerificationStatus.INCOMPLETE
        engineFailure != null || counts.failed > 0 -> VerificationStatus.FAILED
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
