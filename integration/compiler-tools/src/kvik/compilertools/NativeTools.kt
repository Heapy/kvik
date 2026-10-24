package kvik.compilertools

import kvik.cache.NativeFiles
import kvik.compiler.native.NativeToolchainLayout
import kvik.compiler.native.NativeToolchainProvider
import kvik.compiler.native.nativeDependenciesIdentity
import kvik.compiler.native.linuxNativeDependenciesIdentity
import kvik.core.Digest
import kvik.core.NativeTarget
import kvik.toolchains.*

/**
 * Supported installations: unmodified 2.4.20 macOS-arm64 or Linux-x64 properties and already
 * extracted dependencies. Caller keeps all roots AND data-directory metadata stable through use.
 * Use with NativeCompilerExecutor's exact environment/arguments, including offline/internal-server
 * switches. Every successful call rehashes all consumed inventories; there is no mtime cache.
 */
class InstalledNativeToolchainProvider(
    private val trustedToolLock: String,
    toolRoots: Map<ToolComponent, String>,
    private val konanDataRoot: String,
    private val target: NativeTarget = NativeTarget.MACOS_ARM64,
) : NativeToolchainProvider {
    private val toolRoots = toolRoots.toMap()

    override fun verified(identities: Map<String, Digest>): NativeToolchainLayout = verified(identities) {}

    override fun verified(identities: Map<String, Digest>, checkpoint: () -> Unit): NativeToolchainLayout {
        checkpoint()
        val requested = identities.toMap()
        require(requested.keys == target.toolIdentityNames) { "${target.projectName} tool identities require exactly ${target.toolIdentityNames.sorted()}" }
        val apple = target.isApple
        val declaration = parseToolchainDeclaration(trustedToolLock)
        require(if (apple) declaration.host.os == "Darwin" && declaration.host.architecture == "arm64"
            else declaration.host.os == "Linux" && declaration.host.architecture == "x86_64") {
            "${target.projectName} compilation requires a matching ${if (apple) "Darwin arm64" else "Linux x86_64"} host"
        }
        val platformComponent = if (apple) appleSdkComponent(target) else ToolComponent.SYSTEM
        val consumed = setOf(ToolComponent.JDK, ToolComponent.KOTLIN_NATIVE, ToolComponent.LLVM, ToolComponent.LIBFFI,
            platformComponent, if (apple) ToolComponent.XCODE_TOOLCHAIN else ToolComponent.NATIVE_TOOLCHAIN)
        val installed = InstalledToolchainVerifier().verifySelected(trustedToolLock, toolRoots, consumed, checkpoint)
        val native = installed[ToolComponent.KOTLIN_NATIVE]
        val jdk = installed[ToolComponent.JDK]
        val llvm = installed[ToolComponent.LLVM]
        val libffi = installed[ToolComponent.LIBFFI]
        val platform = installed[platformComponent]
        val hostToolchain = installed[if (apple) ToolComponent.XCODE_TOOLCHAIN else ToolComponent.NATIVE_TOOLCHAIN]
        // Native tools use /usr/bin:/bin. A copied inventory elsewhere cannot attest
        // the programs and host libraries reached through these fixed system paths.
        if (!apple) validateLinuxSystemPaths(platform.root, physicalPath("/bin"))
        val dependenciesIdentity = if (apple) nativeDependenciesIdentity(llvm.digest, libffi.digest, hostToolchain.digest)
            else linuxNativeDependenciesIdentity(llvm.digest, libffi.digest, hostToolchain.digest)
        require(requested == mapOf("native" to native.digest, "jdk" to jdk.digest,
            (if (apple) "sdk" else "system") to platform.digest, "native-dependencies" to dependenciesIdentity)) {
            "Requested Native identities differ from verified installations"
        }
        fun executable(path: String) {
            checkpoint()
            NativeFiles.ensureDirectory(path.substringBeforeLast('/'), create = false)
            val info = NativeFiles.info(path) ?: error("Missing verified executable: $path")
            require(NativeFiles.regular(info) && info.mode and 73 != 0) { "Verified tool must be a regular executable: $path" }
        }
        val compiler = "${native.root}/bin/konanc"
        executable(compiler); executable("${native.root}/bin/run_konan"); executable("${jdk.root}/bin/java")
        // Deliberately support one reviewed properties file per host instead of interpreting Java
        // properties, substitutions, dependency profiles and target overrides differently from konanc.
        NativeFiles.ensureDirectory("${native.root}/konan", create = false)
        val properties = if (apple)
            Digest("f8a095afdc2b1fa052238c040562c254c36d7ce455a40a61cbf8065063ef4a4d", 36666)
        else Digest("a0bfd2162d8f9cad8cfc6493813b4a1aa42dd2912fe6683e60b94adcb08c9c7a", 36666)
        require(CompilerFiles.digest("${native.root}/konan/konan.properties", properties.sizeBytes, checkpoint) == properties) {
            "Unsupported Native compiler properties; reviewed 2.4.20 ${target.compilerName} defaults required"
        }
        require(NativeFiles.info("${native.root}/konan/platforms") == null) { "Native platform property overrides are unsupported" }

        val developer = if (apple) {
            require(hostToolchain.root.endsWith("/Toolchains/XcodeDefault.xctoolchain")) { "XcodeDefault.xctoolchain installation required" }
            val directory = hostToolchain.root.removeSuffix("/Toolchains/XcodeDefault.xctoolchain")
            NativeFiles.ensureDirectory(directory, create = false)
            val sdkName = when (target) {
                NativeTarget.MACOS_ARM64 -> "MacOSX"
                NativeTarget.IOS_ARM64 -> "iPhoneOS"
                NativeTarget.IOS_SIMULATOR_ARM64 -> "iPhoneSimulator"
                NativeTarget.LINUX_X64 -> error("Not an Apple target")
            }
            require(physicalPath("$directory/Platforms/$sdkName.platform/Developer/SDKs/$sdkName.sdk") == platform.root) {
                "Verified SDK must be the selected Xcode installation's default ${target.appleSdkName} SDK"
            }
            executable("${hostToolchain.root}/usr/bin/ld")
            directory
        } else {
            val linker = physicalPath("${llvm.root}/bin/ld.lld")
            require(linker.startsWith("${llvm.root}/")) { "Linux linker escapes the verified LLVM installation" }
            executable(linker)
            ""
        }

        NativeFiles.ensureDirectory(konanDataRoot, create = false)
        val data = physicalPath(konanDataRoot)
        for (name in listOf("platforms", "klib", "cache", "konan.properties")) {
            checkpoint()
            require(NativeFiles.info("$data/$name") == null) { "Unsupported KONAN_DATA_DIR override/cache: $name" }
        }
        val dependencies = "$data/dependencies"
        NativeFiles.ensureDirectory(dependencies, create = false)
        val required = if (apple) mapOf("llvm-21-aarch64-macos-essentials-97" to llvm.root, "libffi-3.3-1-macos-arm64" to libffi.root)
            else mapOf("llvm-21-x86_64-linux-essentials-116" to llvm.root, "libffi-3.2.1-2-linux-x86-64" to libffi.root,
                "x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2" to hostToolchain.root)
        for ((name, root) in required) {
            checkpoint()
            NativeFiles.ensureDirectory("$dependencies/$name", create = false)
            require("$dependencies/$name" == root) { "Verified dependency is not at the compiler-selected data path: $name" }
        }
        val markers = NativeFiles.read("$dependencies/.extracted", 64 * 1024).decodeToString(throwOnInvalidSequence = true).lineSequence().toSet()
        checkpoint()
        // The distribution also checks LLDB's presence, although these typed compile/test actions
        // never execute it. Marker + nonempty directory prevents the downloader touching it.
        for (name in required.keys + (if (apple) "lldb-4-macos" else "lldb-4-linux")) {
            val directory = "$dependencies/$name"
            NativeFiles.ensureDirectory(directory, create = false)
            require(name in markers && CompilerFiles.names(directory, checkpoint).isNotEmpty()) { "Dependency is not already extracted: $name" }
            require(NativeFiles.info("$directory/.unstable") == null) { "Unstable dependency would be replaced by compiler: $name" }
        }
        // Offline mode alone still extracts cached archives when a marker is absent. Refuse all
        // archives rather than trusting archive bytes that are absent from the host inventories.
        val cache = "$dependencies/cache"
        if (NativeFiles.info(cache) != null) {
            NativeFiles.ensureDirectory(cache, create = false)
            require(CompilerFiles.names(cache, checkpoint).all { it == ".lock" }) { "Native dependency archive cache must be empty" }
            NativeFiles.info("$cache/.lock")?.let { require(NativeFiles.regular(it) && it.size == 0L) { "Dependency cache lock must be an empty regular file" } }
        }
        checkpoint()
        return NativeToolchainLayout(compiler, jdk.root, if (apple) platform.root else "", data, developer, native.root, target)
    }
}

internal fun validateLinuxSystemPaths(systemRoot: String, canonicalBin: String) {
    require(systemRoot == "/usr" && canonicalBin == "/usr/bin") {
        "Linux Native tools require the verified physical /usr installation and /bin -> /usr/bin (usrmerge)"
    }
}
