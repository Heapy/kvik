@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeFiles
import kvik.core.*
import kvik.execution.platform.*
import kvik.runevidence.committedRunEvents
import kvik.runevidence.verificationRecordFromJson
import kotlin.io.encoding.Base64
import kotlin.time.TimeSource

private const val MAVEN_FILE_LIMIT = 64 * 1024 * 1024
private const val MAVEN_TOTAL_LIMIT = 256L * 1024 * 1024

/** Retained package artifacts are the inputs; this command never starts a build or reads a cache. */
internal fun libraryPublishCommand(args: List<String>): ExitStatus {
    fun arguments(message: String): Nothing = throw CliFailure("KVIK_LIBRARY_PUBLISH_ARGUMENTS", DiagnosticCategory.CONFIGURATION,
        "$message. Use publish-library (--run PATH | --retry ATTEMPT) --repository URL --username USER --password-env NAME --output NEW_DIRECTORY",
        ExitStatus.INVALID_CONFIGURATION)
    val options = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index++]
        if (flag !in setOf("--run", "--retry", "--repository", "--username", "--password-env", "--output")) arguments("Unknown option")
        val value = args.getOrNull(index++) ?: arguments("Missing option value")
        if (value.isBlank() || value.startsWith("--") || options.put(flag, value) != null) arguments("Empty or repeated option")
    }
    if (("--run" in options) == ("--retry" in options)) arguments("Select one retained run or publication attempt")
    val repository = (options["--repository"] ?: arguments("Missing --repository")).trimEnd('/')
    if (repository.length > 4096 || !repository.matches(Regex(
            "(?:https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?|http://127\\.0\\.0\\.1:[0-9]{1,5})(?:/[A-Za-z0-9._~-]+)*")) ||
        repository.substringAfter("://").split('/').drop(1).any { it == "." || it == ".." }) arguments("Expected normalized HTTPS repository URL (or loopback fixture)")
    val username = options["--username"] ?: arguments("Missing --username")
    if (username.length !in 1..1024 || username.any { it.code !in 33..126 || it == ':' }) arguments("Invalid username")
    val passwordEnvironment = options["--password-env"] ?: arguments("Missing --password-env")
    if (!passwordEnvironment.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}"))) arguments("Invalid credential environment name")
    val password = try { boundedEnvironment(passwordEnvironment, 8192) } catch (_: Exception) { arguments("Invalid credential environment value") }
    if (password.isNullOrEmpty() || password.any { it.code !in 33..126 }) arguments("Missing or invalid credential environment value")
    val authorization = "Authorization: Basic ${Base64.encode("$username:$password".encodeToByteArray())}"
    val source = projectPath(currentDirectory(), options["--run"] ?: options.getValue("--retry"))
    val output = projectPath(currentDirectory(), options["--output"] ?: arguments("Missing --output"))
    if (pathsPhysicallyOverlap(source, output)) arguments("Output must be outside the retained source")
    if (NativeFiles.info(output) != null) arguments("Output already exists; use a new attempt directory")
    check(kvik_interrupt_install() == 0) { "Cannot install publication interruption handlers" }
    fun checkpoint() {
        if (kvik_interrupt_requested() != 0) throw CliFailure("KVIK_LIBRARY_PUBLISH_CANCELLED", DiagnosticCategory.CANCELLATION,
            "Publication cancelled; retry from the retained attempt", ExitStatus.CANCELLED)
    }
    try {
        var previousRequest: ByteArray? = null
        val directory = if ("--retry" in options) {
            val bytes = NativeFiles.read("$source/request.json", 1024 * 1024)
            previousRequest = bytes
            val request = parseJson(bytes.decodeToString(throwOnInvalidSequence = true))
                .fields(setOf("schemaVersion", "kind", "repository", "runId", "verification", "files"))
            request.requireVersion()
            require(request.getValue("kind").stringValue() == "maven-publication-request")
            require(request.getValue("repository").stringValue() == repository) { "Retry destination differs" }
            val runId = request.getValue("runId").stringValue()
            require(runId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            "$source/runs/$runId"
        } else source
        NativeFiles.ensureDirectory(directory, create = false)
        val retained = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        fun retain(path: String, bytes: ByteArray) {
            LogicalPath(path)
            require(path !in retained)
            totalBytes += bytes.size
            require(totalBytes <= MAVEN_TOTAL_LIMIT) { "Publication inputs exceed 256 MiB" }
            retained[path] = bytes
        }
        fun read(name: String) = NativeFiles.read("$directory/$name", 16 * 1024 * 1024)
        val verification = read("verification.json")
        val record = verificationRecordFromJson(parseJson(verification.decodeToString(throwOnInvalidSequence = true)))
        require(record.runId == directory.substringAfterLast('/'))
        require(Sha256.digest(verification) == Sha256.digest(record.toJson()))
        val completion = read("completion.json")
        val journal = read("events.jsonl")
        committedRunEvents(record, parseJson(completion.decodeToString(throwOnInvalidSequence = true)), journal)
        require(record.status == VerificationStatus.PASSED && record.requestedChecks.isNotEmpty() &&
            record.requestedChecks.all { it.endsWith(":package") && it in record.completedChecks }) { "Expected completed package-library run" }
        for ((name, bytes) in listOf("verification.json" to verification, "completion.json" to completion, "events.jsonl" to journal))
            retain("runs/${record.runId}/$name", bytes)
        val packages = record.actions.filter { it.actionId.endsWith(":package") }
        require(packages.isNotEmpty() && packages.all { it.successful && it.outputs.files.isNotEmpty() })
        val files = linkedMapOf<String, ByteArray>()
        val entries = linkedMapOf<String, FileEntry>()
        for (result in packages) for (file in result.outputs.files) {
            require(!file.executable && file.path.value.startsWith("library/"))
            val path = file.path.value.removePrefix("library/")
            val parts = path.split('/')
            require(path.length <= 4096 && parts.size in 4..32 && parts.all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it !in setOf(".", "..") })
            require(!parts[parts.lastIndex - 1].endsWith("-SNAPSHOT")) { "Snapshot deployment needs a repository-specific versioning adapter" }
            require(file.digest.sizeBytes <= MAVEN_FILE_LIMIT && (entries[path] == null || entries[path] == file))
            if (entries.put(path, file) != null) continue
            require(entries.size <= 1000)
            checkpoint()
            NativeFiles.ensureDirectory("$directory/library/${path.substringBeforeLast('/')}", create = false)
            val bytes = NativeFiles.read("$directory/library/$path", MAVEN_FILE_LIMIT)
            require(Sha256.digest(bytes) == file.digest) { "Retained package bytes differ" }
            retain("runs/${record.runId}/library/$path", bytes)
            files[path] = bytes
        }
        // Checksum sidecars are deterministic transport artifacts, derived from the verified package bytes.
        for ((path, bytes) in files.toMap()) {
            require("$path.sha256" !in files)
            files["$path.sha256"] = (Sha256.digest(bytes).hash + "\n").encodeToByteArray()
        }
        val manifest = TreeManifest(files.map { (path, bytes) -> FileEntry(LogicalPath(path), Sha256.digest(bytes)) })
        val request = obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "maven-publication-request".json(),
            "repository" to repository.json(), "runId" to record.runId.json(), "verification" to Sha256.digest(verification).toJson(),
            "files" to manifest.toJson()).canonical().encodeToByteArray()
        require(request.size <= 1024 * 1024 && (previousRequest == null || previousRequest.contentEquals(request))) { "Retry inputs differ" }
        retain("request.json", request)
        return publishMavenFiles(repository, authorization, output, files, retained, ::checkpoint)
    } catch (failure: CliFailure) { throw failure
    } catch (_: Exception) {
        throw CliFailure("KVIK_LIBRARY_PUBLISH_FAILED", DiagnosticCategory.INFRASTRUCTURE,
            "Cannot prepare or retain Maven publication; inspect the attempt directory before retrying. No build was executed.", ExitStatus.INCOMPLETE)
    } finally { kvik_interrupt_restore() }
}

