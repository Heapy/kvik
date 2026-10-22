package kvik.resolution

import kotlin.test.*
import kvik.core.*

/** Independent review probes for expansion-state/redirect interactions. */
class WildcardReviewTest {
    private val repo = Repository("review", "https://review.example/maven")
    private fun url(name: String, suffix: String) = "${repo.url}/review/$name/1/$name-1.$suffix"
    private val all = "<exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>"
    private fun edge(name: String, exclusion: String = "") = "<dependency><groupId>review</groupId><artifactId>$name</artifactId><version>1</version>$exclusion</dependency>"
    private fun pom(name: String, dependencies: String = "") = mapOf(url(name, "pom") to "<project><modelVersion>4.0.0</modelVersion><groupId>review</groupId><artifactId>$name</artifactId><version>1</version><dependencies>$dependencies</dependencies></project>".encodeToByteArray(), url(name, "jar") to name.encodeToByteArray())
    private fun module(name: String, extra: String, owner: String = name): ByteArray {
        val ownership = if (owner == name) "" else ",\"url\":\"../../$owner/1/$owner-1.module\""
        return """{"formatVersion":"1.1","component":{"group":"review","module":"$owner","version":"1"$ownership},"variants":[{"name":"runtime","attributes":{"org.gradle.category":"library","org.gradle.usage":"java-runtime","org.jetbrains.kotlin.platform.type":"jvm"}$extra}]}""".encodeToByteArray()
    }
    private fun resolve(entries: Map<String, ByteArray>, vararg names: String): List<LockedSelection> = MavenResolver(object : ArtifactTransport {
        override fun fetch(url: String) = entries[url] ?: throw MissingResource(url)
    }, NativePomParser()).resolveAll(names.map { Coordinate("review", it, "1") }, VariantRequest("jvm", "java-runtime", emptyMap()), listOf(repo))

    @Test fun redirectRetainsEdgePolicyAndDoesNotConflictWithOrdinaryProvider() {
        val redirect = ",\"available-at\":{\"url\":\"../../host/1/host-1.module\",\"group\":\"review\",\"module\":\"host\",\"version\":\"1\"}"
        val bytes = "host".encodeToByteArray()
        val caps = ",\"capabilities\":[{\"group\":\"review\",\"name\":\"host\",\"version\":\"1\"},{\"group\":\"review\",\"name\":\"alias\",\"version\":\"1\"}]"
        val files = ",\"files\":[{\"name\":\"host.jar\",\"url\":\"host-1.jar\",\"size\":${bytes.size},\"sha256\":\"${Sha256.digest(bytes).hash}\"}]"
        val dependencies = ",\"dependencies\":[{\"group\":\"review\",\"module\":\"leaf\",\"version\":{\"requires\":\"1\"}}]"
        val entries = pom("excluded", edge("alias", all)) + pom("ordinary", edge("alias")) + pom("leaf") + mapOf(url("alias", "module") to module("alias", redirect), url("host", "module") to module("host", caps + files + dependencies, "alias"), url("host", "jar") to bytes)
        for (order in listOf(arrayOf("excluded", "ordinary"), arrayOf("ordinary", "excluded"))) {
            val results = resolve(entries, *order).associateBy { it.requested.name }
            assertEquals(listOf("excluded", "host"), results.getValue("excluded").artifacts.map { it.coordinate.name })
            assertEquals(listOf("ordinary", "host", "leaf"), results.getValue("ordinary").artifacts.map { it.coordinate.name })
            assertEquals(3, results.getValue("excluded").metadata.size)
        }
    }

    @Test fun excludedGradleConstraintsDoNotLeakButOrdinaryPathAppliesThem() {
        val constraints = ",\"dependencyConstraints\":[{\"group\":\"review\",\"module\":\"leaf\",\"version\":{\"requires\":\"2\"}}]"
        val entries = pom("excluded", edge("middle", all)) + pom("ordinary", edge("middle")) + pom("leaf") + mapOf(url("middle", "module") to module("middle", constraints))
        assertEquals(2, resolve(entries, "excluded", "leaf").size)
        for (order in listOf(arrayOf("excluded", "ordinary", "leaf"), arrayOf("ordinary", "excluded", "leaf"))) {
            assertTrue(assertFails { resolve(entries, *order) }.message!!.contains("Constraint review:leaf:2"))
        }
    }

    @Test fun propertyBasedAndDuplicateExclusionsRemainUnsupported() {
        for (exclusion in listOf(all.replace("<groupId>*", "<groupId>\${wild}"), all.replace("</exclusion>", "<groupId>*</groupId></exclusion>"), all.replace("</exclusions>", "<exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>"))) {
            assertFails { resolve(pom("root", edge("leaf", exclusion)) + pom("leaf"), "root") }
        }
    }
}
