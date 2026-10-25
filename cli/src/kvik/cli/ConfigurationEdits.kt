@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package kvik.cli

import kotlinx.cinterop.*
import kvik.cache.NativeContentStore
import kvik.cache.NativeFiles
import kvik.core.*
import kvik.locking.*
import kvik.execution.platform.kvik_interrupt_install
import kvik.execution.platform.kvik_interrupt_restore
import kvik.projectsnapshot.projectInputPlan
import kvik.projectsnapshot.ProjectInputPlan
import kvik.resolution.NativeHttpsTransport
import kvik.resolution.NativePomParser
import platform.posix.*

private const val CONFIG_LIMIT = 4 * 1024 * 1024
private const val EDIT_LOCK_LIMIT = 16 * 1024 * 1024
private const val EDIT_PLAN_LIMIT = 64 * 1024 * 1024
private const val CONFIG_COORDINATION = ".kvik-configuration-publication.lock"
private const val DEPENDENCY_COORDINATION = ".kvik-dependency-publication.lock"

private fun readDocument(path: String, limit: Int): JsonValue =
    parseJson(NativeFiles.read(path, limit).decodeToString(throwOnInvalidSequence = true))

private fun fileDigest(path: String, limit: Int): Digest? {
    val info = NativeFiles.info(path) ?: return null
    require(NativeFiles.regular(info)) { "Configuration edits require regular files: $path" }
    return Sha256.digest(NativeFiles.read(path, limit))
}

internal fun policyDocument(options: ProjectOptions): JsonValue = obj(
    "pins" to options.pins.map { it.toString().json() }.json(),
    "scopedPins" to options.scopedPins.map { it.argument().json() }.json(),
    "metadataExclusions" to options.metadataExclusions.map { it.toJson() }.json())

private fun editPolicy(value: JsonValue): ResolverPolicy {
    val fields = value.fields(setOf("pins", "scopedPins", "metadataExclusions"))
    val args = fields.getValue("pins").arrayValue().flatMap { listOf("--pin", it.stringValue()) } +
        fields.getValue("scopedPins").arrayValue().flatMap { listOf("--scope-pin", it.stringValue()) } +
        fields.getValue("metadataExclusions").arrayValue().flatMap { listOf("--exclude-metadata-edge", it.canonical()) }
    return projectOptions(args, lockOptions = true).resolverPolicy()
}

private fun conflict(message: String): Nothing = throw CliFailure("KVIK_EDIT_CONFLICT", DiagnosticCategory.CONFIGURATION,
    message, ExitStatus.INCOMPLETE)

/** Planning reads original inputs; only the resolver's immutable cache may change. */
internal fun configurationCommand(args: List<String>): JsonValue {
    val operation = args.firstOrNull() ?: error("Use config validate|plan|apply")
    require(operation in setOf("validate", "plan", "apply")) { "Unsupported configuration operation" }
    val extra = mutableMapOf<String, String>()
    val remaining = mutableListOf<String>()
    var index = 1
    while (index < args.size) {
        val flag = args[index++]
        if (flag in setOf("--edits", "--plan")) {
            require(flag !in extra && index < args.size) { "Missing or duplicate $flag" }
            extra[flag] = args[index++]
        } else {
            remaining += flag
            if (flag != "--offline") remaining += args.getOrNull(index++) ?: error("Missing $flag value")
        }
    }
    if (operation == "apply") {
        require(extra.keys == setOf("--plan") && remaining.isEmpty()) { "config apply requires only --plan FILE" }
        return applyConfigurationPlan(readDocument(projectPath(currentDirectory(), extra.getValue("--plan")), EDIT_PLAN_LIMIT))
    }
    require("--plan" !in extra && (operation == "plan") == ("--edits" in extra)) { "config plan requires --edits FILE; validate takes no edits" }
    val edits = extra["--edits"]?.let { readDocument(projectPath(currentDirectory(), it), CONFIG_LIMIT) }
    val (source, projectArgs) = sourceProjectOptions(remaining)
    val options = projectOptions(projectArgs, lockOptions = true)
    if (source != null) {
        if (operation == "plan") return sourceDependencyCommand(source, options, true, null,
            configurationEdit = checkNotNull(edits))
        check(kvik_interrupt_install() == 0) { "Cannot install source validation interruption handlers" }
        try {
            return withPreparedSourceProject(source, options) { prepared ->
                validatedConfiguration(prepared.project.configuration, prepared.configuration.bytes, options, prepared.reference.toJson())
            }
        } finally { kvik_interrupt_restore() }
    }
    val project = readProject(options.project)
    if (operation == "validate") return validatedConfiguration(project.configuration, project.bytes, options, null)
    return planConfigurationEdit(project, options, checkNotNull(edits))
}

