package kvik.runevidence

import kvik.core.*
import kotlin.test.*

class VerificationJsonTest {
    private val digest = Sha256.digest("identity".encodeToByteArray())
    private fun record(status: VerificationStatus = VerificationStatus.PASSED): VerificationRecord = aggregateRunEvidence(
        "run", setOf("app:jvm:test"), mapOf("app:jvm:test" to "test"), mapOf("test" to emptySet()),
        listOf(ActionResult("test", digest, ActionStatus.EXECUTED, exitCode = 0)),
        mapOf("test" to TestObservation(listOf(TestResult("example", "app", "jvm", TestStatus.PASSED)), TestCounts(1, 1, 0, 0, true), VerificationStatus.PASSED)),
        digest, digest, digest, digest, mapOf("compiler" to digest), listOf("kvik", "test"), Freshness.CURRENT,
    ).record.copy(status = status)
    private fun JsonValue.change(vararg values: Pair<String, JsonValue>) = JsonValue.Object(objectValue() + values.toMap())
    private fun invalid(document: JsonValue) { assertFails { verificationRecordFromJson(parseJson(document.canonical())) } }

    @Test fun canonicalRoundTripPreservesEveryRecordFieldAndStatus() {
        for (status in VerificationStatus.entries) {
            val original = record(status)
            assertEquals(original, verificationRecordFromJson(parseJson(original.toJson().canonical())))
        }
        val zero = record(VerificationStatus.INCOMPLETE).copy(completedChecks = emptySet(), actions = emptyList(), tests = emptyList(),
            testCounts = TestCounts(null, 0, 0, 0, false), freshnessAtCompletion = Freshness.INDETERMINATE)
        assertEquals(zero, verificationRecordFromJson(zero.toJson()))
    }

    @Test fun unknownMissingAndMistypedFieldsAreRejectedAtEveryLayer() {
        val document = record().toJson()
        for (name in document.objectValue().keys) invalid(JsonValue.Object(document.objectValue() - name))
        invalid(document.change("typo" to true.json()))
        invalid(document.change("schemaVersion" to 2.json()))
        for (name in listOf("status", "freshnessAtCompletion")) invalid(document.change(name to "UNKNOWN".json()))
        invalid(document.change("status" to "PASSED".json()))
        invalid(document.change("inputSnapshot" to obj("hash" to digest.hash.json(), "sizeBytes" to (-1).json())))
        invalid(document.change("toolchains" to listOf(digest.toJson()).json()))
        val action = document.objectValue().getValue("actions").arrayValue().single()
        for (change in listOf(action.change("extra" to true.json()), action.change("key" to JsonValue.Null), action.change("exitCode" to 1.json()),
            action.change("exitCode" to Long.MAX_VALUE.json()), action.change("status" to "executed_unknown".json()), action.change("actionId" to "".json()),
            action.change("outputs" to obj("schemaVersion" to 1.json(), "files" to emptyList<JsonValue>().json(), "extra" to true.json())))) {
            invalid(document.change("actions" to listOf(change).json()))
        }
        val test = document.objectValue().getValue("tests").arrayValue().single()
        invalid(document.change("tests" to listOf(test.change("extra" to true.json())).json()))
        invalid(document.change("tests" to listOf(test.change("status" to "PASSED".json())).json()))
        val counts = document.objectValue().getValue("testCounts")
        for (change in listOf(counts.change("extra" to true.json()), counts.change("executed" to Long.MAX_VALUE.json()),
            counts.change("executed" to 0.json()), counts.change("discovered" to 0.json()), counts.change("discoveryComplete" to JsonValue.Null)))
            invalid(document.change("testCounts" to change))
    }

    @Test fun duplicateEvidenceAndContradictoryPassingRecordsAreRejected() {
        val document = record().toJson()
        for (field in listOf("requestedChecks", "completedChecks", "actions", "tests")) {
            val values = document.objectValue().getValue(field).arrayValue()
            invalid(document.change(field to (values + values).json()))
        }
        invalid(document.change("completedChecks" to listOf("other:jvm:test".json()).json()))
        invalid(document.change("completedChecks" to emptyList<JsonValue>().json()))
        invalid(document.change("freshnessAtCompletion" to "stale".json()))
        invalid(document.change("reproduction" to emptyList<JsonValue>().json()))
        invalid(document.change("tests" to emptyList<JsonValue>().json(), "testCounts" to TestCounts(0, 0, 0, 0, true).toJson()))
        val overflow = TestCounts(Int.MAX_VALUE, Int.MAX_VALUE, 0, Int.MAX_VALUE, true).toJson()
        invalid(document.change("testCounts" to overflow))
    }

    @Test fun checkGrammarAndNonCacheableTestExecutionAreRequired() {
        val document = record().toJson()
        for (check in listOf("", "garbage", "app:jvm", "app:wasm:test", "app:jvm:publish", "1app:jvm:test", "app:jvm:test\n")) {
            invalid(document.change("requestedChecks" to listOf(check.json()).json(), "completedChecks" to listOf(check.json()).json()))
        }
        val action = document.objectValue().getValue("actions").arrayValue().single()
        for (status in listOf("local_hit", "remote_hit")) invalid(document.change("actions" to listOf(action.change("status" to status.json())).json()))
        val twoChecks = record().copy(requestedChecks = setOf("app:jvm:test", "other:macosArm64:test"),
            completedChecks = setOf("app:jvm:test", "other:macosArm64:test"),
            tests = record().tests + TestResult("other", "other", "macosArm64", TestStatus.PASSED), testCounts = TestCounts(2, 2, 0, 0, true))
        invalid(twoChecks.toJson())
        val executed = twoChecks.copy(actions = twoChecks.actions + ActionResult("other-test", digest, ActionStatus.EXECUTED, exitCode = 0))
        assertEquals(executed, verificationRecordFromJson(executed.toJson()))
        val build = record().copy(requestedChecks = setOf("app:jvm:build"), completedChecks = setOf("app:jvm:build"),
            actions = listOf(ActionResult("compile", digest, ActionStatus.LOCAL_HIT, exitCode = 0)), tests = emptyList(), testCounts = TestCounts(0, 0, 0, 0, true))
        assertEquals(build, verificationRecordFromJson(build.toJson()))
    }
}
