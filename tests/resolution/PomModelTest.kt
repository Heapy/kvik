@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.resolution

import kotlin.test.*
import kvik.core.*
import kotlinx.cinterop.*
import platform.posix.*

class PomModelTest {
    private val parser = NativePomParser()
    private val child = Coordinate("example", "child", "1")
    private val parent = Coordinate("example", "parent", "1")
    private val api = jvmVariantRequest("java-api", 11)
    private val runtime = jvmVariantRequest("java-runtime", 11)
    private fun pom(name: String, body: String = "", version: String = "1") = parser.parse("<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>$name</artifactId><version>$version</version>$body</project>".encodeToByteArray())
    private fun parentRef(name: String = "parent") = "<parent><groupId>example</groupId><artifactId>$name</artifactId><version>1</version></parent>"
    private fun dep(name: String, fields: String = "") = "<dependency><groupId>example</groupId><artifactId>$name</artifactId>$fields</dependency>"
    private fun model(childBody: String, parentBody: String) = effectivePom(child, pom("child", parentRef() + childBody)) { requested ->
        assertEquals(parent, requested)
        pom("parent", "<packaging>pom</packaging>$parentBody")
    }

    @Test fun nativeModelMatchesIndependentMavenModelBuilderOracle() {
        fun fixture(path: String): String {
            val file = fopen("${getenv("KVIK_RESOLUTION_ROOT")!!.toKString()}/tests/resolution/oracle/$path", "rb") ?: error("Missing oracle fixture")
            try {
                check(fseek(file, 0, SEEK_END) == 0)
                val size = ftell(file).toInt(); rewind(file)
                return ByteArray(size).also { b -> b.usePinned { check(fread(it.addressOf(0), 1u, size.toULong(), file) == size.toULong()) } }.decodeToString()
            } finally { fclose(file) }
        }
        for (case in listOf("child", "override")) {
            val model = effectivePom(child, parser.parse(fixture("$case/pom.xml").encodeToByteArray())) { parser.parse(fixture("pom.xml").encodeToByteArray()) }
            val lines = fixture("$case/expected.txt").trim().lines()
            assertEquals("packaging=${model.packaging}", lines.first())
            for (usage in listOf(api, runtime)) {
                val scopes = if (usage == api) setOf("compile") else setOf("compile", "runtime")
                val expected = lines.drop(1).filter { line ->
                    line.substringAfter(" scope=").substringBefore(' ') in scopes && line.endsWith("optional=false")
                }.map { Coordinate.parse(it.substringBefore(' ')) }
                assertEquals(expected, model.dependencies(usage))
            }
        }
    }

    @Test fun inheritedFieldsManagementAndExplicitOverridesPreserveScopes() {
        val parentBody = "<properties><chosen>2</chosen></properties><dependencies>" +
            dep("inherited", "<version>1</version><scope>runtime</scope>") +
            dep("private-build", "<version>1</version><scope>provided</scope>") + "</dependencies>" +
            "<dependencyManagement><dependencies>" +
            dep("managed", "<version>\${chosen}</version><scope>runtime</scope>") +
            dep("test-only", "<version>1</version><scope>test</scope>") + "</dependencies></dependencyManagement>"
        val childBody = "<properties><chosen>3</chosen></properties><dependencies>" +
            dep("inherited", "<version>2</version>") + dep("managed") + dep("test-only") +
            dep("explicit", "<version>1</version>") + "</dependencies><dependencyManagement><dependencies>" +
            dep("explicit", "<version>9</version>") + "</dependencies></dependencyManagement>"
        val actual = model(childBody, parentBody)
        assertEquals("jar", actual.packaging) // Parent packaging is never inherited.
        assertEquals(listOf(Coordinate("example", "inherited", "2"), Coordinate("example", "explicit", "1")), actual.dependencies(api))
        assertEquals(listOf(Coordinate("example", "inherited", "2"), Coordinate("example", "managed", "3"), Coordinate("example", "explicit", "1")), actual.dependencies(runtime))
        val override = model("<dependencyManagement><dependencies>" + dep("managed", "<version>4</version>") + "</dependencies></dependencyManagement><dependencies>" + dep("managed") + "</dependencies>", parentBody)
        assertEquals("4", override.dependencies(runtime).single { it.name == "managed" }.version)
        assertEquals(listOf(Coordinate("example", "managed", "4")), override.dependencies(api)) // Child declaration replaces the inherited management entry.
    }

    @Test fun profilesCannotInfluenceAnyResolutionPropertyOrDependency() {
        val property = "<properties><v>1</v><alias>\${v}</alias></properties>"
        val dependency = "<dependencies>" + dep("b", "<version>\${alias}</version>") + "</dependencies>"
        val harmless = "<profiles><profile><id>build</id><activation><jdk>[17,)</jdk></activation><properties><compiler>17</compiler></properties><build/></profile></profiles>"
        assertEquals("1", model(dependency, property + harmless).dependencies(runtime).single().version)
        for (influence in listOf("<properties><v>2</v></properties>", "<dependencies/>", "<dependencyManagement/>", "<repositories/>")) {
            assertFails { model(dependency, property + "<profiles><profile><id>unsafe</id>$influence</profile></profiles>") }
        }
        for (bad in listOf("\${undefined}", "\${env.HOME}", "\${settings.localRepository}")) {
            assertFails { model("<dependencies>" + dep("b", "<version>$bad</version>") + "</dependencies>", "") }
        }
        assertFails { model(dependency, "<properties><alias>\${v}</alias><v>\${alias}</v></properties>") }
    }

