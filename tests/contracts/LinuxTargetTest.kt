package kvik.core

import kotlin.test.*

class LinuxTargetTest {
    @Test fun linuxSettingsAndPublicationRoundtripWithStrictTargetNames() {
        val raw = """{"schemaVersion":1,"name":"linux","repositories":[{"id":"fixture","url":"https://example.test/maven"}],"modules":[{"id":"app",
            "sourceSets":[{"name":"main","directories":["src"],"resources":[],"dependsOn":[],"dependencies":[]},
            {"name":"test","directories":["test"],"resources":[],"dependsOn":["main"],"dependencies":[]}],
            "targets":[{"name":"linuxX64","main":"main","test":"test","compilerOptions":["-module-name=fixture:app"],
            "nativeTimeouts":{"linkMillis":600000},"nativeTestSettings":{"engine":"kotest","kspVersion":"2.3.12","processor":"io.kotest:kotest-framework-symbol-processor:6.2.5"}}],
            "compilerPlugins":[],"publication":{"coordinate":"fixture:app:1"}}]}"""
        val parsed = parseProjectConfiguration(parseJson(raw))
        assertEquals(parsed, parseProjectConfiguration(parseJson(parsed.document.canonical())))
        val module = parsed.project.modules.single()
        assertEquals(600000L, module.targets.single().nativeTimeouts!!.linkMillis)
        assertEquals("app-linuxx64", module.publication!!.coordinateFor("linuxX64").name)
        for (target in listOf("linux_x64", "linuxArm64", "native", "LinuxX64")) {
            assertFails { parseProjectConfiguration(parseJson(raw.replace("linuxX64", target))) }
            assertFails { VariantRequest(target, "kotlin-api", emptyMap()) }
        }
        assertEquals("linux_x64", NativeTarget.require("linuxX64").compilerName)
        assertEquals(setOf("native", "jdk", "system", "native-dependencies"), NativeTarget.LINUX_X64.toolIdentityNames)
        assertFails { TargetSpec("jvm", "main", "test", emptyList(), nativeTimeouts = NativeTimeouts(linkMillis = 1)) }
    }

    @Test fun metadataTargetIntersectionIsExplicitAndDuplicateOrUnknownTargetsFail() {
        val compiler = Sha256.digest("compiler".encodeToByteArray())
        val original = WorkerRequest("id", "metadata-compile", compiler, listOf("Main.kt"), emptyList(), emptyList(), emptyList(), "metadata")
        for (targets in listOf(setOf("jvm", "linuxX64"), setOf("macosArm64", "linuxX64"), setOf("jvm", "macosArm64", "linuxX64"))) {
            val request = original.copy(metadataTargets = targets)
            assertEquals(request, WorkerRequest.fromJson(request.toJson()))
            assertNotEquals(Sha256.digest(original.toJson()), Sha256.digest(request.toJson()))
        }
        for (targets in listOf(emptySet(), setOf("linuxX64"), setOf("jvm", "linuxArm64"))) assertFails { original.copy(metadataTargets = targets) }
        assertFails { WorkerRequest.fromJson(JsonValue.Object(original.toJson().objectValue() +
            ("metadataTargets" to listOf("jvm".json(), "jvm".json()).json()))) }
        assertFails { original.copy(operation = "jvm-compile") }
    }
}
