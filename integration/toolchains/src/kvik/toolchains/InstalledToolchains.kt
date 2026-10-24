@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kvik.toolchains

import kotlinx.cinterop.*
import platform.posix.*
import kvik.core.*

enum class ToolComponent(val lockName: String) {
    JDK("jdk"), KOTLIN_NATIVE("kotlinNative"), LLVM("llvm"), LIBFFI("libffi"), SDK("sdk"), XCODE_TOOLCHAIN("xcodeToolchain"),
    NATIVE_TOOLCHAIN("nativeToolchain"), SYSTEM("system"),
    IOS_SDK("iosSdk"), IOS_SIMULATOR_SDK("iosSimulatorSdk")
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

fun appleSdkComponent(target: NativeTarget): ToolComponent = when (target) {
    NativeTarget.MACOS_ARM64 -> ToolComponent.SDK
    NativeTarget.IOS_ARM64 -> ToolComponent.IOS_SDK
    NativeTarget.IOS_SIMULATOR_ARM64 -> ToolComponent.IOS_SIMULATOR_SDK
    NativeTarget.LINUX_X64 -> error("Linux does not consume an Apple SDK")
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
    fun verify(lockJson: String, roots: Map<ToolComponent, String>, checkpoint: () -> Unit = {}): VerifiedToolchains =
        verifySelected(lockJson, roots, roots.keys, checkpoint)

    /** All declared roots must be supplied, but only consumed components are physically inspected. */
    fun verifySelected(lockJson: String, roots: Map<ToolComponent, String>, components: Set<ToolComponent>,
                       checkpoint: () -> Unit = {}): VerifiedToolchains {
        checkpoint()
        val selected = components.toSet()
        val paths = roots.toMap()
        val lock = parseToolchainDeclaration(lockJson)
        checkpoint()
        require(paths.keys == lock.components.keys) { "Explicit roots for all declared tool components are required" }
        require(selected.isNotEmpty() && lock.components.keys.containsAll(selected)) { "Selected tool components must be declared" }
        val verified = mutableMapOf<ToolComponent, VerifiedToolComponent>()
        for (component in lock.components.keys.filter { it in selected }) {
            val sdkComponent = component in setOf(ToolComponent.SDK, ToolComponent.IOS_SDK, ToolComponent.IOS_SIMULATOR_SDK)
            val captured = captureInventory(paths.getValue(component), limits, sdkComponent, checkpoint)
            require(captured.summary.digest == lock.components.getValue(component)) { "Tool inventory mismatch: ${component.lockName}; expected ${lock.components.getValue(component)}, actual ${captured.summary.digest}" }
            if (sdkComponent) {
                val settings = captured.sdkSettings ?: throw ToolchainVerificationFailure("Unsupported SDK host data: regular SDKSettings.json is required")
                validateSdkSettings(component, settings, lock.host.sdk)
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
    val optional = if (expectedHost.os == "Darwin") setOf(ToolComponent.IOS_SDK, ToolComponent.IOS_SIMULATOR_SDK) else emptySet()
    val components = lock.getValue("components").fields(required.map { it.lockName }.toSet(), optional.map { it.lockName }.toSet())
    val expected = (required + optional.filter { it.lockName in components }).associateWith { Digest.fromJson(components.getValue(it.lockName)) }
    return ToolchainDeclaration(expectedHost, kotlinVersion, expected)
}

internal fun nativeHost(): Triple<String, String, String> = memScoped {
    val host = alloc<utsname>()
    if (uname(host.ptr) != 0) ioFailure("uname")
    Triple(host.sysname.toKString(), host.machine.toKString(), host.release.toKString())
}

internal fun validateSdkSettings(component: ToolComponent, bytes: ByteArray, macosVersion: String) {
    val settings = parseJson(bytes.decodeToString(throwOnInvalidSequence = true)).objectValue()
    val version = settings["Version"]?.stringValue()
        ?: throw ToolchainVerificationFailure("Unsupported SDK host data: SDKSettings.json Version is required")
    if (component == ToolComponent.SDK) {
        require(version.isNotBlank() && version == macosVersion) { "Tool lock SDK version does not match verified SDKSettings.json: $version" }
    } else {
        val platform = when (component) {
            ToolComponent.IOS_SDK -> "iphoneos"
            ToolComponent.IOS_SIMULATOR_SDK -> "iphonesimulator"
            else -> error("Not an SDK component")
        }
        require(version.matches(Regex("[0-9]+(?:\\.[0-9]+){1,2}"))) { "Invalid Apple target SDK version" }
        require(settings["CanonicalName"]?.stringValue() == platform + version &&
            settings["DefaultProperties"]?.objectValue()?.get("PLATFORM_NAME")?.stringValue() == platform &&
            settings["SupportedTargets"]?.objectValue()?.containsKey(platform) == true) {
            "SDKSettings.json does not describe the required $platform platform"
        }
    }
}
