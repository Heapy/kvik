package kvik.packages

import kvik.core.*
import kvik.locking.*
import kvik.resolution.*
import kotlin.test.*

class WorkspaceDependenciesTest {
    private val domain = ResolutionDomain("sample", "jvm", "main", "java-api").toJson()
    private fun source(name: String) = SourceReference(SourceProvenance("kode", name, "revision-$name", "tree-$name"),
        "libraries/$name", Sha256.digest(name.encodeToByteArray()))
    private fun published(name: String, coordinate: String, multiTarget: Boolean = false): ProjectSpec {
        val base = project(emptyList()).project
        val module = base.modules.single()
        return base.copy(name = name, modules = listOf(module.copy(
            targets = if (multiTarget) module.targets + TargetSpec("linuxX64", "main", null, emptyList()) else module.targets,
            publication = LibraryPublicationSpec(Coordinate.parse(coordinate)))))
    }
    private fun query(vararg coordinates: String, status: String = "complete", truncated: Boolean = false,
                      graphTruncated: Boolean = false, diagnostics: List<JsonValue> = emptyList(), lock: Boolean = true) =
        JsonValue.Object(envelope("project-dependencies", obj(), 1000, emptyList(), emptyList(), status = status,
            basis = "locked-inputs", truncated = truncated) + mapOf(
            "configuration" to Sha256.digest("configuration".encodeToByteArray()).toJson(),
            "dependencyLock" to if (lock) Sha256.digest("lock".encodeToByteArray()).toJson() else JsonValue.Null,
            "diagnostics" to diagnostics.json(), "domains" to listOf(obj("domain" to domain, "graph" to obj(
                "truncated" to graphTruncated.json(), "nodes" to coordinates.mapIndexed { index, coordinate ->
                    obj("id" to "$coordinate/variant-$index".json(), "coordinate" to coordinate.json())
                }.json()))).json()))
    private fun input(name: String, spec: ProjectSpec = project(emptyList()).project, query: JsonValue.Object = query()) =
        WorkspaceDependencyProject(name, source(name), spec, query)
    private fun result(vararg projects: WorkspaceDependencyProject, failures: List<JsonValue> = emptyList()) =
        queryWorkspaceDependencies(projects.toList(), failures, 1000).values
    private fun Map<String, JsonValue>.edges() = getValue("edges").arrayValue().map(JsonValue::objectValue)

    @Test fun lockedTransitiveAndTargetRedirectNodesLinkToDeclaredPublications() {
        val configuration = project(listOf("example:outer:1.0"))
        val entries = (component("example:outer:1.0", listOf("example:library:1.0")) +
            component("example:library:1.0") + component("example:library-jvm:1.0")).toMutableMap()
        val rootMetadata = "${repository.url}/example/library/1.0/library-1.0.module"
        val targetMetadata = "${repository.url}/example/library-jvm/1.0/library-jvm-1.0.module"
        val rootDocument = parseJson(entries.getValue(rootMetadata).decodeToString()).objectValue()
        entries[rootMetadata] = JsonValue.Object(rootDocument + ("variants" to rootDocument.getValue("variants").arrayValue().map { variant ->
            val fields = variant.objectValue()
            JsonValue.Object(fields - "files" - "dependencies" + ("available-at" to obj(
                "url" to "../../library-jvm/1.0/library-jvm-1.0.module".json(), "group" to "example".json(),
                "module" to "library-jvm".json(), "version" to "1.0".json())))
        }.json())).canonical().encodeToByteArray()
        val targetDocument = parseJson(entries.getValue(targetMetadata).decodeToString()).objectValue()
        entries[targetMetadata] = JsonValue.Object(targetDocument + ("component" to obj("group" to "example".json(),
            "module" to "library".json(), "version" to "1.0".json(), "url" to "../../library/1.0/library-1.0.module".json())))
            .canonical().encodeToByteArray()
        val effective = effectiveConfiguration(configuration)
        val content = MemoryContent()
        val lock = DependencyLocks(content, NativePomParser()).resolve(effective, MemoryNetwork(entries))
        val locked = queryProjectDependencies("/consumer/kvik.json", "/consumer/kvik.lock.json", "/cache", configuration,
            effective, { lock.bytes() }, content, NativePomParser(), 1000)
        assertEquals("complete", locked.values.getValue("status").stringValue())
        assertTrue(locked.values.getValue("domains").arrayValue().all { entry ->
            entry.objectValue().getValue("graph").objectValue().getValue("edges").arrayValue().any {
                it.objectValue().getValue("kind").stringValue() == "redirect"
            }
        })
        val consumer = input("consumer", configuration.project, locked)
        val producer = input("producer", published("producer", "example:library:1.0", multiTarget = true))
        val value = result(consumer, producer)
        assertEquals("complete", value.getValue("status").stringValue())
        val edges = value.edges()
        assertEquals(setOf("example:outer:1.0", "example:library:1.0", "example:library-jvm:1.0"), edges.map { it.getValue("coordinate").stringValue() }.toSet())
        assertTrue(edges.filter { it.getValue("coordinate").stringValue() == "example:outer:1.0" }.all { it.getValue("match").stringValue() == "external" })
        for (edge in edges.filter { it.getValue("coordinate").stringValue().startsWith("example:library") }) {
            assertEquals("exact", edge.getValue("match").stringValue())
            assertEquals("unchecked", edge.getValue("sourceArtifactEquivalence").stringValue())
            val owner = edge.getValue("producers").arrayValue().single().objectValue()
            assertEquals("producer", owner.getValue("project").stringValue())
            assertEquals(if (edge.getValue("coordinate").stringValue() == "example:library:1.0") JsonValue.Null else "jvm".json(), owner.getValue("target"))
            val matchingDomain = locked.values.getValue("domains").arrayValue().single { it.objectValue().getValue("domain") == edge.getValue("domain") }
            assertTrue(matchingDomain.objectValue().getValue("graph").objectValue().getValue("nodes").arrayValue().any {
                it.objectValue().getValue("id") == edge.getValue("node")
            })
        }
        val summary = value.getValue("projects").arrayValue().single { it.objectValue().getValue("name").stringValue() == "consumer" }.objectValue()
        assertEquals(consumer.source.toJson(), summary.getValue("source"))
        assertEquals(effective.digest.toJson(), summary.getValue("configuration"))
        assertEquals(Sha256.digest(lock.bytes()).toJson(), summary.getValue("dependencyLock"))
        assertFalse("domains" in summary)
    }

