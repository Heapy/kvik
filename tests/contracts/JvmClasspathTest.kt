package kvik.core

import kotlin.test.*

class JvmClasspathTest {
    private val digest = Sha256.digest("classpath".encodeToByteArray())
    private val paths = listOf(LogicalPath("modules/library/jvm/main"), LogicalPath("artifacts/library.jar"))
    private val inputs = TreeManifest(listOf(FileEntry(LogicalPath("modules/library/jvm/main/A.class"), digest), FileEntry(paths[1], digest)))

    @Test fun orderedClasspathRoundTripsAndRequiresExactlyTheCapturedInputs() {
        for (scope in listOf("main", "test")) {
            val metadata = JvmClasspath("app", scope, paths)
            metadata.requireInputs(inputs)
            assertEquals(metadata, JvmClasspath.fromJson(parseJson(metadata.toJson().canonical())))
            assertEquals(paths, JvmClasspath.fromJson(metadata.toJson()).classpath)
            assertFails { metadata.requireInputs(TreeManifest(inputs.files.drop(1))) }
            assertFails { metadata.requireInputs(TreeManifest(inputs.files + FileEntry(LogicalPath("src/Unrelated.kt"), digest))) }
        }
        JvmClasspath("app", "main", emptyList()).requireInputs(TreeManifest(emptyList()))
        assertFails { JvmClasspath("app", "main", emptyList()).requireInputs(inputs) }
        assertFails { JvmClasspath("app", "main", listOf(LogicalPath("not-a-jar")))
            .requireInputs(TreeManifest(listOf(FileEntry(LogicalPath("not-a-jar"), digest)))) }
    }

    @Test fun malformedMetadataIsRejected() {
        val valid = JvmClasspath("app", "test", paths).toJson().values
        for (field in valid.keys) assertFails { JvmClasspath.fromJson(JsonValue.Object(valid - field)) }
        for ((field, value) in listOf("extra" to true.json(), "schemaVersion" to 2.json(), "compilation" to "runtime".json(),
            "module" to "bad/module".json(), "classpath" to JsonValue.Null,
            "classpath" to listOf("../escape.jar".json()).json(), "classpath" to listOf("a.jar".json(), "a.jar".json()).json())) {
            assertFails { JvmClasspath.fromJson(JsonValue.Object(valid + (field to value))) }
        }
        assertFails { JvmClasspath("app", "main", listOf(LogicalPath("bad,path.jar"))) }
    }
}
