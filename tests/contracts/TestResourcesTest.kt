package kvik.core

import kotlin.test.*

class TestResourcesTest {
    @Test fun defaultsCanonicalOrderAndSchedulerRequest() {
        assertEquals(TestResources(), TestResources.fromJson(parseJson("{}")))
        val resources = TestResources(2048, linkedSetOf("port:8080", "db.integration", "0_device-A"))
        assertEquals(ResourceRequest(2048, setOf("port:8080", "db.integration", "0_device-A")), resources.resourceRequest())
        assertEquals("""{"exclusiveResources":["0_device-A","db.integration","port:8080"],"memoryMiB":2048}""", resources.toJson().canonical())
        assertEquals(resources, TestResources.fromJson(resources.toJson()))
        assertEquals(resources.toJson(), resources.copy(exclusiveResources = resources.exclusiveResources.reversed().toSet()).toJson())
        assertEquals(TestResources(1), TestResources.fromJson(parseJson("""{"memoryMiB":1}""")))
        assertEquals(TestResources(exclusiveResources = setOf("db")), TestResources.fromJson(parseJson("""{"exclusiveResources":["db"]}""")))
        TestResources(1_048_576, (1..64).map { "r$it" }.toSet())
        TestResources(exclusiveResources = setOf("A".repeat(128)))
    }

    @Test fun resourcesAreStrictAndBounded() {
        for (json in listOf("null", "[]", "true", "1", """{"typo":1}""", """{"exclusiveResources":["db","db"]}"""))
            assertFails("Accepted $json") { TestResources.fromJson(parseJson(json)) }
        for (value in listOf("null", "true", "\"512\"", "1.5", "[]", "{}", "0", "-1", "1048577", "9223372036854775808"))
            assertFails("Accepted memoryMiB=$value") { TestResources.fromJson(parseJson("""{"memoryMiB":$value}""")) }
        for (value in listOf("null", "true", "\"db\"", "{}", "[null]", "[1]"))
            assertFails("Accepted exclusiveResources=$value") { TestResources.fromJson(parseJson("""{"exclusiveResources":$value}""")) }
        for (name in listOf("", ".db", "-db", "_db", ":db", "db host", "db/host", "db\\host", "db\n", "ä", "A".repeat(129))) {
            assertFails("Accepted resource $name") { TestResources(exclusiveResources = setOf(name)) }
            assertFails { TestResources.fromJson(obj("exclusiveResources" to listOf(name.json()).json())) }
        }
        for (memory in listOf(0L, -1L, 1_048_577L)) assertFails { TestResources(memory) }
        assertFails { TestResources(exclusiveResources = (1..65).map { "r$it" }.toSet()) }
    }

    @Test fun projectResourcesWorkForEveryExecutableTestTargetAndRemainOptional() {
        for (name in listOf("jvm", "macosArm64", "linuxX64", "js", "wasmJs")) {
            val absent = project(name)
            assertNull(absent.project.modules.single().targets.single().testResources)
            for (settings in listOf("{}", """{"memoryMiB":2048,"exclusiveResources":["db"]}""")) {
                val configuration = project(name, settings)
                assertEquals(TestResources.fromJson(parseJson(settings)), configuration.project.modules.single().targets.single().testResources)
                assertEquals(configuration, parseProjectConfiguration(configuration.document))
                assertNotEquals(absent.digest, configuration.digest)
            }
            assertFails { project(name, "{}", test = "null") }
            assertFails { project(name, "null") }
            assertFails { TargetSpec(name, "main", null, emptyList(), testResources = TestResources()) }
        }
        for (name in listOf("iosArm64", "iosSimulatorArm64")) {
            assertNull(project(name, test = "null").project.modules.single().targets.single().testResources)
            assertFails { project(name, "{}", test = "null") }
            assertFails { project(name, "{}") }
        }
    }

    private fun project(target: String, resources: String? = null, test: String = "\"test\""): ProjectConfiguration {
        val property = resources?.let { ",\"testResources\":$it" }.orEmpty()
        return parseProjectConfiguration(parseJson("""{"schemaVersion":1,"name":"sample","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],"modules":[{
            "id":"sample","sourceSets":[
                {"name":"main","directories":[],"resources":[],"dependsOn":[],"dependencies":[]},
                {"name":"test","directories":[],"resources":[],"dependsOn":["main"],"dependencies":[]}
            ],"targets":[{"name":"$target","main":"main","test":$test,"compilerOptions":[]$property}],"compilerPlugins":[]
        }]}"""))
    }
}
