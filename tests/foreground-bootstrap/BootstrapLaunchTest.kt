package kvik.foreground

import kvik.core.*
import kotlin.test.*

class BootstrapLaunchTest {
    private val fields get() = (parseJson(producerDescriptor.decodeToString()) as JsonValue.Object).values
    private fun changed(name: String, value: JsonValue) = JsonValue.Object(fields + (name to value)).canonical().encodeToByteArray()

    @Test fun pythonProducerRoundtripPreservesLiteralArgvAndCurrentBinding() {
        val launch = BootstrapLaunch.parse(producerDescriptor)
        val arguments = fields.getValue("bootstrapArguments").arrayValue().map { it.stringValue() }
        assertEquals(listOf(launch.python, launch.launcher) + arguments + "--print-composition", launch.command(BootstrapQuery.COMPOSITION))
        assertEquals(listOf(launch.python, launch.launcher) + arguments + "--print-launch", launch.command(BootstrapQuery.LAUNCH))
        assertTrue(arguments.any { "$(literal)" in it && "/../" in it })
        assertEquals(listOf("--offline", "--no-build"), arguments.takeLast(2))
        launch.requireCurrent(launch.composition, launch.runtime)
        assertFails { launch.requireCurrent(Sha256.digest("other".encodeToByteArray()), launch.runtime) }
        assertFails { launch.requireCurrent(launch.composition, "/another/kvik.kexe") }
    }

    @Test fun replacementCannotChangePolicyOwnershipOrRequestedComposition() {
        val current = BootstrapLaunch.parse(producerDescriptor)
        val next = Sha256.digest("changed source".encodeToByteArray())
        val replacementFields = fields + mapOf("composition" to next.toJson(),
            "runtime" to current.runtime.replace(current.composition.hash, next.hash).json())
        val replacement = BootstrapLaunch.parse(JsonValue.Object(replacementFields).canonical().encodeToByteArray())
        replacement.requireReplacement(current, next)
        assertFails { replacement.requireReplacement(current, current.composition) }
        for ((name, value) in listOf("python" to "/another/python".json(),
            "bootstrapArguments" to fields.getValue("bootstrapArguments").arrayValue().dropLast(1).json())) {
            val changed = BootstrapLaunch.parse(JsonValue.Object(replacementFields + (name to value)).canonical().encodeToByteArray())
            assertFails { changed.requireReplacement(current, next) }
        }
        val relocated = BootstrapLaunch.parse(JsonValue.Object(replacementFields + mapOf(
            "sourceRoot" to "/other-sources".json(), "launcher" to "/other-sources/kvik".json())).canonical().encodeToByteArray())
        assertFails { relocated.requireReplacement(current, next) }
    }

    @Test fun malformedFlagsAndUnboundPathsNeverBecomeCommands() {
        val original = fields.getValue("bootstrapArguments").arrayValue().map { it.stringValue() }
        val invalid = listOf(
            original + "--offline", original.dropLast(2) + listOf("--print-launch"),
            original.dropLast(2) + listOf("--java-home", "/another/jdk"),
            listOf("--offline") + original.dropLast(2),
            original.map { if (it == "--cache-dir") "--unknown" else it },
            original.filterIndexed { i, _ -> i !in 4..5 },
            original.toMutableList().also { it[1] = "relative/jdk" },
            original.toMutableList().also { it[1] = "/a\u0000b" },
            original.toMutableList().also { it[7] = "/other-cache" },
        )
        for (arguments in invalid) assertFails { BootstrapLaunch.parse(changed("bootstrapArguments", arguments.map { it.json() }.json())) }
        for ((name, value) in listOf("runtime" to "/old/kvik.kexe", "launcher" to "/other/kvik", "sourceRoot" to "relative")) {
            assertFails { BootstrapLaunch.parse(changed(name, value.json())) }
        }
        assertFails { BootstrapLaunch.parse(changed("schemaVersion", 2.json())) }
        assertFails { BootstrapLaunch.parse(changed("extra", true.json())) }
    }

    @Test fun responsesAreBoundedStrictUtf8AndExactSchema() {
        val current = BootstrapLaunch.parse(producerDescriptor)
        val query = obj("schemaVersion" to 1.json(), "composition" to current.composition.toJson())
        assertEquals(current.composition, BootstrapLaunch.parseComposition(query.canonical().encodeToByteArray()))
        assertFails { BootstrapLaunch.parseComposition(producerDescriptor) }
        assertFails { BootstrapLaunch.parse(query.canonical().encodeToByteArray()) }
        assertFails { BootstrapLaunch.parse(producerDescriptor + ByteArray(32768) { 32 }) }
        assertFails { BootstrapLaunch.parseComposition(query.canonical().encodeToByteArray() + ByteArray(1024) { 32 }) }
        assertFails { BootstrapLaunch.parse(byteArrayOf(-1)) }
        assertFails { BootstrapLaunch.parseComposition(byteArrayOf(-1)) }
        assertFails { BootstrapLaunch.parseComposition("{\"schemaVersion\":1,\"schemaVersion\":1,\"composition\":{}}".encodeToByteArray()) }
    }
}
