package kvik.core

import kotlin.test.*

class ApplicationTest {
    private val digest = Sha256.digest("payload".encodeToByteArray())
    private val classes = LogicalPath("modules/app/jvm/main")
    private val jar = LogicalPath("deps/runtime.jar")
    private val input = TreeManifest(listOf(FileEntry(LogicalPath("${classes.value}/sample/MainKt.class"), digest), FileEntry(jar, digest)))
    private fun jvm() = ApplicationLaunch("app", "jvm", ApplicationSpec("sample.MainKt", listOf("literal space", "@literal", ""), mapOf("MODE" to "dev")), listOf(classes, jar))

    @Test fun orderedPortableMetadataRoundtripsAndRequiresExactInputs() {
        val launch = jvm()
        launch.requireInputs(input)
        assertEquals(launch, ApplicationLaunch.fromJson(parseJson(launch.toJson().canonical())))
        assertNotEquals(Sha256.digest(launch.toJson()), Sha256.digest(launch.copy(classpath = launch.classpath.reversed()).toJson()))
        assertNotEquals(Sha256.digest(launch.toJson()), Sha256.digest(launch.copy(application = launch.application.copy(arguments = listOf("changed"))).toJson()))
        assertFails { launch.requireInputs(TreeManifest(input.files.drop(1))) }
        assertFails { launch.requireInputs(TreeManifest(input.files.take(1))) }
        assertFails { launch.copy(classpath = listOf(classes, classes)) }
        assertFails { launch.copy(classpath = emptyList()) }
        assertFails { launch.copy(executable = LogicalPath("app.kexe")) }
        assertFails { ApplicationLaunch.fromJson(JsonValue.Object(launch.toJson().values + ("hostPath" to "/tmp/elsewhere".json()))) }
    }

    @Test fun nativeApplicationsRequireADeclaredExecutableAndFunctionEntryPoint() {
        val executable = LogicalPath("bin/app.kexe")
        val launch = ApplicationLaunch("app", "macosArm64", ApplicationSpec("sample.main"), executable = executable)
        launch.requireInputs(TreeManifest(listOf(FileEntry(executable, digest, true))))
        assertEquals(launch, ApplicationLaunch.fromJson(launch.toJson()))
        assertFails { launch.requireInputs(TreeManifest(listOf(FileEntry(executable, digest)))) }
        assertFails { launch.requireInputs(TreeManifest(emptyList())) }
        assertFails { launch.copy(classpath = listOf(classes)) }
        assertFails { launch.copy(executable = null) }
        assertFails { launch.copy(application = ApplicationSpec("sample.Outer\$Nested")) }
        jvm().copy(application = ApplicationSpec("sample.Outer\$Nested"))
        val linux = launch.copy(target = "linuxX64")
        assertEquals(linux, ApplicationLaunch.fromJson(linux.toJson()))
        assertFails { launch.copy(target = "linuxArm64") }
    }

    @Test fun nodeApplicationsRequireAnExactNonExecutableModule() {
        for (target in listOf("js", "wasmJs")) {
            val path = LogicalPath("program/launch.mjs")
            val launch = ApplicationLaunch("app", target, ApplicationSpec("sample.main"), moduleFile = path)
            assertEquals(launch, ApplicationLaunch.fromJson(launch.toJson()))
            launch.requireInputs(TreeManifest(listOf(FileEntry(path, digest))))
            assertFails { launch.requireInputs(TreeManifest(emptyList())) }
            assertFails { launch.requireInputs(TreeManifest(listOf(FileEntry(path, digest, true)))) }
            assertFails { launch.copy(moduleFile = null) }
            assertFails { launch.copy(moduleFile = LogicalPath("app.wasm")) }
            assertFails { launch.copy(executable = path) }
            assertFails { launch.copy(classpath = listOf(classes)) }
            assertFails { launch.copy(target = "jvm") }
        }
    }

    @Test fun settingsRejectUnknownFieldsNulsInvalidUnicodeAndOversizedPayloads() {
        assertEquals(ApplicationSpec("MainKt"), ApplicationSpec.fromJson(parseJson("""{"entryPoint":"MainKt"}""")))
        for (text in listOf("null", "[]", "{}", """{"entryPoint":"MainKt","args":[]}""",
            """{"entryPoint":"MainKt","arguments":null}""", """{"entryPoint":"MainKt","environment":{"X":true}}""")) {
            assertFails(text) { ApplicationSpec.fromJson(parseJson(text)) }
        }
        for (entry in listOf("", "a..Main", "MainKt\n", "/MainKt", "-jar", "x".repeat(1025))) assertFails { ApplicationSpec(entry) }
        assertFails { ApplicationSpec("MainKt", listOf("a\u0000b")) }
        assertFails { ApplicationSpec("MainKt", listOf("\uD800")) }
        assertFails { ApplicationSpec("MainKt", environment = mapOf("X" to "\uDFFF")) }
        assertFails { ApplicationSpec("MainKt", environment = mapOf("BAD=KEY" to "value")) }
        assertFails { ApplicationSpec("MainKt", environment = mapOf("X" to "a\u0000b")) }
        assertFails { ApplicationSpec("MainKt", List(257) { "" }) }
        assertFails { ApplicationSpec("MainKt", listOf("x".repeat(4097))) }
        assertFails { ApplicationSpec("MainKt", List(16) { "x".repeat(4096) }) }
        assertFails { ApplicationSpec("MainKt", environment = (1..129).associate { "ENV$it" to "" }) }
        assertFails { jvm().copy(classpath = (1..1025).map { LogicalPath("cp/$it") }) }
        assertFails { jvm().copy(classpath = (1..1024).map { LogicalPath("cp/$it/" + "x".repeat(256)) }) }
    }
}