private class MavenFileOutcome(val path: String, val digest: Digest) {
    var state = "not_attempted"
    var httpStatus: Int? = null
    fun toJson() = obj("path" to path.json(), "digest" to digest.toJson(), "state" to state.json(),
        "httpStatus" to (httpStatus?.json() ?: JsonValue.Null))
}

private fun publishMavenFiles(repository: String, authorization: String, output: String,
    files: Map<String, ByteArray>, retained: Map<String, ByteArray>, checkpoint: () -> Unit): ExitStatus {
    val composition = Digest(runtimeComposition, runtimeCompositionSize)
    val action = ActionSpec("kvik-maven-publish-v1", composition, ActionKind.PUBLISH,
        mapOf("os" to "portable", "arch" to "any"), "maven", mapOf("kvik" to composition),
        TreeManifest(retained.map { (path, bytes) -> FileEntry(LogicalPath(path), Sha256.digest(bytes)) }),
        listOf(LogicalPath("outcome")), emptyList(), emptyMap(), obj("repository" to repository.json()),
        timeoutMillis = 300_000, cachePolicy = CachePolicy.NEVER)
    val requestDigest = Sha256.digest(retained.getValue("request.json"))
    val artifacts = retained + mapOf("action.json" to action.toJson().canonical().encodeToByteArray(),
        "intent.json" to obj("schemaVersion" to CONTRACT_VERSION.json(), "state" to "possibly-sent".json(),
            "request" to requestDigest.toJson(),
            "note" to "No outcome means remote state is unknown. Retry reconciles every file before conditional creation.".json()).canonical().encodeToByteArray())
    writeMavenDirectory(output, artifacts, checkpoint)
    val outcomes = files.keys.sorted().map { MavenFileOutcome(it, Sha256.digest(files.getValue(it))) }
    val started = TimeSource.Monotonic.markNow()
    fun transferCheckpoint() {
        checkpoint()
        check(started.elapsedNow().inWholeMilliseconds < action.timeoutMillis) { "Publication deadline exceeded" }
    }
    fun get(file: MavenFileOutcome): MavenReply = mavenRequest("$repository/${file.path}", authorization, null,
        maxOf(1024, file.digest.sizeBytes.toInt() + 1), ::transferCheckpoint)
    fun inspect(file: MavenFileOutcome, response: MavenReply, missingAllowed: Boolean) {
        file.httpStatus = response.status.takeIf { it in 100..599 }
        file.state = when {
            response.error != 0 -> "unknown"
            response.status == 200 -> if (Sha256.digest(response.body) == file.digest) "present" else "conflict"
            response.status == 404 && missingAllowed -> "missing"
            response.status in 400..499 -> "rejected"
            else -> "unknown"
        }
    }
    // Preflight the entire release before the first PUT, so a known conflicting file causes no writes.
    var active: MavenFileOutcome? = null
    try {
        for (file in outcomes) {
            active = file
            file.state = "unknown"
            inspect(file, get(file), true)
            if (file.state !in setOf("present", "missing")) break
        }
        if (outcomes.all { it.state in setOf("present", "missing") }) {
            // Publish binaries before metadata that advertises them. This is not an atomic repository transaction.
            val missing = outcomes.filter { it.state == "missing" }.sortedWith(compareBy(
                { it.path.removeSuffix(".sha256").endsWith(".module") || it.path.removeSuffix(".sha256").endsWith(".pom") }, { it.path }))
            for (file in missing) {
                active = file
                file.state = "unknown"
                val response = mavenRequest("$repository/${file.path}", authorization, files.getValue(file.path), 1024 * 1024, ::transferCheckpoint)
                file.httpStatus = response.status.takeIf { it in 100..599 }
                if (response.error == 0 && (response.status in 200..299 || response.status == 412)) {
                    inspect(file, get(file), false)
                    if (file.state == "present" && response.status != 412) file.state = "published"
                } else if (response.error == 0 && response.status in 400..499) file.state = "rejected"
                if (file.state !in setOf("present", "published")) break
            }
        }
    } catch (_: Exception) {
        // Transport errors and responses can contain credentials. Persist only bounded, typed outcomes.
        active?.state = "unknown"
    }
    val complete = outcomes.all { it.state in setOf("present", "published") }
    val exit = if (complete) ExitStatus.SUCCESS else if (kvik_interrupt_requested() != 0) ExitStatus.CANCELLED else ExitStatus.INCOMPLETE
    val result = obj("schemaVersion" to CONTRACT_VERSION.json(), "kind" to "maven-publication".json(),
        "repository" to repository.json(), "outputDirectory" to output.json(), "request" to requestDigest.toJson(),
        "outcome" to (if (complete) "published" else if (exit == ExitStatus.CANCELLED) "cancelled" else "incomplete").json(),
        "files" to outcomes.map { it.toJson() }.json())
    val resultBytes = result.canonical().encodeToByteArray()
    val actionResult = ActionResult("maven-publish", action.key(Sha256),
        if (complete) ActionStatus.EXECUTED else if (exit == ExitStatus.CANCELLED) ActionStatus.CANCELLED else ActionStatus.FAILED,
        TreeManifest(listOf(FileEntry(LogicalPath("outcome/result.json"), Sha256.digest(resultBytes)))), exit.code)
    writeMavenDirectory("$output/outcome", mapOf("result.json" to resultBytes,
        "action-result.json" to actionResult.toJson().canonical().encodeToByteArray())) {}
    println(result.canonical())
    return exit
}

/** Sync retry inputs and intent, then expose them atomically before any request can occur. */
private fun writeMavenDirectory(output: String, artifacts: Map<String, ByteArray>, checkpoint: () -> Unit) {
    val parent = output.substringBeforeLast('/')
    NativeFiles.ensureDirectory(parent)
    val stage = NativeFiles.tempDirectory(parent)
    try {
        val directories = artifacts.keys.flatMap { path ->
            val parts = path.split('/').dropLast(1)
            parts.indices.map { parts.take(it + 1).joinToString("/") }
        }.distinct().sortedBy { it.count { c -> c == '/' } }
        directories.forEach { NativeFiles.ensureDirectory("$stage/$it") }
        artifacts.forEach { (path, bytes) -> checkpoint(); NativeFiles.writeNew("$stage/$path", bytes) }
        directories.asReversed().forEach { NativeFiles.syncDirectory("$stage/$it") }
        NativeFiles.syncDirectory(stage)
        checkpoint()
        NativeFiles.exposeDirectory(stage, output)
    } finally { NativeFiles.removeTree(stage) }
}
