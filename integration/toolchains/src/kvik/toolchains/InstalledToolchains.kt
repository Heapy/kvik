@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.toolchains

import kotlinx.cinterop.*
import platform.posix.*
import kvik.core.*

enum class ToolComponent(val lockName: String) {
    JDK("jdk"), KOTLIN_NATIVE("kotlinNative"), LLVM("llvm"), LIBFFI("libffi"), SDK("sdk"), XCODE_TOOLCHAIN("xcodeToolchain"),
    NATIVE_TOOLCHAIN("nativeToolchain"), SYSTEM("system")
}

data class ToolHost(val os: String, val architecture: String, val release: String, val sdk: String)

fun toolComponentsFor(os: String, architecture: String): Set<ToolComponent> {
    val shared = setOf(ToolComponent.JDK, ToolComponent.KOTLIN_NATIVE, ToolComponent.LLVM, ToolComponent.LIBFFI)
    return when (os to architecture) {
        "Darwin" to "arm64" -> shared + setOf(ToolComponent.SDK, ToolComponent.XCODE_TOOLCHAIN)
        "Linux" to "x86_64" -> shared + setOf(ToolComponent.NATIVE_TOOLCHAIN, ToolComponent.SYSTEM)
        else -> error("Unsupported toolchain host: $os/$architecture")
    }
}

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

/** No memoization: each call re-reads every regular file in the requested installations. */
class InstalledToolchainVerifier(private val limits: InventoryLimits = InventoryLimits()) {
    fun verify(lockJson: String, roots: Map<ToolComponent, String>, checkpoint: () -> Unit = {}): VerifiedToolchains {
        checkpoint()
        val lock = parseToolchainDeclaration(lockJson)
        checkpoint()
        require(roots.keys == lock.components.keys) { "Explicit roots for all host tool components are required" }
        val verified = mutableMapOf<ToolComponent, VerifiedToolComponent>()
        for (component in lock.components.keys) {
            val captured = captureInventory(roots.getValue(component), limits, component == ToolComponent.SDK, checkpoint)
            require(captured.summary.digest == lock.components.getValue(component)) { "Tool inventory mismatch: ${component.lockName}; expected ${lock.components.getValue(component)}, actual ${captured.summary.digest}" }
            if (component == ToolComponent.SDK) {
                val settings = captured.sdkSettings ?: throw ToolchainVerificationFailure("Unsupported SDK host data: regular SDKSettings.json is required")
                val sdk = parseJson(settings.decodeToString(throwOnInvalidSequence = true)).objectValue()["Version"]?.stringValue()
                    ?: throw ToolchainVerificationFailure("Unsupported SDK host data: SDKSettings.json Version is required")
                require(sdk.isNotBlank() && sdk == lock.host.sdk) { "Tool lock SDK version does not match verified SDKSettings.json: $sdk" }
            }
            verified[component] = VerifiedToolComponent(captured.root, captured.summary)
        }
        checkpoint()
        return VerifiedToolchains(lock.host, lock.kotlinVersion, verified)
    }

    /** A JVM worker consumes the JDK, not Native/LLVM/Xcode. No SDK verification is claimed. */
    fun verifyJdk(lockJson: String, root: String, checkpoint: () -> Unit = {}): VerifiedToolComponent {
        checkpoint()
        val lock = parseToolchainDeclaration(lockJson)
        checkpoint()
        val captured = captureInventory(root, limits, false, checkpoint)
        val expected = lock.components.getValue(ToolComponent.JDK)
        require(captured.summary.digest == expected) { "Tool inventory mismatch: jdk; expected $expected, actual ${captured.summary.digest}" }
        checkpoint()
        return VerifiedToolComponent(captured.root, captured.summary)
    }

}

/** Desired content identities only; no installation paths have been inspected or verified. */
class ToolchainDeclaration internal constructor(
    val host: ToolHost,
    val kotlinVersion: String,
    components: Map<ToolComponent, Digest>,
) {
    val components: Map<ToolComponent, Digest> = components.toMap()
}

/** Strict lock/host validation for planning and cached artifacts; deliberately performs no IO
 * against installations. Physical providers independently verify bytes before fresh execution.
 */
fun parseToolchainDeclaration(lockJson: String): ToolchainDeclaration {
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
    val required = toolComponentsFor(expectedHost.os, expectedHost.architecture)
    if (expectedHost.os == "Linux") require(expectedHost.sdk.isEmpty()) { "Linux host must not declare an Apple SDK" }
    else require(expectedHost.sdk.isNotBlank()) { "Darwin tool lock must declare its SDK version" }
    val components = lock.getValue("components").fields(required.map { it.lockName }.toSet())
    val expected = required.associateWith { Digest.fromJson(components.getValue(it.lockName)) }
    return ToolchainDeclaration(expectedHost, kotlinVersion, expected)
}

internal fun nativeHost(): Triple<String, String, String> = memScoped {
    val host = alloc<utsname>()
    if (uname(host.ptr) != 0) ioFailure("uname")
    Triple(host.sysname.toKString(), host.machine.toKString(), host.release.toKString())
}