    @Test fun parentIdentityCyclesDepthPackagingAndRelativePathsFailClosed() {
        assertFails { effectivePom(child, pom("child", parentRef())) { pom("wrong", "<packaging>pom</packaging>") } }
        assertFails { effectivePom(child, pom("child", parentRef())) { pom("parent") } }
        assertFails { effectivePom(child, pom("child", parentRef())) { pom("parent", "<packaging>pom</packaging>" + parentRef()) } }
        assertFails { effectivePom(child, pom("child", parentRef().replace("</parent>", "<relativePath>../../outside.xml</relativePath></parent>"))) { error("Must not fetch unsafe relative path") } }
        assertFails { effectivePom(child, pom("child", parentRef("p0"))) { c ->
            val next = c.name.removePrefix("p").toInt() + 1
            pom(c.name, "<packaging>pom</packaging>" + parentRef("p$next"))
        } }
        val inheritedIdentity = parser.parse("<project><modelVersion>4.0.0</modelVersion><artifactId>child</artifactId>${parentRef()}</project>".encodeToByteArray())
        assertEquals("jar", effectivePom(child, inheritedIdentity) { pom("parent", "<packaging>pom</packaging>") }.packaging)
    }

    @Test fun unsupportedMergesAndSelectedExclusionsAreRejected() {
        val exclusions = "<exclusions><exclusion><groupId>x</groupId><artifactId>y</artifactId></exclusion></exclusions>"
        assertTrue(model("<dependencies>" + dep("b", "<version>1</version><scope>test</scope>$exclusions") + "</dependencies>", "").dependencies(runtime).isEmpty())
        assertFails { model("<dependencies>" + dep("b", "<version>1</version>$exclusions") + "</dependencies>", "").dependencies(runtime) }
        assertFails { model("<dependencies>" + dep("b", "<version>1</version>").repeat(2) + "</dependencies>", "") }
        assertFails { model("<dependencyManagement><dependencies>" + dep("bom", "<scope>import</scope><type>pom</type><version>1</version>") + "</dependencies></dependencyManagement>", "") }
        assertFails { model("<properties><s>import</s></properties><dependencyManagement><dependencies>" + dep("bom", "<scope>\${s}</scope><type>pom</type><version>1</version>") + "</dependencies></dependencyManagement>", "") }
        assertFails { model("<dependencies>" + dep("b", "<version>1</version><classifier>tests</classifier>") + "</dependencies>", "").dependencies(runtime) }
        assertFails { model("<dependencies>" + dep("b", "<version>1</version>") + "</dependencies>",
            "<dependencies>" + dep("b", "<version>2</version>").replace("<groupId>example</groupId>", "<groupId>\${project.groupId}</groupId>") + "</dependencies>") }
    }

    @Test fun parentFetchAndReplayAreBoundedAndManagementDoesNotConstrainUnrelatedRoots() {
        val repo = Repository("central", "https://example.test/maven")
        val parentText = "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging><dependencyManagement><dependencies>${dep("b", "<version>9</version>")}</dependencies></dependencyManagement></project>"
        val childText = "<project><modelVersion>4.0.0</modelVersion><artifactId>child</artifactId>${parentRef()}</project>"
        val bText = "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>b</artifactId><version>1</version></project>"
        val data = mapOf("child" to childText, "parent" to parentText, "b" to bText)
        val fetched = mutableMapOf<Digest, ByteArray>()
        val transport = object : ArtifactTransport {
            override fun fetch(url: String): ByteArray {
                if (url.endsWith(".module")) throw MissingResource(url)
                val name = url.substringBeforeLast('/').substringBeforeLast('/').substringAfterLast('/')
                return (if (url.endsWith(".pom")) data.getValue(name).encodeToByteArray() else name.encodeToByteArray()).also { fetched[Sha256.digest(it)] = it }
            }
        }
        val selected = MavenResolver(transport, parser).resolveAll(listOf(child, Coordinate("example", "b", "1")), runtime, listOf(repo))
        assertEquals(2, selected.first().metadata.size)
        assertEquals(listOf(child), selected.first().artifacts.map { it.coordinate })
        assertEquals("1", selected.last().artifacts.single().coordinate.version)
        assertFails { MavenResolver(transport, parser, maxComponents = 1).resolve(child, runtime, listOf(repo)) }
        assertFails { MavenResolver(transport, parser, maxComponents = 2).resolveAll(listOf(child, Coordinate("example", "b", "1")), runtime, listOf(repo)) }
        assertFails { MavenResolver(transport, parser, maxRequests = 2).resolve(child, runtime, listOf(repo)) }
        val parentDigest = selected.first().metadata.last().digest
        assertFails { verifyLocked(selected.first()) { if (it == parentDigest) parentText.replace("9", "8").encodeToByteArray() else fetched[it] } }
    }
}
