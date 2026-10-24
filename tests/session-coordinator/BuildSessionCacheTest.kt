package kvik.cli

import kvik.core.*
import kvik.locking.*
import kvik.projectgraph.*
import kvik.plugins.kotlin.KotlinBuildPlugin
import kotlin.test.*

class BuildSessionCacheTest {
    private fun digest(s: String) = Sha256.digest(s.encodeToByteArray())
    private val source = """{"schemaVersion":1,"name":"fixture","repositories":[{"id":"central","url":"https://repo.maven.apache.org/maven2"}],"modules":[{"id":"base","sourceSets":[{"name":"main","directories":["base"],"resources":[],"dependsOn":[],"dependencies":[]}],"targets":[{"name":"jvm","main":"main","test":null,"compilerOptions":[]}],"compilerPlugins":[]},{"id":"app","sourceSets":[{"name":"main","directories":["main"],"resources":[],"dependsOn":[],"dependencies":[{"module":"base"}]},{"name":"test","directories":["test"],"resources":[],"dependsOn":["main"],"dependencies":[]}],"targets":[{"name":"jvm","main":"main","test":"test","compilerOptions":[]}],"compilerPlugins":[]}]}"""
    private val files = TreeManifest(listOf("base/Base.kt", "main/Main.kt", "test/Test.kt").map { FileEntry(LogicalPath(it), digest(it)) })
    private val composition = digest("composition")
    private val tools = mapOf("jvm" to listOf("jdk", "jvm-compiler", "jvm-worker", "junit").associateWith(::digest))
    private val request = ProjectGraphRequest(setOf("app"), setOf("jvm"), ProjectMode.BUILD)
    private val plugins = listOf(KotlinBuildPlugin())

    @Test fun exactModelsAreRetainedButChangedBytesAreParsedAndValidated() {
        val cache = BuildSessionCache()
        cache.begin(); val first = cache.configuration(source.encodeToByteArray())
        assertSame(first, cache.configuration(source.encodeToByteArray())); assertFalse(cache.modelReused)
        cache.begin(); assertSame(first, cache.configuration(source.encodeToByteArray())); assertTrue(cache.modelReused)
        cache.begin(); assertNotSame(first, cache.configuration((source + "\n").encodeToByteArray())); assertFalse(cache.modelReused)
        assertFails { cache.configuration(source.replace("\"jvm\"", "\"bogus\"").encodeToByteArray()) }
    }

    @Test fun graphReuseRequiresAllFreshInputsAndNeverRetainsDependencyResults() {
        val cache = BuildSessionCache()
        val config = cache.configuration(source.encodeToByteArray()); val effective = effectiveConfiguration(config)
        val lock = DependencyLock(effective.digest, emptyList())
        fun graph(tree: TreeManifest = files, snapshot: Digest = digest("snapshot"), lockBytes: String = "lock",
                  toolIds: Map<String, Map<String, Digest>> = tools, toolDoc: Digest = digest("tools"),
                  selected: ProjectGraphRequest = request, comp: Digest = composition) = cache.graph(effective, lock, tree, snapshot,
            lockBytes.encodeToByteArray(), comp, toolIds, toolDoc, selected, plugins)
        cache.begin(); val first = graph(); assertFalse(cache.graphReused)
        cache.begin(); assertSame(first, graph()); assertTrue(cache.graphReused)
        val baseNode = first.nodes.single { it.id == "base:jvm:main" }
        val appNode = first.nodes.single { it.id == "app:jvm:main" }
        val baseAction = baseNode.resolve(emptyMap())
        fun resolve(output: String) = appNode.resolve(mapOf(baseNode.id to ActionResult(baseNode.id, baseAction.key(Sha256),
            ActionStatus.EXECUTED, TreeManifest(listOf(FileEntry(LogicalPath("classes/Base.class"), digest(output)))), 0)))
        assertNotEquals(resolve("before").key(Sha256), resolve("after").key(Sha256))
        cache.begin(); assertNotSame(first, graph(files.copy(files = files.files.map { it.copy(digest = digest("changed")) }), digest("source-edit"))); assertFalse(cache.graphReused)
        cache.begin(); assertNotSame(first, graph(lockBytes = "different-lock-bytes")); assertFalse(cache.graphReused)
        cache.begin(); assertNotSame(first, graph(toolDoc = digest("changed-tool-roots"))); assertFalse(cache.graphReused)
        cache.begin(); assertNotSame(first, graph(toolIds = tools + ("jvm" to tools.getValue("jvm") + ("jdk" to digest("changed-jdk"))))); assertFalse(cache.graphReused)
        cache.begin(); assertNotSame(first, graph(comp = digest("replacement-runtime"))); assertFalse(cache.graphReused)
        val changedConfig = cache.configuration(source.replace("fixture", "changed").encodeToByteArray())
        val changedEffective = effectiveConfiguration(changedConfig)
        assertNotSame(first, cache.graph(changedEffective, DependencyLock(changedEffective.digest, emptyList()), files, digest("snapshot"),
            "lock".encodeToByteArray(), composition, tools, digest("tools"), request, plugins))
    }

    @Test fun retainedTestGraphStillResolvesNeverCachedTestsFromFreshOutputs() {
        val cache = BuildSessionCache(); val effective = effectiveConfiguration(cache.configuration(source.encodeToByteArray()))
        val selected = request.copy(mode = ProjectMode.TEST, jvmTests = mapOf("app" to JvmTests(listOf("fixture.Tests"))))
        fun graph() = cache.graph(effective, DependencyLock(effective.digest, emptyList()), files, digest("snapshot"), "lock".encodeToByteArray(),
            composition, tools, digest("tools"), selected, plugins)
        val first = graph(); cache.begin(); assertSame(first, graph())
        val results = linkedMapOf<String, ActionResult>()
        for (node in first.nodes) {
            val action = node.resolve(results.filterKeys { it in node.dependencies })
            if (node.id == "app:jvm:test") assertEquals(CachePolicy.NEVER, action.cachePolicy)
            results[node.id] = ActionResult(node.id, action.key(Sha256), ActionStatus.EXECUTED,
                TreeManifest(listOf(FileEntry(LogicalPath("classes/Example.class"), digest(node.id)))), 0)
        }
    }
}
