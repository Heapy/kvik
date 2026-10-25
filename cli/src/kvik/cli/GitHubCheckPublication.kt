@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.platform.*

/** Credentials are transport-only and intentionally have no generated toString(). */
internal class GitHubCheckDestination(val repository: String, val sha: String, val name: String, val api: String, val token: String)

internal fun githubCheckDestination(options: Map<String, String>, defaultName: String,
    arguments: (String) -> Nothing): GitHubCheckDestination {
    val repository = options["--repository"] ?: arguments("Missing --repository")
    if (!repository.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,99}/[A-Za-z0-9_.-]{1,100}")) ||
        repository.substringAfter('/') in setOf(".", "..")) arguments("Invalid repository")
    val sha = options["--sha"]?.lowercase() ?: arguments("Missing --sha")
    if (!sha.matches(Regex("[0-9a-f]{40}"))) arguments("Expected a full 40-character commit SHA")
    val name = options["--name"] ?: defaultName
    if (name.length !in 1..100 || name.any { it.code < 32 || it.code == 127 }) arguments("Invalid check name")
    val api = options["--api-url"] ?: "https://api.github.com"
    if (!api.matches(Regex("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:/api/v3)?")) &&
        !api.matches(Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}(?:/api/v3)?"))) arguments("Expected HTTPS API origin (or loopback fixture)")
    val tokenEnvironment = options["--token-env"] ?: "GITHUB_TOKEN"
    if (!tokenEnvironment.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}"))) arguments("Invalid token environment name")
    val token = try { boundedEnvironment(tokenEnvironment, 8192) } catch (_: Exception) { arguments("Invalid token environment value") }
    if (token == null || token.isEmpty() || token.any { it.code !in 33..126 }) arguments("Missing or invalid GitHub token environment value")
    return GitHubCheckDestination(repository, sha, name, api, token)
}

