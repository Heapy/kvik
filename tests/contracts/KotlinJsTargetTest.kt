package kvik.core

import kotlin.test.*

class KotlinJsTargetTest {
    private fun document(target: String, settings: JsonValue? = null): JsonValue {
        val spec = obj("name" to target.json(), "main" to "main".json(), "test" to "test".json(), "compilerOptions" to emptyList<JsonValue>().json())
        return obj("schemaVersion" to 1.json(), "name" to "web".json(),
            "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.maven.apache.org/maven2".json())).json(),
            "modules" to listOf(obj("id" to "library".json(), "compilerPlugins" to emptyList<JsonValue>().json(),
                "sourceSets" to listOf("main", "test").map { name -> obj("name" to name.json(),
                    "directories" to listOf("src/$name".json()).json(), "resources" to emptyList<JsonValue>().json(),
                    "dependsOn" to emptyList<JsonValue>().json(), "dependencies" to emptyList<JsonValue>().json()) }.json(),
                "targets" to listOf(if (settings == null) spec else JsonValue.Object(spec.values + ("jsTestSettings" to settings))).json())).json())
    }

    @Test fun jsAndWasmHaveSeparatePlatformsAndVariants() {
        for (target in KotlinJsTarget.entries) {
            assertEquals(target.projectName, parseProjectConfiguration(document(target.projectName)).project.modules.single().targets.single().name)
            assertEquals(target, KotlinJsTarget.find(target.projectName))
            assertEquals(target.projectName, VariantRequest(target.projectName, "kotlin-api", target.variantAttributes).platform)
        }
        assertEquals("ir", KotlinJsTarget.JS.variantAttributes["org.jetbrains.kotlin.js.compiler"])
        assertEquals("js", KotlinJsTarget.WASM_JS.variantAttributes["org.jetbrains.kotlin.wasm.target"])
        assertNull(KotlinJsTarget.find("wasmWasi"))
        assertFails { parseProjectConfiguration(document("wasmWasi")) }
    }

    @Test fun testSettingsHaveStableDefaultsAndAreTargetSpecific() {
        assertEquals(JsTestSettings(), JsTestSettings.fromJson(obj()))
        val settings = JsTestSettings("example.*", 1_000)
        assertEquals(settings, JsTestSettings.fromJson(settings.toJson()))
        for (target in KotlinJsTarget.entries) assertEquals(settings,
            parseProjectConfiguration(document(target.projectName, settings.toJson())).project.modules.single().targets.single().jsTestSettings)
        assertFails { parseProjectConfiguration(document("jvm", settings.toJson())) }
        assertFails { parseProjectConfiguration(document("macosArm64", settings.toJson())) }
        assertFails { TargetSpec("js", "main", null, emptyList(), jsTestSettings = settings) }
        assertFails { JsTestSettings("") }
        assertFails { JsTestSettings("line\nbreak") }
        assertFails { JsTestSettings(timeoutMillis = 0) }
        assertFails { JsTestSettings.fromJson(obj("engine" to "unimplemented".json())) }
    }

    @Test fun unsupportedTargetIntegrationsFailExplicitly() {
        for (target in KotlinJsTarget.entries) {
            assertFails { TargetSpec(target.projectName, "main", "test", emptyList(), testSettings = JvmTestSettings(emptyList())) }
            assertFails { TargetSpec(target.projectName, "main", null, emptyList(), abi = AbiSettings(LogicalPath("api.txt"))) }
            val plugin = CompilerPluginSpec("plugin", null, setOf(target.projectName), setOf("main"), emptyList(), emptyList(),
                id = "sample", compilerVersion = "2.4.20")
            assertTrue(plugin.appliesTo(target.projectName, "test"))
        }
    }
}
