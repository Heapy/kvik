@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kvik.cache.NativeContentStore
import kvik.cache.NativeFiles
import kvik.execution.NativeRunDirectory
import kvik.core.*
import kvik.execution.platform.kvik_interrupt_install
import kvik.execution.platform.kvik_interrupt_requested
import kvik.execution.platform.kvik_interrupt_restore
import kvik.locking.DependencyLocks
import kvik.locking.effectiveConfiguration
import kvik.projectgraph.ProjectGraphRequest
import kvik.projectgraph.ProjectMode
import kvik.projectgraph.projectToolTargets
import kvik.projectsnapshot.*
import kvik.runevidence.committedRunEvents
import kvik.runevidence.verificationRecordFromJson

private data class ChangeVerificationOptions(val build: BuildOptions, val baseline: String?, val full: Boolean, val planOnly: Boolean)

private fun changeVerificationOptions(args: List<String>): ChangeVerificationOptions {
    val remaining = mutableListOf<String>()
    var baseline: String? = null
    var full = false
    var planOnly = false
    var index = 0
    while (index < args.size) {
        when (val flag = args[index++]) {
            "--since" -> {
                require(baseline == null) { "Duplicate --since" }
                baseline = args.getOrNull(index++)?.takeIf { it.isNotBlank() && !it.startsWith("--") }
                    ?: error("--since requires a retained Kvik run directory")
            }
            "--full" -> { require(!full) { "Duplicate --full" }; full = true }
            "--plan-only" -> { require(!planOnly) { "Duplicate --plan-only" }; planOnly = true }
            else -> {
                remaining += flag
                // Preserve option values, including names that happen to resemble verification options.
                if (flag !in setOf("--offline", "--coverage")) remaining += args.getOrNull(index++) ?: error("Missing value for $flag")
            }
        }
    }
    require(baseline != null || full) { "Use verify --since RUN_DIRECTORY or verify --full" }
    return ChangeVerificationOptions(buildOptions(remaining), baseline, full, planOnly)
}

private data class VerificationBaseline(val snapshot: Digest, val inputs: TreeManifest, val completed: Set<String>)

/** The journal binds the retained input manifest. A raw inputs.json is never sufficient to omit a check. */
private fun verificationBaseline(directory: String, root: String, configurationPath: LogicalPath,
                                 current: ProjectInputSnapshot, configuration: Digest, lock: Digest,
                                 desired: Map<String, Map<String, Digest>>, fullReasons: MutableSet<String>): VerificationBaseline {
    NativeFiles.ensureDirectory(directory, create = false)
    fun bytes(name: String) = NativeFiles.read("$directory/$name", 16 * 1024 * 1024)
    fun document(name: String) = parseJson(bytes(name).decodeToString(throwOnInvalidSequence = true))
    val record = verificationRecordFromJson(document("verification.json"))
    require(record.runId == directory.substringAfterLast('/')) { "Baseline run identity differs from directory" }
    val events = committedRunEvents(record, document("completion.json"), bytes("events.jsonl"))
    val started = events.first().payload.values
    require(Digest.fromJson(started.getValue("inputSnapshot")) == record.inputSnapshot) { "Baseline start snapshot differs from completion" }
    require(started.getValue("requestedChecks").arrayValue().map(JsonValue::stringValue).toSet() == record.requestedChecks) {
        "Baseline requested checks differ from completion"
    }
    require(record.source == null && "source" !in started) { "Source-provider baselines cannot narrow a local verification" }
    require(started["inputScope"]?.stringValue() == "project") { "Baseline does not bind a complete project snapshot" }
    val manifest = document("inputs.json")
    require(started["inputManifest"]?.let(Digest::fromJson) == Sha256.digest(manifest)) { "Baseline input manifest is unbound or changed" }
    val inputs = TreeManifest.fromJson(manifest)
    val metadata = document("run.json").fields(setOf("schemaVersion", "projectRoot", "configurationPath", "lockPath", "toolsPath", "targets"),
        setOf("inputSelection", "toolTargets", "source", "sourceCache", "sourceRetention"))
    metadata.requireVersion()
    require("source" !in metadata && "inputSelection" !in metadata) { "Partial/source-provider baselines cannot narrow verification" }
    if (metadata.getValue("projectRoot").stringValue() != root || metadata.getValue("configurationPath").stringValue() != configurationPath.value)
        fullReasons += "project-identity-changed"
    if (record.configuration != configuration) fullReasons += "configuration-changed"
    if (record.dependencyLock != lock) fullReasons += "dependency-lock-changed"
    if (!bytes("configuration.json").contentEquals(current.configurationBytes)) fullReasons += "configuration-bytes-changed"
    if (!bytes("dependency-lock.json").contentEquals(current.lockBytes)) fullReasons += "dependency-lock-bytes-changed"
    if (record.composition != Digest(runtimeComposition, runtimeCompositionSize)) fullReasons += "runtime-composition-changed"
    for ((target, identities) in desired) for ((name, identity) in identities) {
        if (record.toolchains["$target:$name"] != identity) fullReasons += "tool-identities-changed"
    }
    if (record.freshnessAtCompletion != Freshness.CURRENT) fullReasons += "baseline-not-current-at-completion"
    return VerificationBaseline(record.inputSnapshot, inputs,
        if (record.freshnessAtCompletion == Freshness.CURRENT) record.completedChecks else emptySet())
}

