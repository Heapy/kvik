package kvik.core

/** Native target names shared by project variants, compiler invocations and artifact metadata. */
enum class NativeTarget(val projectName: String, val compilerName: String, val os: String, val architecture: String) {
    MACOS_ARM64("macosArm64", "macos_arm64", "macos", "arm64"),
    LINUX_X64("linuxX64", "linux_x64", "linux", "x64");

    val platform: Map<String, String> get() = mapOf("os" to os, "arch" to architecture)
    val publicationSuffix: String get() = projectName.lowercase()
    val toolIdentityNames: Set<String> get() = setOf("native", "jdk", "native-dependencies",
        if (this == MACOS_ARM64) "sdk" else "system")

    companion object {
        fun find(projectName: String): NativeTarget? = entries.firstOrNull { it.projectName == projectName }
        fun require(projectName: String): NativeTarget = requireNotNull(find(projectName)) { "Unsupported Native target: $projectName" }
    }
}
