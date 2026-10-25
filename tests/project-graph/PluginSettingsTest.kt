package kvik.projectgraph

import kvik.core.*
import kvik.locking.*
import kotlin.test.*

class PluginSettingsTest {
    private fun project(settings: JsonValue? = null): ProjectConfiguration = parseProjectConfiguration(JsonValue.Object(
        obj("schemaVersion" to 1.json(), "name" to "settings".json(),
            "repositories" to listOf(obj("id" to "central".json(), "url" to "https://repo.example.test/maven".json())).json(),
            "modules" to emptyList<JsonValue>().json()).values +
            if (settings == null) emptyMap() else mapOf("pluginSettings" to settings)))

    private class Plugin : ConfigurableBuildPlugin {
        override val id = "example.plugin"
        override val apiVersion = CONTRACT_VERSION
        override val configurationSchema = obj()
        override val settingsSchema = obj("type" to "object".json())
        val seen = mutableListOf<JsonValue.Object>()
        override fun validateSettings(settings: JsonValue.Object) {
            seen += settings
            val fields = settings.fields(emptySet(), setOf("label"))
            fields["label"]?.let { require(it.stringValue().isNotBlank()) { "label must not be blank" } }
        }
    }

    @Test fun rejectsMalformedAndUnboundedSettings() {
        for (settings in listOf(JsonValue.Null, "text".json(), obj("example.plugin" to true.json()),
            obj("bad/id" to obj()), obj("a".repeat(129) to obj()),
            obj("example.plugin" to obj("label" to "x".repeat(256 * 1024).json())),
            JsonValue.Object((0..128).associate { "plugin$it" to obj() }))) assertFails { project(settings) }
    }

    @Test fun rejectsUnknownAndNonConfigurablePlugins() {
        val settings = project(obj("example.plugin" to obj())).pluginSettings
        assertFailsWith<IllegalArgumentException> { validatePluginSettings(emptyList(), settings) }
        val plugin = object : BuildPlugin {
            override val id = "example.plugin"
            override val apiVersion = CONTRACT_VERSION
            override val configurationSchema = obj()
        }
        assertFailsWith<IllegalArgumentException> { validatePluginSettings(listOf(plugin), settings) }
        validatePluginSettings(listOf(plugin), emptyMap())
    }

    @Test fun validatesDefaultsAndReportsPluginContext() {
        val plugin = Plugin()
        validatePluginSettings(listOf(plugin), emptyMap())
        assertEquals(listOf(obj()), plugin.seen)
        for (value in listOf(obj("label" to "".json()), obj("lable" to "typo".json()), obj("label" to 2.json()))) {
            val failure = assertFailsWith<IllegalArgumentException> {
                validatePluginSettings(listOf(plugin), mapOf(plugin.id to value))
            }
            assertTrue(failure.message!!.contains(plugin.id))
        }
        assertEquals(plugin.settingsSchema, plugin.descriptor().values["settingsSchema"])
    }

    @Test fun editsPreserveOtherPluginsAndBindEffectiveIdentity() {
        val original = project(obj("other.plugin" to obj("unchanged" to true.json())))
        fun edit(config: ProjectConfiguration, vararg operations: JsonValue) = editProjectConfiguration(config,
            obj("schemaVersion" to 1.json(), "operations" to operations.toList().json()))
        val settings = obj("label" to "release".json())
        val changed = edit(original, obj("op" to "set-plugin-settings".json(), "plugin" to "example.plugin".json(), "settings" to settings))
        assertEquals(original.document.values - "pluginSettings", changed.document.values - "pluginSettings")
        assertEquals(original.pluginSettings.getValue("other.plugin"), changed.pluginSettings["other.plugin"])
        assertNotEquals(effectiveConfiguration(original).digest, effectiveConfiguration(changed).digest)
        val effective = effectiveConfiguration(changed)
        val context = ProjectBuildContext(effective, DependencyLock(effective.digest, emptyList()), TreeManifest(emptyList()),
            Sha256.digest(byteArrayOf()), emptyMap())
        assertEquals(settings, context.settings("example.plugin"))
        assertEquals(obj(), context.settings("absent"))
        val removed = edit(changed, obj("op" to "remove-plugin-settings".json(), "plugin" to "example.plugin".json()))
        assertEquals(original.document, removed.document)
        assertFails { edit(removed, obj("op" to "remove-plugin-settings".json(), "plugin" to "absent".json())) }
    }

    @Test fun compositionRejectsUnknownSettingsBeforeProviderCallback() {
        val configuration = project(obj("unknown" to obj()))
        val effective = effectiveConfiguration(configuration)
        val context = ProjectBuildContext(effective, DependencyLock(effective.digest, emptyList()), TreeManifest(emptyList()),
            Sha256.digest(byteArrayOf()), emptyMap())
        var called = false
        val provider = object : ProjectBuildPlugin {
            override val id = "provider"
            override val apiVersion = CONTRACT_VERSION
            override val configurationSchema = obj()
            override fun projectGraph(context: ProjectBuildContext, request: ProjectGraphRequest): ProjectActionGraph {
                called = true
                error("Must not run")
            }
            override fun scriptGraph(context: ProjectBuildContext, request: ScriptGraphRequest): ProjectActionGraph = error("Must not run")
        }
        assertFailsWith<IllegalArgumentException> {
            composedProjectActionGraph(listOf(provider), context, ProjectGraphRequest(setOf("app"), setOf("jvm"), ProjectMode.BUILD))
        }
        assertFalse(called)
    }
}
