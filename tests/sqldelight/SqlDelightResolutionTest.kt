package kvik.locking

import kvik.core.*
import kotlin.test.*

class SqlDelightResolutionTest {
    private val EffectiveConfiguration.domains get() = document.objectValue().getValue("domains").arrayValue().map(ResolutionDomain::fromJson)
    private fun configuration(unrelatedWasm: Boolean = false): ProjectConfiguration = parseProjectConfiguration(parseJson("""
        {"schemaVersion":1,"name":"generated","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],
        "modules":[{"id":"data","publication":{"coordinate":"fixture:data:1"},
          "sourceSets":[
            {"name":"commonMain","directories":[],"resources":[],"dependsOn":[],"dependencies":[{"coordinate":"app.cash.sqldelight:runtime:2.3.2"}]},
            {"name":"test","directories":[],"resources":[],"dependsOn":["commonMain"],"dependencies":[]},
            {"name":"unrelated","directories":["plain"],"resources":[],"dependsOn":[],"dependencies":[]}],
          "targets":[
            {"name":"macosArm64","main":"commonMain","test":"test","compilerOptions":[]},
            {"name":"js","main":"commonMain","test":"test","compilerOptions":[]},
            {"name":"wasmJs","main":"${if (unrelatedWasm) "unrelated" else "commonMain"}","test":null,"compilerOptions":[]}],
          "compilerPlugins":[],"sqlDelight":{"sourceSet":"commonMain","sourceDirectory":"sql","packageName":"fixture.db","className":"Database"}}]}
    """))

    @Test fun nonJvmConsumersShareOneHostDomainAndKeepRuntimeVariantsSeparate() {
        val config = configuration()
        val effective = effectiveConfiguration(config)
        val host = ResolutionDomain("data", "jvm", "main", "java-runtime", "sqldelight-compiler")
        assertEquals(setOf(host), effective.domains.filter { it.purpose == "sqldelight-compiler" }.toSet())
        val tools = effective.requests.filter { it.domain == host }
        assertEquals(sqlDelightCompilerCoordinates("2.3.2").toSet(), tools.map { it.coordinate }.toSet())
        assertTrue(tools.all { it.variant.platform == "jvm" && it.variant.usage == "java-runtime" })
        assertTrue(effective.requests.filter { it.domain.purpose == "dependencies" }.none { it.domain.target == "jvm" })
        assertEquals(setOf("common", "macosArm64", "js", "wasmJs"), effective.requests.filter {
            it.coordinate == Coordinate("app.cash.sqldelight", "runtime", "2.3.2")
        }.map { it.domain.target }.toSet())
    }

    @Test fun generatedOnlyCommonPublicationLocksMetadataAndItsGenerator() {
        val config = configuration()
        assertTrue(hasCommonPublicationSources(config.project.modules.single()))
        val common = DependencyScope("data", "common", "main", "kotlin-metadata")
        assertContains(defaultDependencyScopes(config), common)
        val effective = effectiveConfiguration(config, scopes = listOf(common))
        assertTrue(effective.domains.any { it.target == "common" })
        assertEquals(1, effective.domains.count { it.purpose == "sqldelight-compiler" })
    }

    @Test fun unrelatedTargetDoesNotResolveGeneratorWhileTestPreparationDoes() {
        val config = configuration(unrelatedWasm = true)
        val plain = effectiveConfiguration(config, scopes = listOf(DependencyScope("data", "wasmJs", "main", "kotlin-api")))
        assertTrue(plain.domains.none { it.purpose == "sqldelight-compiler" })
        val tests = effectiveConfiguration(config, scopes = listOf(DependencyScope("data", "js", "test", "kotlin-api")))
        assertEquals(1, tests.domains.count { it.purpose == "sqldelight-compiler" })
    }
}
