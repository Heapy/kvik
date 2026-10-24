package kvik.resolution

import kvik.core.*
import kotlin.test.*

class ModuleExclusionsTest {
    private val repo = Repository("test", "https://repo.example/maven")
    private val variant = jvmVariantRequest("java-runtime", 25)
    private fun coordinate(name: String) = Coordinate.parse(if (':' in name) name else "example:$name:1")
    private fun url(c: Coordinate, suffix: String) = "${repo.url}/${c.group.replace('.', '/')}/${c.name}/${c.version}/${c.name}-${c.version}.$suffix"
    private fun rule(group: String, module: String) = obj("group" to group.json(), "module" to module.json())
    private fun edge(name: String, excludes: List<JsonValue> = emptyList()): JsonValue {
        val c = coordinate(name)
        return obj("group" to c.group.json(), "module" to c.name.json(), "version" to obj("requires" to c.version.json()), "excludes" to excludes.json())
    }
    private fun component(name: String, dependencies: List<JsonValue> = emptyList()): Map<String, ByteArray> {
        val c = coordinate(name)
        val bytes = name.encodeToByteArray()
        val digest = Sha256.digest(bytes)
        return mapOf(url(c, "jar") to bytes, url(c, "module") to obj("formatVersion" to "1.1".json(),
            "component" to obj("group" to c.group.json(), "module" to c.name.json(), "version" to c.version.json()),
            "variants" to listOf(obj("name" to "runtime".json(), "attributes" to obj("org.gradle.category" to "library".json(),
                "org.gradle.usage" to "java-runtime".json(), "org.jetbrains.kotlin.platform.type" to "jvm".json()),
                "dependencies" to dependencies.json(), "files" to listOf(obj("name" to "${c.name}-${c.version}.jar".json(),
                    "url" to "${c.name}-${c.version}.jar".json(), "size" to digest.sizeBytes.json(), "sha256" to digest.hash.json())).json())).json())
            .canonical().encodeToByteArray())
    }
    private class Network(val entries: Map<String, ByteArray>) : ArtifactTransport {
        val fetched = linkedMapOf<String, ByteArray>()
        override fun fetch(url: String): ByteArray = (entries[url] ?: throw MissingResource(url)).also { fetched[url] = it }
    }
    private fun resolve(entries: Map<String, ByteArray>, roots: List<String>): Pair<ResolutionGraph, Network> {
        val network = Network(entries)
        return MavenResolver(network, NativePomParser()).resolveGraph(roots.map(::coordinate), variant, listOf(repo)) to network
    }

    @Test fun moduleExclusionsArePathSpecificAcrossRootsAndDiamondsAndReplayMetadata() {
        val entries = component("restricted", listOf(edge("middle", listOf(rule("blocked", "*"), rule("*", "drop"))))) +
            component("ordinary", listOf(edge("middle"))) +
            component("middle", listOf(edge("blocked:leaf:1"), edge("drop"), edge("keep"))) +
            component("blocked:leaf:1") + component("drop") + component("keep")
        val isolated = resolve(entries, listOf("restricted"))
        assertEquals(setOf("restricted", "middle", "keep"), isolated.first.selections.single().artifacts.map { it.coordinate.name }.toSet())
        assertFalse(isolated.second.fetched.keys.any { "/blocked/" in it || "/drop/" in it })
        assertEquals(2, isolated.first.edges.count { it.kind == "excluded" && it.semantics == "metadata-exclusion" && it.exclusion == null })
        assertTrue(isolated.first.selections.single().metadata.any { it.url.endsWith("restricted-1.module") })
        assertEquals(isolated.first, resolve(isolated.second.fetched, listOf("restricted")).first)
        for (order in listOf(listOf("restricted", "ordinary"), listOf("ordinary", "restricted"))) {
            val graph = resolve(entries, order).first
            val selections = graph.selections.associateBy { it.requested.name }
            assertEquals(setOf("restricted", "middle", "keep"), selections.getValue("restricted").artifacts.map { it.coordinate.name }.toSet())
            assertEquals(setOf("ordinary", "middle", "leaf", "drop", "keep"), selections.getValue("ordinary").artifacts.map { it.coordinate.name }.toSet())
            assertEquals(2, graph.nodes.count { it.coordinate.name == "middle" })
            assertEquals(graph.nodes.size, graph.nodes.map { it.id }.distinct().size)
            val diamond = resolve(entries + component("root", order.map { edge(it) }), listOf("root")).first
            assertEquals(setOf("root", "restricted", "ordinary", "middle", "leaf", "drop", "keep"), diamond.selections.single().artifacts.map { it.coordinate.name }.toSet())
            graph.toJson()
        }
    }

