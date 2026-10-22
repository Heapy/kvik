@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.toolchains

import kotlinx.cinterop.*
import platform.posix.*
import kvik.core.*

enum class ToolComponent(val lockName: String) {
    JDK("jdk"), KOTLIN_NATIVE("kotlinNative"), LLVM("llvm"), LIBFFI("libffi"), SDK("sdk"), XCODE_TOOLCHAIN("xcodeToolchain")
}

data class ToolHost(val os: String, val architecture: String, val release: String, val sdk: String)

/** Created only by successful verification, and valid only while the caller keeps this root stable. */
class VerifiedToolComponent internal constructor(val root: String, val inventory: InventorySummary) {
    val digest: Digest get() = inventory.digest
}

class VerifiedToolchains internal constructor(
    val host: ToolHost,
    val kotlinVersion: String,
    components: Map<ToolComponent, VerifiedToolComponent>,
) {
    private val components = components.toMap()
    operator fun get(component: ToolComponent): VerifiedToolComponent = components.getValue(component)
}

/** No memoization: each verify call re-reads every regular file in all six installations. */
class InstalledToolchainVerifier(private val limits: InventoryLimits = InventoryLimits()) {
    fun verify(lockJson: String, roots: Map<ToolComponent, String>): VerifiedToolchains {
        require(lockJson.length <= 1048576 && lockJson.encodeToByteArray(throwOnInvalidSequence = true).size <= 1048576) { "Tool lock exceeds 1 MiB" }
        val lock = parseJson(lockJson).fields(setOf("schemaVersion", "kotlinVersion", "host", "components"))
        lock.requireVersion()
        val kotlinVersion = lock.getValue("kotlinVersion").stringValue()
        require(kotlinVersion == "2.4.20") { "Unsupported Kotlin toolchain version: $kotlinVersion" }
        val hostFields = lock.getValue("host").fields(setOf("os", "architecture", "release", "sdk"))
        val expectedHost = ToolHost(hostFields.getValue("os").stringValue(), hostFields.getValue("architecture").stringValue(),
            hostFields.getValue("release").stringValue(), hostFields.getValue("sdk").stringValue())
        val actualHost = nativeHost()
        require(expectedHost.os == actualHost.first && expectedHost.architecture == actualHost.second && expectedHost.release == actualHost.third) { "Tool lock host does not match uname: $actualHost" }
        require(actualHost.first == "Darwin" && actualHost.second == "arm64") { "Unsupported toolchain host: $actualHost" }
        require(roots.keys == ToolComponent.entries.toSet()) { "Explicit roots for all six tool components are required" }
        val components = lock.getValue("components").fields(ToolComponent.entries.map { it.lockName }.toSet())
        val expected = ToolComponent.entries.associateWith { Digest.fromJson(components.getValue(it.lockName)) }
        val verified = mutableMapOf<ToolComponent, VerifiedToolComponent>()
        for (component in ToolComponent.entries) {
            val captured = captureInventory(roots.getValue(component), limits, component == ToolComponent.SDK)
            require(captured.summary.digest == expected.getValue(component)) { "Tool inventory mismatch: ${component.lockName}; expected ${expected.getValue(component)}, actual ${captured.summary.digest}" }
            if (component == ToolComponent.SDK) {
                val settings = captured.sdkSettings ?: throw ToolchainVerificationFailure("Unsupported SDK host data: regular SDKSettings.json is required")
                val sdk = parseJson(settings.decodeToString(throwOnInvalidSequence = true)).objectValue()["Version"]?.stringValue()
                    ?: throw ToolchainVerificationFailure("Unsupported SDK host data: SDKSettings.json Version is required")
                require(sdk.isNotBlank() && sdk == expectedHost.sdk) { "Tool lock SDK version does not match verified SDKSettings.json: $sdk" }
            }
            verified[component] = VerifiedToolComponent(captured.root, captured.summary)
        }
        return VerifiedToolchains(expectedHost, kotlinVersion, verified)
    }
}

internal fun nativeHost(): Triple<String, String, String> = memScoped {
    val host = alloc<utsname>()
    if (uname(host.ptr) != 0) ioFailure("uname")
    Triple(host.sysname.toKString(), host.machine.toKString(), host.release.toKString())
}
