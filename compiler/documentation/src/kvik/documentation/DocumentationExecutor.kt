package kvik.documentation

import kvik.core.*
import kvik.cache.NativeFiles
import kvik.cache.NativeOutputTrees
import kvik.compiler.jvm.JvmToolchainProvider
import kvik.compiler.native.NativeToolchainProvider
import kvik.execution.NativeRunDirectory
import kvik.execution.preparingAction

data class DocumentationActionObservation(val result: ActionResult, val diagnostics: List<Diagnostic>, val directory: String,
    val processResult: ProcessResult? = null) {
    val stdoutFile: String get() = "$directory/documentation.stdout"
    val stderrFile: String get() = "$directory/documentation.stderr"
    fun toJson(): JsonValue.Object = obj("result" to result.toJson(), "diagnostics" to diagnostics.map { it.toJson() }.json(),
        "directory" to directory.json(), "stdoutFile" to stdoutFile.json(), "stderrFile" to stderrFile.json())
}

class DocumentationActionExecutor(private val runId: String, private val runDirectory: String, private val host: ProcessHost,
    private val content: ContentStore, private val toolchains: JvmToolchainProvider,
    private val nativeProviders: Map<NativeTarget, NativeToolchainProvider> = emptyMap()) : ActionExecutor {
    private val started = mutableSetOf<String>()
    private val recorded = linkedMapOf<String, DocumentationActionObservation>()
    val observations: Map<String, DocumentationActionObservation> get() = recorded.toMap()
    override fun resources(action: ActionSpec) = ResourceRequest(2048)
    private data class Prepared(val java: String, val workerClasspath: String, val distribution: DocumentationDistribution, val jars: Map<LogicalPath, ByteArray>)

    override fun start(runId: String, actionId: String, action: ActionSpec): RunningAction {
        require(this.runId == runId && actionId !in started)
        if (action.implementation == "kvik-documentation-native-stdlib-v1") return nativeStdlib(actionId, action)
        val spec = DocumentationSpec.fromJson(action.parameters)
        val expected = documentationAction(spec, action.inputs, action.composition, action.toolchains)
        require(action == expected) { "Documentation action differs from its typed specification" }
        started += actionId
        return preparingAction(actionId, expected, prepare = { checkpoint ->
            val layout = toolchains.verifiedExecution(expected.toolchains, checkpoint)
            require(layout.identities == expected.toolchains) { "Documentation tool identities differ" }
            val manifest = requireNotNull(layout.documentationManifest) { "Documentation tools are not bundled" }
            val identity = requireNotNull(layout.documentationIdentity) { "Documentation tools have no verified identity" }
            checkpoint()
            val distribution = DocumentationDistribution.fromJson(parseJson(NativeFiles.read(manifest, 1024 * 1024).decodeToString(throwOnInvalidSequence = true)))
            require(distribution.identity == identity) { "Documentation manifest differs from the verified bundle" }
            val jars = distribution.artifacts.files.associate { file ->
                checkpoint()
                val path = "${layout.compilerArtifacts}/${file.path.value}"
                val bytes = NativeFiles.read(path, 128 * 1024 * 1024)
                require(Sha256.digest(bytes) == file.digest) { "Documentation jar differs: ${file.path.value}" }
                checkpoint()
                file.path to bytes
            }
            Prepared(layout.javaExecutable, listOf(layout.workerClasses, layout.btaApiJar, layout.stdlibJar).joinToString(":"), distribution, jars)
        }, start = { prepared -> launch(actionId, expected, spec, prepared) })
    }

    private fun nativeStdlib(actionId: String, action: ActionSpec): RunningAction {
        val target = NativeTarget.require(action.parameters.fields(setOf("target")).getValue("target").stringValue())
        require(action == documentationNativeStdlibAction(target, action.composition, action.toolchains)) { "Native stdlib action differs from its typed recipe" }
        val provider = nativeProviders[target] ?: error("Missing Native documentation provider: ${target.projectName}")
        started += actionId
        return preparingAction(actionId, action, prepare = { checkpoint ->
            provider.verified(action.toolchains, checkpoint).also { require(it.target == target && it.identities == action.toolchains) { "Native documentation provider identities or target differ" } }
        }, start = { layout ->
            object : RunningAction {
                private var result: ActionResult? = null
                private var cancelled = false
                private var closed = false
                override fun cancel() { if (result == null) cancelled = true }
                override fun close() { closed = true }
                override fun poll(): ActionResult? {
                    result?.let { return it }; check(!closed)
                    if (cancelled) return ActionResult(actionId, action.key(Sha256), ActionStatus.CANCELLED, exitCode = 130,
                        diagnostic = "Native documentation stdlib capture cancelled").also { result = it }
                    val directory = NativeRunDirectory(runDirectory).path
                    var outputs = TreeManifest(emptyList())
                    var diagnostics = emptyList<Diagnostic>()
                    var status = ActionStatus.FAILED
                    try {
                        val root = "${layout.nativeHome}/klib/common/stdlib"
                        validateBoundedTree(root, 64 * 1024 * 1024)
                        require(NativeFiles.regular(NativeFiles.info("$root/default/manifest") ?: error("Missing Native stdlib manifest")))
                        outputs = TreeManifest(NativeOutputTrees(content).capture(root).files.map { it.copy(path = LogicalPath("stdlib/${it.path.value}")) })
                        require(outputs.files.isNotEmpty())
                        status = ActionStatus.EXECUTED
                    } catch (failure: Exception) {
                        diagnostics = listOf(Diagnostic("KVIK_DOCUMENTATION_STDLIB_INVALID", DiagnosticCategory.TOOLCHAIN,
                            failure.message ?: "Invalid Native documentation stdlib", actionId, actionId.substringBefore(':'), target.projectName))
                    }
                    return ActionResult(actionId, action.key(Sha256), status, outputs, if (status == ActionStatus.EXECUTED) 0 else 1,
                        diagnostics.firstOrNull()?.message).also { result = it; recorded[actionId] = DocumentationActionObservation(it, diagnostics, directory) }
                }
            }
        })
    }

    private fun launch(actionId: String, action: ActionSpec, spec: DocumentationSpec, prepared: Prepared): RunningAction {
        val directory = NativeRunDirectory(runDirectory).path
        val trees = NativeOutputTrees(content)
        trees.materialize(action.inputs, "$directory/input")
        for (name in listOf("home", "tmp", "tools", "output")) NativeFiles.ensureDirectory("$directory/$name")
        // Empty source sets still retain their refinement edges; no directory from another set is inherited.
        spec.sourceSets.forEach { NativeFiles.ensureDirectory("$directory/input/${it.sourceRoot.value}") }
        prepared.jars.forEach { (path, bytes) -> NativeFiles.writeNew("$directory/tools/${path.value}", bytes) }
        val configuration = documentationConfiguration(spec, directory, prepared.distribution)
        NativeFiles.writeNew("$directory/configuration.json", configuration.canonical().encodeToByteArray())
        val request = obj("specification" to spec.toJson(), "configuration" to configuration,
            "distribution" to prepared.distribution.toJson(), "inputs" to action.inputs.toJson())
        val requestBytes = request.canonical().encodeToByteArray()
        require(requestBytes.size <= 4 * 1024 * 1024) { "Documentation worker request exceeds 4 MiB" }
        NativeFiles.writeNew("$directory/request.json", requestBytes)
        val process = host.start(ProcessRequest(listOf(prepared.java, "-Xmx1536m", "-Djava.io.tmpdir=$directory/tmp", "-Duser.home=$directory/home",
            "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", prepared.workerClasspath, "kvik.jvm.DocumentationInputs", directory, "request.json"), directory, action.environment + mapOf("HOME" to "$directory/home", "TMPDIR" to "$directory/tmp"),
            "$directory/documentation.stdout", "$directory/documentation.stderr", action.timeoutMillis))
        return object : RunningAction {
            private var result: ActionResult? = null
            private var cancelled = false
            private var closed = false
            override fun cancel() { if (result == null) { cancelled = true; process.cancel() } }
            override fun close() { if (!closed) { process.close(); closed = true } }
            override fun poll(): ActionResult? {
                result?.let { return it }; check(!closed)
                val exit = process.poll() ?: return null
                close()
                var status = ActionStatus.FAILED
                var outputs = TreeManifest(emptyList())
                var diagnostics = emptyList<Diagnostic>()
                try {
                    when {
                        cancelled || exit.cancelled -> { status = ActionStatus.CANCELLED; diagnostics = listOf(Diagnostic("KVIK_DOCUMENTATION_CANCELLED", DiagnosticCategory.CANCELLATION, "Documentation generation cancelled")) }
                        exit.timedOut -> diagnostics = listOf(Diagnostic("KVIK_DOCUMENTATION_TIMEOUT", DiagnosticCategory.INFRASTRUCTURE, "Documentation generation timed out after ${action.timeoutMillis} ms"))
                        exit.setupFailure != null -> diagnostics = listOf(Diagnostic("KVIK_DOCUMENTATION_LAUNCH_FAILED", DiagnosticCategory.INFRASTRUCTURE,
                            "Documentation JVM setup failed at ${exit.setupFailure.stage.name.lowercase()}: errno=${exit.setupFailure.errno}"))
                        else -> {
                            val log = NativeFiles.read("$directory/documentation.stdout", 4 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true) +
                                NativeFiles.read("$directory/documentation.stderr", 4 * 1024 * 1024).decodeToString(throwOnInvalidSequence = true)
                            require(exit.exitCode == 0) { "Dokka exited ${exit.exitCode}: ${documentationLogSuffix(log)}" }
                            validateDocumentationOutput("$directory/output")
                            outputs = trees.capture("$directory/output")
                            status = ActionStatus.EXECUTED
                        }
                    }
                } catch (failure: Exception) {
                    diagnostics = listOf(Diagnostic("KVIK_DOCUMENTATION_FAILED", DiagnosticCategory.COMPILATION, failure.message ?: "Documentation generation failed"))
                }
                diagnostics = diagnostics.map { it.copy(actionId = actionId, module = spec.module, target = "common") }
                return ActionResult(actionId, action.key(Sha256), status, outputs, exit.exitCode, diagnostics.firstOrNull()?.message).also {
                    result = it; recorded[actionId] = DocumentationActionObservation(it, diagnostics, directory, exit)
                }
            }
        }
    }
}

