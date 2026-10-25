package kvik.foreground

import kvik.core.*

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun terminalLeasePlatform(): String = when (kotlin.native.Platform.osFamily to kotlin.native.Platform.cpuArchitecture) {
    kotlin.native.OsFamily.MACOSX to kotlin.native.CpuArchitecture.ARM64 -> "macos-arm64"
    kotlin.native.OsFamily.LINUX to kotlin.native.CpuArchitecture.X64 -> "linux-x64"
    else -> error("Unsupported terminal lease host")
}

/** A replacement must explicitly accept a live terminal lease before raw-mode exec. */
fun requireKeySessionCapabilities(bytes: ByteArray, selected: BootstrapLaunch, leaseBytes: Int) {
    requireSessionCapabilities(bytes, selected, 2, leaseBytes)
}

/** Application continuation support is required even for line/JSON controls. */
fun requireSessionCapabilities(bytes: ByteArray, selected: BootstrapLaunch, version: Int, leaseBytes: Int? = null, sourceRevisions: Boolean = false) {
    require(bytes.size <= 8192) { "Session capabilities exceed 8 KiB" }
    val fields = parseJson(bytes.decodeToString(throwOnInvalidSequence = true)).fields(
        setOf("schemaVersion", "composition", "runtime", "resumeVersions", "terminalLease"), setOf("processOwnershipVersion", "sourceRevisions"))
    fields.requireVersion()
    if (sourceRevisions) require(fields["sourceRevisions"]?.booleanValue() == true) { "Replacement cannot follow source revisions" }
    require(Digest.fromJson(fields.getValue("composition")) == selected.composition &&
        fields.getValue("runtime").stringValue() == selected.runtime) { "Session capability composition/runtime mismatch" }
    val versions = fields.getValue("resumeVersions").arrayValue().map { it.longValue() }
    require(versions.size <= 8 && versions.all { it > 0 } && versions.distinct().size == versions.size && version.toLong() in versions) { "Replacement cannot resume session version $version" }
    if (version >= 3) selected.requireApplicationOwnership()
    val ownership = fields["processOwnershipVersion"]?.longValue()
    require(ownership == null || ownership == 1L) { "Unsupported recursive process ownership capability" }
    if (version >= 3) require(ownership == 1L) { "Replacement lacks recursive process ownership version 1" }
    val terminal = fields.getValue("terminalLease").fields(setOf("schemaVersion", "platform", "sizeBytes"))
    terminal.requireVersion()
    val size = terminal.getValue("sizeBytes").longValue()
    require(terminal.getValue("platform").stringValue() == terminalLeasePlatform() && size in 1..2048 && (leaseBytes == null || size == leaseBytes.toLong())) {
        "Replacement terminal lease is incompatible"
    }
}
