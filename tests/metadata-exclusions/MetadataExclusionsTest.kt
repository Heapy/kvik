@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.metadataexclusions

import kvik.core.*
import kvik.resolution.*
import kvik.locking.*
import kvik.cache.*
import kvik.packages.queryProjectDependencies
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.*

private val repo = Repository("fixture", "https://example.org/maven")
private val parent = Coordinate.parse("example:parent:1")
private val omitted = Coordinate.parse("example:jvm-only:1")
private val native = VariantRequest("macosArm64", "kotlin-api", emptyMap())
private val domain = ResolutionDomain("sample", "macosArm64", "main", "kotlin-api")
private fun path(c: Coordinate, suffix: String) = "${repo.url}/${c.group.replace('.', '/')}/${c.name}/${c.version}/${c.name}-${c.version}.$suffix"
private fun metadata(c: Coordinate = parent, deps: List<Coordinate> = listOf(omitted), platform: Boolean = false, redirect: Boolean = false): ByteArray {
    val variant = obj("name" to "native".json(), "attributes" to obj("org.gradle.category" to "library".json(),
        "org.gradle.usage" to "kotlin-api".json(), "org.jetbrains.kotlin.platform.type" to "native".json(), "org.jetbrains.kotlin.native.target" to "macos_arm64".json()),
        "dependencies" to deps.map { obj("group" to it.group.json(), "module" to it.name.json(), "version" to obj("requires" to it.version.json())).let { value ->
            if (!platform) value else JsonValue.Object(value.objectValue() + ("attributes" to obj("org.gradle.category" to "platform".json()))) } }.json())
    val selected = if (!redirect) variant else obj("name" to "native".json(), "attributes" to variant.objectValue().getValue("attributes"),
        "available-at" to obj("url" to "../../child/1/child-1.module".json(), "group" to c.group.json(), "module" to "child".json(), "version" to "1".json()))
    return obj("formatVersion" to "1.1".json(), "component" to obj("group" to c.group.json(), "module" to c.name.json(), "version" to c.version.json()),
        "variants" to listOf(selected).json()).canonical().encodeToByteArray()
}
private class Transport(val entries: Map<String, ByteArray>) : ArtifactTransport {
    val calls = mutableListOf<String>()
    override fun fetch(url: String): ByteArray { calls += url; return entries[url] ?: throw MissingResource(url) }
}
private fun rule(bytes: ByteArray = metadata()) = MetadataEdgeExclusion(parent, Sha256.digest(bytes), "native", omitted, "Unsupported upstream JVM edge, explicitly reviewed")
private fun resolver(transport: Transport, rules: List<MetadataEdgeExclusion>, pins: List<Coordinate> = emptyList()) =
    MavenResolver(transport, NativePomParser(), pins, metadataExclusions = rules)
private fun config(roots: List<Coordinate> = listOf(parent)) = parseProjectConfiguration(obj("schemaVersion" to 1.json(), "name" to "sample".json(),
    "repositories" to listOf(obj("id" to repo.id.json(), "url" to repo.url.json())).json(), "modules" to listOf(obj("id" to "sample".json(),
        "sourceSets" to listOf(obj("name" to "main".json(), "directories" to emptyList<JsonValue>().json(), "resources" to emptyList<JsonValue>().json(),
            "dependsOn" to emptyList<JsonValue>().json(), "dependencies" to roots.map { obj("coordinate" to it.toString().json()) }.json())).json(),
        "targets" to listOf(obj("name" to "macosArm64".json(), "main" to "main".json(), "test" to JsonValue.Null, "compilerOptions" to emptyList<JsonValue>().json())).json(),
        "compilerPlugins" to emptyList<JsonValue>().json())).json()))
private fun effective(rules: List<ScopedMetadataExclusion> = emptyList(), project: ProjectConfiguration = config()) =
    effectiveConfiguration(project, ResolverPolicy(metadataExclusions = rules), listOf(DependencyScope("sample", "macosArm64", "main", "kotlin-api")))