    @Test fun singleTargetBaseIsNotAnEmittedPublicationAndSelfLinksAreExplicit() {
        val value = result(input("self", published("self", "example:lib:1"), query("example:lib:1", "example:lib-jvm:1")))
        assertEquals(listOf("example:lib-jvm:1"), value.getValue("publications").arrayValue().map { it.objectValue().getValue("coordinate").stringValue() })
        assertEquals("external", value.edges().single { it.getValue("coordinate").stringValue() == "example:lib:1" }.getValue("match").stringValue())
        val self = value.edges().single { it.getValue("coordinate").stringValue() == "example:lib-jvm:1" }
        assertEquals("self", self.getValue("producers").arrayValue().single().objectValue().getValue("project").stringValue())
    }

    @Test fun ambiguousOwnershipIsIncompleteWithoutChoosingAWinner() {
        val value = result(input("consumer", query = query("example:lib-jvm:1")),
            input("z", published("z", "example:lib:1")), input("a", published("a", "example:lib:1")))
        assertEquals("incomplete", value.getValue("status").stringValue())
        val edge = value.edges().single()
        assertEquals("ambiguous", edge.getValue("match").stringValue())
        assertEquals(listOf("a", "z"), edge.getValue("producers").arrayValue().map { it.objectValue().getValue("project").stringValue() })
        assertTrue(value.getValue("diagnostics").arrayValue().any { it.objectValue().getValue("code").stringValue() == "KVIK_WORKSPACE_PUBLICATION_AMBIGUOUS" })
    }

    @Test fun versionMismatchAndExternalDependenciesAreInformational() {
        val value = result(input("consumer", query = query("example:lib-jvm:2", "example:outside:1")),
            input("old", published("old", "example:lib:1")), input("new", published("new", "example:lib:3")))
        assertEquals("complete", value.getValue("status").stringValue())
        val mismatch = value.edges().single { it.getValue("match").stringValue() == "version-mismatch" }
        assertEquals(listOf("example:lib-jvm:1", "example:lib-jvm:3"), mismatch.getValue("otherVersions").arrayValue().map(JsonValue::stringValue))
        assertEquals(emptyList(), mismatch.getValue("producers").arrayValue())
        assertEquals("external", value.edges().single { it.getValue("coordinate").stringValue() == "example:outside:1" }.getValue("match").stringValue())
    }

    @Test fun failuresMissingLocksAndTruncatedDomainsRemainIncomplete() {
        val original = obj("code" to "LOCK_BAD".json(), "message" to "Lock replay failed".json(), "source" to "locked-metadata".json())
        val failed = input("failed", query = query(status = "incomplete", diagnostics = listOf(original), lock = false))
        val missing = input("missing", query = query("example:ignored:1", lock = false))
        val truncated = input("truncated", query = query("example:external:1", graphTruncated = true))
        val failure = obj("project" to "unavailable".json(), "code" to "SOURCE_MISSING".json(), "message" to "Revision unavailable".json())
        val value = result(failed, missing, truncated, failures = listOf(failure))
        assertEquals("incomplete", value.getValue("status").stringValue())
        assertTrue(value.getValue("truncated").booleanValue())
        assertEquals(listOf("example:external:1"), value.edges().map { it.getValue("coordinate").stringValue() })
        val diagnostics = value.getValue("diagnostics").arrayValue()
        assertTrue(JsonValue.Object(original.values + ("project" to "failed".json())) in diagnostics)
        assertTrue(failure in diagnostics)
        val domainDiagnostic = diagnostics.single { it.objectValue().getValue("code").stringValue() == "KVIK_WORKSPACE_DOMAIN_TRUNCATED" }.objectValue()
        assertEquals("truncated", domainDiagnostic.getValue("project").stringValue())
        assertEquals(domain, domainDiagnostic.getValue("domain"))
        assertEquals("incomplete", result(input("top", query = query(truncated = true))).getValue("status").stringValue())
        assertEquals("incomplete", result(input("top", query = query(status = "incomplete"))).getValue("status").stringValue())
    }

