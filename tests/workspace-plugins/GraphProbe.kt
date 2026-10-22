@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.workspaceplugins

import kvik.core.*
import kvik.cache.*
import kvik.actioncache.*
import kvik.execution.*
import kvik.locking.*
import kvik.resolution.*
import kvik.projectgraph.*
import kvik.compiler.jvm.*
import platform.posix.*

fun main(args: Array<String>) {
    val request = parseJson(readJvmFile(args.single()).decodeToString()).objectValue()
    fun string(name: String) = request.getValue(name).stringValue()
    val content = NativeContentStore(string("store"))
    val inputs = TreeManifest.fromJson(request.getValue("snapshot"))
    for (file in inputs.files) check(content.put(readJvmFile("${string("fixture")}/${file.path.value}", 64 * 1024 * 1024)) == file.digest)
    val config = parseProjectConfiguration(request.getValue("project"))
    val effective = effectiveConfiguration(config, ResolverPolicy(listOf(Coordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:2.4.20"), Coordinate.parse("org.jetbrains:annotations:13.0"))))
    val locks = DependencyLocks(content, NativePomParser())
    val lockPath = "${string("locks")}/${effective.digest.hash}.json"
    val lock = if (access(lockPath, F_OK) == 0) locks.loadLocked(readJvmFile(lockPath, 16 * 1024 * 1024), effective) else {
        val remote = NativeHttpsTransport()
        val transport = object : ArtifactTransport {
            override fun fetch(url: String): ByteArray {
                val path = "${string("httpCache")}/${Sha256.digest(url.encodeToByteArray()).hash}"
                if (access(path, F_OK) == 0) return readJvmFile(path, 64 * 1024 * 1024)
                if (access("$path.missing", F_OK) == 0) throw MissingResource(url)
                if (request.getValue("offline").booleanValue()) error("Offline metadata cache miss: $url")
                return try { remote.fetch(url).also { writeJvmFile(path, it) } }
                catch (missing: MissingResource) { writeJvmFile("$path.missing", url.encodeToByteArray()); throw missing }
            }
        }
        locks.resolve(effective, transport).also { writeJvmFile(lockPath, it.bytes()) }
    }
    check(locks.loadLocked(lock.bytes(), effective) == lock)
    val tools = request.getValue("toolchains").objectValue().mapValues { Digest.fromJson(it.value) }
    val layout = request.getValue("layout").objectValue()
    fun tool(name: String) = layout.getValue(name).stringValue()
    val provider = JvmToolchainProvider { actual ->
        check(actual == tools)
        JvmToolchainLayout(actual, tool("javaExecutable"), tool("workerClasses"), tool("btaApiJar"), tool("stdlibJar"), tool("junitJar"), tool("compilerArtifacts"), tool("compilerManifest"))
    }
    val selected = setOf("consumer", "consumerTwo", "unrelated")
    val graph = projectActionGraph(effective, lock, inputs, Digest.fromJson(request.getValue("composition")), mapOf("jvm" to tools),
        ProjectGraphRequest(selected, setOf("jvm"), ProjectMode.TEST, selected.associateWith { JvmTests(listOf(if (it == "unrelated") "fixture.unrelated.UnrelatedTest" else "fixture.consumer.ConsumerTest")) }))
    val actions = linkedMapOf<String, ActionSpec>()
    val nodes = graph.nodes.map { node -> node.copy(resolve = { results -> node.resolve(results).also { actions[node.id] = it } }) }
    val run = NativeRunDirectory(string("runs"))
    NativeProcessHost(string("guardian")).use { host ->
        val executor = JvmActionExecutor(run.runId, run.path, host, content, provider)
        val cached = LocalCachingActionExecutor(executor, NativeActionCache(string("actionCache"), content), content)
        val events = mutableListOf<RunEvent>()
        SchedulerRun(run.runId, nodes, cached, ResourceBudget(3, 6144), EventJournal { events += it }).use { scheduler ->
            while (!scheduler.tick()) usleep(10000u)
            writeJvmFile(string("report"), obj("results" to JsonValue.Object(scheduler.results.mapValues { it.value.toJson() }),
                "actions" to JsonValue.Object(actions.mapValues { it.value.toJson() }),
                "edges" to JsonValue.Object(nodes.associate { it.id to it.dependencies.sorted().map(String::json).json() }),
                "observations" to JsonValue.Object(executor.observations.mapValues { it.value.toJson() }),
                "events" to events.map { it.toJson() }.json(), "lock" to lock.toJson(), "effective" to effective.document,
                "run" to run.path.json()).canonical().encodeToByteArray())
        }
    }
}
