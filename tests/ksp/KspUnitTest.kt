package kvik.compiler.ksp

import kvik.core.*
import kotlin.test.*

class KspUnitTest {
    @Test fun declaredSeparateClasspathsAndNonincrementalOptionsArePartOfTheGenerationIdentity() {
        val source = LogicalPath("test/Spec.kt")
        val api = LogicalPath("ksp/runtime-api.jar")
        val processor = LogicalPath("processor/processor.jar")
        val library = LogicalPath("lib/main.klib")
        val compilation = KspNativeCompilation("fixture", "fixture-test", listOf(source), listOf(source), listOf(library), listOf(api), listOf(processor))
        val digest = Sha256.digest(byteArrayOf(1))
        val inputs = TreeManifest(listOf(source, api, processor, library).map { FileEntry(it, digest) })
        val tools = listOf("native", "jdk", "sdk", "native-dependencies").associateWith { digest }
        val action = kspNativeAction(compilation, inputs, digest, tools)
        assertEquals(compilation, KspNativeCompilation.fromJson(compilation.toJson()))
        assertEquals(CachePolicy.LOCAL, action.cachePolicy)
        assertTrue(action.arguments.containsAll(listOf("-language-version=2.4", "-api-version=2.4", "-incremental=false", "-target=macosArm64")))
        assertEquals("./input/processor/processor.jar", action.arguments.last())
        assertTrue(action.arguments[action.arguments.indexOf("-classpath") + 1].contains("runtime-api.jar"))
        val changed = Sha256.digest(byteArrayOf(2))
        for (path in listOf(source, api, processor, library)) assertNotEquals(action.key(Sha256), kspNativeAction(compilation,
            TreeManifest(inputs.files.map { if (it.path == path) it.copy(digest = changed) else it }), digest, tools).key(Sha256))
        assertFails { kspNativeAction(compilation, TreeManifest(inputs.files.filter { it.path != processor }), digest, tools) }
        assertFails { compilation.copy(languageVersion = "2.3") }
        assertFails { compilation.copy(processorClasspath = emptyList()) }
        assertFails { KspNativeCompilation.fromJson(JsonValue.Object(compilation.toJson().objectValue() + ("incremental" to true.json()))) }
    }
}
