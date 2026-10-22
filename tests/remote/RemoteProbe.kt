package kvik.cache.remote.tests

import kvik.cache.*
import kvik.cache.remote.*
import kvik.core.*

private val platform = mapOf("os" to "fixture", "architecture" to "portable-bytes")
private fun specification(label: String, policy: CachePolicy = CachePolicy.REMOTE, kind: ActionKind = ActionKind.GENERATE): ActionSpec {
    val tool = Sha256.digest("fixture-tool-v1".encodeToByteArray())
    val input = Sha256.digest("fixture-input".encodeToByteArray())
    return ActionSpec("kvik.fixture/v1", tool, kind, platform, "fixture", mapOf("fixture" to tool),
        TreeManifest(listOf(FileEntry(LogicalPath("src/input.txt"), input))),
        listOf(LogicalPath("out"), LogicalPath("single.txt")), listOf("fixture-generator", "", label), mapOf("MODE" to "deterministic"),
        timeoutMillis = 1250, cachePolicy = policy)
}
fun main(args: Array<String>) {
    val operation = args[0]
    val root = args[1]
    val endpoint = RemoteEndpoint(args[2], trustedWriters = true)
    val label = args.getOrElse(3) { "default" }
    val diagnostics = { value: CacheDiagnostic -> println("DIAGNOSTIC ${value.code}: ${value.message}") }
    val content = NativeContentStore("$root/cas", diagnostic = diagnostics)
    val local = NativeActionCache("$root/actions", content, diagnostics)
    val limits = TransferLimits(rpcMillis = 500, operationMillis = 5000, requests = if (operation == "limited") 1 else 2048)
    val approvals = if (operation == "unapproved") emptySet() else setOf(PortabilityApproval("kvik.fixture/v1", "fixture", platform, "tests/remote fixture determinism only"))
    val remote = RemoteActionCache(content, local, endpoint, approvals, limits, diagnostics)
    val action = if (operation == "test") specification(label, CachePolicy.NEVER, ActionKind.TEST) else specification(label)
    val mapping = ReapiMapping(action, limits)
    when (operation) {
        "selftest" -> wireChecks(action, limits)
        "identity" -> println("IDENTITY ${mapping.localKey.hash} ${mapping.actionDigest.hash} ${mapping.actionDigest.sizeBytes}")
        "publish", "unapproved", "test", "failed", "oversize", "undeclared" -> {
            content.put("fixture-input".encodeToByteArray())
            val output = content.put(if (operation == "oversize") ByteArray(1024 * 1024 + 1) { 1 } else "output-$label".encodeToByteArray())
            val outputs = if (operation == "undeclared") TreeManifest(listOf(FileEntry(LogicalPath("unrelated"), output))) else TreeManifest(listOf(FileEntry(LogicalPath("out/bin/run"), output, true),
                FileEntry(LogicalPath("out/share/data"), output), FileEntry(LogicalPath("single.txt"), output)))
            val status = if (operation == "failed") ActionStatus.FAILED else ActionStatus.EXECUTED
            val result = ActionResult("fixture", action.key(Sha256), status, outputs, if (status == ActionStatus.EXECUTED) 0 else 1)
            println("PUBLISHED ${remote.publish(action, result)}")
        }
        "lookup", "limited" -> {
            val hit = remote.lookup(action)
            println(if (hit == null) "MISS" else "HIT ${if (hit.remote) "remote" else "local"} ${hit.action.outputs.toJson().canonical()}")
            if (hit != null && args.getOrNull(4) != "no-restore") NativeOutputTrees(content).materialize(hit.action.outputs, "$root/restored")
        }
        "mapping" -> {
            for ((digest, bytes) in mapping.blobs) println("BLOB ${digest.hash} ${digest.sizeBytes} ${bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }}")
        }
        else -> error("Unknown operation")
    }
}
