package kvik.contracts

import kvik.core.*
import kotlin.test.*

class ContractTest {
    @Test fun applicationLifecycleEventsAreAcceptedWithoutAdmittingUnknownTypes() {
        val digest = Sha256.digest(byteArrayOf()).toJson()
        val payloads = mapOf(
            "application-prepared" to obj("directory" to "/run".json(), "descriptor" to digest, "actionId" to "app:jvm:application".json()),
            "application-started" to obj("preparationRun" to "prepared".json(), "descriptor" to digest, "module" to "app".json(),
                "target" to "jvm".json(), "stdout" to "/run/out".json(), "stderr" to "/run/err".json()),
            "application-finished" to obj("preparationRun" to "prepared".json(), "exitCode" to 17.json(),
                "cancelled" to false.json(), "timedOut" to false.json(), "stdout" to "/run/out".json(), "stderr" to "/run/err".json()),
        )
        for ((kind, payload) in payloads) {
            val event = RunEvent("run", 1, kind, payload)
            val document = parseJson(event.toJson().canonical()).objectValue()
            assertEquals(kind.json(), document.getValue("type"))
            assertEquals(payload, document.getValue("payload"))
        }
        assertFails { RunEvent("run", 1, "application-unknown", obj()) }
        assertFails { RunEvent("run", 0, "application-started", payloads.getValue("application-started")) }
    }

