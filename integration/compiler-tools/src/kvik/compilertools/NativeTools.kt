package kvik.compilertools

import kvik.cache.NativeFiles
import kvik.compiler.native.NativeToolchainLayout
import kvik.compiler.native.NativeToolchainProvider
import kvik.compiler.native.nativeDependenciesIdentity
import kvik.core.Digest
import kvik.toolchains.*

/**
 * Supported installation: unmodified 2.4.20 macOS-arm64 properties, local Xcode, and already
 * extracted dependencies. Caller keeps all roots AND data-directory metadata stable through use.
 * Use with NativeCompilerExecutor's exact environment/arguments, including offline/internal-server
 * switches. Every successful call rehashes all six inventories; there is no mtime cache.
 */
class InstalledNativeToolchainProvider(
    private val trustedToolLock: String,
    toolRoots: Map<ToolComponent, String>,
    private val konanDataRoot: String,
) : NativeToolchainProvider {
    private val toolRoots = toolRoots.toMap()

    override fun verified(identities: Map<String, Digest>): NativeToolchainLayout {
        val requested = identities.toMap()
        require(requested.keys == setOf("native", "jdk", "sdk", "native-dependencies")) { "Native tool identities require exactly native, jdk, sdk and native-dependencies" }
        val installed = InstalledToolchainVerifier().verify(trustedToolLock, toolRoots)
        val native = installed[ToolComponent.KOTLIN_NATIVE]
        val jdk = installed[ToolComponent.JDK]
        val sdk = installed[ToolComponent.SDK]
        val llvm = installed[ToolComponent.LLVM]
        val libffi = installed[ToolComponent.LIBFFI]
        val xcode = installed[ToolComponent.XCODE_TOOLCHAIN]
        require(requested == mapOf("native" to native.digest, "jdk" to jdk.digest, "sdk" to sdk.digest,
            "native-dependencies" to nativeDependenciesIdentity(llvm.digest, libffi.digest, xcode.digest))) {
            "Requested Native identities differ from verified installations"
        }
        fun executable(path: String) {
            NativeFiles.ensureDirectory(path.substringBeforeLast('/'), create = false)
            val info = NativeFiles.info(path) ?: error("Missing verified executable: $path")
            require(NativeFiles.regular(info) && info.mode and 73 != 0) { "Verified tool must be a regular executable: $path" }
        }
        val compiler = "${native.root}/bin/konanc"
        executable(compiler); executable("${native.root}/bin/run_konan"); executable("${jdk.root}/bin/java")
        // Deliberately support one reviewed properties file instead of partially interpreting Java
        // properties, substitutions, dependency profiles and target overrides differently from konanc.
        NativeFiles.ensureDirectory("${native.root}/konan", create = false)
        require(CompilerFiles.digest("${native.root}/konan/konan.properties", 36666) ==
            Digest("f8a095afdc2b1fa052238c040562c254c36d7ce455a40a61cbf8065063ef4a4d", 36666)) {
            "Unsupported Native compiler properties; reviewed 2.4.20 macOS-arm64 defaults required"
        }
        require(NativeFiles.info("${native.root}/konan/platforms") == null) { "Native platform property overrides are unsupported" }

        require(xcode.root.endsWith("/Toolchains/XcodeDefault.xctoolchain")) { "XcodeDefault.xctoolchain installation required" }
        val developer = xcode.root.removeSuffix("/Toolchains/XcodeDefault.xctoolchain")
        NativeFiles.ensureDirectory(developer, create = false)
        require(physicalPath("$developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk") == sdk.root) {
            "Verified SDK must be the selected Xcode installation's default macOS SDK"
        }
        executable("${xcode.root}/usr/bin/ld")

        NativeFiles.ensureDirectory(konanDataRoot, create = false)
        val data = physicalPath(konanDataRoot)
        for (name in listOf("platforms", "klib", "cache", "konan.properties")) {
            require(NativeFiles.info("$data/$name") == null) { "Unsupported KONAN_DATA_DIR override/cache: $name" }
        }
        val dependencies = "$data/dependencies"
        NativeFiles.ensureDirectory(dependencies, create = false)
        val required = mapOf("llvm-21-aarch64-macos-essentials-97" to llvm.root, "libffi-3.3-1-macos-arm64" to libffi.root)
        for ((name, root) in required) {
            NativeFiles.ensureDirectory("$dependencies/$name", create = false)
            require("$dependencies/$name" == root) { "Verified dependency is not at the compiler-selected data path: $name" }
        }
        val markers = NativeFiles.read("$dependencies/.extracted", 64 * 1024).decodeToString(throwOnInvalidSequence = true).lineSequence().toSet()
        // The distribution also checks LLDB's presence, although these typed compile/test actions
        // never execute it. Marker + nonempty directory prevents the downloader touching it.
        for (name in required.keys + "lldb-4-macos") {
            val directory = "$dependencies/$name"
            NativeFiles.ensureDirectory(directory, create = false)
            require(name in markers && CompilerFiles.names(directory).isNotEmpty()) { "Dependency is not already extracted: $name" }
            require(NativeFiles.info("$directory/.unstable") == null) { "Unstable dependency would be replaced by compiler: $name" }
        }
        // Offline mode alone still extracts cached archives when a marker is absent. Refuse all
        // archives rather than trusting archive bytes that are absent from the six inventories.
        val cache = "$dependencies/cache"
        if (NativeFiles.info(cache) != null) {
            NativeFiles.ensureDirectory(cache, create = false)
            require(CompilerFiles.names(cache).all { it == ".lock" }) { "Native dependency archive cache must be empty" }
            NativeFiles.info("$cache/.lock")?.let { require(NativeFiles.regular(it) && it.size == 0L) { "Dependency cache lock must be an empty regular file" } }
        }
        return NativeToolchainLayout(compiler, jdk.root, sdk.root, data, developer, native.root)
    }
}
