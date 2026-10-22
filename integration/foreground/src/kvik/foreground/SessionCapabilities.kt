package kvik.foreground

import kvik.core.*

/** A replacement must explicitly accept a live terminal lease before raw-mode exec. */
fun requireKeySessionCapabilities(bytes: ByteArray, selected: BootstrapLaunch, leaseBytes: Int) {
    requireSessionCapabilities(bytes, selected, 2, leaseBytes)
}

/** Application continuation support is required even for line/JSON controls. */
fun requireSessionCapabilities(bytes: ByteArray, selected: BootstrapLaunch, version: Int, leaseBytes: Int? = null) {
    require(bytes.size <= 8192) { "Session capabilities exceed 8 KiB" }
    val fields = parseJson(bytes.decodeToString(throwOnInvalidSequence = true)).fields(
        setOf("schemaVersion", "composition", "runtime", "resumeVersions", "terminalLease"))
    fields.requireVersion()
    require(Digest.fromJson(fields.getValue("composition")) == selected.composition &&
        fields.getValue("runtime").stringValue() == selected.runtime) { "Session capability composition/runtime mismatch" }
    val versions = fields.getValue("resumeVersions").arrayValue().map { it.longValue() }
    require(versions.size <= 8 && versions.all { it > 0 } && versions.distinct().size == versions.size && version.toLong() in versions) { "Replacement cannot resume session version $version" }
    val terminal = fields.getValue("terminalLease").fields(setOf("schemaVersion", "platform", "sizeBytes"))
    terminal.requireVersion()
    val size = terminal.getValue("sizeBytes").longValue()
    require(terminal.getValue("platform").stringValue() == "macos-arm64" && size in 1..2048 && (leaseBytes == null || size == leaseBytes.toLong())) {
        "Replacement terminal lease is incompatible"
    }
}