    @Test fun inheritedAndDeclaredExclusionsCombineAndContinueThroughPomDependencies() {
        val pom = """<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>lower</artifactId><version>1</version><dependencies>
            <dependency><groupId>blocked</groupId><artifactId>one</artifactId><version>1</version></dependency>
            <dependency><groupId>example</groupId><artifactId>two</artifactId><version>1</version></dependency>
            <dependency><groupId>example</groupId><artifactId>keep</artifactId><version>1</version></dependency>
            </dependencies></project>""".encodeToByteArray()
        val entries = component("root", listOf(edge("middle", listOf(rule("blocked", "*"))))) +
            component("middle", listOf(edge("lower", listOf(rule("example", "two"))))) +
            mapOf(url(coordinate("lower"), "pom") to pom, url(coordinate("lower"), "jar") to byteArrayOf(1)) + component("keep")
        val graph = resolve(entries, listOf("root")).first
        assertEquals(setOf("root", "middle", "lower", "keep"), graph.selections.single().artifacts.map { it.coordinate.name }.toSet())
        assertEquals(setOf("blocked:one:1", "example:two:1"), graph.edges.filter { it.kind == "excluded" }.map { it.requirement.toString() }.toSet())
    }

    @Test fun allExclusionAppliesBelowIncomingDependencyAndStillVerifiesItsArtifact() {
        val root = component("root", listOf(edge("middle", listOf(rule("*", "*")))))
        val middle = component("middle", listOf(edge("missing")))
        assertEquals(listOf("root", "middle"), resolve(root + middle, listOf("root")).first.selections.single().artifacts.map { it.coordinate.name })
        val corrupt = middle + (url(coordinate("middle"), "jar") to byteArrayOf(8))
        assertTrue(assertFails { resolve(root + corrupt, listOf("root")) }.message.orEmpty().contains("Artifact digest mismatch"))
    }

    @Test fun inheritedExclusionsFilterConstraintsButAnOrdinaryPathStillAppliesThem() {
        val middle = component("middle")
        val document = parseJson(middle.getValue(url(coordinate("middle"), "module")).decodeToString()).objectValue()
        val constrained = JsonValue.Object(document + ("variants" to document.getValue("variants").arrayValue().map {
            JsonValue.Object(it.objectValue() + ("dependencyConstraints" to listOf(obj("group" to "example".json(), "module" to "leaf".json(),
                "version" to obj("requires" to "2".json()))).json()))
        }.json())).canonical().encodeToByteArray()
        val entries = component("restricted", listOf(edge("middle", listOf(rule("*", "*"))))) + component("ordinary", listOf(edge("middle"))) +
            middle + (url(coordinate("middle"), "module") to constrained) + component("leaf")
        assertEquals(2, resolve(entries, listOf("restricted", "leaf")).first.selections.size)
        for (roots in listOf(listOf("restricted", "ordinary", "leaf"), listOf("ordinary", "restricted", "leaf"))) {
            assertTrue(assertFails { resolve(entries, roots) }.message.orEmpty().contains("Constraint example:leaf:2"))
        }
        val pom = """<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>platform</artifactId><version>1</version><packaging>pom</packaging>
            <dependencyManagement><dependencies><dependency><groupId>example</groupId><artifactId>leaf</artifactId><version>2</version></dependency></dependencies></dependencyManagement></project>""".encodeToByteArray()
        val platform = JsonValue.Object(edge("platform", listOf(rule("*", "*"))).objectValue() + ("attributes" to obj("org.gradle.category" to "platform".json())))
        val withPlatform = component("root", listOf(platform)) + mapOf(url(coordinate("platform"), "pom") to pom) + component("leaf")
        assertEquals(2, resolve(withPlatform, listOf("root", "leaf")).first.selections.size)
    }