private fun validatedConfiguration(configuration: ProjectConfiguration, bytes: ByteArray, options: ProjectOptions, source: JsonValue?): JsonValue {
    val effective = effectiveConfiguration(configuration, options.resolverPolicy())
    return obj("schemaVersion" to 1.json(), "kind" to "configuration-validation".json(), "status" to "valid".json(),
        "configurationFile" to Sha256.digest(bytes).toJson(), "effectiveConfiguration" to effective.digest.toJson(),
        "modules" to configuration.project.modules.map { it.id.json() }.json(), "source" to (source ?: JsonValue.Null))
}

internal fun configurationEditInputs(configuration: ProjectConfiguration): ProjectInputPlan {
    val inputs = projectInputPlan(configuration)
    val additional = configuration.project.modules.flatMap { module ->
        module.targets.mapNotNull { it.abi?.baseline } + module.documentation?.includes.orEmpty()
    }
    return inputs.copy(files = (inputs.files + additional).distinct())
}

private fun editPaths(project: ProjectFile, lock: String, store: String) {
    require(lock.startsWith("${project.root}/") && lock != project.path && !sameEntry(lock, project.path)) {
        "Configuration edit lock must be a distinct file inside the project directory"
    }
    for (path in listOf(project.path, lock)) require(path.substringAfterLast('/').lowercase() !in setOf(CONFIG_COORDINATION, DEPENDENCY_COORDINATION)) {
        "Configuration edits cannot replace publication coordination files"
    }
    require(!pathsPhysicallyOverlap(store, project.path) && !pathsPhysicallyOverlap(store, lock)) { "Content store overlaps edited files" }
    val inputs = configurationEditInputs(project.configuration)
    requireDisjointInputPath(project.root, project.path, inputs, lock)
    requireDisjointInputPath(project.root, project.path, inputs, store, listOf(lock))
    for (coordination in listOf("${project.root}/$CONFIG_COORDINATION", "${lock.substringBeforeLast('/')}/$DEPENDENCY_COORDINATION")) {
        requireDisjointInputPath(project.root, project.path, inputs, coordination, listOf(lock))
    }
    val directory = "${project.root}/.kvik/configuration-edits"
    requireDisjointInputPath(project.root, project.path, inputs, directory, listOf(lock))
    require(!pathsPhysicallyOverlap(directory, store)) { "Configuration edit journal overlaps content store" }
}