/** Pinned Dokka emits unresolved types as direct text inside a declaration signature.
 * Prose and string literal spans are deliberately outside this check. */
internal fun unresolvedDocumentation(text: String): Boolean {
    val opening = "<div class=\"symbol monospace\">"
    var cursor = 0
    while (true) {
        val start = text.indexOf(opening, cursor).takeIf { it >= 0 } ?: return false
        val end = text.indexOf("</div>", start + opening.length).takeIf { it >= 0 } ?: return false
        cursor = start + opening.length
        var depth = 0
        while (cursor < end) {
            val tag = text.indexOf('<', cursor).let { if (it < 0 || it > end) end else it }
            if (depth == 0 && text.substring(cursor, tag).contains("ERROR CLASS: Symbol not found")) return true
            if (tag == end) break
            val close = text.indexOf('>', tag).takeIf { it in (tag + 1)..end } ?: break
            val markup = text.substring(tag + 1, close)
            val name = markup.removePrefix("/").substringBefore(' ').substringBefore('/').lowercase()
            if (markup.startsWith('/')) depth = maxOf(0, depth - 1)
            else if (!markup.endsWith('/') && name !in setOf("br", "wbr", "img", "hr", "input") && !markup.startsWith('!')) depth++
            cursor = close + 1
        }
        cursor = end + 6
    }
}

