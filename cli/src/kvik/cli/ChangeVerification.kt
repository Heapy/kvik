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
import kvik.projectgraph.supportsCoreChangeSelection
import kvik.projectsnapshot.*
import kvik.runevidence.committedRunEvents
import kvik.runevidence.verificationRecordFromJson

private data class ChangeVerificationOptions(val build: BuildOptions, val baselines: List<String>, val checks: Set<String>, val full: Boolean, val planOnly: Boolean)

private fun changeVerificationOptions(args: List<String>): ChangeVerificationOptions {
    val remaining = mutableListOf<String>()
    val baselines = mutableListOf<String>()
    val checks = linkedSetOf<String>()
    var full = false
    var planOnly = false
    var index = 0
    while (index < args.size) {
        when (val flag = args[index++]) {
            "--since" -> {
                val baseline = args.getOrNull(index++)?.takeIf { it.isNotBlank() && !it.startsWith("--") }
                    ?: error("--since requires a retained Kvik run directory")
                require(baseline !in baselines) { "Duplicate --since directory" }
                require(baselines.size < 256) { "At most 256 baseline runs are supported" }
                baselines += baseline
            }
            "--check" -> {
                val kind = args.getOrNull(index++) ?: error("--check requires build, test or abi-check")
                require(kind in setOf("build", "test", "abi-check")) { "Unsupported verification check: $kind" }
                require(checks.add(kind)) { "Duplicate verification check: $kind" }
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
    require(baselines.isNotEmpty() || full) { "Use verify --since RUN_DIRECTORY or verify --full" }
    return ChangeVerificationOptions(buildOptions(remaining), baselines, checks.ifEmpty { setOf("test") }, full, planOnly)
}

private fun verificationMode(kind: String): ProjectMode = when (kind) {
    "build" -> ProjectMode.BUILD
    "test" -> ProjectMode.TEST
    "abi-check" -> ProjectMode.ABI_CHECK
    else -> error("Unsupported verification check: $kind")
}

private data class VerificationGroup(val mode: ProjectMode, val modules: Set<String>, val targets: Set<String>, val checks: Set<String>)

/** Preserve graph parallelism without introducing unselected module/target pairs. */
private fun verificationGroups(selected: Set<String>): List<VerificationGroup> = listOf("build", "test", "abi-check").flatMap { kind ->
    val byTarget = selected.filter { it.substringAfterLast(':') == kind }.groupBy { it.split(':')[1] }
    byTarget.entries.groupBy { entry -> entry.value.map { it.substringBefore(':') }.toSet() }
        .entries.sortedBy { it.key.sorted().joinToString(":") }.map { (modules, entries) ->
            VerificationGroup(verificationMode(kind), modules, entries.map { it.key }.toSet(), entries.flatMap { it.value }.toSet())
        }
}

private data class VerificationBaseline(val snapshot: Digest, val manifest: Digest, val completed: Set<String>, val toolchains: Map<String, Digest>)

/** The journal binds the retained input manifest. A raw inputs.json is never sufficient to omit a check. */
private fun verificationBaseline(directory: String, root: String, configurationPath: LogicalPath,
                                 current: ProjectInputSnapshot, configuration: Digest, lock: Digest,
                                 fullReasons: MutableSet<String>, retainInputs: (TreeManifest) -> Unit): VerificationBaseline {
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
    val boundSelection = started["inputSelection"]?.let(SnapshotSelection::fromJson)
        ?: SnapshotSelection.Project.takeIf { started["inputScope"]?.stringValue() == "project" }
    require(boundSelection == current.selection) { "Baseline does not bind the same complete verification input selection" }
    require(started["inputScope"]?.stringValue() == (if (boundSelection == SnapshotSelection.Project) "project" else "partial")) {
        "Baseline input scope differs from its bound selection"
    }
    val manifest = document("inputs.json")
    val manifestDigest = Sha256.digest(manifest)
    require(started["inputManifest"]?.let(Digest::fromJson) == manifestDigest) { "Baseline input manifest is unbound or changed" }
    val inputs = TreeManifest.fromJson(manifest)
    val metadata = document("run.json").fields(setOf("schemaVersion", "projectRoot", "configurationPath", "lockPath", "toolsPath", "targets"),
        setOf("inputSelection", "toolTargets", "source", "sourceCache", "sourceRetention"))
    metadata.requireVersion()
    require("source" !in metadata && (metadata["inputSelection"]?.let(SnapshotSelection::fromJson) ?: SnapshotSelection.Project) == current.selection) {
        "Baseline input selection metadata differs from its bound selection"
    }
    if (metadata.getValue("projectRoot").stringValue() != root || metadata.getValue("configurationPath").stringValue() != configurationPath.value)
        fullReasons += "project-identity-changed"
    if (record.configuration != configuration) fullReasons += "configuration-changed"
    if (record.dependencyLock != lock) fullReasons += "dependency-lock-changed"
    if (!bytes("configuration.json").contentEquals(current.configurationBytes)) fullReasons += "configuration-bytes-changed"
    if (!bytes("dependency-lock.json").contentEquals(current.lockBytes)) fullReasons += "dependency-lock-bytes-changed"
    if (record.composition != Digest(runtimeComposition, runtimeCompositionSize)) fullReasons += "runtime-composition-changed"
    if (record.freshnessAtCompletion != Freshness.CURRENT) fullReasons += "baseline-not-current-at-completion"
    retainInputs(inputs)
    return VerificationBaseline(record.inputSnapshot, manifestDigest,
        if (record.freshnessAtCompletion == Freshness.CURRENT) record.completedChecks else emptySet(), record.toolchains)
}

internal fun changeVerificationCommand(args: List<String>): ExitStatus {
    val parsed = changeVerificationOptions(args)
    val options = parsed.build
    require(!options.coverage || "test" in parsed.checks && options.targets == setOf("jvm")) { "--coverage requires --check test and JVM-only execution" }
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
    val inputSelection = if ("abi-check" in parsed.checks) SnapshotSelection.AbiCheck(options.modules, options.targets) else SnapshotSelection.Project
    requireDisjointStore(project, storePath, lockPath, toolsPath, inputSelection)
    val inputPlan = projectInputPlan(project.configuration, inputSelection)
    requireDisjointInputPath(root, project.path, inputPlan, verificationPath, listOf(lockPath, toolsPath))
    requireDisjointInputPath(root, project.path, inputPlan, runsPath, listOf(lockPath, toolsPath))
    val policy = options.project.resolverPolicy()
    val initial = effectiveConfiguration(project.configuration, policy)
    val content = NativeContentStore(storePath, maxBlobBytes = initial.maxArtifactBytes)
    val snapshots = NativeProjectSnapshotter(root, content)
    val snapshot = when (val captured = snapshots.capture(project.name, lockPath, Sha256.digest(project.bytes), inputSelection)) {
        is SnapshotCapture.Captured -> captured.snapshot
        is SnapshotCapture.Indeterminate -> throw CliFailure("KVIK_INPUT_CHANGED", DiagnosticCategory.CONFIGURATION, captured.reason, ExitStatus.INCOMPLETE)
    }
    val effective = effectiveConfiguration(snapshot.configuration, policy)
    val lock = DependencyLocks(content, kvik.resolution.NativePomParser()).loadLocked(snapshot.lockBytes, effective)
    val toolTargets = parsed.checks.flatMap { kind -> projectToolTargets(snapshot.configuration.project,
        ProjectGraphRequest(options.modules, options.targets, verificationMode(kind))) }.toSet()
    val nodeTargets = if ("test" in parsed.checks) options.targets.filter { KotlinJsTarget.find(it) != null }.toSet() else emptySet()
    val desired = BuildTools(toolsPath).desiredIdentities(toolTargets, nodeTargets)
    val fullReasons = linkedSetOf<String>()
    if (parsed.full) fullReasons += "requested-full"
    if (options.coverage) fullReasons += "coverage-requested"
    // Source plugins may read any captured input; core module edges cannot describe their impact.
    if (!supportsCoreChangeSelection(buildPlugins)) fullReasons += "source-build-plugins"
    var baselineFailure: String? = null
    val baselineDirectories = parsed.baselines.map { projectPath(currentDirectory(), it) }
    require(baselineDirectories.distinct().size == baselineDirectories.size) { "Duplicate baseline directory" }
    // All accepted baselines must bind the same manifest; keep only its first parsed inventory.
    var baselineInputs: TreeManifest? = null
    val baselines = baselineDirectories.mapNotNull { directory ->
        try { verificationBaseline(directory, root, project.name, snapshot, effective.digest, lock.digest, fullReasons) { inputs ->
            if (baselineInputs == null) baselineInputs = inputs
        } }
        catch (failure: Exception) {
            fullReasons += "baseline-invalid"
            baselineFailure = failure.message ?: "Cannot validate baseline"
            null
        }
    }
    val firstBaseline = baselines.firstOrNull()
    val compatibleSnapshots = baselines.size == baselineDirectories.size && baselines.all {
        it.snapshot == firstBaseline?.snapshot && it.manifest == firstBaseline?.manifest
    }
    if (!compatibleSnapshots) fullReasons += "baseline-snapshots-differ"
    val baseline = firstBaseline.takeIf { compatibleSnapshots }
    val completed = if (compatibleSnapshots) baselines.flatMap { it.completed }.toSet() else emptySet()
    val recordedTools = mutableMapOf<String, Digest>()
    for (candidate in baselines) for ((key, identity) in candidate.toolchains) {
        if (recordedTools.put(key, identity)?.let { it != identity } == true) fullReasons += "baseline-tool-identities-conflict"
    }
    if (baselines.isNotEmpty()) {
        for ((target, identities) in desired) for ((name, identity) in identities) {
            if (recordedTools["$target:$name"] != identity) fullReasons += "tool-identities-changed"
        }
        // An unrelated run cannot supply a missing tool declaration for a completed check.
        for (candidate in baselines) for (check in candidate.completed) {
            val parts = check.split(':')
            if (parts.size != 3 || parts[0] !in options.modules || parts[1] !in options.targets || parts[2] !in parsed.checks) continue
            val required = projectToolTargets(snapshot.configuration.project,
                ProjectGraphRequest(setOf(parts[0]), setOf(parts[1]), verificationMode(parts[2])))
            for (target in required) for ((name, identity) in desired.getValue(target)) {
                if (name == "node" && (parts[2] != "test" || target != parts[1])) continue
                if (candidate.toolchains["$target:$name"] != identity) fullReasons += "baseline-check-tools-incomplete"
            }
        }
    }
    val previous = baselineInputs.takeIf { baseline != null }?.files?.associateBy { it.path }.orEmpty()
    val current = snapshot.inputs.files.associateBy { it.path }
    val changed = if (baseline == null) emptySet() else (previous.keys + current.keys).filter { previous[it] != current[it] }.toSet()
    if (baseline != null && baseline.snapshot != snapshot.fingerprint && changed.isEmpty() && fullReasons.isEmpty())
        fullReasons += "directory-inventory-changed"
    val selection = selectAffectedChecks(snapshot.configuration, options.modules, options.targets, parsed.checks, changed,
        completed, fullReasons)
    val limitations = listOf(
        "Scope is explicitly selected build, test and ABI checks with their prerequisites; documentation and script checks are not requested.",
        "Omitted checks were completed in compatible retained baselines. freshChecks records newly completed checks; build and ABI actions may use verified cache entries, while selected tests execute freshly.",
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
        "baselineRun" to (baselineDirectories.firstOrNull()?.json() ?: JsonValue.Null),
        "baselineRuns" to baselineDirectories.map { it.json() }.json(),
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
        try { BuildTools(toolsPath).desiredIdentities(toolTargets, nodeTargets) == desired } catch (_: Exception) { false }
    fun guard(): Boolean {
        if (kvik_interrupt_requested() != 0) { status = "cancelled"; exitStatus = ExitStatus.CANCELLED; reason = "Verification interrupted"; return false }
        if (!stable()) { status = "incomplete"; exitStatus = ExitStatus.INCOMPLETE; reason = "Inputs or desired tools changed during verification"; return false }
        return true
    }
    if (guard() && !parsed.planOnly) {
        status = if (selection.selected.isEmpty()) "unchanged" else "passed"
        val groups = verificationGroups(selection.selected)
        for (group in groups) {
            val checks = group.checks
            if (!guard()) break
            val groupOptions = options.copy(modules = group.modules, targets = group.targets, coverage = options.coverage && group.mode == ProjectMode.TEST,
                coverageExcludes = if (group.mode == ProjectMode.TEST) options.coverageExcludes else emptyList())
            try {
                val groupTools = desired.mapValues { (target, identities) -> identities.filterKeys { name ->
                    name != "node" || group.mode == ProjectMode.TEST && target in group.targets
                } }
                val outcome = executeSelectedVerificationBuild(groupOptions, group.mode, snapshot.fingerprint, inputSelection, groupTools)
                val runPath = requireNotNull(outcome.directory) { "Verification execution did not retain a run directory" }
                val record = verificationRecordFromJson(parseJson(NativeFiles.read("$runPath/verification.json", 16 * 1024 * 1024)
                    .decodeToString(throwOnInvalidSequence = true)))
                committedRunEvents(record, parseJson(NativeFiles.read("$runPath/completion.json", 16 * 1024 * 1024)
                    .decodeToString(throwOnInvalidSequence = true)), NativeFiles.read("$runPath/events.jsonl", 16 * 1024 * 1024))
                require(record.inputSnapshot == snapshot.fingerprint && record.requestedChecks == checks.toSet()) {
                    "Verification execution scope or inputs differ from selection"
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
                    ExitStatus.CANCELLED -> { status = "cancelled"; exitStatus = ExitStatus.CANCELLED; reason = "Verification execution interrupted" }
                    ExitStatus.FAILED -> if (status != "incomplete") { status = "failed"; exitStatus = ExitStatus.FAILED; reason = "Selected checks or prerequisites failed" }
                    else -> { status = "incomplete"; exitStatus = outcome.status; reason = "Selected verification execution was incomplete" }
                }
            } catch (failure: Exception) {
                status = "incomplete"
                exitStatus = ExitStatus.INCOMPLETE
                reason = failure.message ?: "Cannot complete selected verification execution"
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