/** Persist complete retry inputs and intent before the single non-cacheable POST. */
internal fun publishGitHubCheck(destination: GitHubCheckDestination, output: String, reportPath: String, reportBytes: ByteArray,
    sourceStatus: VerificationStatus, title: String, summary: String, retained: Map<String, ByteArray>, checkpoint: () -> Unit): ExitStatus {
    val repository = destination.repository
    val sha = destination.sha
    val name = destination.name
    val api = destination.api
    val token = destination.token
    require(summary.length <= 65_535 && title.length <= 1000) { "Check output exceeds supported bounds" }
    val reportDigest = Sha256.digest(reportBytes)
    require(retained[reportPath]?.contentEquals(reportBytes) == true) { "Publication report is not retained" }
    val artifacts = linkedMapOf<String, ByteArray>()
    var totalBytes = 0L
    fun retain(path: String, bytes: ByteArray) {
        LogicalPath(path)
        require(path !in artifacts) { "Duplicate retained path" }
        totalBytes += bytes.size
        require(totalBytes <= 256L * 1024 * 1024) { "Publication inputs exceed 256 MiB" }
        artifacts[path] = bytes
    }
    retained.forEach { (path, bytes) -> retain(path, bytes) }
    val conclusion = when (sourceStatus) {
        VerificationStatus.PASSED -> "success"
        VerificationStatus.FAILED -> "failure"
        VerificationStatus.INCOMPLETE -> "action_required"
        VerificationStatus.CANCELLED -> "cancelled"
    }
    var stage: String? = null
    try {
        val request = obj("name" to name.json(), "head_sha" to sha.json(), "status" to "completed".json(), "conclusion" to conclusion.json(),
            "external_id" to reportDigest.hash.json(), "output" to obj("title" to title.json(), "summary" to summary.json()))
        val requestBytes = request.canonical().encodeToByteArray()
        retain("request.json", requestBytes)
        val requestDigest = Sha256.digest(requestBytes)
        val composition = Digest(runtimeComposition, runtimeCompositionSize)
        val action = ActionSpec("kvik-github-check-publish-v1", composition, ActionKind.PUBLISH,
            mapOf("os" to "portable", "arch" to "any"), "reports", mapOf("kvik" to composition),
            TreeManifest(listOf(FileEntry(LogicalPath("request.json"), requestDigest), FileEntry(LogicalPath(reportPath), reportDigest))),
            listOf(LogicalPath("outcome")), emptyList(), emptyMap(),
            obj("apiUrl" to api.json(), "repository" to repository.json(), "apiVersion" to "2026-03-10".json()), timeoutMillis = 30_000, cachePolicy = CachePolicy.NEVER)
        retain("action.json", action.toJson().canonical().encodeToByteArray())
        retain("intent.json", obj("schemaVersion" to CONTRACT_VERSION.json(), "state" to "possibly-sent".json(),
            "request" to requestDigest.toJson(), "report" to reportDigest.toJson(),
            "note" to "No outcome means the remote result is unknown. POST retries may create another check.".json()).canonical().encodeToByteArray())
        val parent = output.substringBeforeLast('/')
        NativeFiles.ensureDirectory(parent)
        val pending = NativeFiles.tempDirectory(parent).also { stage = it }
        val directories = artifacts.keys.flatMap { path ->
            val parts = path.split('/').dropLast(1)
            parts.indices.map { parts.take(it + 1).joinToString("/") }
        }.distinct().sortedBy { it.count { c -> c == '/' } }
        directories.forEach { NativeFiles.ensureDirectory("$pending/$it") }
        artifacts.forEach { (file, bytes) -> checkpoint(); NativeFiles.writeNew("$pending/$file", bytes) }
        directories.asReversed().forEach { NativeFiles.syncDirectory("$pending/$it") }
        NativeFiles.syncDirectory(pending)
        checkpoint()
        // Persist intent and inputs before any external side effect, including when the process is killed.
        NativeFiles.exposeDirectory(pending, output)
        var httpStatus: Int? = null
        var checkId: Long? = null
        var outcome = "unknown"
        try {
            checkpoint()
            val response = githubPost(api, repository, token, requestBytes, checkpoint)
            httpStatus = response.httpStatus.takeIf { it > 0 }
            if (response.transportError == 0 && response.httpStatus in 400..499) outcome = "rejected"
            if (response.transportError == 0 && response.httpStatus == 201) {
                val value = parseJson(response.body.decodeToString(throwOnInvalidSequence = true)).objectValue()
                require(value.getValue("head_sha").stringValue() == sha && value.getValue("name").stringValue() == name &&
                    value.getValue("external_id").stringValue() == reportDigest.hash && value.getValue("status").stringValue() == "completed" &&
                    value.getValue("conclusion").stringValue() == conclusion) { "Response does not match request" }
                val id = value.getValue("id").longValue()
                require(id > 0) { "Invalid remote check identity" }
                checkId = id
                outcome = "published"
            }
        } catch (_: Exception) {
            // Never persist server bodies or exception text: either can contain credentials.
        }
        val result = obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "github-check-publication".json(),
            "outputDirectory" to output.json(), "repository" to repository.json(), "headSha" to sha.json(),
            "sourceStatus" to sourceStatus.name.lowercase().json(), "outcome" to outcome.json(),
            "httpStatus" to (httpStatus?.json() ?: JsonValue.Null), "checkRunId" to (checkId?.json() ?: JsonValue.Null),
            "request" to requestDigest.toJson(), "report" to reportDigest.toJson())
        val exit = if (outcome == "published") ExitStatus.SUCCESS else if (kvik_interrupt_requested() != 0) ExitStatus.CANCELLED else ExitStatus.INCOMPLETE
        val resultBytes = result.canonical().encodeToByteArray()
        val resultStage = NativeFiles.tempDirectory(output)
        try {
            NativeFiles.writeNew("$resultStage/result.json", resultBytes)
            val actionResult = ActionResult("github-check-publish", action.key(Sha256),
                if (outcome == "published") ActionStatus.EXECUTED else if (exit == ExitStatus.CANCELLED) ActionStatus.CANCELLED else ActionStatus.FAILED,
                TreeManifest(listOf(FileEntry(LogicalPath("outcome/result.json"), Sha256.digest(resultBytes)))), exit.code)
            NativeFiles.writeNew("$resultStage/action-result.json", actionResult.toJson().canonical().encodeToByteArray())
            NativeFiles.syncDirectory(resultStage)
            NativeFiles.exposeDirectory(resultStage, "$output/outcome")
        } finally { NativeFiles.removeTree(resultStage) }
        println(result.canonical())
        return exit
    } finally { stage?.let(NativeFiles::removeTree) }
}
