package kvik.core

import kotlin.test.*

class ProjectSettingsInspectionTest {
    private val file = LogicalPath("config/kvik.json")
    private fun configuration(testOptions: String = "") = parseProjectConfiguration(parseJson("""{
        "schemaVersion":1,"name":"settings","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],
        "pluginSettings":{"fixture.generator":{"mode":"fast"}},
        "modules":[{"id":"library","sourceSets":[
          {"name":"main","directories":["src"],"resources":[],"dependsOn":[],"dependencies":[{"coordinate":"example:api:1"}]},
          {"name":"test","directories":["test"],"resources":[],"dependsOn":["main"],"dependencies":[]}],
          "targets":[{"name":"jvm","main":"main","test":"test","compilerOptions":["-jvm-target=17"],
            "testSettings":{"classes":["example.Test"]}$testOptions}],
          "compilerPlugins":[{"coordinate":"example:plugin:1","targets":["jvm"],"scopes":["test"],"options":[],"inputs":[]}]}]
    }"""))

    @Test fun inheritedOptionsAndAppliedPluginsHaveExactDeclarationOrigins() {
        val values = projectSettingsInspection(configuration(), file)
        val main = values.getValue("target:library:jvm:main:compilerOptions")
        val test = values.getValue("target:library:jvm:test:compilerOptions")
        assertEquals(main.value, test.value)
        assertEquals("inherited", test.source)
        assertEquals(listOf(ConfigurationOrigin(file, "/modules/0/targets/0/compilerOptions")), test.origins)
        assertEquals(emptyList(), values.getValue("target:library:jvm:main:compilerPlugins").value.arrayValue())
        assertEquals(listOf(ConfigurationOrigin(file, "/modules/0/compilerPlugins/0")),
            values.getValue("target:library:jvm:test:compilerPlugins").origins)
        val overridden = projectSettingsInspection(configuration(",\"testCompilerOptions\":[]"), file).getValue("target:library:jvm:test:compilerOptions")
        assertEquals("configuration", overridden.source)
        assertTrue(overridden.value.arrayValue().isEmpty())
        assertEquals("/modules/0/targets/0/testCompilerOptions", overridden.origins.single().pointer)
    }

    @Test fun defaultsComeFromParsedSettingsAndNeverInventAuthoringLocations() {
        val values = projectSettingsInspection(configuration(), file)
        for ((key, expected) in mapOf("target:library:jvm:testSettings:parallelism" to 1.json(),
            "target:library:jvm:testSettings:maxRetries" to 0.json(),
            "target:library:jvm:testSettings:engines" to listOf("junit".json()).json(),
            "target:library:jvm:testResources:memoryMiB" to 512.json(),
            "target:library:jvm:testResources:cpuSlots" to 1.json(),
            "sourceSet:library:main:dependency:0:scope" to "compile".json(),
            "sourceSet:library:main:dependency:0:exported" to false.json())) {
            assertEquals(InspectedSetting(expected, "default"), values.getValue(key), key)
        }
        assertEquals("/modules/0/targets/0/testSettings/classes", values.getValue("target:library:jvm:testSettings:classes").origins.single().pointer)
        assertEquals("/pluginSettings/fixture.generator", values.getValue("buildPlugin:fixture.generator:settings").origins.single().pointer)
        values.values.forEach { assertEquals(it, InspectedSetting.fromJson(parseJson(it.toJson().canonical()))) }
    }
}