private fun planConfigurationEdit(project: ProjectFile, options: ProjectOptions, edits: JsonValue): JsonValue {
    val changed = editProjectConfiguration(project.configuration, edits).withScriptDeclarations(localScriptSourceReader(project.root))
    val bytes = if (changed.document == project.configuration.document) project.bytes else (changed.document.canonical() + "\n").encodeToByteArray()
    require(bytes.size <= CONFIG_LIMIT) { "Edited configuration exceeds 4 MiB" }
    val effective = effectiveConfiguration(changed, options.resolverPolicy())
    val lockPath = projectPath(project.root, options.lock)
    val store = projectPath(project.root, options.store)
    editPaths(project, lockPath, store)
    editPaths(project.copy(bytes = bytes, configuration = changed), lockPath, store)
    val previous = fileDigest(lockPath, EDIT_LOCK_LIMIT)
    val lock = if (options.offline) {
        require(previous != null) { "Offline planning requires a compatible existing lock" }
        DependencyLocks(ReadOnlyContentStore(store, effective.maxArtifactBytes), NativePomParser())
            .loadLocked(NativeFiles.read(lockPath, EDIT_LOCK_LIMIT), effective)
    } else {
        val content = NativeContentStore(store, maxBlobBytes = effective.maxArtifactBytes)
        DependencyLocks(content, NativePomParser()).resolve(effective, NativeHttpsTransport(maxBytes = effective.maxArtifactBytes))
    }
    val lockBytes = lock.bytes()
    require(lockBytes.size <= EDIT_LOCK_LIMIT) { "Edited dependency lock exceeds 16 MiB" }
    if (fileDigest(project.path, CONFIG_LIMIT) != Sha256.digest(project.bytes) || fileDigest(lockPath, EDIT_LOCK_LIMIT) != previous)
        conflict("Configuration or lock changed while preparing the edit; plan again")
    if (effectiveConfiguration(changed.withScriptDeclarations(localScriptSourceReader(project.root)), options.resolverPolicy()).digest != effective.digest)
        conflict("Script declarations changed while preparing the edit; plan again")
    return obj("schemaVersion" to 1.json(), "kind" to "configuration-edit-plan".json(),
        "root" to project.root.json(), "project" to project.name.value.json(), "lock" to lockPath.removePrefix("${project.root}/").json(),
        "store" to store.json(), "policy" to policyDocument(options), "edits" to edits,
        "effectiveConfiguration" to effective.digest.toJson(),
        "configuration" to obj("before" to Sha256.digest(project.bytes).toJson(), "after" to Sha256.digest(bytes).toJson(),
            "beforeContent" to project.bytes.decodeToString(throwOnInvalidSequence = true).json(), "content" to bytes.decodeToString(throwOnInvalidSequence = true).json()),
        "dependencyLock" to obj("before" to (previous?.toJson() ?: JsonValue.Null), "after" to Sha256.digest(lockBytes).toJson(),
            "content" to lockBytes.decodeToString(throwOnInvalidSequence = true).json()))
}

private data class EditFile(val path: String, val before: Digest?, val after: Digest, val bytes: ByteArray, val limit: Int)