    @Test fun externalVariantSelectionIgnoresAlternateShadowedAndEmbeddedPublications() {
        val original = component("root")
        val document = parseJson(original.getValue(url(coordinate("root"), "module")).decodeToString()).objectValue()
        val external = document.getValue("variants").arrayValue().single().objectValue()
        for (bundling in listOf("shadowed", "embedded")) {
            val alternative = JsonValue.Object(external + mapOf("name" to bundling.json(), "attributes" to
                JsonValue.Object(external.getValue("attributes").objectValue() + ("org.gradle.dependency.bundling" to bundling.json()))))
            fun with(variants: List<JsonValue>) = original + (url(coordinate("root"), "module") to
                JsonValue.Object(document + ("variants" to variants.json())).canonical().encodeToByteArray())
            for (variants in listOf(listOf(alternative, JsonValue.Object(external)), listOf(JsonValue.Object(external), alternative))) {
                assertEquals("runtime", resolve(with(variants), listOf("root")).first.nodes.single().variant)
            }
            assertFails { resolve(with(listOf(alternative)), listOf("root")) }
        }
    }

    @Test fun exclusionContextSurvivesRedirectAndDoesNotExcludeRedirectDestinationItself() {
        val alias = coordinate("alias")
        val host = coordinate("host")
        val redirect = obj("formatVersion" to "1.1".json(), "component" to obj("group" to alias.group.json(), "module" to alias.name.json(), "version" to alias.version.json()),
            "variants" to listOf(obj("name" to "runtime".json(), "attributes" to obj("org.gradle.category" to "library".json(), "org.gradle.usage" to "java-runtime".json(),
                "org.jetbrains.kotlin.platform.type" to "jvm".json()), "available-at" to obj("url" to "../../host/1/host-1.module".json(),
                    "group" to host.group.json(), "module" to host.name.json(), "version" to host.version.json()))).json()).canonical().encodeToByteArray()
        val hostEntries = component("host", listOf(edge("missing")))
        val hostDocument = parseJson(hostEntries.getValue(url(host, "module")).decodeToString()).objectValue()
        val ownership = hostDocument.getValue("component").objectValue() + mapOf("module" to alias.name.json(), "url" to "../../alias/1/alias-1.module".json())
        val entries = component("root", listOf(edge("alias", listOf(rule("*", "*"))))) + hostEntries +
            mapOf(url(alias, "module") to redirect, url(host, "module") to JsonValue.Object(hostDocument + ("component" to JsonValue.Object(ownership))).canonical().encodeToByteArray())
        val graph = resolve(entries, listOf("root")).first
        assertEquals(listOf("root", "host"), graph.selections.single().artifacts.map { it.coordinate.name })
        assertEquals(3, graph.selections.single().metadata.size)
        assertEquals(1, graph.edges.count { it.kind == "redirect" })
    }

    @Test fun malformedOrUnboundedExclusionsFailClosed() {
        val invalid = listOf(rule("blocked*", "leaf"), rule("", "leaf"), rule("blocked", "leaf?"),
            obj("group" to "blocked".json()), obj("group" to "blocked".json(), "module" to "leaf".json(), "version" to "1".json()))
        for (rule in invalid) assertFails { resolve(component("root", listOf(edge("middle", listOf(rule)))) + component("middle"), listOf("root")) }
        assertFails { resolve(component("root", listOf(edge("middle", List(65) { rule("group$it", "*") }))) + component("middle"), listOf("root")) }
        val cycle = component("root", listOf(edge("middle", listOf(rule("unused", "one"))))) +
            component("middle", listOf(edge("root", listOf(rule("unused", "two")))))
        val failure = assertFails { MavenResolver(Network(cycle), NativePomParser(), maxComponents = 2)
            .resolve(coordinate("root"), variant, listOf(repo)) }
        assertTrue(failure.message.orEmpty().contains("expansion depth bound"))
    }
}