    @Test fun sha256KnownAnswers() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.digest(byteArrayOf()).hash)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.digest("abc".encodeToByteArray()).hash)
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", Sha256.digest(ByteArray(1_000_000) { 'a'.code.toByte() }).hash)
    }

    @Test fun canonicalJsonIsUnambiguous() {
        val value = parseJson(""" {"z":[3,2,1],"a":"x\n\t\u0000","emoji":"\ud83d\ude00"} """)
        assertEquals("""{"a":"x\u000a\u0009\u0000","emoji":"😀","z":[3,2,1]}""", value.canonical())
        assertEquals(value, parseJson(value.canonical()))
        assertEquals(parseJson("""{"b":2,"a":1}""").canonical(), parseJson("""{"a":1,"b":2}""").canonical())
    }

    @Test fun malformedOrAmbiguousJsonIsRejected() {
        for (text in listOf("""{"a":1,"a":2}""", "[1,]", "01", "1.0", "1e3", "9223372036854775808", "true false", "\"\\ud800\"", "\"\\udc00\"", "\"\n\"", "{", "[")) {
            assertFails("accepted $text") { parseJson(text) }
        }
        assertFails { parseJson("[[[[]]]]", maxDepth = 2) }
    }

    @Test fun unsafePathsAndFileDirectoryCollisionsAreRejected() {
        for (path in listOf("", "/tmp/a", "../a", "a/../b", "a/./b", "a//b", "a/", "C:x", "a\\b", "a\u0000b")) {
            assertFails("accepted $path") { LogicalPath(path) }
        }
        val digest = Sha256.digest(byteArrayOf())
        assertFails { TreeManifest(listOf(FileEntry(LogicalPath("a"), digest), FileEntry(LogicalPath("a/b"), digest))) }
        assertFails { TreeManifest(listOf(FileEntry(LogicalPath("a"), digest), FileEntry(LogicalPath("a"), digest))) }
    }

    private fun action(): ActionSpec {
        val empty = Sha256.digest(byteArrayOf())
        return ActionSpec("kvik.jvm-compile.v1", empty, ActionKind.COMPILE, mapOf("os" to "macos", "arch" to "arm64"), "jvm",
            mapOf("compiler" to empty), TreeManifest(listOf(FileEntry(LogicalPath("src/Hello.kt"), Sha256.digest("hello".encodeToByteArray())))),
            listOf(LogicalPath("classes")), listOf("-jvm-target", "21"), mapOf("LANG" to "C"))
    }

    @Test fun identityTracksSemanticChangesAndPreservesOrdering() {
        val original = action()
        val key = original.key(Sha256)
        assertEquals(Digest("9c917f50d8db74a23e5398444b0575c6b2f2bd4605b7d45690a3c86c788049fc", 675), key)
        val changed = Sha256.digest("changed".encodeToByteArray())
        for (mutation in listOf(original.copy(composition = changed), original.copy(target = "macosArm64"),
            original.copy(arguments = original.arguments.reversed()), original.copy(environment = mapOf("LANG" to "en_US")),
            original.copy(toolchains = mapOf("compiler" to changed)), original.copy(inputs = TreeManifest(emptyList())),
            original.copy(inputs = TreeManifest(listOf(FileEntry(LogicalPath("src/Hello.kt"), changed)))),
            original.copy(inputs = TreeManifest(listOf(FileEntry(LogicalPath("src/Renamed.kt"), original.inputs.files.single().digest)))),
            original.copy(parameters = obj("plugin" to changed.toJson())))) {
            assertNotEquals(key, mutation.key(Sha256))
        }
        assertEquals(key, original.copy(platform = linkedMapOf("arch" to "arm64", "os" to "macos")).key(Sha256))
    }

    @Test fun effectsCannotBecomeCacheHits() {
        for (kind in listOf(ActionKind.TEST, ActionKind.SCRIPT, ActionKind.PUBLISH, ActionKind.SERVER, ActionKind.BENCHMARK)) {
            assertFails { action().copy(kind = kind) }
            action().copy(kind = kind, cachePolicy = CachePolicy.NEVER)
        }
    }

    @Test fun graphRejectsMissingDependenciesAndReportsCyclePath() {
        validateGraph(mapOf("app" to setOf("plugin"), "plugin" to emptySet()))
        assertFails { validateGraph(mapOf("app" to setOf("missing"))) }
        val failure = assertFails { validateGraph(mapOf("app" to setOf("plugin"), "plugin" to setOf("app"))) }
        assertContains(failure.message ?: "", "app -> plugin -> app")
    }

    @Test fun zeroTestsAndUnavailableInputsCannotProveSuccess() {
        assertFalse(TestCounts(0, 0, 0, 0, true).satisfiesExecution)
        assertFalse(TestCounts(null, 1, 0, 0, false).satisfiesExecution)
        assertFalse(TestCounts(2, 1, 0, 0, true).satisfiesExecution)
        assertTrue(TestCounts(2, 1, 0, 1, true).satisfiesExecution)
        val original = Sha256.digest("a".encodeToByteArray())
        assertEquals(Freshness.CURRENT, freshness(original, original))
        assertEquals(Freshness.STALE, freshness(original, Sha256.digest("b".encodeToByteArray())))
        assertEquals(Freshness.INDETERMINATE, freshness(original, null))
    }

    @Test fun verificationCannotClaimMissingScopesOrInventTestCounts() {
        val digest = Sha256.digest(byteArrayOf())
        val result = ActionResult("hello:jvm:compile", digest, ActionStatus.LOCAL_HIT, exitCode = 0)
        val record = VerificationRecord("run", setOf("hello:jvm:test"), setOf("hello:jvm:test"), digest, digest, digest, digest,
            mapOf("compiler" to digest), listOf(result), listOf(TestResult("suite.test", "hello", "jvm", TestStatus.PASSED)),
            TestCounts(1, 1, 0, 0, true), VerificationStatus.PASSED, Freshness.CURRENT, listOf("kvik", "test"))
        assertFails { record.copy(testCounts = TestCounts(2, 2, 0, 0, true)) }
        assertFails { record.copy(requestedChecks = setOf("hello:jvm:test", "hello:macosArm64:test")) }
        assertFails { record.copy(freshnessAtCompletion = Freshness.STALE) }
        assertFails { record.copy(tests = emptyList(), testCounts = TestCounts(0, 0, 0, 0, true)) }
        assertFails { record.copy(tests = record.tests + record.tests, testCounts = TestCounts(2, 2, 0, 0, true)) }
    }

    @Test fun mavenCoordinatesCannotEscapeRepositoriesOrSelectMutableVersions() {
        assertEquals("org.example:library:1.0", Coordinate.parse("org.example:library:1.0").toString())
        for (value in listOf("org..example:x:1", "org.example:..:1", "org.example:x:..", "org.example:x:1-SNAPSHOT", "org.example:x:[1,2)", "org.example:x:1+")) {
            assertFails("accepted $value") { Coordinate.parse(value) }
        }
    }

    @Test fun workerProtocolRejectsVersionSkewAndContradictorySuccess() {
        val diagnostic = Diagnostic("KVIK_COMPILATION_ERROR", DiagnosticCategory.COMPILATION, "Unresolved reference", path = "src/Hello.kt", line = 1)
        val result = WorkerResult("request", WorkerStatus.COMPILATION_ERROR, 1, listOf(diagnostic))
        assertEquals(result, WorkerResult.fromJson(parseJson(result.toJson().canonical())))
        assertFails { result.copy(status = WorkerStatus.SUCCESS) }
        val encoded = result.toJson().objectValue()
        assertFails { WorkerResult.fromJson(JsonValue.Object(encoded + ("schemaVersion" to 2.json()))) }
        assertFails { WorkerResult.fromJson(JsonValue.Object(encoded + ("unknown" to true.json()))) }
        assertFails { TestResult("", "hello", "jvm", TestStatus.PASSED) }
        assertFails { Digest.fromJson(obj("hash" to Sha256.digest(byteArrayOf()).hash.json(), "sizeBytes" to 0.json(), "algorithm" to "sha1".json())) }
    }
}