internal fun changeVerificationCommand(args: List<String>): ExitStatus {
    val parsed = changeVerificationOptions(args)
    val options = parsed.build
    require(!options.coverage || options.targets == setOf("jvm")) { "--coverage requires JVM-only test execution" }
    check(kvik_interrupt_install() == 0) { "Cannot install verification interruption handlers" }
    try { return executeChangeVerification(parsed, options) }
    finally { kvik_interrupt_restore() }
}

private fun executeChangeVerification(parsed: ChangeVerificationOptions, options: BuildOptions): ExitStatus {
    val project = readProject(options.project.project)
    val root = project.root
    val storePath = projectPath(root, options.project.store)
    val lockPath = projectPath(root, options.project.lock)
    val toolsPath = projectPath(root, options.tools)
    val runsPath = projectPath(root, options.runs)
    val verificationPath = "$root/.kvik/verification"
    requireDisjointStore(project, storePath, lockPath, toolsPath)
    val inputPlan = projectInputPlan(project.configuration, SnapshotSelection.Project)
    requireDisjointInputPath(root, project.path, inputPlan, verificationPath, listOf(lockPath, toolsPath))
    requireDisjointInputPath(root, project.path, inputPlan, runsPath, listOf(lockPath, toolsPath))
    val policy = options.project.resolverPolicy()
    val initial = effectiveConfiguration(project.configuration, policy)
    val content = NativeContentStore(storePath, maxBlobBytes = initial.maxArtifactBytes)
    val snapshots = NativeProjectSnapshotter(root, content)
    val snapshot = when (val captured = snapshots.capture(project.name, lockPath, Sha256.digest(project.bytes), SnapshotSelection.Project)) {
        is SnapshotCapture.Captured -> captured.snapshot
        is SnapshotCapture.Indeterminate -> throw CliFailure("KVIK_INPUT_CHANGED", DiagnosticCategory.CONFIGURATION, captured.reason, ExitStatus.INCOMPLETE)
    }
    val effective = effectiveConfiguration(snapshot.configuration, policy)
    val lock = DependencyLocks(content, kvik.resolution.NativePomParser()).loadLocked(snapshot.lockBytes, effective)
    val toolTargets = projectToolTargets(snapshot.configuration.project, ProjectGraphRequest(options.modules, options.targets, ProjectMode.TEST))
    val desired = BuildTools(toolsPath).desiredIdentities(toolTargets)
    val fullReasons = linkedSetOf<String>()
    if (parsed.full) fullReasons += "requested-full"
    if (options.coverage) fullReasons += "coverage-requested"
    var baselineFailure: String? = null
    val baselineDirectory = parsed.baseline?.let { projectPath(currentDirectory(), it) }
    val baseline = baselineDirectory?.let { directory ->
        try { verificationBaseline(directory, root, project.name, snapshot, effective.digest, lock.digest, desired, fullReasons) }
        catch (failure: Exception) {
            fullReasons += "baseline-invalid"
            baselineFailure = failure.message ?: "Cannot validate baseline"
            null
        }
    }
    val previous = baseline?.inputs?.files?.associateBy { it.path }.orEmpty()
    val current = snapshot.inputs.files.associateBy { it.path }
    val changed = if (baseline == null) emptySet() else (previous.keys + current.keys).filter { previous[it] != current[it] }.toSet()
    if (baseline != null && baseline.snapshot != snapshot.fingerprint && changed.isEmpty() && fullReasons.isEmpty())
        fullReasons += "directory-inventory-changed"
    val selection = selectAffectedTests(snapshot.configuration, options.modules, options.targets, changed,
        baseline?.completed.orEmpty(), fullReasons)
    val limitations = listOf(
        "Scope is selected module/target tests and their compilation/generator prerequisites; ABI, documentation and script checks are not requested.",
        "Omitted checks were completed in the retained baseline; they are not fresh test executions.",
        "Changes are detected from declared file content, executable bits and directory inventories, regardless of Git staging state.",
        "Filesystem observations detect changes while observed; they do not provide an atomic snapshot or exclude ABA edits."
    )
    NativeFiles.ensureDirectory(verificationPath)
    val directory = NativeRunDirectory(verificationPath)
    NativeFiles.syncDirectory(verificationPath)
    fun persist(name: String, value: JsonValue) {
        NativeFiles.writeNew("${directory.path}/$name", value.canonical().encodeToByteArray())
        NativeFiles.syncDirectory(directory.path)
    }
    val context = mapOf(
        "schemaVersion" to CONTRACT_VERSION.json(), "directory" to directory.path.json(),
        "baselineRun" to (baselineDirectory?.json() ?: JsonValue.Null),
        "baselineSnapshot" to (baseline?.snapshot?.toJson() ?: JsonValue.Null),
        "inputSnapshot" to snapshot.fingerprint.toJson(), "limitations" to limitations.map { it.json() }.json()
    )
    val plan = JsonValue.Object(context + mapOf("kind" to "verification-selection".json(), "status" to "planned".json(), "selection" to selection.toJson()))
    persist("selection.json", plan)
    persist("inputs.json", snapshot.inputs.toJson())
    NativeFiles.writeNew("${directory.path}/configuration.json", snapshot.configurationBytes)
    NativeFiles.writeNew("${directory.path}/dependency-lock.json", snapshot.lockBytes)
    NativeFiles.syncDirectory(directory.path)
    println(plan.canonical())
    var reason: String? = baselineFailure
    var status = "planned"
    val executionRuns = mutableListOf<JsonValue>()
    val fresh = linkedSetOf<String>()
    var exitStatus = ExitStatus.SUCCESS
    fun stable(): Boolean = snapshots.freshness(snapshot) == Freshness.CURRENT &&
        try { BuildTools(toolsPath).desiredIdentities(toolTargets) == desired } catch (_: Exception) { false }
    fun guard(): Boolean {
        if (kvik_interrupt_requested() != 0) { status = "cancelled"; exitStatus = ExitStatus.CANCELLED; reason = "Verification interrupted"; return false }
        if (!stable()) { status = "incomplete"; exitStatus = ExitStatus.INCOMPLETE; reason = "Inputs or desired tools changed during verification"; return false }
        return true
    }
    if (guard() && !parsed.planOnly) {
        status = if (selection.selected.isEmpty()) "unchanged" else "passed"
        val groups = selection.selected.groupBy { it.split(':')[1] }.entries.sortedBy { it.key }
        for ((target, checks) in groups) {
            if (!guard()) break
            val groupOptions = options.copy(modules = checks.map { it.substringBefore(':') }.toSet(), targets = setOf(target))
            try {
                val outcome = executeSelectedTestBuild(groupOptions, snapshot.fingerprint, desired)
                val runPath = requireNotNull(outcome.directory) { "Test execution did not retain a run directory" }
                val record = verificationRecordFromJson(parseJson(NativeFiles.read("$runPath/verification.json", 16 * 1024 * 1024)
                    .decodeToString(throwOnInvalidSequence = true)))
                committedRunEvents(record, parseJson(NativeFiles.read("$runPath/completion.json", 16 * 1024 * 1024)
                    .decodeToString(throwOnInvalidSequence = true)), NativeFiles.read("$runPath/events.jsonl", 16 * 1024 * 1024))
                require(record.inputSnapshot == snapshot.fingerprint && record.requestedChecks == checks.toSet()) {
                    "Test execution scope or inputs differ from selection"
                }
                val groupStatus = when (outcome.status) {
                    ExitStatus.SUCCESS -> "passed"
                    ExitStatus.FAILED -> "failed"
                    ExitStatus.CANCELLED -> "cancelled"
                    else -> "incomplete"
                }
                executionRuns += obj("directory" to runPath.json(), "status" to groupStatus.json(),
                    "completedChecks" to record.completedChecks.sorted().map { it.json() }.json())
                if (record.freshnessAtCompletion == Freshness.CURRENT) fresh += record.completedChecks
                when (outcome.status) {
                    ExitStatus.SUCCESS -> Unit
                    ExitStatus.CANCELLED -> { status = "cancelled"; exitStatus = ExitStatus.CANCELLED; reason = "Test execution interrupted" }
                    ExitStatus.FAILED -> if (status != "incomplete") { status = "failed"; exitStatus = ExitStatus.FAILED; reason = "Selected tests or prerequisites failed" }
                    else -> { status = "incomplete"; exitStatus = outcome.status; reason = "Selected test execution was incomplete" }
                }
            } catch (failure: Exception) {
                status = "incomplete"
                exitStatus = ExitStatus.INCOMPLETE
                reason = failure.message ?: "Cannot complete selected test execution"
            }
            if (status == "cancelled" || !guard()) break
        }
        if (status == "passed" && fresh != selection.selected) {
            status = "incomplete"; exitStatus = ExitStatus.INCOMPLETE; reason = "Not every selected check completed"
        }
    }
    guard()
    val result = JsonValue.Object(context + mapOf(
        "kind" to "change-verification".json(), "status" to status.json(),
        "selectedChecks" to selection.selected.sorted().map { it.json() }.json(),
        "omittedChecks" to (selection.requested - selection.selected).sorted().map { it.json() }.json(),
        "freshChecks" to fresh.sorted().map { it.json() }.json(), "executionRuns" to executionRuns.json(),
        "reason" to (reason?.json() ?: JsonValue.Null)
    ))
    persist("result.json", result)
    if (!parsed.planOnly || status != "planned") println(result.canonical())
    return exitStatus
}
