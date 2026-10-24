package kvik.core

/** Native target names shared by project variants, compiler invocations and artifact metadata. */
enum class NativeTarget(val projectName: String, val compilerName: String, val os: String, val architecture: String) {
    MACOS_ARM64("macosArm64", "macos_arm64", "macos", "arm64"),
    LINUX_X64("linuxX64", "linux_x64", "linux", "x64"),
    IOS_ARM64("iosArm64", "ios_arm64", "ios", "arm64"),
    IOS_SIMULATOR_ARM64("iosSimulatorArm64", "ios_simulator_arm64", "ios", "arm64");

    val platform: Map<String, String> get() = mapOf("os" to os, "arch" to architecture)
    val isApple: Boolean get() = os == "macos" || os == "ios"
    val isIos: Boolean get() = os == "ios"
    val supportsHostExecution: Boolean get() = !isIos
    /** The compiler runs on the supported host even when producing an iOS artifact. */
    val executionPlatform: Map<String, String> get() = if (isApple) MACOS_ARM64.platform else platform
    val appleSdkName: String? get() = when (this) {
        MACOS_ARM64 -> "macosx"
        IOS_ARM64 -> "iphoneos"
        IOS_SIMULATOR_ARM64 -> "iphonesimulator"
        LINUX_X64 -> null
    }
    val publicationSuffix: String get() = projectName.lowercase()
    val toolIdentityNames: Set<String> get() = setOf("native", "jdk", "native-dependencies",
        if (isApple) "sdk" else "system")

    companion object {
        fun find(projectName: String): NativeTarget? = entries.firstOrNull { it.projectName == projectName }
        fun require(projectName: String): NativeTarget = requireNotNull(find(projectName)) { "Unsupported Native target: $projectName" }
    }
}
