package kvik.core

import kotlin.test.*

class AbiTest {
    private val digest = Sha256.digest("artifact".encodeToByteArray())

    @Test fun snapshotsBindFormatCompilerAndExactInputTree() {
        for (target in listOf("jvm", "macosArm64", "linuxX64", "iosArm64", "iosSimulatorArm64", "js", "wasmJs")) {
            val snapshot = AbiSnapshot("library", target, digest, "public class Sample\n")
            assertEquals(snapshot, AbiSnapshot.fromJson(snapshot.toJson()))
            for (version in listOf("2.4.10", "2.4.21")) {
                val reader = snapshot.copy(compilerVersion = version)
                assertEquals(reader, AbiSnapshot.fromJson(reader.toJson()))
                assertNotEquals(snapshot.toJson(), reader.toJson())
            }
            for ((field, value) in listOf("readerVersion" to "0.18.0", "compilerVersion" to "2.3.0", "format" to "unknown")) {
                assertFails { AbiSnapshot.fromJson(JsonValue.Object(snapshot.toJson().values + (field to value.json()))) }
            }
        }
        assertFails { AbiSnapshot("library", "unknown", digest, "") }
        assertFails { AbiSnapshot("library", "jvm", digest, "public class Sample\r\n") }
        assertFails { AbiSnapshot("library", "jvm", digest, "unterminated") }
    }

    @Test fun requestsCannotSmuggleUnrelatedOrExecutableFiles() {
        val classes = TreeManifest(listOf(FileEntry(LogicalPath("classes/Sample.class"), digest)))
        val request = AbiWorkerRequest("request", digest, AbiExtraction("library", "jvm", LogicalPath("classes")), classes)
        assertEquals(request, AbiWorkerRequest.fromJson(request.toJson()))
        assertFails { request.copy(inputs = TreeManifest(classes.files + FileEntry(LogicalPath("other/secret"), digest))) }
        assertFails { request.copy(inputs = TreeManifest(classes.files.map { it.copy(executable = true) })) }
        assertFails { request.copy(extraction = AbiExtraction("library", "iosArm64", LogicalPath("module.klib"))) }
        val native = request.copy(extraction = AbiExtraction("library", "iosArm64", LogicalPath("module.klib")),
            inputs = TreeManifest(listOf(FileEntry(LogicalPath("module.klib"), digest))))
        assertEquals(native, AbiWorkerRequest.fromJson(native.toJson()))
        for (target in KotlinJsTarget.entries) {
            val web = native.copy(extraction = native.extraction.copy(target = target.projectName))
            assertEquals(web, AbiWorkerRequest.fromJson(web.toJson()))
            assertFails { web.extraction.copy(artifact = LogicalPath("library.jar")) }
            assertFails { web.copy(inputs = TreeManifest(web.inputs.files + FileEntry(LogicalPath("other.klib"), digest))) }
        }
        assertFails { AbiSettings.fromJson(obj("baseline" to "../api.txt".json())) }
        assertFails { AbiSettings.fromJson(obj("baseline" to "api.txt".json(), "update" to true.json())) }
    }
}
