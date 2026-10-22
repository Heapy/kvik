package kvik.foreground.activationtests
import kotlin.test.*
import kvik.foreground.*
import kvik.core.*

private val d = Sha256.digest("fixture".encodeToByteArray())
private val ready = ApplicationReady(d, "/private/tmp/run", d, d, d, "app", "jvm")
private fun JsonValue.bytes() = canonical().encodeToByteArray()

class ApplicationActivationTest {
    @Test fun recordsRoundTripAndBindExactReady() {
        assertEquals(ready, ApplicationReady.parse(ready.toJson().bytes()))
        val activation = ApplicationActivation(d, Sha256.digest(ready.toJson()))
        assertEquals(activation, ApplicationActivation.parse(activation.toJson().bytes()))
        activation.requireReady(ready)
        val started = ApplicationStarted(d, activation.ready, "/private/tmp/run/execution")
        assertEquals(started, ApplicationStarted.parse(started.toJson().bytes()))
    }
    @Test fun mismatchedTokenAndReadyDigestFail() {
        val activation = ApplicationActivation(d, Sha256.digest(ready.toJson()))
        assertFails { activation.copy(token = Sha256.digest("other".encodeToByteArray())).requireReady(ready) }
        assertFails { activation.copy(ready = d).requireReady(ready) }
        assertFails { activation.requireReady(ready.copy(preparationDirectory = "/other")) }
        assertFails { activation.requireReady(ready.copy(module = "other")) }
    }
    @Test fun unknownMissingVersionAndNoncanonicalRecordsFail() {
        for (value in listOf(JsonValue.Object(ready.toJson().values + ("extra" to true.json())),
            JsonValue.Object(ready.toJson().values - "module"), JsonValue.Object(ready.toJson().values + ("schemaVersion" to 2.json())))) {
            assertFails { ApplicationReady.parse(value.bytes()) }
        }
        assertFails { ApplicationReady.parse(ready.toJson().bytes() + byteArrayOf(10)) }
        assertFails { ApplicationActivation.parse("{}".encodeToByteArray()) }
        assertFails { ApplicationStarted.parse("{}".encodeToByteArray()) }
        assertFails { ApplicationReady.parse(byteArrayOf(-1)) }
    }
    @Test fun pathsScopesAndUnicodeBoundsFailClosed() {
        for (path in listOf("relative", "/", "/x/../y", "/x/./y", "/x//y", "/x/", "/x\n", "/x\u0000", "/" + "x".repeat(4096))) {
            assertFails { ready.copy(preparationDirectory = path) }
            assertFails { ApplicationStarted(d, d, path) }
        }
        assertFails { ready.copy(module = "app\n") }
        assertFails { ready.copy(target = "native") }
        assertFails { ready.copy(preparationDirectory = "/" + "☃".repeat(3000)) }
        assertFails { ready.copy(preparationDirectory = "/" + "\uD800") }
    }
    @Test fun oversizedAndInvalidDigestRecordsReject() {
        assertFails { ApplicationReady.parse(ByteArray(8193) { 32 }) }
        assertFails { ApplicationActivation.parse(ByteArray(8193) { 32 }) }
        assertFails { ApplicationStarted.parse(ByteArray(8193) { 32 }) }
        val bad = JsonValue.Object(ready.toJson().values + ("token" to obj("hash" to (d.hash + "\n").json(), "sizeBytes" to 0.json())))
        assertFails { ApplicationReady.parse(bad.bytes()) }
    }
}
