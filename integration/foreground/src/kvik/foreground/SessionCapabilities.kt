package kvik.foreground

import kvik.core.*

/** A replacement must explicitly accept a live terminal lease before raw-mode exec. */
fun requireKeySessionCapabilities(bytes: ByteArray, selected: BootstrapLaunch, leaseBytes: Int) {
    require(bytes.size <= 8192) { "Session capabilities exceed 8 KiB" }
    val fields = parseJson(bytes.decodeToString(throwOnInvalidSequence = true)).fields(
        setOf("schemaVersion", "composition", "runtime", "resumeVersions", "terminalLease"))
    fields.requireVersion()
    require(Digest.fromJson(fields.getValue("composition")) == selected.composition &&
        fields.getValue("runtime").stringValue() == selected.runtime) { "Session capability composition/runtime mismatch" }
    val versions = fields.getValue("resumeVersions").arrayValue().map { it.longValue() }
    require(versions.size <= 8 && versions.all { it > 0 } && versions.distinct().size == versions.size && 2L in versions) { "Replacement cannot resume key controls" }
    val terminal = fields.getValue("terminalLease").fields(setOf("schemaVersion", "platform", "sizeBytes"))
    terminal.requireVersion()
    require(terminal.getValue("platform").stringValue() == "macos-arm64" && terminal.getValue("sizeBytes").longValue() == leaseBytes.toLong()) {
        "Replacement terminal lease is incompatible"
    }
}