class MetadataExclusionsTest {
    @Test fun excludesOnlyExactOrdinaryEdgeBeforePinsAndRecordsItsEvidence() {
        val bytes = metadata(); val r = rule(bytes)
        val transport = Transport(mapOf(path(parent, "module") to bytes))
        assertFails { resolver(transport, emptyList()).resolve(parent, native, listOf(repo)) }
        transport.calls.clear()
        val graph = resolver(transport, listOf(r), listOf(omitted.copy(version = "2"))).resolveGraph(listOf(parent), native, listOf(repo), true)
        assertEquals(listOf(path(parent, "module")), transport.calls)
        val edge = graph.edges.single()
        assertEquals("excluded", edge.kind); assertNull(edge.target); assertNull(edge.selected); assertFalse(edge.transitive)
        assertEquals(omitted, edge.requirement); assertEquals(r, edge.exclusion)
        assertEquals(listOf(parent), graph.nodes.map { it.coordinate })
        val json = graph.toJson()
        assertEquals(r.digest, Digest.fromJson(json.objectValue().getValue("edges").arrayValue().single().objectValue().getValue("exclusion").objectValue().getValue("digest")))
        val work = getenv("KVIK_METADATA_WORK")!!.toKString()
        NativeFiles.publish("$work/excluded-graph.json", json.canonical().encodeToByteArray(), 1024 * 1024)
        val alias = Coordinate.parse("example:alias:1")
        val aliasBytes = metadata(alias, redirect = true).decodeToString().replace("child", "parent").encodeToByteArray()
        val owned = JsonValue.Object(parseJson(bytes.decodeToString()).objectValue() + ("component" to obj("group" to alias.group.json(),
            "module" to alias.name.json(), "version" to alias.version.json(), "url" to "../../alias/1/alias-1.module".json()))).canonical().encodeToByteArray()
        val aliased = resolver(Transport(mapOf(path(alias, "module") to aliasBytes, path(parent, "module") to owned)), listOf(rule(owned)))
            .resolveGraph(listOf(alias, parent), native, listOf(repo), true)
        assertEquals(1, aliased.edges.count { it.kind == "excluded" })
        assertEquals(parent, aliased.nodes.single { it.id == aliased.edges.single { edge -> edge.kind == "excluded" }.source }.coordinate)
        assertEquals(2, aliased.selections.size)
        val directTransport = Transport(mapOf(path(parent, "module") to bytes, path(omitted, "module") to metadata(omitted, emptyList())))
        val direct = resolver(directTransport, listOf(r)).resolveGraph(listOf(parent, omitted), native, listOf(repo), true)
        assertTrue(path(omitted, "module") in directTransport.calls)
        assertEquals(setOf(parent, omitted), direct.nodes.map { it.coordinate }.toSet())
        assertEquals(listOf(parent, omitted), direct.selections.map { it.requested })
        assertEquals(omitted, direct.nodes.single { it.id == direct.roots[1].node }.coordinate)
        assertEquals("excluded", direct.edges.single().kind)
        assertNull(direct.edges.single().target)
    }

    @Test fun staleVersionsBytesVariantsAndMissingEdgesFailClosed() {
        val original = metadata(); val r = rule(original)
        val changed = listOf(r.copy(metadata = Sha256.digest(byteArrayOf(1))), r.copy(variant = "other"), r.copy(dependency = omitted.copy(version = "2")))
        for (candidate in changed) assertFails { resolver(Transport(mapOf(path(parent,"module") to original)), listOf(candidate)).resolve(parent, native, listOf(repo)) }
        val newer = parent.copy(version = "2")
        assertFails { resolver(Transport(mapOf(path(newer,"module") to metadata(newer))), listOf(r), listOf(newer)).resolve(parent, native, listOf(repo)) }
        val constraints = metadata().decodeToString().replace("\"dependencies\":", "\"dependencyConstraints\":").encodeToByteArray()
        for (bytes in listOf(metadata(deps = emptyList()), metadata(deps = listOf(omitted, omitted)), constraints, metadata(platform = true), metadata(redirect = true))) {
            assertFails { resolver(Transport(mapOf(path(parent,"module") to bytes)), listOf(r.copy(metadata = Sha256.digest(bytes)))).resolve(parent, native, listOf(repo)) }
        }
        val pom = "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>parent</artifactId><version>1</version></project>".encodeToByteArray()
        assertFails { resolver(Transport(mapOf(path(parent,"pom") to pom)), listOf(r.copy(metadata = Sha256.digest(pom)))).resolve(parent,native,listOf(repo)) }
    }

