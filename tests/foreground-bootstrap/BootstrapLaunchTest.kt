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

    @Test fun sourceGraphProducerPreservesBothPathsAndOptionalCache() {
        val launch = BootstrapLaunch.parse(sourceGraphProducerDescriptor)
        val graphFields = parseJson(sourceGraphProducerDescriptor.decodeToString()).objectValue()
        val arguments = graphFields.getValue("bootstrapArguments").arrayValue().map { it.stringValue() }
        assertEquals(16, arguments.size)
        assertEquals(listOf("--source-lock", "/source locks $(literal)/../selected.json",
            "--source-cache", "/source cache $(literal)/../selected"), arguments.subList(10, 14))
        assertEquals(listOf(launch.python, launch.launcher) + arguments + "--print-composition", launch.command(BootstrapQuery.COMPOSITION))
        assertEquals(listOf(launch.python, launch.launcher) + arguments + "--print-launch", launch.command(BootstrapQuery.LAUNCH))
        launch.requireCurrent(launch.composition, launch.runtime)
        fun descriptor(values: List<String>) = JsonValue.Object(graphFields + ("bootstrapArguments" to values.map { it.json() }.json()))
            .canonical().encodeToByteArray()
        val lockOnly = arguments.filterIndexed { index, _ -> index !in 12..13 }
        assertEquals(lockOnly, BootstrapLaunch.parse(descriptor(lockOnly)).command(BootstrapQuery.LAUNCH).drop(2).dropLast(1))
        val legacy = arguments.filterIndexed { index, _ -> index !in 10..13 }
        assertEquals(legacy, BootstrapLaunch.parse(descriptor(legacy)).command(BootstrapQuery.LAUNCH).drop(2).dropLast(1))
        for (invalid in listOf(
            arguments.filterIndexed { index, _ -> index !in 10..11 }, // Cache without lock.
            arguments.take(11), // Lock has no value.
            arguments.take(13), // Cache has no value.
            arguments.take(10) + arguments.subList(12, 14) + arguments.subList(10, 12) + arguments.takeLast(2),
            arguments.take(10) + arguments.subList(10, 12) + arguments.subList(10, 12),
            arguments.take(14) + arguments.subList(12, 14),
            arguments.toMutableList().also { it[11] = "relative/lock.json" },
            arguments.toMutableList().also { it[13] = "relative/cache" },
            arguments.toMutableList().also { it[11] = "/lock\u0000.json" },
            arguments.toMutableList().also { it[13] = "/cache\u0000path" },
            arguments + "--offline",
        )) assertFails { BootstrapLaunch.parse(descriptor(invalid)) }
    }

    @Test fun sharedStorePolicySurvivesQueriesAndCannotChangeAtReplacement() {
        val launch = BootstrapLaunch.parse(sharedStoreProducerDescriptor)
        val fields = parseJson(sharedStoreProducerDescriptor.decodeToString()).objectValue()
        val arguments = fields.getValue("bootstrapArguments").arrayValue().map { it.stringValue() }
        assertEquals(18, arguments.size)
        assertEquals(listOf("--runtime-store", "/shared store $(literal)/../selected"), arguments.subList(14, 16))
        assertEquals(listOf(launch.python, launch.launcher) + arguments + "--print-launch", launch.command(BootstrapQuery.LAUNCH))
        fun parse(values: List<String>) = BootstrapLaunch.parse(JsonValue.Object(fields +
            ("bootstrapArguments" to values.map { it.json() }.json())).canonical().encodeToByteArray())
        // The shared store is also valid for legacy local-source compositions.
        val legacy = arguments.filterIndexed { index, _ -> index !in 10..13 }
        assertEquals(legacy, parse(legacy).command(BootstrapQuery.LAUNCH).drop(2).dropLast(1))
        for (invalid in listOf(arguments.take(15), arguments + "--runtime-store",
            arguments.toMutableList().also { it[15] = "relative/store" },
            arguments.toMutableList().also { it[15] = "/store\u0000path" },
            arguments.take(10) + arguments.subList(14, 16) + arguments.subList(10, 14))) {
            assertFails { parse(invalid) }
        }
        for (changed in listOf(arguments.toMutableList().also { it[15] = "/different-store" },
            arguments.filterIndexed { index, _ -> index !in 14..15 })) {
            assertFails { parse(changed).requireReplacement(launch, launch.composition) }
        }
    }

    @Test fun replacementCannotChangeSourceLockOrCachePolicy() {
        val current = BootstrapLaunch.parse(sourceGraphProducerDescriptor)
        val graphFields = parseJson(sourceGraphProducerDescriptor.decodeToString()).objectValue()
        val arguments = graphFields.getValue("bootstrapArguments").arrayValue().map { it.stringValue() }
        val next = Sha256.digest("new source graph".encodeToByteArray())
        val replacement = graphFields + mapOf("composition" to next.toJson(),
            "runtime" to current.runtime.replace(current.composition.hash, next.hash).json())
        fun parse(values: List<String>) = BootstrapLaunch.parse(JsonValue.Object(replacement +
            ("bootstrapArguments" to values.map { it.json() }.json())).canonical().encodeToByteArray())
        parse(arguments).requireReplacement(current, next)
        for (changed in listOf(
            arguments.toMutableList().also { it[11] = "/another/source.lock.json" },
            arguments.toMutableList().also { it[13] = "/another/source-cache" },
            arguments.filterIndexed { index, _ -> index !in 12..13 },
            arguments.filterIndexed { index, _ -> index !in 10..13 },
        )) assertFails { parse(changed).requireReplacement(current, next) }
    }

    @Test fun toolAcquisitionPolicySurvivesRecomposition() {
        val launch = BootstrapLaunch.parse(toolArchivesProducerDescriptor)
        val fields = parseJson(toolArchivesProducerDescriptor.decodeToString()).objectValue()
        val arguments = fields.getValue("bootstrapArguments").arrayValue().map { it.stringValue() }
        assertEquals(24, arguments.size)
        assertEquals(listOf("--tool-archives", "/tool locks $(literal)/../archives.json", "--tool-cache", "/tool cache"),
            arguments.subList(16, 20))
        assertEquals(listOf(launch.python, launch.launcher) + arguments + "--print-launch", launch.command(BootstrapQuery.LAUNCH))
        fun parse(values: List<String>) = BootstrapLaunch.parse(JsonValue.Object(fields +
            ("bootstrapArguments" to values.map { it.json() }.json())).canonical().encodeToByteArray())
        for (invalid in listOf(arguments.filterIndexed { index, _ -> index !in 16..17 },
            arguments.take(17), arguments.take(19), arguments + "--tool-cache",
            arguments.toMutableList().also { it[17] = "relative/archives.json" })) {
            assertFails { parse(invalid) }
        }
        for (changed in listOf(arguments.toMutableList().also { it[17] = "/another/archives.json" },
            arguments.toMutableList().also { it[19] = "/another/cache" },
            arguments.filterIndexed { index, _ -> index !in 18..19 })) {
            assertFails { parse(changed).requireReplacement(launch, launch.composition) }
        }
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

    @Test fun commandDeadlineSurvivesQueriesAndReplacement() {
        val launch = BootstrapLaunch.parse(timeoutProducerDescriptor)
        val fields = parseJson(timeoutProducerDescriptor.decodeToString()).objectValue()
        val arguments = fields.getValue("bootstrapArguments").arrayValue().map { it.stringValue() }
        assertEquals(20, arguments.size)
        assertEquals(1800, launch.commandTimeoutSeconds)
        assertEquals(5_400_000L, launch.rebuildTimeoutMillis)
        assertEquals(listOf("--bootstrap-command-timeout", "1800", "--offline", "--no-build", "--print-launch"),
            launch.command(BootstrapQuery.LAUNCH).takeLast(5))
        fun parse(values: List<String>) = BootstrapLaunch.parse(JsonValue.Object(fields +
            ("bootstrapArguments" to values.map { it.json() }.json())).canonical().encodeToByteArray())
        for (value in listOf("0", "3601", "-1", "1.5", "inf", "01", "1800\n", "999999999999999999")) {
            assertFails { parse(arguments.toMutableList().also { it[17] = value }) }
        }
        assertFails { parse(arguments.take(17)) }
        assertFails { parse(arguments + listOf("--bootstrap-command-timeout", "1800")) }
        for (value in listOf("1", "3600")) {
            val changed = parse(arguments.toMutableList().also { it[17] = value })
            assertFails { changed.requireReplacement(launch, launch.composition) }
        }
        val default = parse(arguments.filterIndexed { index, _ -> index !in 16..17 })
        assertEquals(300, default.commandTimeoutSeconds)
        assertEquals(900_000L, default.rebuildTimeoutMillis)
        assertFails { default.requireReplacement(launch, launch.composition) }
    }
}