    @Test fun projectNodeDomainAndFailureInputOrderDoNotAffectOutput() {
        val nodes = query("example:lib-jvm:1", "example:outside:1")
        val firstDomain = nodes.values.getValue("domains").arrayValue().single().objectValue()
        val secondDomain = JsonValue.Object(firstDomain + ("domain" to ResolutionDomain("sample", "jvm", "main", "java-runtime").toJson()))
        val twoDomains = JsonValue.Object(nodes.values + ("domains" to listOf(JsonValue.Object(firstDomain), secondDomain).json()))
        val reversedDomains = twoDomains.values.getValue("domains").arrayValue().reversed().map { entry ->
            val fields = entry.objectValue(); val graph = fields.getValue("graph").objectValue()
            JsonValue.Object(fields + ("graph" to JsonValue.Object(graph + ("nodes" to graph.getValue("nodes").arrayValue().reversed().json()))))
        }
        val consumer = input("consumer", query = twoDomains)
        val producer = input("producer", published("producer", "example:lib:1", multiTarget = true))
        val failures = listOf("z", "a").map { obj("project" to it.json(), "code" to "MISSING".json(), "message" to "Unavailable".json()) }
        assertEquals(queryWorkspaceDependencies(listOf(consumer, producer), failures, 1000),
            queryWorkspaceDependencies(listOf(producer.copy(project = producer.project.copy(modules = producer.project.modules.map { it.copy(targets = it.targets.reversed()) })),
                consumer.copy(dependencies = JsonValue.Object(twoDomains.values + ("domains" to reversedDomains.json())))), failures.reversed(), 1000))
    }

    @Test fun projectIdentityAndResponseBoundsFailExplicitly() {
        val one = input("one")
        assertFailsWith<IllegalArgumentException> { queryWorkspaceDependencies(listOf(one, one), emptyList(), 1000) }
        assertFailsWith<IllegalArgumentException> { queryWorkspaceDependencies(List(257) { one.copy(name = "p$it") }, emptyList(), 1000) }
        assertFailsWith<IllegalArgumentException> { queryWorkspaceDependencies(listOf(one), listOf(obj("project" to "one".json(), "code" to "MISSING".json(), "message" to "Unavailable".json())), 1000) }
        val hugeDiagnostic = obj("code" to "HUGE".json(), "message" to "x".repeat(16 * 1024 * 1024).json())
        assertFailsWith<IllegalArgumentException> { result(input("large", query = query(diagnostics = listOf(hugeDiagnostic)))) }
    }

    @Test fun emptyWorkspaceAndCancellationDuringAggregation() {
        assertEquals("complete", queryWorkspaceDependencies(emptyList(), emptyList(), 1000).values.getValue("status").stringValue())
        val inputs = listOf(input("consumer", query = query(*Array(20) { "example:dependency-$it:1" })))
        var calls = 0
        queryWorkspaceDependencies(inputs, emptyList(), 1000) { calls++ }
        class Cancelled : Exception()
        for (stop in listOf(1, calls / 2, calls)) {
            var seen = 0
            val failure = Cancelled()
            assertSame(failure, assertFailsWith<Cancelled> {
                queryWorkspaceDependencies(inputs, emptyList(), 1000) { if (++seen == stop) throw failure }
            })
        }
    }

    @Test fun compactInputPreservesGraphMeaningWithoutRetainingDetailedQueries() {
        val original = query("example:lib-jvm:1", graphTruncated = true)
        val domain = original.values.getValue("domains").arrayValue().single().objectValue()
        val graph = domain.getValue("graph").objectValue()
        val enriched = JsonValue.Object(original.values + mapOf("sources" to listOf("metadata".json()).json(),
            "declarations" to listOf("large declarations".json()).json(), "domains" to listOf(JsonValue.Object(domain +
                ("graph" to JsonValue.Object(graph + mapOf("paths" to listOf("large paths".json()).json(), "nodes" to
                    graph.getValue("nodes").arrayValue().map { JsonValue.Object(it.objectValue() +
                        ("artifacts" to listOf("artifact metadata".json()).json())) }.json()))))).json()))
        val compact = compactWorkspaceDependencyQuery(enriched)
        assertTrue("sources" !in compact.values && "declarations" !in compact.values)
        val compactGraph = compact.values.getValue("domains").arrayValue().single().objectValue().getValue("graph").objectValue()
        assertEquals(setOf("nodes", "truncated"), compactGraph.keys)
        assertEquals(setOf("id", "coordinate"), compactGraph.getValue("nodes").arrayValue().single().objectValue().keys)
        assertEquals(result(input("consumer", query = enriched)), result(input("consumer", query = compact)))
    }
}