internal fun documentationLogSuffix(text: String): String = text.takeLast(2048).let {
    if (it.firstOrNull()?.isLowSurrogate() == true) it.drop(1) else it
}

private fun validateDocumentationOutput(root: String) {
    require(NativeFiles.names(root) == listOf("html")) { "Unexpected documentation output roots" }
    val index = NativeFiles.info("$root/html/index.html") ?: error("Documentation index.html is missing")
    require(NativeFiles.regular(index) && index.size > 0) { "Documentation index.html is invalid" }
    validateBoundedTree("$root/html", 16 * 1024 * 1024, inspectHtml = true)
}

private fun validateBoundedTree(root: String, fileLimit: Int, inspectHtml: Boolean = false) {
    var count = 0
    var total = 0L
    fun visit(directory: String, depth: Int) {
        require(depth <= 128) { "Documentation tree is too deep" }
        require(NativeFiles.directory(NativeFiles.info(directory) ?: error("Documentation directory disappeared")))
        for (name in NativeFiles.names(directory)) {
            require(++count <= 100_000) { "Documentation output entry count exceeds limit" }
            val path = "$directory/$name"
            val info = NativeFiles.info(path) ?: error("Documentation output disappeared")
            if (NativeFiles.directory(info)) visit(path, depth + 1)
            else {
                require(NativeFiles.regular(info) && info.mode and 73 == 0 && info.size <= fileLimit) { "Invalid documentation output file" }
                total += info.size
                require(total <= 256L * 1024 * 1024) { "Documentation output exceeds 256 MiB" }
                if (inspectHtml && name.endsWith(".html")) require(!unresolvedDocumentation(NativeFiles.read(path, fileLimit).decodeToString(throwOnInvalidSequence = true))) { "Documentation contains unresolved symbols" }
            }
        }
    }
    visit(root, 0)
}