/** Two guarded renames with a durable intent: interrupted publication is explicitly resumable. */
private fun applyConfigurationPlan(value: JsonValue): JsonValue {
    val fields = value.fields(setOf("schemaVersion", "kind", "root", "project", "lock", "store", "policy", "edits", "effectiveConfiguration", "configuration", "dependencyLock"))
    fields.requireVersion(); require(fields.getValue("kind").stringValue() == "configuration-edit-plan")
    val root = fields.getValue("root").stringValue()
    NativeFiles.ensureDirectory(root, create = false)
    val name = LogicalPath(fields.getValue("project").stringValue())
    require('/' !in name.value) { "Project filename must be relative to its own directory" }
    val lockName = LogicalPath(fields.getValue("lock").stringValue())
    val configuration = fields.getValue("configuration").fields(setOf("before", "after", "beforeContent", "content"))
    val beforeBytes = configuration.getValue("beforeContent").stringValue().encodeToByteArray()
    require(beforeBytes.size <= CONFIG_LIMIT && Sha256.digest(beforeBytes) == Digest.fromJson(configuration.getValue("before"))) { "Invalid original configuration bytes" }
    fun entry(path: String, document: JsonValue, limit: Int, original: Boolean = false): EditFile {
        val data = document.fields(setOf("before", "after", "content") + if (original) setOf("beforeContent") else emptySet())
        val bytes = data.getValue("content").stringValue().encodeToByteArray()
        val after = Digest.fromJson(data.getValue("after"))
        require(bytes.size <= limit && Sha256.digest(bytes) == after) { "Edited file digest differs from its content" }
        return EditFile(path, data.getValue("before").let { if (it == JsonValue.Null) null else Digest.fromJson(it) }, after, bytes, limit)
    }
    val configFile = entry("$root/${name.value}", fields.getValue("configuration"), CONFIG_LIMIT, true)
    val lockFile = entry("$root/${lockName.value}", fields.getValue("dependencyLock"), EDIT_LOCK_LIMIT)
    val original = parseProjectConfiguration(parseJson(beforeBytes.decodeToString(throwOnInvalidSequence = true)))
        .withScriptDeclarations(localScriptSourceReader(root))
    val changed = parseProjectConfiguration(parseJson(configFile.bytes.decodeToString(throwOnInvalidSequence = true)))
        .withScriptDeclarations(localScriptSourceReader(root))
    require(editProjectConfiguration(original, fields.getValue("edits")).document == changed.document) { "Plan content does not match its declared edits" }
    val effective = effectiveConfiguration(changed, editPolicy(fields.getValue("policy")))
    require(effective.digest == Digest.fromJson(fields.getValue("effectiveConfiguration"))) { "Effective configuration digest differs from plan" }
    val store = fields.getValue("store").stringValue()
    val project = ProjectFile(root, name, configFile.bytes, changed)
    editPaths(project, lockFile.path, store)
    editPaths(project.copy(bytes = beforeBytes, configuration = original), lockFile.path, store)
    DependencyLocks(ReadOnlyContentStore(store, effective.maxArtifactBytes), NativePomParser()).loadLocked(lockFile.bytes, effective)
    val planBytes = value.canonical().encodeToByteArray()
    require(planBytes.size <= EDIT_PLAN_LIMIT) { "Edit plan exceeds 64 MiB" }
    val identity = Sha256.digest(planBytes)
    val directory = "$root/.kvik/configuration-edits/${identity.hash}"
    var resumed = false
    var writes = 0
    NativeFiles.withLock("$root/$CONFIG_COORDINATION") {
        NativeFiles.ensureDirectory(lockFile.path.substringBeforeLast('/'))
        withDependencyPublicationLock(lockFile.path) {
            // Include physical aliases after acquiring the same lock used by ordinary resolve.
            editPaths(project, lockFile.path, store)
            val intent = "$directory/intent.json"
            resumed = NativeFiles.info(intent) != null
            if (resumed) require(NativeFiles.read(intent, EDIT_PLAN_LIMIT).contentEquals(planBytes)) { "Configuration edit intent is corrupted" }
            val files = listOf(configFile, lockFile)
            fun checkInput(file: EditFile) {
                val current = fileDigest(file.path, file.limit)
                if (current != file.before && !(resumed && current == file.after))
                    conflict("${file.path} changed since planning; retained recovery directory: $directory")
            }
            fun checkDeclarations() {
                if (effectiveConfiguration(changed.withScriptDeclarations(localScriptSourceReader(root)), editPolicy(fields.getValue("policy"))).digest != effective.digest)
                    conflict("Script declarations changed since planning; plan again")
            }
            checkDeclarations()
            files.forEach(::checkInput)
            NativeFiles.ensureDirectory(directory)
            NativeFiles.publish(intent, planBytes, EDIT_PLAN_LIMIT)
            for (file in files) {
                if (fileDigest(file.path, file.limit) == file.after) continue
                checkInput(file)
                val parent = file.path.substringBeforeLast('/')
                val stage = NativeFiles.tempDirectory(parent)
                try {
                    val temporary = "$stage/content"
                    NativeFiles.writeNew(temporary, file.bytes)
                    val mode = NativeFiles.info(file.path)?.mode?.and(511) ?: 420
                    check(chmod(temporary, mode.convert()) == 0) { "Cannot preserve edited file permissions" }
                    val descriptor = open(temporary, O_RDONLY or O_NOFOLLOW or O_CLOEXEC)
                    check(descriptor >= 0) { "Cannot open staged configuration edit" }
                    try { check(fsync(descriptor) == 0) { "Cannot persist edited file permissions" } } finally { close(descriptor) }
                    checkInput(file)
                    checkDeclarations()
                    if (rename(temporary, file.path) != 0) NativeFiles.fail("publish configuration edit")
                    NativeFiles.syncDirectory(parent)
                    writes++
                } finally { NativeFiles.removeTree(stage) }
            }
            for (file in files) if (fileDigest(file.path, file.limit) != file.after) conflict("Edited files changed during publication; inspect $directory")
            checkDeclarations()
            NativeFiles.publish("$directory/completion.json", obj("schemaVersion" to 1.json(), "plan" to identity.toJson()).canonical().encodeToByteArray(), 4096)
        }
    }
    return obj("schemaVersion" to 1.json(), "kind" to "configuration-edit-result".json(), "plan" to identity.toJson(),
        "status" to (if (writes == 0) "unchanged" else if (resumed) "recovered" else "applied").json(),
        "configuration" to configFile.after.toJson(), "dependencyLock" to lockFile.after.toJson(), "directory" to directory.json())
}
