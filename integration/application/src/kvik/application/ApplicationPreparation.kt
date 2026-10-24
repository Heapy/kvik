package kvik.application

import kvik.cache.NativeFiles
import kvik.cache.NativeOutputTrees
import kvik.core.*

private const val DESCRIPTOR_LIMIT = 8 * 1024
private const val METADATA_LIMIT = 256 * 1024
private const val RECORD_LIMIT = 16 * 1024 * 1024
private const val INPUT_PREFIX = "application/input/"
private const val METADATA_PATH = "application/launch.json"

/** Portable binding only: run completion, journal and current project/tool checks belong to the caller. */
data class PreparedApplicationDescriptor(
    val runId: String,
    val actionId: String,
    val verification: Digest,
    val outputs: Digest,
) {
    init {
        require(runId.isNotBlank() && runId.none { it.code < 32 }) { "Invalid prepared run identity" }
        require(actionId.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*:(jvm|macosArm64|linuxX64):application"))) { "Invalid application action identity" }
        require(toJson().canonical().encodeToByteArray().size <= DESCRIPTOR_LIMIT) { "Prepared descriptor exceeds 8 KiB" }
    }
    fun toJson(): JsonValue.Object = obj("schemaVersion" to 1.json(), "runId" to runId.json(),
        "actionId" to actionId.json(), "verification" to verification.toJson(), "outputs" to outputs.toJson())
    companion object {
        fun fromJson(value: JsonValue): PreparedApplicationDescriptor {
            require(value.canonical().encodeToByteArray().size <= DESCRIPTOR_LIMIT) { "Prepared descriptor exceeds 8 KiB" }
            val fields = value.fields(setOf("schemaVersion", "runId", "actionId", "verification", "outputs"))
            fields.requireVersion()
            return PreparedApplicationDescriptor(fields.getValue("runId").stringValue(), fields.getValue("actionId").stringValue(),
                Digest.fromJson(fields.getValue("verification")), Digest.fromJson(fields.getValue("outputs")))
        }
    }
}

/**
 * Requires an existing invocation-owned, stable run directory. Refuses existing prepared paths.
 * The prepared tree is exposed atomically before the immutable descriptor; an interrupted operation
 * can leave an uncommitted tree. This function neither commits the run nor starts an application.
 */
fun materializePreparedApplication(
    record: VerificationRecord,
    content: ContentStore,
    runDirectory: String,
): PreparedApplicationDescriptor {
    val proof = preparationProof(record)
    val metadata = proof.outputs.files.single { it.path.value == METADATA_PATH }
    val bytes = content.get(metadata.digest) ?: error("Prepared launch metadata is unavailable")
    validateBundle(proof, bytes)
    NativeFiles.ensureDirectory(runDirectory, create = false)
    require(NativeFiles.info("$runDirectory/prepared") == null && NativeFiles.info("$runDirectory/prepared.json") == null) {
        "Prepared destination already exists"
    }
    NativeOutputTrees(content).materialize(proof.outputs, "$runDirectory/prepared")
    // Recheck the materialized bytes/modes before committing the portable handoff.
    validateTree(proof.outputs, "$runDirectory/prepared")
    NativeFiles.publish("$runDirectory/prepared.json", proof.descriptor.toJson().canonical().encodeToByteArray(), DESCRIPTOR_LIMIT)
    return proof.descriptor
}

/** Read-only verification: no CAS writes or process creation; caller keeps physical roots stable through use. */
fun validatePreparedApplication(
    record: VerificationRecord,
    descriptor: PreparedApplicationDescriptor,
    runDirectory: String,
): ApplicationLaunch {
    val proof = preparationProof(record)
    require(descriptor == proof.descriptor) { "Prepared descriptor does not bind this verification record" }
    NativeFiles.ensureDirectory(runDirectory, create = false)
    val stored = NativeFiles.read("$runDirectory/prepared.json", DESCRIPTOR_LIMIT)
    require(stored.contentEquals(descriptor.toJson().canonical().encodeToByteArray())) { "Prepared descriptor file differs from its canonical binding" }
    val root = "$runDirectory/prepared"
    validateTree(proof.outputs, root)
    return validateBundle(proof, NativeFiles.read("$root/$METADATA_PATH", METADATA_LIMIT))
}

private data class PreparationProof(val record: VerificationRecord, val outputs: TreeManifest, val descriptor: PreparedApplicationDescriptor)

private fun preparationProof(record: VerificationRecord): PreparationProof {
    val encoded = record.toJson().canonical().encodeToByteArray()
    require(encoded.size <= RECORD_LIMIT) { "Verification record exceeds 16 MiB" }
    val snapshot = record.copy(requestedChecks = record.requestedChecks.toSet(), completedChecks = record.completedChecks.toSet(),
        toolchains = record.toolchains.toMap(), actions = record.actions.map { it.copy(outputs = TreeManifest(it.outputs.files.toList())) },
        tests = record.tests.toList(), reproduction = record.reproduction.toList())
    require(snapshot.actions.map { it.actionId }.distinct().size == snapshot.actions.size) { "Duplicate action result" }
    require(snapshot.toJson().canonical().encodeToByteArray().contentEquals(encoded)) { "Verification record changed during snapshot" }
    require(snapshot.status == VerificationStatus.PASSED && snapshot.freshnessAtCompletion == Freshness.CURRENT) { "Preparation requires passed/current verification" }
    val check = snapshot.requestedChecks.singleOrNull() ?: error("Preparation requires exactly one requested application check")
    require(snapshot.completedChecks == setOf(check) && check.endsWith(":application")) { "Application check is not completed" }
    val terminal = snapshot.actions.singleOrNull { it.actionId == check } ?: error("Application terminal result missing")
    require(terminal.successful && terminal.key != null) { "Application terminal was not successful" }
    val metadata = terminal.outputs.files.singleOrNull { it.path.value == METADATA_PATH } ?: error("Application launch metadata missing")
    require(!metadata.executable && metadata.digest.sizeBytes <= METADATA_LIMIT) { "Invalid application metadata output" }
    require(terminal.outputs.files.all { it.path.value == METADATA_PATH || it.path.value.startsWith(INPUT_PREFIX) }) { "Unexpected application bundle output" }
    require(terminal.outputs.files.all { it.digest.sizeBytes <= Int.MAX_VALUE }) { "Prepared file exceeds whole-buffer filesystem limit" }
    return PreparationProof(snapshot, terminal.outputs,
        PreparedApplicationDescriptor(snapshot.runId, check, Sha256.digest(encoded), Sha256.digest(terminal.outputs.toJson())))
}

private fun validateBundle(proof: PreparationProof, metadata: ByteArray): ApplicationLaunch {
    val expected = proof.outputs.files.single { it.path.value == METADATA_PATH }
    require(metadata.size <= METADATA_LIMIT && Sha256.digest(metadata) == expected.digest) { "Application metadata SHA-256/size mismatch" }
    val launch = ApplicationLaunch.fromJson(parseJson(metadata.decodeToString(throwOnInvalidSequence = true)))
    require(metadata.contentEquals(launch.toJson().canonical().encodeToByteArray())) { "Application launch metadata must be canonical typed output" }
    require("${launch.module}:${launch.target}:application" == proof.descriptor.actionId) { "Application launch scope differs from requested check" }
    val inputs = TreeManifest(proof.outputs.files.filter { it.path.value != METADATA_PATH }.map {
        it.copy(path = LogicalPath(it.path.value.removePrefix(INPUT_PREFIX)))
    })
    val prefix = "${launch.target}:"
    val tools = proof.record.toolchains.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    val action = applicationBundleAction(launch, inputs, proof.record.composition, tools)
    require(action.key(Sha256) == proof.record.actions.single { it.actionId == proof.descriptor.actionId }.key) { "Application bundle action key mismatch" }
    return launch
}

/** Exact children checks also reject extra empty directories, unlike a files-only manifest capture. */
private fun validateTree(manifest: TreeManifest, root: String) {
    NativeFiles.ensureDirectory(root, create = false)
    val files = manifest.files.associateBy { it.path.value }
    val children = mutableMapOf("" to mutableSetOf<String>())
    for (entry in manifest.files) {
        val parts = entry.path.value.split('/')
        for (index in parts.indices) children.getOrPut(parts.take(index).joinToString("/")) { mutableSetOf() }.add(parts[index])
    }
    fun visit(relative: String) {
        val physical = if (relative.isEmpty()) root else "$root/$relative"
        require(NativeFiles.names(physical).toSet() == children.getValue(relative)) { "Prepared directory inventory mismatch: $relative" }
        for (name in children.getValue(relative)) {
            val path = if (relative.isEmpty()) name else "$relative/$name"
            val info = NativeFiles.info("$root/$path") ?: error("Prepared entry disappeared: $path")
            val entry = files[path]
            if (entry == null) {
                require(NativeFiles.directory(info)) { "Prepared directory is a link or special file: $path" }
                visit(path)
            } else {
                require(NativeFiles.regular(info) && (info.mode and 73 != 0) == entry.executable) { "Prepared file type/executable mismatch: $path" }
                require(Sha256.digest(NativeFiles.read("$root/$path", entry.digest.sizeBytes.toInt())) == entry.digest) { "Prepared file SHA-256/size mismatch: $path" }
            }
        }
    }
    visit("")
}