    @Test fun unvisitedRulesAllowedOnlyForPartialTraversalAndCannotExcludeDirectRoot() {
        val other = Coordinate.parse("example:other:1"); val transport = Transport(mapOf(path(other,"module") to metadata(other, emptyList())))
        assertEquals(1, resolver(transport,listOf(rule())).resolveGraph(listOf(other),native,listOf(repo)).nodes.size)
        assertFails { resolver(transport,listOf(rule())).resolveAll(listOf(other),native,listOf(repo),true) }
        assertFails { resolver(transport,listOf(rule())).resolve(omitted,native,listOf(repo)) }
    }

    @Test fun policyIdentityCanonicalizationBoundsAndDomainValidation() {
        val scoped = ScopedMetadataExclusion(domain,rule())
        assertEquals(scoped,parseMetadataEdgeExclusion(scoped.argument()))
        val plain=effective();val corrected=effective(listOf(scoped))
        assertNotEquals(plain.digest,corrected.digest)
        assertFalse(plain.document.canonical().contains("metadataExclusions"))
        assertNotEquals(corrected.digest,effective(listOf(scoped.copy(rule=scoped.rule.copy(reason="Different explanation")))).digest)
        val other=scoped.copy(rule=scoped.rule.copy(parent=Coordinate.parse("example:other:1")))
        assertEquals(effective(listOf(scoped,other)).digest,effective(listOf(other,scoped)).digest)
        assertFails { ResolverPolicy(metadataExclusions=listOf(scoped,scoped)) }
        assertFails { effective(listOf(scoped),config(emptyList())) }
        assertFails { effective(listOf(scoped.copy(domain=domain.copy(consumer=ResolutionConsumer.Module("unknown"))))) }
        assertFails { parseMetadataEdgeExclusion(" ".repeat(4097)) }
        assertFails { parseMetadataEdgeExclusion(scoped.argument().dropLast(1)+",\"extra\":true}") }
        assertFails { scoped.rule.copy(reason="bad\n") }
        assertFails { ResolverPolicy(metadataExclusions=(1..33).map { scoped.copy(rule=scoped.rule.copy(parent=Coordinate("example","parent$it","1"))) }) }
    }

    @Test fun lockPersistsPolicyAndReplaysExactMetadataWithoutNetwork() {
        val work=getenv("KVIK_METADATA_WORK")!!.toKString();val content=NativeContentStore("$work/cas")
        val scoped=ScopedMetadataExclusion(domain,rule());val configuration=effective(listOf(scoped))
        val locks=DependencyLocks(content,NativePomParser())
        val result=locks.resolve(configuration,Transport(mapOf(path(parent,"module") to metadata())))
        assertEquals(listOf(scoped),result.metadataExclusions)
        assertEquals(result,parseDependencyLock(result.bytes()))
        assertEquals(result,locks.loadLocked(result.bytes(),configuration))
        val query = queryProjectDependencies("/fixture/kvik.json", "/fixture/kvik.lock.json", "$work/cas", config(), configuration,
            { result.bytes() }, content, NativePomParser(), 1L)
        assertTrue(query.objectValue().getValue("diagnostics").arrayValue().isEmpty())
        val graph = query.objectValue().getValue("domains").arrayValue().single().objectValue().getValue("graph")
        assertEquals("excluded", graph.objectValue().getValue("edges").arrayValue().single().objectValue().getValue("kind").stringValue())
        NativeFiles.publish("$work/excluded-project.json", query.canonical().encodeToByteArray(), 1024*1024)
        assertFails { locks.loadLocked(result.bytes(),effective()) }
        val unvisited = scoped.copy(rule = scoped.rule.copy(parent = Coordinate.parse("example:unvisited:1")))
        assertFails { locks.resolve(effective(listOf(scoped, unvisited)), Transport(mapOf(path(parent,"module") to metadata()))) }
        assertFails { locks.loadLocked(result.copy(metadataExclusions=emptyList()).bytes(),configuration) }
        val fields=parseJson(result.bytes().decodeToString()).objectValue()
        assertFails { parseDependencyLock(JsonValue.Object(fields+("metadataExclusions" to listOf(scoped.toJson(),scoped.toJson()).json())).canonical().encodeToByteArray()) }
        NativeFiles.publish("$work/excluded-lock.json",result.bytes(),1024*1024)
    }
}
